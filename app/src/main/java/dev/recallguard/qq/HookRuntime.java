package dev.recallguard.qq;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Small adapter that keeps hook sites readable while using only libxposed API 102. */
final class HookRuntime {
    private static final String TAG = "QQRecallGuard";
    private static volatile XposedModule module;

    private HookRuntime() {}

    static void attach(XposedModule value) {
        module = value;
    }

    static void log(String message) {
        XposedModule value = module;
        if (value != null) value.log(Log.INFO, TAG, message);
        else Log.i(TAG, message);
    }

    static void log(String message, Throwable error) {
        XposedModule value = module;
        if (value != null) value.log(Log.ERROR, TAG, message, error);
        else Log.e(TAG, message, error);
    }

    static List<XposedInterface.HookHandle> hookAllMethods(
            Class<?> type, String name, int priority, Callback callback) {
        List<XposedInterface.HookHandle> handles = tryHookAllMethods(
                type, name, priority, callback);
        if (handles.isEmpty()) {
            throw new IllegalArgumentException("No method " + type.getName() + '.' + name);
        }
        return handles;
    }

    static List<XposedInterface.HookHandle> tryHookAllMethods(
            Class<?> type, String name, int priority, Callback callback) {
        ArrayList<XposedInterface.HookHandle> handles = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (!name.equals(method.getName())) continue;
            method.setAccessible(true);
            handles.add(hook(method, priority, callback));
        }
        return handles;
    }

    static List<XposedInterface.HookHandle> hookAllConstructors(
            Class<?> type, int priority, Callback callback) {
        ArrayList<XposedInterface.HookHandle> handles = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            constructor.setAccessible(true);
            handles.add(hook(constructor, priority, callback));
        }
        if (handles.isEmpty()) {
            throw new IllegalArgumentException("No constructor " + type.getName());
        }
        return handles;
    }

    static XposedInterface.HookHandle hook(
            Executable executable, int priority, Callback callback) {
        XposedModule value = module;
        if (value == null) throw new IllegalStateException("libxposed is not attached");
        return value.hook(executable)
                .setPriority(priority)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> intercept(chain, callback));
    }

    private static Object intercept(XposedInterface.Chain chain, Callback callback)
            throws Throwable {
        HookParam param = new HookParam(chain.getThisObject(), chain.getArgs().toArray());
        callback.before(param);
        if (!param.returnEarly) {
            try {
                param.result = chain.proceed(param.args);
            } catch (Throwable error) {
                param.throwable = error;
            }
        }
        callback.after(param);
        if (param.throwable != null) throw param.throwable;
        return param.result;
    }

    abstract static class Callback {
        void before(HookParam param) throws Throwable {}
        void after(HookParam param) throws Throwable {}
    }

    static final class HookParam {
        final Object thisObject;
        final Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        HookParam(Object thisObject, Object[] args) {
            this.thisObject = thisObject;
            this.args = args;
        }

        Object getResult() {
            return result;
        }

        void setResult(Object value) {
            result = value;
            throwable = null;
            returnEarly = true;
        }

        @Override
        public String toString() {
            return "HookParam{" + thisObject + ", args=" + Arrays.toString(args) + '}';
        }
    }
}
