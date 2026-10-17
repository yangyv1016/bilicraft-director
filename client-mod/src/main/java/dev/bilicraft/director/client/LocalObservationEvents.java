package dev.bilicraft.director.client;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/** Observation events owned by the Minecraft thread, independent of browser and HTTP sessions. */
final class LocalObservationEvents {
    private final String historyId = UUID.randomUUID().toString();
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long nextId;

    Entry append(String stage, String source, UUID playerId, String playerName, String server, String world,
                 String reason, long timestamp) {
        reason = plainText(reason);
        if (reason.length() > 512) {
            int end = Character.isHighSurrogate(reason.charAt(511)) ? 511 : 512;
            reason = reason.substring(0, end);
        }
        Entry entry = new Entry(++nextId, timestamp, plainText(stage), plainText(source), playerId,
                plainText(playerName), plainText(server), plainText(world), reason);
        entries.addLast(entry);
        while (entries.size() > 200) entries.removeFirst();
        return entry;
    }

    String historyId() {
        return historyId;
    }

    List<Entry> entries() {
        return List.copyOf(entries);
    }

    private static String plainText(String text) {
        return text.replaceAll("§.", "").replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
    }

    record Entry(long id, long timestamp, String stage, String source, UUID playerId, String playerName,
                 String server, String world, String reason) { }
}
