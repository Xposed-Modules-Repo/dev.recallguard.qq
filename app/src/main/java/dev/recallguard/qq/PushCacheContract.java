package dev.recallguard.qq;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** IPC-only contract for the module-owned push cache. */
final class PushCacheContract {
    static final String AUTHORITY = "dev.recallguard.qq.pushcache";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);

    static final String METHOD_PUT = "put_candidate";
    static final String METHOD_LIST = "list_pending";
    static final String METHOD_MARK_RESTORED = "mark_restored";
    static final String METHOD_STATUS = "status";

    static final String KEY_OK = "ok";
    static final String KEY_CANDIDATES = "candidates";
    static final String KEY_STABLE_KEY = "stable_key";
    static final String KEY_PUSH_GLOBAL_ID = "push_global_id";
    static final String KEY_TEXT = "text";
    static final String KEY_ACTION_PARAM = "action_param";
    static final String KEY_JUMP_TYPE = "jump_type";
    static final String KEY_FROM_UIN = "from_uin";
    static final String KEY_GROUP_CODE = "group_code";
    static final String KEY_GROUP_UIN = "group_uin";
    static final String KEY_PEER_UIN = "peer_uin";
    static final String KEY_CHAT_TYPE = "chat_type";
    static final String KEY_PUSH_MSG_SEQ = "push_msg_seq";
    static final String KEY_POST_TIME_MS = "post_time_ms";
    static final String KEY_MSG_TIME_MS = "msg_time_ms";
    static final String KEY_TOMBSTONE_KEY = "tombstone_key";
    static final String EXTRA_METHOD = "method";
    static final String EXTRA_PAYLOAD = "payload";
    static final String EXTRA_RESULT = "result_receiver";
    static final String EXTRA_CALLER_PROOF = "caller_proof";

    private static final String TAG = "QQRecallGuard";
    private static final String MODULE_PACKAGE = "dev.recallguard.qq";
    private static final String BRIDGE_ACTIVITY =
            "dev.recallguard.qq.PushCacheBridgeActivity";
    private static volatile Handler resultHandler;

    private PushCacheContract() {}

    static boolean putCandidate(Context context, Bundle candidate) {
        return dispatchWrite(context, candidate);
    }

    static ArrayList<Bundle> listPending(Context context) {
        Bundle result = call(context, METHOD_LIST, Bundle.EMPTY);
        if (result == null || !result.getBoolean(KEY_OK, false)) return new ArrayList<>();
        ArrayList<Bundle> values = result.getParcelableArrayList(KEY_CANDIDATES);
        return values == null ? new ArrayList<>() : values;
    }

    static boolean markRestored(Context context, String stableKey, String tombstoneKey) {
        Bundle extras = new Bundle();
        extras.putString(KEY_STABLE_KEY, stableKey);
        extras.putString(KEY_TOMBSTONE_KEY, tombstoneKey);
        Bundle result = call(context, METHOD_MARK_RESTORED, extras);
        return result != null && result.getBoolean(KEY_OK, false);
    }

    static Bundle status(Context context) {
        return call(context, METHOD_STATUS, Bundle.EMPTY);
    }

    private static Bundle call(Context context, String method, Bundle extras) {
        if (context == null) return null;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "refusing synchronous push cache IPC on main thread method=" + method);
            return null;
        }
        try {
            return context.getContentResolver().call(URI, method, null, extras);
        } catch (Throwable error) {
            Log.w(TAG, "direct push cache IPC unavailable; using bridge method=" + method);
            return callViaActivity(context, method, extras);
        }
    }

    static Bundle callProviderDirect(Context context, String method, Bundle extras) {
        try {
            return context.getContentResolver().call(URI, method, null, extras);
        } catch (Throwable error) {
            Log.e(TAG, "module-local provider call failed method=" + method, error);
            return null;
        }
    }

    private static boolean dispatchWrite(Context context, Bundle candidate) {
        if (context == null) return false;
        try {
            Context caller = context.getApplicationContext() == null
                    ? context : context.getApplicationContext();
            Bundle payload = new Bundle(candidate);
            // Never make provider/database IPC part of MCS' notification Binder callback.
            resultHandler().post(() -> {
                Bundle result = call(caller, METHOD_PUT, payload);
                if (result == null || !result.getBoolean(KEY_OK, false)) {
                    Log.e(TAG, "asynchronous push cache write failed open");
                }
            });
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "push cache write dispatch failed open", error);
            return false;
        }
    }

    private static Bundle callViaActivity(Context context, String method, Bundle extras) {
        try {
            CountDownLatch completed = new CountDownLatch(1);
            Bundle[] result = new Bundle[1];
            ResultReceiver receiver = new ResultReceiver(resultHandler()) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    result[0] = resultData;
                    completed.countDown();
                }
            };
            Intent intent = bridgeIntent(context, method, extras);
            intent.putExtra(EXTRA_RESULT, receiver);
            context.startActivity(intent);
            if (!completed.await(1800L, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "push cache activity bridge timed out method=" + method);
                return null;
            }
            return result[0];
        } catch (Throwable error) {
            Log.e(TAG, "push cache activity bridge failed open method=" + method, error);
            return null;
        }
    }

    private static Intent bridgeIntent(Context context, String method, Bundle extras) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(MODULE_PACKAGE, BRIDGE_ACTIVITY));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        intent.putExtra(EXTRA_METHOD, method);
        intent.putExtra(EXTRA_PAYLOAD, extras);
        Intent proofIntent = new Intent("dev.recallguard.qq.CALLER_PROOF");
        proofIntent.setPackage(context.getPackageName());
        PendingIntent proof = PendingIntent.getBroadcast(context,
                0x51475000 ^ method.hashCode(), proofIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        intent.putExtra(EXTRA_CALLER_PROOF, proof);
        return intent;
    }

    private static Handler resultHandler() {
        Handler value = resultHandler;
        if (value != null) return value;
        synchronized (PushCacheContract.class) {
            value = resultHandler;
            if (value == null) {
                HandlerThread thread = new HandlerThread("QQRecallGuard-cache-result");
                thread.start();
                value = new Handler(thread.getLooper());
                resultHandler = value;
            }
            return value;
        }
    }
}
