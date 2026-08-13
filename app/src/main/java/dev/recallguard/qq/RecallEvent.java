package dev.recallguard.qq;

final class RecallEvent {
    static final int C2C = 1;
    static final int GROUP = 2;

    final int chatType;
    final String peerUid;
    final String operatorUid;
    final long msgSeq;

    RecallEvent(int chatType, String peerUid, String operatorUid, long msgSeq) {
        this.chatType = chatType;
        this.peerUid = peerUid;
        this.operatorUid = operatorUid;
        this.msgSeq = msgSeq;
    }
}
