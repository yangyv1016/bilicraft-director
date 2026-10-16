package dev.bilicraft.director.client;

import java.util.ArrayDeque;
import java.util.List;

/** Plain-text, memory-only chat for the paired local console; owned by the Minecraft thread. */
final class LocalChatHistory {
    static final int MAX_SEND_LENGTH = 256;
    private static final int MAX_MESSAGES = 100;
    private static final int MAX_RECEIVED_LENGTH = 2_048;
    private final ArrayDeque<Entry> messages = new ArrayDeque<>();
    private long nextId;

    void append(String text, long receivedAt) {
        text = text.replaceAll("§.", "");
        if (text.isBlank()) return;
        if (text.length() > MAX_RECEIVED_LENGTH) {
            int end = Character.isHighSurrogate(text.charAt(MAX_RECEIVED_LENGTH - 1))
                    ? MAX_RECEIVED_LENGTH - 1 : MAX_RECEIVED_LENGTH;
            text = text.substring(0, end);
        }
        messages.addLast(new Entry(++nextId, text, receivedAt));
        while (messages.size() > MAX_MESSAGES) messages.removeFirst();
    }

    List<Entry> messages() {
        return List.copyOf(messages);
    }

    void clear() {
        messages.clear();
    }

    record Entry(long id, String text, long receivedAt) { }
}
