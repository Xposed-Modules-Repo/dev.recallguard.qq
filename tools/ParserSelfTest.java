package dev.recallguard.qq;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Host-side, dependency-free smoke test for the two recall protobuf layouts. */
public final class ParserSelfTest {
    public static void main(String[] args) {
        List<RecallEvent> c2c = RecallParser.parseMsgPush(msgPush(528, 138,
                fieldBytes(1, concat(fieldString(1, "u_friend"), fieldVarint(20, 321)))));
        require(c2c.size() == 1, "C2C count");
        require(c2c.get(0).chatType == RecallEvent.C2C, "C2C chat type");
        require("u_friend".equals(c2c.get(0).peerUid), "C2C peer");
        require(c2c.get(0).msgSeq == 321, "C2C seq");

        byte[] groupInfo = concat(fieldString(1, "u_operator"),
                fieldBytes(3, concat(fieldVarint(1, 654), fieldString(6, "u_author"))));
        byte[] group = concat(new byte[7], fieldVarint(1, 7), fieldVarint(4, 123456),
                fieldBytes(11, groupInfo));
        List<RecallEvent> groupEvents = RecallParser.parseMsgPush(msgPush(732, 17, group));
        require(groupEvents.size() == 1, "group count");
        require(groupEvents.get(0).chatType == RecallEvent.GROUP, "group chat type");
        require("123456".equals(groupEvents.get(0).peerUid), "group peer");
        require("u_operator".equals(groupEvents.get(0).operatorUid), "group operator");
        require(groupEvents.get(0).msgSeq == 654, "group seq");

        require(RecallParser.parseMsgPush(msgPush(166, 2, fieldString(1, "normal"))).isEmpty(),
                "negative control: ordinary push must not become a recall");
        boolean malformedRejected = false;
        try {
            RecallParser.parseMsgPush(new byte[]{0x0a, 0x7f, 0x01});
        } catch (IllegalArgumentException expected) {
            malformedRejected = true;
        }
        require(malformedRejected, "negative control: truncated protobuf rejected");

        System.out.println("ParserSelfTest PASS: C2C, group, ordinary-push and malformed-input controls");
    }

    private static byte[] msgPush(int type, int subType, byte[] msgContent) {
        byte[] contentHead = concat(fieldVarint(1, type), fieldVarint(2, subType));
        byte[] body = fieldBytes(2, msgContent);
        byte[] message = concat(fieldBytes(2, contentHead), fieldBytes(3, body));
        return fieldBytes(1, message);
    }

    private static byte[] fieldString(int number, String value) {
        return fieldBytes(number, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] fieldVarint(int number, long value) {
        return concat(varint(((long) number << 3)), varint(value));
    }

    private static byte[] fieldBytes(int number, byte[] value) {
        return concat(varint(((long) number << 3) | 2), varint(value.length), value);
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int b = (int) (value & 0x7f);
            value >>>= 7;
            out.write(value == 0 ? b : b | 0x80);
        } while (value != 0);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] value : values) out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
