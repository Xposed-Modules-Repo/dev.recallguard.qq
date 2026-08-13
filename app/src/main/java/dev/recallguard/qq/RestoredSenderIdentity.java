package dev.recallguard.qq;

import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repairs only the in-memory AIO model for a module-created restored bubble.
 *
 * The local kernel record intentionally remains the minimal record accepted by
 * addLocalRecordMsgWithExtInfos.  Before QQ builds AIOMsgItem's refresh keys and avatar model,
 * this hook copies the sender carried by QQRG's private element marker into that one Java
 * MsgRecord instance.  Nothing is written back to the NT database.
 */
final class RestoredSenderIdentity {
    private static final String TAG = "QQRecallGuard";
    private static final Set<Long> LOGGED_MSG_IDS = ConcurrentHashMap.newKeySet();

    private RestoredSenderIdentity() {}

    static void install(ClassLoader hostLoader) {
        try {
            Class<?> recordType = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.MsgRecord", false, hostLoader);
            Class<?> itemType = Class.forName(
                    "com.tencent.mobileqq.aio.msg.AIOMsgItem", false, hostLoader);
            HookRuntime.hook(itemType.getDeclaredConstructor(recordType), 10_000,
                    new HookRuntime.Callback() {
                        @Override
                        void before(HookRuntime.HookParam param) {
                            if (param.args.length == 0 || param.args[0] == null) return;
                            apply(param.args[0], hostLoader);
                        }
                    });
            HookRuntime.log("restored sender UI identity hook installed");
        } catch (Throwable error) {
            Log.e(TAG, "restored sender UI identity hook unavailable", error);
            HookRuntime.log("restored sender UI identity hook unavailable", error);
        }
    }

    private static void apply(Object record, ClassLoader hostLoader) {
        try {
            JSONObject marker = findMarker(record);
            if (marker == null) return;

            Class<?> type = record.getClass();
            int chatType = type.getField("chatType").getInt(record);
            int markerChatType = marker.optInt("chatType", chatType);
            if (markerChatType != chatType) return;

            String senderUid = marker.optString("senderUid", "").trim();
            String senderUin = marker.optString("senderUin", "").trim();

            // Version-1 records (including the already restored test9 bubble) did not carry
            // sender fields. In C2C the peer is necessarily the visual sender.
            if (chatType == RecallEvent.C2C && senderUid.isEmpty()) {
                senderUid = stringField(type, record, "peerUid");
            }
            if (chatType == RecallEvent.C2C && senderUin.isEmpty()) {
                long peerUin = type.getField("peerUin").getLong(record);
                if (peerUin > 0L) senderUin = Long.toString(peerUin);
            }
            if (senderUid.isEmpty() && isPositiveUin(senderUin)) {
                senderUid = QQBridge.uinToUid(senderUin, hostLoader);
            }
            if (senderUin.isEmpty() && !senderUid.isEmpty()) {
                senderUin = QQBridge.uidToUin(senderUid, hostLoader);
            }
            if (senderUid.isEmpty() || !isPositiveUin(senderUin)) return;

            type.getField("senderUid").set(record, senderUid);
            type.getField("senderUin").setLong(record, Long.parseLong(senderUin));
            setNonEmpty(type, record, "sendNickName", marker.optString("senderNick", ""));
            setNonEmpty(type, record, "sendRemarkName", marker.optString("senderRemark", ""));
            setNonEmpty(type, record, "sendMemberName",
                    marker.optString("senderMemberName", ""));
            long msgId = type.getField("msgId").getLong(record);
            if (LOGGED_MSG_IDS.add(msgId)) {
                Log.i(TAG, "restored sender UI identity applied chatType=" + chatType);
            }
        } catch (Throwable error) {
            Log.w(TAG, "restored sender UI identity skipped", error);
        }
    }

    private static JSONObject findMarker(Object record) throws Exception {
        Field elementsField = record.getClass().getField("elements");
        Object rawElements = elementsField.get(record);
        if (!(rawElements instanceof List)) return null;
        for (Object element : (List<?>) rawElements) {
            if (element == null) continue;
            Field extField = element.getClass().getField("extBufForUI");
            Object raw = extField.get(element);
            if (!(raw instanceof byte[])) continue;
            String encoded = new String((byte[]) raw, StandardCharsets.UTF_8);
            if (!encoded.startsWith(QQBridge.RESTORE_MARKER_PREFIX)) continue;
            JSONObject marker = new JSONObject(
                    encoded.substring(QQBridge.RESTORE_MARKER_PREFIX.length()));
            int version = marker.optInt("v", 0);
            if ((version == 1 || version == 2)
                    && !marker.optString("pushKey", "").isEmpty()
                    && !marker.optString("tombstoneKey", "").isEmpty()) {
                return marker;
            }
        }
        return null;
    }

    private static String stringField(Class<?> type, Object value, String name) throws Exception {
        Object result = type.getField(name).get(value);
        return result instanceof String ? ((String) result).trim() : "";
    }

    private static void setNonEmpty(Class<?> type, Object target, String fieldName, String value)
            throws Exception {
        if (value == null || value.trim().isEmpty()) return;
        type.getField(fieldName).set(target, value.trim());
    }

    private static boolean isPositiveUin(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            return Long.parseLong(value) > 0L;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
