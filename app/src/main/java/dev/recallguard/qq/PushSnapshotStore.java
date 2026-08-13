package dev.recallguard.qq;

import android.app.Application;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recovers MCS-persisted candidates and asynchronously inspects any still-active QQ notification.
 * ColorOS/HeyTap may receive and display a push while QQ has no process; that notification is
 * then the only remaining local source for content that the server has already replaced with a
 * recall tombstone before the next message sync.
 */
final class PushSnapshotStore {
    private static final String TAG = "QQRecallGuard";
    private static final AtomicBoolean CAPTURED = new AtomicBoolean(false);
    private static final AtomicInteger HISTORY_GENERATION = new AtomicInteger();
    private static final Object RECOVERY_LOCK = new Object();
    private static final ArrayList<PushCandidate> PUSHES = new ArrayList<>();
    private static final ArrayList<RecallTombstone> TOMBSTONES = new ArrayList<>();
    private static final ArrayList<BounceCandidate> BOUNCES = new ArrayList<>();
    private static final Set<String> RESTORES_IN_FLIGHT = new HashSet<>();
    private static volatile BounceCandidate inFlight;
    private static volatile Context appContext;
    private static volatile ClassLoader hostClassLoader;
    private static volatile Handler recoveryWorker;

    private PushSnapshotStore() {}

    static void installEarlyCapture(ClassLoader hostLoader) {
        hostClassLoader = hostLoader;
        try {
            HookRuntime.hookAllMethods(Application.class, "attach", 10_000,
                    new HookRuntime.Callback() {
                @Override
                void after(HookRuntime.HookParam param) {
                    if (!(param.args[0] instanceof Context)) return;
                    capture((Context) param.args[0], "Application.attach-after");
                }
            });
            HookRuntime.log("asynchronous ColorOS recovery hook installed");
        } catch (Throwable error) {
            HookRuntime.log("asynchronous push recovery hook failed", error);
        }
        hookJumpActivity(hostLoader);
    }

    static void capture(Context context, String phase) {
        if (!CAPTURED.compareAndSet(false, true)) return;
        appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        workerHandler().post(() -> captureAsync(phase));
    }

    private static void captureAsync(String phase) {
        loadPersistentCandidates();
        int activeCount = -1;
        try {
            Context context = appContext;
            NotificationManager manager = context == null ? null : (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            StatusBarNotification[] active = manager == null
                    ? null : manager.getActiveNotifications();
            activeCount = active == null ? 0 : active.length;
            if (active != null) {
                for (StatusBarNotification item : active) {
                    if (item != null && "com.tencent.mobileqq".equals(item.getPackageName())) {
                        captureRecoveryCandidate(item);
                    }
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "asynchronous notification recovery scan failed", error);
        }

        Log.i(TAG, "asynchronous recovery ready phase=" + phase + " active=" + activeCount);
        startBounceQueue();
        scheduleHistoryQueries();
        workerHandler().postDelayed(PushSnapshotStore::reloadPersistentCandidates, 3000L);
        scheduleReconcile(1000L);
    }

    private static void hookJumpActivity(ClassLoader hostLoader) {
        try {
            Class<?> jump = Class.forName(
                    "com.tencent.mobileqq.activity.JumpActivity", false, hostLoader);
            HookRuntime.Callback observer = new HookRuntime.Callback() {
                @Override
                void before(HookRuntime.HookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    Intent intent = ((Activity) param.thisObject).getIntent();
                    captureJumpIntent(intent);
                }
            };
            int hookCount = HookRuntime.tryHookAllMethods(
                    jump, "onCreate", 10_000, observer).size();
            hookCount += HookRuntime.tryHookAllMethods(
                    jump, "doOnCreate", 10_000, observer).size();
            if (hookCount == 0) throw new NoSuchMethodException("JumpActivity lifecycle");
            HookRuntime.log("third-push JumpActivity observer installed");
        } catch (Throwable error) {
            HookRuntime.log("JumpActivity observer unavailable", error);
        }
    }

    private static void captureRecoveryCandidate(StatusBarNotification item) {
        try {
            if (!"com.tencent.mobileqq".equals(item.getPackageName())) return;
            Notification notification = item.getNotification();
            if (notification == null || notification.extras == null) return;
            CharSequence value = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            String text = value == null ? "" : value.toString();
            if (text.trim().isEmpty()) return;

            String actionParam = notification.extras.getString(PushRelayMcs.EXTRA_ACTION_PARAM);
            String globalId = notification.extras.getString("PushGlobalId");
            String key = globalId == null || globalId.isEmpty() ? item.getKey() : globalId;
            if (actionParam != null && !actionParam.isEmpty()) {
                addPushCandidate(text, actionParam, item.getPostTime(), key, "relayed-extra");
                return;
            }
            PendingIntent contentIntent = notification.contentIntent;
            if (contentIntent != null
                    && PushRelayMcs.MCS_PACKAGE.equals(contentIntent.getCreatorPackage())) {
                synchronized (RECOVERY_LOCK) {
                    BOUNCES.add(new BounceCandidate(
                            text, item.getPostTime(), key, contentIntent));
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "captureRecoveryCandidate failed", error);
        }
    }

    private static void startBounceQueue() {
        Handler handler = mainHandler();
        if (handler == null) return;
        synchronized (RECOVERY_LOCK) {
            if (BOUNCES.isEmpty() || inFlight != null) return;
        }
        handler.postDelayed(PushSnapshotStore::sendNextBounce, 120L);
    }

    private static void sendNextBounce() {
        final BounceCandidate candidate;
        synchronized (RECOVERY_LOCK) {
            if (inFlight != null || BOUNCES.isEmpty()) return;
            candidate = BOUNCES.remove(0);
            inFlight = candidate;
        }
        try {
            // This read-only probe starts QQ's normal JumpActivity with the immutable original
            // Intent. The high-priority hook above copies action_param and strips it before QQ
            // can navigate, so the user remains on the ordinary cold-start screen.
            candidate.pendingIntent.send();
        } catch (Throwable error) {
            Log.e(TAG, "third-push PendingIntent probe failed", error);
            synchronized (RECOVERY_LOCK) {
                if (inFlight == candidate) inFlight = null;
            }
        }
        Handler handler = mainHandler();
        if (handler != null) {
            handler.postDelayed(() -> {
                synchronized (RECOVERY_LOCK) {
                    if (inFlight == candidate) inFlight = null;
                }
                sendNextBounce();
            }, 450L);
        }
    }

    private static void captureJumpIntent(Intent intent) {
        if (intent == null
                || !"com.tencent.mobileqq.third.push".equals(intent.getAction())) return;
        try {
            BounceCandidate probe = inFlight;
            String actionParam = intent.getStringExtra("action_param");
            if (actionParam == null || actionParam.isEmpty()) {
                actionParam = intent.getStringExtra(PushRelayMcs.EXTRA_ACTION_PARAM);
            }
            String text = intent.getStringExtra(PushRelayMcs.EXTRA_PUSH_TEXT);
            long postTime = System.currentTimeMillis();
            String key = "jump:" + postTime;
            if (probe != null) {
                if (text == null || text.isEmpty()) text = probe.text;
                postTime = probe.postTime;
                key = probe.key;
            }
            if (text != null && !text.trim().isEmpty()
                    && actionParam != null && !actionParam.isEmpty()) {
                addPushCandidate(text, actionParam, postTime, key,
                        probe == null ? "clicked-relay" : "qq-pending-intent-probe");
            }

            if (probe != null) {
                // Suppress navigation caused only by our probe. A real notification click has
                // no inFlight marker and retains QQ's normal open-conversation behavior.
                intent.setAction(null);
                intent.removeExtra("action_param");
                intent.removeExtra(PushRelayMcs.EXTRA_ACTION_PARAM);
                intent.removeExtra(PushRelayMcs.EXTRA_PUSH_TEXT);
                synchronized (RECOVERY_LOCK) {
                    if (inFlight == probe) inFlight = null;
                }
                Handler handler = mainHandler();
                if (handler != null) handler.postDelayed(PushSnapshotStore::sendNextBounce, 100L);
            }
        } catch (Throwable error) {
            Log.e(TAG, "captureJumpIntent failed", error);
        }
    }

    private static void addPushCandidate(String text, String actionParam, long postTime,
            String key, String source) {
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
                Log.i(TAG, "unsupported offline push jumptype=" + jumpType);
                return;
            }
            if (peerUin.isEmpty()) return;
            String stableKey = key == null || key.isEmpty()
                    ? actionParam + ':' + postTime : key;
            synchronized (RECOVERY_LOCK) {
                for (PushCandidate existing : PUSHES) {
                    if (existing.key.equals(stableKey)) return;
                }
                PUSHES.add(new PushCandidate(stableKey, text, chatType, peerUin,
                        action.optString("fromuin", ""), postTime / 1000L,
                        action.optLong("msgseq", 0L)));
            }
            Log.i(TAG, "offline push mapped source=" + source + " chatType=" + chatType
                    + " peerUin=" + peerUin + " textLength=" + text.length());
            scheduleReconcile(300L);
            scheduleHistoryQueries();
        } catch (Throwable error) {
            Log.e(TAG, "parse offline push action_param failed", error);
        }
    }

    static void onSyncEnd() {
        scheduleHistoryQueries();
    }

    static void onHistoryRecords(List<?> records) {
        if (records == null) return;
        for (Object record : records) inspectRecord(record);
        scheduleReconcile(150L);
    }

    private static void inspectRecord(Object record) {
        if (record == null) return;
        try {
            Object rawElements = record.getClass().getMethod("getElements").invoke(record);
            if (!(rawElements instanceof List)) return;
            repairRestoredMarker((List<?>) rawElements);
            Object revokeElement = null;
            for (Object element : (List<?>) rawElements) {
                if (element == null) continue;
                Object elementType = element.getClass().getMethod("getElementType").invoke(element);
                if (!(elementType instanceof Number)
                        || ((Number) elementType).intValue() != 8) continue;
                Object gray = element.getClass().getMethod("getGrayTipElement").invoke(element);
                if (gray == null) continue;
                revokeElement = gray.getClass().getMethod("getRevokeElement").invoke(gray);
                if (revokeElement != null) break;
            }
            if (revokeElement == null) return;
            int chatType = ((Number) record.getClass().getMethod("getChatType")
                    .invoke(record)).intValue();
            String peerUid = String.valueOf(record.getClass().getMethod("getPeerUid")
                    .invoke(record));
            long msgSeq = ((Number) record.getClass().getMethod("getMsgSeq")
                    .invoke(record)).longValue();
            long msgTime = ((Number) record.getClass().getMethod("getMsgTime")
                    .invoke(record)).longValue();
            onRecallTombstone(chatType, peerUid, msgSeq, msgTime, revokeElement);
        } catch (Throwable error) {
            Log.e(TAG, "inspect history record failed", error);
        }
    }

    private static void repairRestoredMarker(List<?> elements) {
        Context context = appContext;
        if (context == null) return;
        for (Object element : elements) {
            if (element == null) continue;
            try {
                Object raw = element.getClass().getMethod("getExtBufForUI").invoke(element);
                if (!(raw instanceof byte[])) continue;
                String encoded = new String((byte[]) raw, StandardCharsets.UTF_8);
                if (!encoded.startsWith(QQBridge.RESTORE_MARKER_PREFIX)) continue;
                JSONObject marker = new JSONObject(
                        encoded.substring(QQBridge.RESTORE_MARKER_PREFIX.length()));
                String pushKey = marker.optString("pushKey", "");
                String tombstoneKey = marker.optString("tombstoneKey", "");
                if (!pushKey.isEmpty() && !tombstoneKey.isEmpty()) {
                    final String finalPushKey = pushKey;
                    final String finalTombstoneKey = tombstoneKey;
                    workerHandler().post(() -> {
                        if (PushCacheContract.markRestored(
                                context, finalPushKey, finalTombstoneKey)) {
                            removePush(finalPushKey);
                            Log.i(TAG, "repaired restored cache state from local marker");
                        }
                    });
                }
            } catch (NoSuchMethodException ignored) {
                return;
            } catch (Throwable error) {
                Log.w(TAG, "local restore marker inspection failed", error);
            }
        }
    }

    private static void scheduleHistoryQueries() {
        synchronized (RECOVERY_LOCK) {
            if (PUSHES.isEmpty()) return;
        }
        Handler handler = mainHandler();
        if (handler == null) return;
        int generation = HISTORY_GENERATION.incrementAndGet();
        long[] delays = {250L, 1200L, 3500L, 8000L};
        for (long delay : delays) {
            handler.postDelayed(() -> {
                if (HISTORY_GENERATION.get() != generation || !hasPendingPushes()) return;
                queryPendingHistory();
            }, delay);
        }
    }

    private static void reloadPersistentCandidates() {
        loadPersistentCandidates();
        scheduleHistoryQueries();
        scheduleReconcile(250L);
    }

    private static void queryPendingHistory() {
        ClassLoader loader = hostClassLoader;
        if (loader == null) return;
        List<PushCandidate> pushes;
        synchronized (RECOVERY_LOCK) {
            pushes = new ArrayList<>(PUSHES);
        }
        for (PushCandidate push : pushes) {
            String peerUid = push.chatType == RecallEvent.C2C
                    ? QQBridge.uinToUid(push.peerUin, loader) : push.peerUin;
            if (peerUid == null || peerUid.isEmpty()) continue;
            QQBridge.queryLatestRecords(push.chatType, peerUid, loader);
        }
    }

    static void onRecallTombstone(int chatType, String peerUid, long msgSeq, long msgTime,
            Object revokeElement) {
        if (peerUid == null || peerUid.isEmpty()) return;
        String key = chatType + ":" + peerUid + ':' + msgSeq;
        String senderUid = revokeString(revokeElement, "getOrigMsgSenderUid");
        String senderNick = revokeString(revokeElement, "getOrigMsgSenderNick");
        String senderRemark = revokeString(revokeElement, "getOrigMsgSenderRemark");
        String senderMemberName = revokeString(revokeElement, "getOrigMsgSenderMemRemark");
        synchronized (RECOVERY_LOCK) {
            for (RecallTombstone existing : TOMBSTONES) {
                if (existing.key.equals(key)) return;
            }
            TOMBSTONES.add(new RecallTombstone(key, chatType, peerUid, msgSeq, msgTime,
                    senderUid, senderNick, senderRemark, senderMemberName));
        }
        Log.i(TAG, "cold-sync recall tombstone chatType=" + chatType
                + " peerUid=" + peerUid + " seq=" + msgSeq + " time=" + msgTime);
        scheduleReconcile(350L);
    }

    private static void scheduleReconcile(long delayMs) {
        Handler handler = mainHandler();
        if (handler != null) handler.postDelayed(PushSnapshotStore::reconcile, delayMs);
    }

    private static void reconcile() {
        Context context = appContext;
        ClassLoader loader = hostClassLoader;
        if (context == null || loader == null) return;
        List<PushCandidate> pushes;
        List<RecallTombstone> tombstones;
        synchronized (RECOVERY_LOCK) {
            pushes = new ArrayList<>(PUSHES);
            tombstones = new ArrayList<>(TOMBSTONES);
        }
        if (pushes.isEmpty() || tombstones.isEmpty()) return;

        for (PushCandidate push : pushes) {
            String peerUid = push.chatType == RecallEvent.C2C
                    ? QQBridge.uinToUid(push.peerUin, loader) : push.peerUin;
            if (peerUid == null || peerUid.isEmpty()) continue;

            RecallTombstone match = null;
            long bestDelta = Long.MAX_VALUE;
            for (RecallTombstone tombstone : tombstones) {
                if (tombstone.chatType != push.chatType
                        || !peerUid.equals(tombstone.peerUid)) continue;
                long delta = Math.abs(tombstone.msgTime - push.postTimeSeconds);
                if (delta <= 15L * 60L && delta < bestDelta) {
                    match = tombstone;
                    bestDelta = delta;
                }
            }
            if (match == null) continue;
            final RecallTombstone selected = match;
            final String pushFlight = "push:" + push.key;
            final String tombstoneFlight = "tombstone:" + selected.key;
            synchronized (RECOVERY_LOCK) {
                if (RESTORES_IN_FLIGHT.contains(pushFlight)
                        || RESTORES_IN_FLIGHT.contains(tombstoneFlight)) continue;
                RESTORES_IN_FLIGHT.add(pushFlight);
                RESTORES_IN_FLIGHT.add(tombstoneFlight);
            }
            String senderUin = push.senderUin;
            String senderUid = selected.senderUid;
            if (!senderUid.isEmpty()) {
                String mappedUin = QQBridge.uidToUin(senderUid, loader);
                if (!mappedUin.isEmpty()) senderUin = mappedUin;
            }
            if (senderUin == null || senderUin.isEmpty()) {
                senderUin = push.chatType == RecallEvent.C2C ? push.peerUin : "";
            }
            if (senderUid.isEmpty() && !senderUin.isEmpty()) {
                senderUid = QQBridge.uinToUid(senderUin, loader);
            }
            if (senderUid.isEmpty() && push.chatType == RecallEvent.C2C) {
                senderUid = peerUid;
            }
            boolean accepted = QQBridge.addPreservedPushText(push.chatType, peerUid,
                    push.text, push.postTimeSeconds, selected.msgSeq, push.key,
                    selected.key, senderUid, senderUin, selected.senderNick,
                    selected.senderRemark, selected.senderMemberName, loader, success -> {
                        synchronized (RECOVERY_LOCK) {
                            RESTORES_IN_FLIGHT.remove(pushFlight);
                            RESTORES_IN_FLIGHT.remove(tombstoneFlight);
                        }
                        if (!success) return;
                        workerHandler().post(() -> {
                            if (PushCacheContract.markRestored(
                                    context, push.key, selected.key)) {
                                removePush(push.key);
                                Log.i(TAG, "offline recalled text restored chatType="
                                        + push.chatType + " peerUid=" + peerUid
                                        + " seq=" + selected.msgSeq);
                            }
                        });
                    });
            if (!accepted) {
                synchronized (RECOVERY_LOCK) {
                    RESTORES_IN_FLIGHT.remove(pushFlight);
                    RESTORES_IN_FLIGHT.remove(tombstoneFlight);
                }
            }
        }
    }

    private static void removePush(String stableKey) {
        synchronized (RECOVERY_LOCK) {
            PUSHES.removeIf(value -> value.key.equals(stableKey));
            if (PUSHES.isEmpty()) HISTORY_GENERATION.incrementAndGet();
        }
    }

    private static boolean hasPendingPushes() {
        synchronized (RECOVERY_LOCK) {
            return !PUSHES.isEmpty();
        }
    }

    private static void loadPersistentCandidates() {
        Context context = appContext;
        if (context == null) return;
        try {
            ArrayList<Bundle> values = PushCacheContract.listPending(context);
            int loaded = 0;
            synchronized (RECOVERY_LOCK) {
                for (Bundle value : values) {
                    String key = value.getString(PushCacheContract.KEY_STABLE_KEY, "");
                    String text = value.getString(PushCacheContract.KEY_TEXT, "");
                    String peerUin = value.getString(PushCacheContract.KEY_PEER_UIN, "");
                    int chatType = value.getInt(PushCacheContract.KEY_CHAT_TYPE, 0);
                    long postTime = value.getLong(PushCacheContract.KEY_POST_TIME_MS, 0L)
                            / 1000L;
                    if (key.isEmpty() || text.trim().isEmpty() || peerUin.isEmpty()
                            || chatType == 0 || postTime <= 0L) continue;
                    boolean duplicate = false;
                    for (PushCandidate existing : PUSHES) {
                        if (existing.key.equals(key)) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (duplicate) continue;
                    PUSHES.add(new PushCandidate(key, text, chatType, peerUin,
                            value.getString(PushCacheContract.KEY_FROM_UIN, ""), postTime,
                            value.getLong(PushCacheContract.KEY_PUSH_MSG_SEQ, 0L)));
                    loaded++;
                }
            }
            Log.i(TAG, "provider recovery state loaded candidates=" + loaded);
        } catch (Throwable error) {
            Log.e(TAG, "load provider recovery state failed open", error);
        }
    }

    private static Handler mainHandler() {
        Looper looper = Looper.getMainLooper();
        return looper == null ? null : new Handler(looper);
    }

    private static Handler workerHandler() {
        Handler value = recoveryWorker;
        if (value != null) return value;
        synchronized (PushSnapshotStore.class) {
            value = recoveryWorker;
            if (value == null) {
                HandlerThread thread = new HandlerThread("QQRecallGuard-recovery");
                thread.start();
                value = new Handler(thread.getLooper());
                recoveryWorker = value;
            }
            return value;
        }
    }

    private static String revokeString(Object revokeElement, String methodName) {
        if (revokeElement == null) return "";
        try {
            Object value = revokeElement.getClass().getMethod(methodName).invoke(revokeElement);
            return value instanceof String ? ((String) value).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static final class BounceCandidate {
        final String text;
        final long postTime;
        final String key;
        final PendingIntent pendingIntent;

        BounceCandidate(String text, long postTime, String key, PendingIntent pendingIntent) {
            this.text = text;
            this.postTime = postTime;
            this.key = key;
            this.pendingIntent = pendingIntent;
        }
    }

    private static final class PushCandidate {
        final String key;
        final String text;
        final int chatType;
        final String peerUin;
        final String senderUin;
        final long postTimeSeconds;
        final long pushMsgSeq;

        PushCandidate(String key, String text, int chatType, String peerUin,
                String senderUin, long postTimeSeconds, long pushMsgSeq) {
            this.key = key;
            this.text = text;
            this.chatType = chatType;
            this.peerUin = peerUin;
            this.senderUin = senderUin;
            this.postTimeSeconds = postTimeSeconds;
            this.pushMsgSeq = pushMsgSeq;
        }
    }

    private static final class RecallTombstone {
        final String key;
        final int chatType;
        final String peerUid;
        final long msgSeq;
        final long msgTime;
        final String senderUid;
        final String senderNick;
        final String senderRemark;
        final String senderMemberName;

        RecallTombstone(String key, int chatType, String peerUid, long msgSeq, long msgTime,
                String senderUid, String senderNick, String senderRemark,
                String senderMemberName) {
            this.key = key;
            this.chatType = chatType;
            this.peerUid = peerUid;
            this.msgSeq = msgSeq;
            this.msgTime = msgTime;
            this.senderUid = senderUid == null ? "" : senderUid;
            this.senderNick = senderNick == null ? "" : senderNick;
            this.senderRemark = senderRemark == null ? "" : senderRemark;
            this.senderMemberName = senderMemberName == null ? "" : senderMemberName;
        }
    }

}
