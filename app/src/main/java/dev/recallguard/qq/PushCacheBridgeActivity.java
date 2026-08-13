package dev.recallguard.qq;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;

import java.util.Arrays;

/** No-display, caller-validated IPC trampoline into the module-owned provider. */
public final class PushCacheBridgeActivity extends Activity {
    private static final String TAG = "QQRecallGuard";
    private static final String QQ_PACKAGE = "com.tencent.mobileqq";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        String method = intent == null ? ""
                : intent.getStringExtra(PushCacheContract.EXTRA_METHOD);
        String expected = PushCacheContract.METHOD_PUT.equals(method)
                ? PushRelayMcs.MCS_PACKAGE : QQ_PACKAGE;
        Bundle result;
        if (!isExpectedCaller(expected)) {
            Log.e(TAG, "rejected push cache activity caller method=" + method);
            result = failed();
        } else {
            grantProviderVisibility();
            Bundle payload = intent == null ? null
                    : intent.getBundleExtra(PushCacheContract.EXTRA_PAYLOAD);
            result = PushCacheContract.callProviderDirect(this, method,
                    payload == null ? Bundle.EMPTY : payload);
            if (result == null) result = failed();
            Log.i(TAG, "push cache activity completed method=" + method
                    + " ok=" + result.getBoolean(PushCacheContract.KEY_OK, false));
        }
        ResultReceiver receiver = intent == null ? null
                : intent.getParcelableExtra(PushCacheContract.EXTRA_RESULT,
                        ResultReceiver.class);
        if (receiver != null) receiver.send(0, result);
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    private boolean isExpectedCaller(String expectedPackage) {
        try {
            int uid = getLaunchedFromUid();
            String launchedFrom = getLaunchedFromPackage();
            PackageManager manager = getPackageManager();
            String[] packages = manager.getPackagesForUid(uid);
            if (expectedPackage.equals(launchedFrom) && packages != null
                    && Arrays.asList(packages).contains(expectedPackage)) return true;

            PendingIntent proof = getIntent().getParcelableExtra(
                    PushCacheContract.EXTRA_CALLER_PROOF, PendingIntent.class);
            if (proof == null || !expectedPackage.equals(proof.getCreatorPackage())) {
                Log.e(TAG, "push cache caller mismatch launchedFrom=" + launchedFrom
                        + " uid=" + uid);
                return false;
            }
            String[] creatorPackages = manager.getPackagesForUid(proof.getCreatorUid());
            return creatorPackages != null
                    && Arrays.asList(creatorPackages).contains(expectedPackage);
        } catch (Throwable error) {
            Log.e(TAG, "cannot validate push cache activity caller", error);
            return false;
        }
    }

    private void grantProviderVisibility() {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        tryGrant(this, PushRelayMcs.MCS_PACKAGE, flags);
        tryGrant(this, QQ_PACKAGE, flags);
    }

    private static void tryGrant(Context context, String packageName, int flags) {
        try {
            context.grantUriPermission(packageName, PushCacheContract.URI, flags);
        } catch (Throwable error) {
            Log.w(TAG, "URI visibility grant unavailable for " + packageName, error);
        }
    }

    private static Bundle failed() {
        Bundle value = new Bundle();
        value.putBoolean(PushCacheContract.KEY_OK, false);
        return value;
    }
}
