package dev.recallguard.qq;

import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** Adds QQ-readable recovery metadata while ColorOS' system push process owns the Intent. */
final class PushRelayMcs {
    static final String MCS_PACKAGE = "com.heytap.mcs";
    static final String EXTRA_ACTION_PARAM = "dev.recallguard.qq.ACTION_PARAM";
    static final String EXTRA_PUSH_TEXT = "dev.recallguard.qq.PUSH_TEXT";
    static final String EXTRA_RELAYED = "dev.recallguard.qq.RELAYED";

    private static final String QQ_PACKAGE = "com.tencent.mobileqq";
    private static final String TAG = "QQRecallGuard";
    private static final AtomicBoolean NOTIFICATION_HOOK_INSTALLED = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, Long> RECENT_RELAYS =
            new ConcurrentHashMap<>();
    private static final LongAdder TOTAL_CALLBACKS = new LongAdder();
    private static final LongAdder QQ_CALLBACKS = new LongAdder();
    private static final LongAdder PERSISTED_CALLBACKS = new LongAdder();

    private PushRelayMcs() {}

    static void install() {
        installNotificationHook();
    }

    private static void installNotificationHook() {
        if (!NOTIFICATION_HOOK_INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> proxy = Class.forName(
                    "android.app.INotificationManager$Stub$Proxy", false,
                    PushRelayMcs.class.getClassLoader());
            HookRuntime.hookAllMethods(proxy, "enqueueNotificationWithTag", 10_000,
                    new HookRuntime.Callback() {
                        @Override
                        void before(HookRuntime.HookParam param) {
                            TOTAL_CALLBACKS.increment();
                            relayQqNotification(param.args);
                        }
                    });
            HookRuntime.log("ColorOS MCS notification relay installed once");
        } catch (Throwable error) {
            NOTIFICATION_HOOK_INSTALLED.set(false);
            HookRuntime.log("ColorOS MCS relay unavailable", error);
        }
    }

    private static void relayQqNotification(Object[] args) {
        try {
            Notification notification = null;
            int notificationId = 0;
            boolean qqTarget = false;
            for (Object arg : args) {
                if (arg instanceof String && QQ_PACKAGE.equals(arg)) qqTarget = true;
                if (arg instanceof Integer) notificationId = (Integer) arg;
                if (arg instanceof Notification) notification = (Notification) arg;
            }
            if (!qqTarget || notification == null) return;
            QQ_CALLBACKS.increment();

            Intent original = readIntent(notification.contentIntent);
            if (original == null || !"com.tencent.mobileqq.third.push".equals(original.getAction())) {
                return;
            }
            String actionParam = original.getStringExtra("action_param");
            CharSequence visible = notification.extras == null
                    ? null : notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            String pushText = visible == null ? "" : visible.toString();
            if (actionParam == null || actionParam.isEmpty() || pushText.trim().isEmpty()) return;

            String relayIdentity = actionParam + '\u0000' + pushText;
            long elapsed = SystemClock.elapsedRealtime();
            Long previous = RECENT_RELAYS.put(relayIdentity, elapsed);
            if (previous != null && elapsed - previous < 5000L) return;
            RECENT_RELAYS.entrySet().removeIf(entry -> elapsed - entry.getValue() > 30000L);

            Context context = currentApplication();
            boolean persisted = context != null && persistCandidate(context, notification,
                    notificationId, pushText, actionParam);
            if (persisted) PERSISTED_CALLBACKS.increment();

            if (notification.extras == null) notification.extras = new Bundle();
            notification.extras.putString(EXTRA_ACTION_PARAM, actionParam);
            notification.extras.putString(EXTRA_PUSH_TEXT, pushText);
            notification.extras.putBoolean(EXTRA_RELAYED, true);

            // Preserve the recovery payload even when the user launches QQ by tapping this
            // auto-cancel notification, in which case it disappears before Application.attach.
            if (context != null) {
                Intent wrapped = new Intent(original);
                wrapped.putExtra(EXTRA_ACTION_PARAM, actionParam);
                wrapped.putExtra(EXTRA_PUSH_TEXT, pushText);
                wrapped.putExtra(EXTRA_RELAYED, true);
                int requestCode = 0x51470000 ^ notificationId ^ actionParam.hashCode();
                notification.contentIntent = PendingIntent.getActivity(context, requestCode, wrapped,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            long qqCount = QQ_CALLBACKS.sum();
            Log.i(TAG, "ColorOS push relayed id=" + notificationId
                    + " textLength=" + pushText.length() + " persisted=" + persisted
                    + " counters=" + TOTAL_CALLBACKS.sum() + '/' + qqCount + '/'
                    + PERSISTED_CALLBACKS.sum());
        } catch (Throwable error) {
            Log.e(TAG, "relayQqNotification failed", error);
        }
    }

    private static boolean persistCandidate(Context context, Notification notification,
            int notificationId, String text, String actionParam) {
        try {
            JSONObject action = new JSONObject(actionParam);
            String jumpType = action.optString("jumptype", "");
            int chatType;
            String peerUin;
            if ("57618".equals(jumpType)) {
                chatType = RecallEvent.C2C;
                peerUin = action.optString("fromuin", "");
            } else if ("57619".equals(jumpType)) {
                chatType = RecallEvent.GROUP;
                peerUin = action.optString("groupcode", "");
            } else if ("57620".equals(jumpType)) {
                chatType = RecallEvent.GROUP;
                peerUin = action.optString("groupuin", "");
            } else {
                return false;
            }
            if (peerUin.isEmpty()) return false;

            String globalId = notification.extras == null ? ""
                    : notification.extras.getString("PushGlobalId", "");
            long postTime = System.currentTimeMillis();
            long msgTime = notification.when > 0L ? notification.when : postTime;
            String stableKey = globalId;
            if (stableKey.isEmpty()) {
                String identity = notificationId + "\u0000" + jumpType + "\u0000"
                        + peerUin + "\u0000" + action.optLong("msgseq", 0L) + "\u0000"
                        + actionParam + "\u0000" + text;
                stableKey = "mcs:" + UUID.nameUUIDFromBytes(
                        identity.getBytes(StandardCharsets.UTF_8));
            }

            Bundle value = new Bundle();
            value.putString(PushCacheContract.KEY_STABLE_KEY, stableKey);
            value.putString(PushCacheContract.KEY_PUSH_GLOBAL_ID, globalId);
            value.putString(PushCacheContract.KEY_TEXT, text);
            value.putString(PushCacheContract.KEY_ACTION_PARAM, actionParam);
            value.putString(PushCacheContract.KEY_JUMP_TYPE, jumpType);
            value.putString(PushCacheContract.KEY_FROM_UIN,
                    action.optString("fromuin", ""));
            value.putString(PushCacheContract.KEY_GROUP_CODE,
                    action.optString("groupcode", ""));
            value.putString(PushCacheContract.KEY_GROUP_UIN,
                    action.optString("groupuin", ""));
            value.putString(PushCacheContract.KEY_PEER_UIN, peerUin);
            value.putInt(PushCacheContract.KEY_CHAT_TYPE, chatType);
            value.putLong(PushCacheContract.KEY_PUSH_MSG_SEQ,
                    action.optLong("msgseq", 0L));
            value.putLong(PushCacheContract.KEY_POST_TIME_MS, postTime);
            value.putLong(PushCacheContract.KEY_MSG_TIME_MS, msgTime);
            return PushCacheContract.putCandidate(context, value);
        } catch (Throwable error) {
            Log.e(TAG, "persist MCS push candidate failed open", error);
            return false;
        }
    }

    private static Intent readIntent(PendingIntent pendingIntent) throws Exception {
        if (pendingIntent == null) return null;
        Method getIntent = PendingIntent.class.getDeclaredMethod("getIntent");
        getIntent.setAccessible(true);
        Object value = getIntent.invoke(pendingIntent);
        return value instanceof Intent ? (Intent) value : null;
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object value = activityThread.getDeclaredMethod("currentApplication").invoke(null);
            return value instanceof Application ? (Application) value : null;
        } catch (Throwable error) {
            Log.e(TAG, "current MCS application unavailable", error);
            return null;
        }
    }
}
