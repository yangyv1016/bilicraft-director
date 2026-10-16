package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
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
    private final HashSet<String> playedTargetIds = new HashSet<>();
    private String lastPlayedTargetId;
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
        int missing = bufferSize - pending.size();
        if (missing <= 0) return;
        for (ShowcaseTarget target : sampleAutomaticCandidates(candidates, missing, now)) {
            pending.add(new Entry(UUID.randomUUID().toString(), target, Source.AUTO,
                    defaultDuration(target, sceneDurationSeconds)));
        }
    }

    /** Start another round only after the eligible pool, current shot and pending queue are exhausted. */
    public boolean beginNextRound(List<ShowcaseTarget> candidates, int sceneDurationSeconds, long now) {
        if (sceneDurationSeconds < 30 || sceneDurationSeconds > 600) {
            throw new IllegalArgumentException("Scene duration must be 30..600 seconds");
        }
        if (current != null || !pending.isEmpty() || candidates.isEmpty()
                || candidates.stream().anyMatch(target -> !playedTargetIds.contains(target.id()))) return false;
        List<ShowcaseTarget> ready = candidates.stream().filter(target -> !isSuppressed(target.id(), now)
                && automaticSuppressedUntil.getOrDefault(target.id(), 0L) <= now).toList();
        if (ready.isEmpty()) return false;
        List<ShowcaseTarget> firstChoices = ready.stream().filter(target -> !target.id().equals(lastPlayedTargetId)).toList();
        if (firstChoices.isEmpty() && candidates.stream().anyMatch(target -> !target.id().equals(lastPlayedTargetId))) {
            return false; // Wait for another point's backoff instead of immediately repeating the last shot.
        }
        // Retain progress for other worlds, and retain all failure/skip/player cooldowns.
        for (ShowcaseTarget target : candidates) playedTargetIds.remove(target.id());
        // Seed the first entry locally so a delayed AI reply cannot choose the previous round's last shot first.
        fill(firstChoices.isEmpty() ? ready : firstChoices, 1, sceneDurationSeconds, now);
        return true;
    }

    /** Shared random pool for local fills and AI offers, excluding queued and already played targets. */
    public List<ShowcaseTarget> sampleAutomaticCandidates(List<ShowcaseTarget> candidates, int limit, long now) {
        if (limit < 1 || limit > 12) throw new IllegalArgumentException("Candidate limit must be 1..12");
        LinkedHashMap<String, ShowcaseTarget> unique = new LinkedHashMap<>();
        for (ShowcaseTarget target : candidates) {
            if (automaticCandidateAvailable(target.id(), now)) {
                unique.putIfAbsent(target.id(), target);
            }
        }
        ArrayList<ShowcaseTarget> scenes = new ArrayList<>();
        ArrayList<ShowcaseTarget> players = new ArrayList<>();
        for (ShowcaseTarget target : unique.values()) {
            (target instanceof ShowcaseTarget.Player ? players : scenes).add(target);
        }
        ArrayList<ShowcaseTarget> selected = new ArrayList<>();
        while (selected.size() < limit && (!scenes.isEmpty() || !players.isEmpty())) {
            boolean chooseScene = players.isEmpty() || (!scenes.isEmpty() && random.nextDouble() < 0.8);
            ArrayList<ShowcaseTarget> pool = chooseScene ? scenes : players;
            ShowcaseTarget target = pool.remove(random.nextInt(pool.size()));
            selected.add(target);
        }
        return List.copyOf(selected);
    }

    public boolean automaticCandidateAvailable(String targetId, long now) {
        return !playedTargetIds.contains(targetId) && (current == null || !current.target().id().equals(targetId))
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

    public Entry peek() {
        return pending.isEmpty() ? null : pending.getFirst();
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

    /** Remove ineligible automatic entries without changing explicit manual choices. */
    public Entry pruneAutomatic(Predicate<ShowcaseTarget> eligible) {
        pending.removeIf(entry -> entry.source() == Source.AUTO && !eligible.test(entry.target()));
        if (current != null && current.source() == Source.AUTO && !eligible.test(current.target())) {
            Entry ended = current;
            finishCurrent();
            return ended;
        }
        return null;
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

    /** Called after a scene arrives or a permitted player actually enters the shot, never for a failed request. */
    public void markPlayed(String targetId) {
        lastPlayedTargetId = targetId;
        if (playedTargetIds.add(targetId)) {
            pending.removeIf(entry -> entry.source() == Source.AUTO && entry.target().id().equals(targetId));
        }
    }

    public void resetSession() {
        clear();
        playedTargetIds.clear();
        lastPlayedTargetId = null;
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
