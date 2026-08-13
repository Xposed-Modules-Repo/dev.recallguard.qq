package dev.recallguard.qq;

import android.util.Log;

final class NativeBridge {
    private static final String TAG = "QQRecallGuard";

    private NativeBridge() {}

    static int ensureLoaded() {
        try {
            Log.i(TAG, "loading modern native library");
            System.loadLibrary("recall_guard");
            boolean installedExisting = install();
            int value = status();
            Log.i(TAG, "modern native library loaded status=" + value
                    + " installedExisting=" + installedExisting);
            HookRuntime.log("modern native library loaded status=" + value
                    + " installedExisting=" + installedExisting);
            return value;
        } catch (Throwable error) {
            Log.e(TAG, "modern native library load failed", error);
            HookRuntime.log("modern native library load failed", error);
            return -1;
        }
    }

    static native boolean install();
    static native boolean isInstalled();
    static native int status();
}
