package dev.recallguard.qq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class RecallParser {
    private RecallParser() {}

    static List<RecallEvent> parseMsgPush(byte[] envelope) {
        List<ProtoReader.Field> root = new ProtoReader(envelope).readAll();
        byte[] messageBytes = ProtoReader.firstBytes(root, 1);
        if (messageBytes == null) return Collections.emptyList();

        List<ProtoReader.Field> message = new ProtoReader(messageBytes).readAll();
        byte[] contentHeadBytes = ProtoReader.firstBytes(message, 2);
        byte[] bodyBytes = ProtoReader.firstBytes(message, 3);
        if (contentHeadBytes == null || bodyBytes == null) return Collections.emptyList();

        List<ProtoReader.Field> contentHead = new ProtoReader(contentHeadBytes).readAll();
        int type = (int) ProtoReader.firstVarint(contentHead, 1, -1);
        int subType = (int) ProtoReader.firstVarint(contentHead, 2, -1);

        List<ProtoReader.Field> body = new ProtoReader(bodyBytes).readAll();
        byte[] msgContent = ProtoReader.firstBytes(body, 2);
        if (msgContent == null) return Collections.emptyList();

        if (type == 528 && subType == 138) return parseC2c(msgContent);
        if (type == 732 && subType == 17) return parseGroup(msgContent);
        return Collections.emptyList();
    }

    private static List<RecallEvent> parseC2c(byte[] content) {
        List<ProtoReader.Field> recall = new ProtoReader(content).readAll();
        ArrayList<RecallEvent> result = new ArrayList<>();
        for (byte[] infoBytes : ProtoReader.repeatedBytes(recall, 1)) {
            List<ProtoReader.Field> info = new ProtoReader(infoBytes).readAll();
            String fromUid = ProtoReader.firstString(info, 1);
            long seq = ProtoReader.firstVarint(info, 20, 0);
            if (!fromUid.isEmpty() && seq > 0) {
                // Incoming third-party recall: fromUid is both operator and peer.
                result.add(new RecallEvent(RecallEvent.C2C, fromUid, fromUid, seq));
            }
        }
        return result;
    }

    private static List<RecallEvent> parseGroup(byte[] content) {
        if (content.length <= 7) return Collections.emptyList();
        byte[] trimmed = Arrays.copyOfRange(content, 7, content.length);
        List<ProtoReader.Field> group = new ProtoReader(trimmed).readAll();
        long opType = ProtoReader.firstVarint(group, 1, -1);
        long groupCode = ProtoReader.firstVarint(group, 4, 0);
        byte[] recallInfoBytes = ProtoReader.firstBytes(group, 11);
        if (opType != 7 || groupCode <= 0 || recallInfoBytes == null) {
            return Collections.emptyList();
        }
        List<ProtoReader.Field> recallInfo = new ProtoReader(recallInfoBytes).readAll();
        String operatorUid = ProtoReader.firstString(recallInfo, 1);
        ArrayList<RecallEvent> result = new ArrayList<>();
        for (byte[] infoBytes : ProtoReader.repeatedBytes(recallInfo, 3)) {
            List<ProtoReader.Field> info = new ProtoReader(infoBytes).readAll();
            long seq = ProtoReader.firstVarint(info, 1, 0);
            if (seq > 0) {
                result.add(new RecallEvent(RecallEvent.GROUP, Long.toString(groupCode), operatorUid, seq));
            }
        }
        return result;
    }
}
