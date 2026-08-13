package dev.recallguard.qq;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/** Modern libxposed API 102 entry for the QQ and ColorOS MCS target processes. */
public final class HookEntry extends XposedModule {
    private static final String QQ_PACKAGE = "com.tencent.mobileqq";
    private static final String TAG = "QQRecallGuard";
    private static final String MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean KERNEL_BOUNDARY_ATTEMPTED = new AtomicBoolean(false);
    private static final ConcurrentHashMap<Integer, Long> RECENT_PUSHES =
            new ConcurrentHashMap<>();
    private static volatile ClassLoader hostClassLoader;
    private static volatile String processName;
    private static volatile Handler pushWorker;
    private static volatile int earlyNativeStatus = -1;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        HookRuntime.attach(this);
        processName = param.getProcessName();
        if (!QQ_PACKAGE.equals(processName)
                && !PushRelayMcs.MCS_PACKAGE.equals(processName)) {
            detach();
            return;
        }
        HookRuntime.log("modern entry loaded process=" + processName
                + " api=" + getApiVersion() + " framework=" + getFrameworkVersion());
        if (QQ_PACKAGE.equals(processName)) {
            HookRuntime.log("registering native load callback at module-load");
            earlyNativeStatus = NativeBridge.ensureLoaded();
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (QQ_PACKAGE.equals(processName) && QQ_PACKAGE.equals(param.getPackageName())) {
            HookRuntime.log("native callback ready before package code status="
                    + earlyNativeStatus);
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (PushRelayMcs.MCS_PACKAGE.equals(processName)
                && PushRelayMcs.MCS_PACKAGE.equals(packageName)) {
            if (INSTALLED.compareAndSet(false, true)) PushRelayMcs.install();
            return;
        }
        if (!QQ_PACKAGE.equals(processName) || !QQ_PACKAGE.equals(packageName)) return;
        if (!INSTALLED.compareAndSet(false, true)) return;

        long started = SystemClock.elapsedRealtime();
        ClassLoader loader = param.getClassLoader();
        hostClassLoader = loader;
        PushSnapshotStore.installEarlyCapture(loader);
        RestoredSenderIdentity.install(loader);
        hookKernelLoadBoundary(loader);
        scheduleBoundedNativeFallback();
        hookColdSyncRecords(loader);
        hookProtocolStream(loader);
        int nativeStatus = earlyNativeStatus < 0
                ? NativeBridge.ensureLoaded() : NativeBridge.status();
        Log.i(TAG, "modern anti-recall installed setupMs="
                + (SystemClock.elapsedRealtime() - started)
                + " nativeStatus=" + nativeStatus);
        HookRuntime.log("modern anti-recall setup complete nativeStatus=" + nativeStatus);
    }

    /** Event-driven fallback for QQ's linker path, which does not emit LSPosed's dlopen callback. */
    private static void hookKernelLoadBoundary(ClassLoader hostLoader) {
        try {
            Class<?> setter = Class.forName(
                    "com.tencent.qqnt.kernel.api.impl.KernelSetterImpl", false, hostLoader);
            HookRuntime.hookAllConstructors(setter, 10_000, new HookRuntime.Callback() {
                @Override
                void before(HookRuntime.HookParam param) {
                    if (!KERNEL_BOUNDARY_ATTEMPTED.compareAndSet(false, true)) return;
                    long started = SystemClock.elapsedRealtime();
                    boolean installed = NativeBridge.isInstalled() || NativeBridge.install();
                    int status = NativeBridge.status();
                    Log.i(TAG, "event-driven kernel boundary installed=" + installed
                            + " status=" + status + " scanMs="
                            + (SystemClock.elapsedRealtime() - started));
                    HookRuntime.log("event-driven kernel boundary installed=" + installed
                            + " status=" + status);
                }
            });
            HookRuntime.log("KernelSetterImpl event boundary installed");
        } catch (Throwable error) {
            HookRuntime.log("KernelSetterImpl event boundary unavailable", error);
        }
    }

    /**
     * QQ's private linker does not consistently reach the framework dlopen callback, and the
     * KernelSetter constructor can precede onPackageReady. Probe the mapping at four bounded,
     * exponentially spaced worker-thread boundaries. Native code guarantees that the expensive
     * signature scan itself runs at most once after libkernel.so appears.
     */
    private static void scheduleBoundedNativeFallback() {
        final long[] delaysMs = {50L, 200L, 800L, 2000L};
        Handler worker = pushHandler();
        for (int index = 0; index < delaysMs.length; index++) {
            final int attempt = index + 1;
            worker.postDelayed(() -> {
                if (NativeBridge.isInstalled()) {
                    if (attempt == delaysMs.length) {
                        int status = NativeBridge.status();
                        Log.i(TAG, "bounded native fallback settled status=" + status);
                        HookRuntime.log("bounded native fallback settled status=" + status);
                    }
                    return;
                }
                long started = SystemClock.elapsedRealtime();
                boolean installed = NativeBridge.install();
                int status = NativeBridge.status();
                if (installed || attempt == delaysMs.length) {
                    Log.i(TAG, "bounded native fallback attempt=" + attempt
                            + " installed=" + installed + " status=" + status
                            + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
                    HookRuntime.log("bounded native fallback attempt=" + attempt
                            + " installed=" + installed + " status=" + status);
                }
            }, delaysMs[index]);
        }
    }

    /**
     * Normal records never traverse their element list. Only a positive recallTime getter result
     * enters the compatibility path; persistent offline tombstones are inspected by the bounded
     * history query that runs only when the private cache has pending candidates.
     */
    private static void hookColdSyncRecords(ClassLoader hostLoader) {
        try {
            Class<?> record = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.MsgRecord", false, hostLoader);
            Field recallTime = record.getField("recallTime");
            Method getSeq = record.getMethod("getMsgSeq");
            Method getType = record.getMethod("getMsgType");
            Method getSubType = record.getMethod("getSubMsgType");
            Method getChatType = record.getMethod("getChatType");
            Method getElements = record.getMethod("getElements");
            Method getPeerUid = record.getMethod("getPeerUid");
            Method getMsgTime = record.getMethod("getMsgTime");

            HookRuntime.hook(record.getMethod("getRecallTime"), -100,
                    new HookRuntime.Callback() {
                        @Override
                        void after(HookRuntime.HookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof Long) || (Long) result <= 0L) return;
                            neutralizeRecallMarker(param.thisObject, recallTime, getSeq, getType,
                                    getSubType, getChatType, getElements, getPeerUid, getMsgTime);
                            param.setResult(0L);
                        }
                    });

            Class<?> listener = Class.forName(
                    "com.tencent.qqnt.kernel.api.impl.kn", false, hostLoader);
            hookSyncBoundary(listener, "onNtMsgSyncStart");
            hookSyncBoundary(listener, "onNtFirstViewMsgSyncEnd");
            hookSyncBoundary(listener, "onNtMsgSyncEnd");
            Log.i(TAG, "cold-sync narrow observers installed");
        } catch (Throwable error) {
            Log.e(TAG, "hookColdSyncRecords failed", error);
        }
    }

    private static void hookSyncBoundary(Class<?> listener, String methodName) {
        HookRuntime.hookAllMethods(listener, methodName, -100, new HookRuntime.Callback() {
            @Override
            void after(HookRuntime.HookParam param) {
                if ("onNtFirstViewMsgSyncEnd".equals(methodName)
                        || "onNtMsgSyncEnd".equals(methodName)) {
                    PushSnapshotStore.onSyncEnd();
                }
            }
        });
    }

    private static void neutralizeRecallMarker(Object value, Field recallTime, Method getSeq,
            Method getType, Method getSubType, Method getChatType, Method getElements,
            Method getPeerUid, Method getMsgTime) {
        try {
            observeRecallTombstone(value, getSeq, getType, getSubType, getChatType,
                    getElements, getPeerUid, getMsgTime);
            long markedAt = recallTime.getLong(value);
            if (markedAt <= 0L) return;
            Log.w(TAG, "positive recallTime neutralized chatType=" + getChatType.invoke(value)
                    + " type=" + getType.invoke(value) + '/' + getSubType.invoke(value)
                    + " seq=" + getSeq.invoke(value));
            recallTime.setLong(value, 0L);
        } catch (Throwable error) {
            Log.e(TAG, "neutralizeRecallMarker failed", error);
        }
    }

    private static void observeRecallTombstone(Object value, Method getSeq, Method getType,
            Method getSubType, Method getChatType, Method getElements,
            Method getPeerUid, Method getMsgTime) {
        try {
            Object rawElements = getElements.invoke(value);
            if (!(rawElements instanceof List)) return;
            Object revokeElement = findRevokeElement((List<?>) rawElements);
            if (revokeElement == null) return;
            int chatType = ((Number) getChatType.invoke(value)).intValue();
            String peerUid = String.valueOf(getPeerUid.invoke(value));
            long seq = ((Number) getSeq.invoke(value)).longValue();
            long msgTime = ((Number) getMsgTime.invoke(value)).longValue();
            Log.i(TAG, "recall gray record type=" + getType.invoke(value)
                    + "/" + getSubType.invoke(value) + " seq=" + seq);
            PushSnapshotStore.onRecallTombstone(
                    chatType, peerUid, seq, msgTime, revokeElement);
        } catch (Throwable error) {
            Log.e(TAG, "observeRecallTombstone failed", error);
        }
    }

    private static Object findRevokeElement(List<?> elements) throws Exception {
        for (Object element : elements) {
            if (element == null) continue;
            Class<?> type = element.getClass();
            Method elementType = ElementAccess.method(type, "getElementType");
            Object value = elementType.invoke(element);
            if (!(value instanceof Number) || ((Number) value).intValue() != 8) continue;
            Object gray = ElementAccess.method(type, "getGrayTipElement").invoke(element);
            if (gray == null) continue;
            Object revoke = ElementAccess.method(gray.getClass(), "getRevokeElement").invoke(gray);
            if (revoke != null) return revoke;
        }
        return null;
    }

    private static void hookProtocolStream(ClassLoader hostLoader) {
        try {
            Class<?> proxy = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession$CppProxy",
                    false, hostLoader);
            int count = 0;
            for (Method method : proxy.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!"onMsfPush".equals(method.getName()) || params.length < 2
                        || params[0] != String.class || params[1] != byte[].class) continue;
                method.setAccessible(true);
                HookRuntime.hook(method, -51, new HookRuntime.Callback() {
                    @Override
                    void after(HookRuntime.HookParam param) {
                        enqueuePush((String) param.args[0], (byte[]) param.args[1],
                                hostLoader, "java");
                    }
                });
                count++;
            }
            if (count == 0) {
                throw new NoSuchMethodException("IQQNTWrapperSession.CppProxy.onMsfPush");
            }
            HookRuntime.log("protocol observers installed=" + count);
        } catch (Throwable error) {
            Log.e(TAG, "hookProtocolStream failed", error);
            HookRuntime.log("hookProtocolStream failed", error);
        }
    }

    /** Called after QQ's original native onMsfPush returns. */
    static void onNativePush(String command, byte[] payload) {
        ClassLoader loader = hostClassLoader;
        if (loader != null) enqueuePush(command, payload, loader, "native");
    }

    private static void enqueuePush(
            String command, byte[] payload, ClassLoader loader, String source) {
        if (!MSG_PUSH.equals(command) || payload == null) return;
        int fingerprint = 31 * payload.length + Arrays.hashCode(payload);
        long now = SystemClock.elapsedRealtime();
        Long previous = RECENT_PUSHES.put(fingerprint, now);
        if (previous != null && now - previous < 5000L) return;
        if (RECENT_PUSHES.size() > 64) {
            RECENT_PUSHES.entrySet().removeIf(entry -> now - entry.getValue() > 30000L);
        }
        pushHandler().post(() -> dispatchPush(payload, loader, source));
    }

    private static void dispatchPush(byte[] payload, ClassLoader loader, String source) {
        try {
            List<RecallEvent> recalls = RecallParser.parseMsgPush(payload);
            if (!recalls.isEmpty()) {
                Log.i(TAG, "recall push parsed source=" + source + " count=" + recalls.size());
            }
            for (RecallEvent event : recalls) {
                Looper mainLooper = Looper.getMainLooper();
                if (mainLooper != null) {
                    new Handler(mainLooper).postDelayed(
                            () -> QQBridge.addRecallTip(event, loader), 250L);
                } else {
                    QQBridge.addRecallTip(event, loader);
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "recall protocol parse failed source=" + source, error);
        }
    }

    private static Handler pushHandler() {
        Handler value = pushWorker;
        if (value != null) return value;
        synchronized (HookEntry.class) {
            value = pushWorker;
            if (value == null) {
                HandlerThread thread = new HandlerThread("QQRecallGuard-push");
                thread.start();
                value = new Handler(thread.getLooper());
                pushWorker = value;
            }
            return value;
        }
    }

    /** Per-class reflection cache used only after the cheap elementType filter is relevant. */
    private static final class ElementAccess {
        private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();

        static Method method(Class<?> type, String name) throws NoSuchMethodException {
            String key = type.getName() + '#' + name;
            Method cached = METHODS.get(key);
            if (cached != null) return cached;
            Method resolved = type.getMethod(name);
            resolved.setAccessible(true);
            Method raced = METHODS.putIfAbsent(key, resolved);
            return raced == null ? resolved : raced;
        }
    }
}
