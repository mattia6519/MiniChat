package com.example.minichat;

final class ChatMessage {
    final int fromId;
    final int toId;
    final String text;
    final boolean outgoing;
    final long timestamp;

    ChatMessage(int fromId, int toId, String text, boolean outgoing, long timestamp) {
        this.fromId = fromId;
        this.toId = toId;
        this.text = text;
        this.outgoing = outgoing;
        this.timestamp = timestamp;
    }
}
