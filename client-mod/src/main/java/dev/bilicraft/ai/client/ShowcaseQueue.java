package dev.bilicraft.ai.client;

import dev.bilicraft.ai.protocol.Protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Game-thread queue state. It selects existing targets and never moves the camera or requests server data. */
public final class ShowcaseQueue {
    public static final int MAX_PENDING = 64;

    public enum Source { AUTO, MANUAL }

    public record Entry(String entryId, ShowcaseTarget target, Source source, int durationSeconds) {
    }

    private final RandomGenerator random;
    private final ArrayList<Entry> pending = new ArrayList<>();
    private final Map<String, Long> suppressedUntil = new HashMap<>();
    private final Map<String, Long> automaticSuppressedUntil = new HashMap<>();
    private Entry current;
    private long remainingMillis;
    private long lastSceneTick;

    public ShowcaseQueue() {
        this(RandomGenerator.getDefault());
    }

    ShowcaseQueue(RandomGenerator random) {
        this.random = random;
    }

    public Entry manualEntry(ShowcaseTarget target, int durationSeconds) {
        boolean player = target instanceof ShowcaseTarget.Player;
        if (durationSeconds < (player ? Protocol.MIN_FOLLOW_SECONDS : 30)
                || durationSeconds > (player ? Protocol.MAX_FOLLOW_SECONDS : 600)) {
            throw new IllegalArgumentException(player ? "Player duration must be 180..480 seconds"
                    : "Scene duration must be 30..600 seconds");
        }
        return new Entry(UUID.randomUUID().toString(), target, Source.MANUAL, durationSeconds);
    }

    public int defaultDuration(ShowcaseTarget target, int sceneDurationSeconds) {
        return target instanceof ShowcaseTarget.Player
                ? random.nextInt(Protocol.MIN_FOLLOW_SECONDS, Protocol.MAX_FOLLOW_SECONDS + 1) : sceneDurationSeconds;
    }

    public void add(ShowcaseTarget target, int index, int durationSeconds) {
        if (pending.size() >= MAX_PENDING || index < 0 || index > pending.size()) {
            throw new IllegalArgumentException("Queue is full or insertion index is invalid");
        }
        pending.add(index, manualEntry(target, durationSeconds));
    }

    public void remove(String entryId) {
        int index = -1;
        for (int item = 0; item < pending.size(); item++) {
            if (pending.get(item).entryId().equals(entryId)) {
                index = item;
                break;
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException("Pending entry does not exist");
        }
        pending.remove(index);
    }

    public void move(String entryId, int index) {
        if (index < 0 || index >= pending.size()) {
            throw new IllegalArgumentException("Queue index is invalid");
        }
        Entry entry = pending.stream().filter(item -> item.entryId().equals(entryId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Pending entry does not exist"));
        pending.remove(entry);
        pending.add(index, entry);
    }

    public void moveBy(String entryId, int delta) {
        if (delta != -1 && delta != 1) {
            throw new IllegalArgumentException("Relative queue movement must be one position");
        }
        Entry entry = pending.stream().filter(item -> item.entryId().equals(entryId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Pending entry does not exist"));
        int index = pending.indexOf(entry);
        move(entryId, Math.clamp(index + delta, 0, pending.size() - 1));
    }

    public void adjust(String entryId, int deltaSeconds, long now, boolean paused) {
        if (deltaSeconds != -10 && deltaSeconds != 10) {
            throw new IllegalArgumentException("时长每次只能增加或减少10秒");
        }
        boolean adjustingCurrent = current != null && current.entryId().equals(entryId);
        int pendingIndex = -1;
        Entry entry = adjustingCurrent ? current : null;
        if (!adjustingCurrent) {
            for (int index = 0; index < pending.size(); index++) {
                if (pending.get(index).entryId().equals(entryId)) {
                    pendingIndex = index;
                    entry = pending.get(index);
                    break;
                }
            }
        }
        if (entry == null) {
            throw new IllegalArgumentException("该队列项已结束或不存在，未调整其他项");
        }
        boolean player = entry.target() instanceof ShowcaseTarget.Player;
        if (player && adjustingCurrent) {
            throw new IllegalArgumentException("当前玩家时长须经导播服务核准");
        }
        if (adjustingCurrent && tickScene(now, paused)) {
            throw new IllegalArgumentException("当前场景已经结束，无法再调整时长");
        }
        int duration = entry.durationSeconds() + deltaSeconds;
        if (duration < (player ? Protocol.MIN_FOLLOW_SECONDS : 30)
                || duration > (player ? Protocol.MAX_FOLLOW_SECONDS : 600)) {
            throw new IllegalArgumentException(player ? "已达玩家时长边界（180～480秒），未调整"
                    : "已达场景时长边界（30～600秒），未调整");
        }
        Entry adjusted = new Entry(entry.entryId(), entry.target(), entry.source(), duration);
        if (adjustingCurrent) {
            current = adjusted;
            remainingMillis = Math.max(0, remainingMillis + deltaSeconds * 1_000L);
        } else {
            pending.set(pendingIndex, adjusted);
        }
    }

    public void applyPlayerDuration(String entryId, int approvedDurationSeconds) {
        if (current == null || !current.entryId().equals(entryId) || !(current.target() instanceof ShowcaseTarget.Player)
                || approvedDurationSeconds < Protocol.MIN_FOLLOW_SECONDS || approvedDurationSeconds > Protocol.MAX_FOLLOW_SECONDS) {
            throw new IllegalArgumentException("Player duration approval does not match the current entry");
        }
        current = new Entry(current.entryId(), current.target(), current.source(), approvedDurationSeconds);
    }

    public void fill(List<ShowcaseTarget> candidates, int bufferSize, int sceneDurationSeconds, long now) {
        if (bufferSize < 1 || bufferSize > 12 || sceneDurationSeconds < 30 || sceneDurationSeconds > 600) {
            throw new IllegalArgumentException("Queue settings outside supported range");
        }
        suppressedUntil.values().removeIf(until -> until <= now);
        automaticSuppressedUntil.values().removeIf(until -> until <= now);
        HashSet<String> occupied = new HashSet<>();
        if (current != null) {
            occupied.add(current.target().id());
        }
        for (Entry entry : pending) {
            occupied.add(entry.target().id());
        }
        LinkedHashMap<String, ShowcaseTarget> unique = new LinkedHashMap<>();
        for (ShowcaseTarget target : candidates) {
            if (!occupied.contains(target.id()) && !isSuppressed(target.id(), now)
                    && automaticSuppressedUntil.getOrDefault(target.id(), 0L) <= now) {
                unique.putIfAbsent(target.id(), target);
            }
        }
        ArrayList<ShowcaseTarget> scenes = new ArrayList<>();
        ArrayList<ShowcaseTarget> players = new ArrayList<>();
        for (ShowcaseTarget target : unique.values()) {
            (target instanceof ShowcaseTarget.Player ? players : scenes).add(target);
        }
        while (pending.size() < bufferSize && (!scenes.isEmpty() || !players.isEmpty())) {
            boolean chooseScene = players.isEmpty() || (!scenes.isEmpty() && random.nextDouble() < 0.8);
            ArrayList<ShowcaseTarget> pool = chooseScene ? scenes : players;
            ShowcaseTarget target = pool.remove(random.nextInt(pool.size()));
            pending.add(new Entry(UUID.randomUUID().toString(), target, Source.AUTO,
                    defaultDuration(target, sceneDurationSeconds)));
        }
    }

    public boolean automaticCandidateAvailable(String targetId, long now) {
        return (current == null || !current.target().id().equals(targetId))
                && pending.stream().noneMatch(entry -> entry.target().id().equals(targetId))
                && !isSuppressed(targetId, now) && automaticSuppressedUntil.getOrDefault(targetId, 0L) <= now;
    }

    /** Append approved AI choices in response order; never edit or reorder existing manual entries. */
    public int appendAutomatic(List<ShowcaseTarget> choices, int bufferSize, int sceneDurationSeconds, long now) {
        if (bufferSize < 1 || bufferSize > 12 || sceneDurationSeconds < 30 || sceneDurationSeconds > 600) {
            throw new IllegalArgumentException("Queue settings outside supported range");
        }
        int added = 0;
        for (ShowcaseTarget target : choices) {
            if (pending.size() >= bufferSize) {
                break;
            }
            if (automaticCandidateAvailable(target.id(), now)) {
                pending.add(new Entry(UUID.randomUUID().toString(), target, Source.AUTO,
                        defaultDuration(target, sceneDurationSeconds)));
                added++;
            }
        }
        return added;
    }

    public Entry poll() {
        return pending.isEmpty() ? null : pending.removeFirst();
    }

    public void start(Entry entry, long now) {
        pending.removeIf(item -> item.source() == Source.AUTO && item.target().id().equals(entry.target().id()));
        current = entry;
        remainingMillis = entry.target() instanceof ShowcaseTarget.Player ? 0 : entry.durationSeconds() * 1_000L;
        lastSceneTick = now;
    }

    /** Paused ticks update the clock origin, so opening a menu never consumes scene playback time. */
    public boolean tickScene(long now, boolean paused) {
        if (current == null || current.target() instanceof ShowcaseTarget.Player) {
            return false;
        }
        long elapsed = Math.max(0, now - lastSceneTick);
        lastSceneTick = now;
        if (!paused) {
            remainingMillis = Math.max(0, remainingMillis - elapsed);
        }
        return remainingMillis == 0;
    }

    public void finishCurrent() {
        current = null;
        remainingMillis = 0;
    }

    public void refreshCurrentTarget(ShowcaseTarget target) {
        if (current == null || !current.target().id().equals(target.id())) {
            throw new IllegalArgumentException("Refreshed target must match the current queue entry");
        }
        current = new Entry(current.entryId(), target, current.source(), current.durationSeconds());
    }

    public void removePlayers() {
        pending.removeIf(entry -> entry.target() instanceof ShowcaseTarget.Player);
        if (current != null && current.target() instanceof ShowcaseTarget.Player) {
            finishCurrent();
        }
    }

    public void suppress(String targetId, long until) {
        suppressedUntil.merge(targetId, until, Math::max);
    }

    public boolean isSuppressed(String targetId, long now) {
        return suppressedUntil.getOrDefault(targetId, 0L) > now;
    }

    public void suppressAutomatic(String targetId, long until) {
        automaticSuppressedUntil.merge(targetId, until, Math::max);
    }

    public void clear() {
        pending.clear();
        finishCurrent();
    }

    public Entry current() {
        return current;
    }

    public List<Entry> pending() {
        return List.copyOf(pending);
    }

    public int remainingSeconds() {
        return (int) ((remainingMillis + 999) / 1_000);
    }
}
