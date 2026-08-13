package dev.recallguard.qq;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class QQBridge {
    private static final String TAG = "QQRecallGuard";
    static final String RESTORE_MARKER_PREFIX = "QQRG1:";
    private static volatile Object kernelMsgService;
    private static volatile Object kernelMsgServiceApi;

    private QQBridge() {}

    static void addRecallTip(RecallEvent event, ClassLoader hostLoader) {
        try {
            Object service = getKernelMsgService(hostLoader);
            Object contact = newKernelObject(hostLoader,
                    "com.tencent.qqnt.kernelpublic.nativeinterface.Contact",
                    "com.tencent.qqnt.kernel.nativeinterface.Contact",
                    event.chatType, event.peerUid, "");

            JSONObject root = new JSONObject();
            root.put("align", "center");
            JSONArray items = new JSONArray();

            if (event.chatType == RecallEvent.GROUP && event.operatorUid != null && !event.operatorUid.isEmpty()) {
                String operatorUin = uidToUin(event.operatorUid, hostLoader);
                String operatorName = resolveGroupMemberName(event.peerUid, operatorUin, hostLoader);
                JSONObject user = new JSONObject();
                user.put("type", "qq");
                user.put("col", "3");
                user.put("tp", "0");
                user.put("uid", event.operatorUid);
                user.put("jp", event.operatorUid);
                user.put("nm", operatorName);
                if (!operatorUin.isEmpty()) user.put("uin", operatorUin);
                items.put(user);
                items.put(text("尝试撤回"));
            } else {
                items.put(text("对方尝试撤回"));
            }

            JSONObject locator = new JSONObject();
            locator.put("type", "url");
            locator.put("txt", "一条消息");
            locator.put("col", "3");
            locator.put("local_jp", 58);
            JSONObject params = new JSONObject();
            params.put("seq", event.msgSeq);
            locator.put("param", params);
            items.put(locator);
            root.put("items", items);

            long busiId = event.chatType == RecallEvent.C2C ? 2021L : 2022L;
            Object grayElement = newKernelObject(hostLoader,
                    "com.tencent.qqnt.kernelpublic.nativeinterface.JsonGrayElement",
                    "com.tencent.qqnt.kernel.nativeinterface.JsonGrayElement",
                    busiId, root.toString(), "尝试撤回一条消息", false, null);

            Method target = null;
            for (Method method : service.getClass().getMethods()) {
                if (method.getName().equals("addLocalJsonGrayTipMsg") && method.getParameterCount() == 5) {
                    target = method;
                    break;
                }
            }
            if (target == null) throw new NoSuchMethodException("addLocalJsonGrayTipMsg");
            target.setAccessible(true);
            target.invoke(service, contact, grayElement, true, true, null);
            Log.i(TAG, "native locator inserted chatType=" + event.chatType + " seq=" + event.msgSeq);
        } catch (Throwable error) {
            Log.e(TAG, "addRecallTip failed", error);
        }
    }

    static boolean addPreservedPushText(int chatType, String peerUid, String content,
            long postTimeSeconds, long recalledSeq, String pushKey, String tombstoneKey,
            String senderUid, String senderUin, String senderNick, String senderRemark,
            String senderMemberName, ClassLoader hostLoader, Completion completion) {
        try {
            Object service = getKernelMsgService(hostLoader);
            Object contact = newKernelObject(hostLoader,
                    "com.tencent.qqnt.kernelpublic.nativeinterface.Contact",
                    "com.tencent.qqnt.kernel.nativeinterface.Contact",
                    chatType, peerUid, "");

            String body = "【该消息已撤回】\n" + content;
            Class<?> textType = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.TextElement", false, hostLoader);
            Object textElement = textType.getDeclaredConstructor().newInstance();
            setField(textElement, "content", body);

            Class<?> elementType = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.MsgElement", false, hostLoader);
            Object msgElement = elementType.getDeclaredConstructor().newInstance();
            setField(msgElement, "elementType", 1);
            setField(msgElement, "textElement", textElement);
            JSONObject marker = new JSONObject();
            marker.put("v", 2);
            marker.put("pushKey", pushKey);
            marker.put("tombstoneKey", tombstoneKey);
            marker.put("chatType", chatType);
            marker.put("senderUid", senderUid == null ? "" : senderUid);
            marker.put("senderUin", senderUin == null ? "" : senderUin);
            marker.put("senderNick", senderNick == null ? "" : senderNick);
            marker.put("senderRemark", senderRemark == null ? "" : senderRemark);
            marker.put("senderMemberName", senderMemberName == null ? "" : senderMemberName);
            setField(msgElement, "extBufForUI", (RESTORE_MARKER_PREFIX + marker)
                    .getBytes(StandardCharsets.UTF_8));

            Class<?> infoType = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.AddLocalMsgInfo",
                    false, hostLoader);
            Object msgInfo = infoType.getDeclaredConstructor().newInstance();
            long uniqueId = localUniqueId(pushKey, tombstoneKey);
            setField(msgInfo, "msgId", uniqueId);
            setField(msgInfo, "msgSeq", recalledSeq);
            setField(msgInfo, "cliSeq", uniqueId);
            setField(msgInfo, "msgTime", postTimeSeconds);
            setField(msgInfo, "msgRandom", uniqueId ^ 0x51475247L);

            Class<?> paramsType = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.AddLocalRecordMsgParams",
                    false, hostLoader);
            Object params = paramsType.getDeclaredConstructor().newInstance();
            setField(params, "msgElement", msgElement);
            setField(params, "msgInfo", msgInfo);
            setField(params, "front", false);
            setField(params, "needNotify", false);
            setField(params, "needRecentContact", true);
            setField(params, "needStore", true);

            Class<?> callbackType = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback",
                    false, hostLoader);
            InvocationHandler handler = (proxy, method, args) -> {
                if ("onResult".equals(method.getName())) {
                    int code = args != null && args.length > 0 && args[0] instanceof Number
                            ? ((Number) args[0]).intValue() : -1;
                    String message = args != null && args.length > 1
                            ? String.valueOf(args[1]) : "";
                    Log.i(TAG, "local preserved text result code=" + code
                            + " message=" + message);
                    if (completion != null) completion.onComplete(code == 0);
                    return null;
                }
                if (method.getDeclaringClass() == Object.class) {
                    if ("toString".equals(method.getName())) return "QQRecallGuardLocalCallback";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) {
                        return args != null && args.length == 1 && proxy == args[0];
                    }
                }
                return null;
            };
            Object callback = Proxy.newProxyInstance(
                    hostLoader, new Class<?>[]{callbackType}, handler);

            Method target = null;
            for (Method method : service.getClass().getMethods()) {
                if (method.getName().equals("addLocalRecordMsgWithExtInfos")
                        && method.getParameterCount() == 4) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                throw new NoSuchMethodException("addLocalRecordMsgWithExtInfos");
            }
            target.setAccessible(true);
            target.invoke(service, contact, 2L, params, callback);
            Log.i(TAG, "local preserved text submitted chatType=" + chatType
                    + " seq=" + recalledSeq + " textLength=" + content.length()
                    + " localId=" + uniqueId);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "addPreservedPushText failed", error);
            if (completion != null) completion.onComplete(false);
            return false;
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static long localUniqueId(String pushKey, String tombstoneKey) {
        String value = pushKey + '\u0000' + tombstoneKey;
        long hash = 0xcbf29ce484222325L;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte item : bytes) {
            hash ^= item & 0xffL;
            hash *= 0x100000001b3L;
        }
        // Positive high-range id reserved by this module; deterministic across crash recovery.
        return 0x6000000000000000L | (hash & 0x0fffffffffffffffL);
    }

    interface Completion {
        void onComplete(boolean success);
    }

    private static JSONObject text(String value) throws Exception {
        JSONObject item = new JSONObject();
        item.put("type", "nor");
        item.put("txt", value);
        return item;
    }

    private static Object getKernelMsgService(ClassLoader hostLoader) throws Exception {
        Object cached = kernelMsgService;
        if (cached != null) return cached;

        Object appRuntime = getAppRuntime(hostLoader);
        if (appRuntime == null) throw new IllegalStateException("AppRuntime is null");

        Class<?> kernelServiceApi = Class.forName("com.tencent.qqnt.kernel.api.IKernelService", false, hostLoader);
        Method getRuntimeService = findMethod(appRuntime.getClass(), "getRuntimeService", 2);
        Object kernelService = getRuntimeService.invoke(appRuntime, kernelServiceApi, "");
        Object msgService = findMethod(kernelService.getClass(), "getMsgService", 0).invoke(kernelService);

        try {
            cached = findMethod(msgService.getClass(), "getService", 0).invoke(msgService);
        } catch (NoSuchMethodException ignored) {
            cached = msgService;
        }
        kernelMsgService = cached;
        return cached;
    }

    private static Object getAppRuntime(ClassLoader hostLoader) throws Exception {
        Class<?> mobileQQ = Class.forName("mqq.app.MobileQQ", false, hostLoader);
        Object mobile = mobileQQ.getField("sMobileQQ").get(null);
        return mobileQQ.getMethod("peekAppRuntime").invoke(mobile);
    }

    static String uidToUin(String uid, ClassLoader hostLoader) {
        try {
            Class<?> qRoute = Class.forName("com.tencent.mobileqq.qroute.QRoute", false, hostLoader);
            Class<?> relationApi = Class.forName("com.tencent.relation.common.api.IRelationNTUinAndUidApi", false, hostLoader);
            Object api = qRoute.getMethod("api", Class.class).invoke(null, relationApi);
            Object value = relationApi.getMethod("getUinFromUid", String.class).invoke(api, uid);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    static String uinToUid(String uin, ClassLoader hostLoader) {
        try {
            Class<?> qRoute = Class.forName(
                    "com.tencent.mobileqq.qroute.QRoute", false, hostLoader);
            Class<?> relationApi = Class.forName(
                    "com.tencent.relation.common.api.IRelationNTUinAndUidApi",
                    false, hostLoader);
            Object api = qRoute.getMethod("api", Class.class).invoke(null, relationApi);
            Object value = relationApi.getMethod("getUidFromUin", String.class)
                    .invoke(api, uin);
            return value instanceof String ? (String) value : "";
        } catch (Throwable error) {
            Log.w(TAG, "uinToUid failed for " + uin, error);
            return "";
        }
    }

    static boolean queryLatestRecords(int chatType, String peerUid, ClassLoader hostLoader) {
        try {
            Object service = getKernelMsgServiceApi(hostLoader);
            Object contact = newKernelObject(hostLoader,
                    "com.tencent.qqnt.kernelpublic.nativeinterface.Contact",
                    "com.tencent.qqnt.kernel.nativeinterface.Contact",
                    chatType, peerUid, "");
            Class<?> callbackType = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback",
                    false, hostLoader);
            InvocationHandler handler = (proxy, method, args) -> {
                if ("onResult".equals(method.getName()) && args != null && args.length >= 3
                        && args[2] instanceof List) {
                    int code = args[0] instanceof Number ? ((Number) args[0]).intValue() : -1;
                    Log.i(TAG, "latest record query result code=" + code
                            + " count=" + ((List<?>) args[2]).size());
                    PushSnapshotStore.onHistoryRecords((List<?>) args[2]);
                }
                if (method.getDeclaringClass() == Object.class) {
                    if ("toString".equals(method.getName())) return "QQRecallGuardHistoryCallback";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) {
                        return args != null && args.length == 1 && proxy == args[0];
                    }
                }
                return null;
            };
            Object callback = Proxy.newProxyInstance(
                    hostLoader, new Class<?>[]{callbackType}, handler);
            Method target = null;
            for (Method method : service.getClass().getMethods()) {
                if ("getLatestDbMsgs".equals(method.getName())
                        && method.getParameterCount() == 3) {
                    target = method;
                    break;
                }
            }
            if (target == null) throw new NoSuchMethodException("getLatestDbMsgs");
            target.setAccessible(true);
            target.invoke(service, contact, 80, callback);
            Log.i(TAG, "latest record query submitted chatType=" + chatType);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "queryLatestRecords failed chatType=" + chatType, error);
            return false;
        }
    }

    private static Object getKernelMsgServiceApi(ClassLoader hostLoader) throws Exception {
        Object cached = kernelMsgServiceApi;
        if (cached != null) return cached;
        Object appRuntime = getAppRuntime(hostLoader);
        if (appRuntime == null) throw new IllegalStateException("AppRuntime is null");
        Class<?> kernelServiceApi = Class.forName(
                "com.tencent.qqnt.kernel.api.IKernelService", false, hostLoader);
        Method getRuntimeService = findMethod(appRuntime.getClass(), "getRuntimeService", 2);
        Object kernelService = getRuntimeService.invoke(appRuntime, kernelServiceApi, "");
        cached = findMethod(kernelService.getClass(), "getMsgService", 0).invoke(kernelService);
        kernelMsgServiceApi = cached;
        return cached;
    }

    /** QQ 9.2.60: resolve the actual group card/remark through the host's ContactUtils. */
    private static String resolveGroupMemberName(String groupUin, String memberUin, ClassLoader hostLoader) {
        if (memberUin == null || memberUin.isEmpty()) return "群成员";
        try {
            Object appRuntime = getAppRuntime(hostLoader);
            Class<?> contactUtils = Class.forName("com.tencent.mobileqq.utils.ab", false, hostLoader);
            for (Method method : contactUtils.getDeclaredMethods()) {
                if (!method.getName().equals("x") || !Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != String.class || method.getParameterCount() != 3) continue;
                method.setAccessible(true);
                Object result = method.invoke(null, appRuntime, groupUin, memberUin);
                if (result instanceof String && !((String) result).trim().isEmpty()) {
                    return ((String) result).replace("\u202E", "");
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "group member name fallback", error);
        }
        return memberUin;
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + '.' + name);
    }

    private static Object newKernelObject(ClassLoader loader, String preferred, String fallback, Object... args) throws Exception {
        Class<?> type;
        try {
            type = Class.forName(preferred, false, loader);
        } catch (ClassNotFoundException ignored) {
            type = Class.forName(fallback, false, loader);
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != args.length) continue;
            constructor.setAccessible(true);
            try {
                return constructor.newInstance(args);
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                // Try the next overload with the same arity.
            }
        }
        throw new NoSuchMethodException("constructor " + type.getName() + "/" + args.length);
    }
}
