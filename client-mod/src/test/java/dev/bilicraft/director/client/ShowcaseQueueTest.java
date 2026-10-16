package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShowcaseQueueTest {
    @Test
    void aiChoicesAppendInTheirOrderWithoutReorderingManualEntriesOrRepeatingOccupiedTargets() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(4));
        queue.start(queue.manualEntry(scene("current"), 120), 0);
        queue.add(scene("manual"), 0, 90);
        var manual = queue.pending().getFirst();
        int added = queue.appendAutomatic(List.of(scene("current"), scene("manual"), scene("second"),
                scene("second"), scene("third"), scene("excess")), 3, 120, 0);
        assertEquals(2, added);
        assertEquals(manual, queue.pending().getFirst());
        assertEquals(List.of("manual", "second", "third"), queue.pending().stream().map(entry -> entry.target().name()).toList());
    }

    @Test
    void aiCannotReinsertAutomaticallySkippedOrSuppressedTargetsButManualIntentRemainsAllowed() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(4));
        queue.suppressAutomatic(scene("skip").id(), 30_000);
        queue.suppress(scene("invalid").id(), 30_000);
        assertFalse(queue.automaticCandidateAvailable(scene("skip").id(), 0));
        assertEquals(0, queue.appendAutomatic(List.of(scene("skip"), scene("invalid")), 4, 120, 0));
        queue.add(scene("skip"), 0, 120);
        assertEquals("skip", queue.poll().target().name());
        assertEquals(2, queue.appendAutomatic(List.of(scene("skip"), scene("invalid")), 4, 120, 30_000));
    }
    private static ShowcaseTarget scene(String id) {
        return new ShowcaseTarget.Scene(new Message.SceneView(id, id, Message.SceneKind.VIEWPOINT,
                "minecraft:overworld", 0, 80, 0, 6));
    }

    private static ShowcaseTarget player() {
        return new ShowcaseTarget.Player(new Message.PlayerView(UUID.randomUUID(), "player", "minecraft:overworld",
                0, 80, 0, 0, 0, 0));
    }

    @Test
    void manualInsertionAndMovesPreserveEntryIdentityAndDuration() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(1));
        queue.add(scene("first"), 0, 90);
        queue.add(scene("last"), 1, 120);
        queue.add(scene("middle"), 1, 60);
        String lastId = queue.pending().getLast().entryId();
        queue.move(lastId, 0);
        assertEquals(List.of("last", "first", "middle"), queue.pending().stream().map(e -> e.target().name()).toList());
        assertEquals(lastId, queue.pending().getFirst().entryId());
        assertEquals(120, queue.pending().getFirst().durationSeconds());
        queue.remove(lastId);
        assertEquals(List.of("first", "middle"), queue.pending().stream().map(e -> e.target().name()).toList());
        assertTrue(queue.pending().stream().allMatch(e -> e.source() == ShowcaseQueue.Source.MANUAL));
    }

    @Test
    void autoFillOnlyAppendsAndNeverReordersOrRemovesManualEntries() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(2));
        queue.add(scene("manual-b"), 0, 60);
        queue.add(scene("manual-a"), 1, 90);
        List<ShowcaseQueue.Entry> original = queue.pending();
        queue.fill(List.of(scene("auto-c"), scene("auto-d")), 4, 120, 0);
        assertEquals(original, queue.pending().subList(0, 2));
        assertEquals(4, queue.pending().size());
        assertTrue(queue.pending().subList(2, 4).stream().allMatch(e -> e.source() == ShowcaseQueue.Source.AUTO));
        queue.fill(List.of(scene("extra")), 1, 120, 1);
        assertEquals(4, queue.pending().size());
        assertEquals(original, queue.pending().subList(0, 2));
    }

    @Test
    void currentAndPendingTargetsAreNotAutomaticallyRepeatedEvenWithDuplicateCandidates() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(3));
        ShowcaseTarget current = scene("current");
        queue.start(queue.manualEntry(current, 120), 0);
        queue.add(scene("queued"), 0, 120);
        queue.fill(List.of(current, scene("queued"), scene("only-new"), scene("only-new")), 4, 120, 0);
        assertEquals(List.of("queued", "only-new"), queue.pending().stream().map(e -> e.target().name()).toList());
        assertEquals(2, queue.pending().size(), "finite candidates must not be padded to four");
    }

    @Test
    void missingTargetClassFallsBackToAvailableScenesOrPlayers() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(4));
        queue.fill(List.of(scene("a"), scene("b")), 4, 45, 0);
        assertEquals(2, queue.pending().size());
        assertTrue(queue.pending().stream().allMatch(e -> e.durationSeconds() == 45));
        queue.clear();
        queue.fill(List.of(player(), player()), 4, 120, 0);
        assertEquals(2, queue.pending().size());
        assertTrue(queue.pending().stream().allMatch(e -> e.durationSeconds() >= 180 && e.durationSeconds() <= 480));
    }

    @Test
    void deterministicSamplingFavorsScenesAtAboutEightyPercentWhenBothKindsExist() {
        Random random = new Random(0xBCAD);
        int scenePicks = 0;
        for (int index = 0; index < 1_000; index++) {
            ShowcaseQueue queue = new ShowcaseQueue(random);
            queue.fill(List.of(scene("scene"), player()), 1, 120, 0);
            if (queue.pending().getFirst().target() instanceof ShowcaseTarget.Scene) {
                scenePicks++;
            }
        }
        assertTrue(scenePicks >= 750 && scenePicks <= 850, "scene picks: " + scenePicks);
    }

    @Test
    void menuPauseDoesNotConsumeSceneTimeAndResumeUsesOnlyUnpausedTicks() {
        ShowcaseQueue queue = new ShowcaseQueue();
        queue.start(queue.manualEntry(scene("timed"), 30), 1_000);
        assertFalse(queue.tickScene(11_000, false));
        assertEquals(20, queue.remainingSeconds());
        assertFalse(queue.tickScene(111_000, true));
        assertEquals(20, queue.remainingSeconds());
        assertFalse(queue.tickScene(130_999, false));
        assertEquals(1, queue.remainingSeconds());
        assertTrue(queue.tickScene(131_000, false));
        assertEquals(0, queue.remainingSeconds());
    }

    @Test
    void playerEntriesNeverExpireOnTheLocalSceneTimer() {
        ShowcaseQueue queue = new ShowcaseQueue();
        queue.start(queue.manualEntry(player(), 180), 1_000);
        assertFalse(queue.tickScene(1_000_000, false));
        assertNotNull(queue.current());
        assertEquals(0, queue.remainingSeconds());
    }

    @Test
    void revokingPlayerAccessKeepsSceneEntriesAndCurrentScene() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseQueue.Entry current = queue.manualEntry(scene("current"), 120);
        queue.start(current, 0);
        queue.add(player(), 0, 180);
        queue.add(scene("next"), 1, 120);
        queue.removePlayers();
        assertEquals(current, queue.current());
        assertEquals(1, queue.pending().size());
        assertInstanceOf(ShowcaseTarget.Scene.class, queue.pending().getFirst().target());
        queue.start(queue.manualEntry(player(), 180), 0);
        queue.removePlayers();
        assertNull(queue.current());
        assertEquals(1, queue.pending().size());
    }

    @Test
    void suppressedTargetsCannotAutofillUntilTheBackoffExpires() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget target = scene("retry");
        queue.suppress(target.id(), 30_000);
        queue.suppress(target.id(), 10_000);
        queue.fill(List.of(target), 4, 120, 29_999);
        assertTrue(queue.pending().isEmpty());
        queue.fill(List.of(target), 4, 120, 30_000);
        assertEquals(1, queue.pending().size());
    }

    @Test
    void clearInvalidatesAllWorldEntriesWhileFinishOnlyEndsTheCurrentEntry() {
        ShowcaseQueue queue = new ShowcaseQueue();
        queue.start(queue.manualEntry(scene("current"), 120), 0);
        queue.add(scene("pending"), 0, 120);
        queue.finishCurrent();
        assertNull(queue.current());
        assertEquals(1, queue.pending().size());
        queue.clear();
        assertTrue(queue.pending().isEmpty());
        assertNull(queue.current());
        assertNull(queue.poll());
    }

    @Test
    void invalidQueueOperationsAndOverCapacityDoNotChangeTheExistingOrder() {
        ShowcaseQueue queue = new ShowcaseQueue();
        for (int index = 0; index < ShowcaseQueue.MAX_PENDING; index++) {
            queue.add(scene("scene-" + index), index, 120);
        }
        List<ShowcaseQueue.Entry> before = queue.pending();
        assertThrows(IllegalArgumentException.class, () -> queue.add(scene("overflow"), 64, 120));
        assertThrows(IllegalArgumentException.class, () -> queue.move(before.getFirst().entryId(), 64));
        assertThrows(IllegalArgumentException.class, () -> queue.remove("missing"));
        assertEquals(before, queue.pending());
        assertThrows(UnsupportedOperationException.class, () -> queue.pending().clear());
    }

    @Test
    void sceneDurationAndBufferBoundsAreEnforced() {
        ShowcaseQueue queue = new ShowcaseQueue();
        assertThrows(IllegalArgumentException.class, () -> queue.manualEntry(scene("short"), 29));
        assertThrows(IllegalArgumentException.class, () -> queue.manualEntry(scene("long"), 601));
        assertThrows(IllegalArgumentException.class, () -> queue.fill(List.of(), 0, 120, 0));
        assertThrows(IllegalArgumentException.class, () -> queue.fill(List.of(), 13, 120, 0));
        assertThrows(IllegalArgumentException.class, () -> queue.fill(List.of(), 4, 601, 0));
    }

    @Test
    void refreshingCurrentTargetPreservesIdentityAndElapsedSceneTime() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget original = scene("renamed");
        ShowcaseQueue.Entry entry = queue.manualEntry(original, 60);
        queue.start(entry, 0);
        queue.tickScene(10_000, false);
        ShowcaseTarget updated = new ShowcaseTarget.Scene(new Message.SceneView("renamed", "New name", Message.SceneKind.AREA,
                "minecraft:overworld", 10, 80, 10, 8));
        queue.refreshCurrentTarget(updated);
        assertEquals(entry.entryId(), queue.current().entryId());
        assertEquals(ShowcaseQueue.Source.MANUAL, queue.current().source());
        assertEquals("New name", queue.current().target().name());
        assertEquals(50, queue.remainingSeconds());
        assertThrows(IllegalArgumentException.class, () -> queue.refreshCurrentTarget(scene("other")));
    }

    @Test
    void relativeMovementResolvesTheEntryAfterTheHeadHasAlreadyStarted() {
        ShowcaseQueue queue = new ShowcaseQueue();
        for (String name : List.of("a", "b", "c", "d")) {
            queue.add(scene(name), queue.pending().size(), 120);
        }
        String b = queue.pending().get(1).entryId();
        queue.start(queue.poll(), 0);
        queue.moveBy(b, 1);
        assertEquals(List.of("c", "b", "d"), queue.pending().stream().map(e -> e.target().name()).toList());
        queue.moveBy(b, -1);
        queue.moveBy(b, -1);
        assertEquals(List.of("b", "c", "d"), queue.pending().stream().map(e -> e.target().name()).toList());
        assertThrows(IllegalArgumentException.class, () -> queue.moveBy(b, 2));
    }

    @Test
    void skippingASceneOnlyDelaysAutomaticRefillAndKeepsExplicitManualRepeatsPlayable() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget target = scene("only-scene");
        queue.start(queue.manualEntry(target, 120), 0);
        queue.suppressAutomatic(target.id(), 30_000);
        queue.finishCurrent();
        queue.fill(List.of(target), 4, 120, 1);
        assertTrue(queue.pending().isEmpty(), "skip must not immediately auto-replay the only scene");
        assertFalse(queue.isSuppressed(target.id(), 1), "manual execution does not use the auto-only backoff");
        queue.add(target, 0, 45);
        queue.start(queue.poll(), 1);
        assertEquals(ShowcaseQueue.Source.MANUAL, queue.current().source());
        assertEquals(45, queue.current().durationSeconds());
        queue.finishCurrent();
        queue.fill(List.of(target), 4, 120, 30_000);
        assertEquals(1, queue.pending().size());
    }

    @Test
    void immediateManualShowRemovesOnlyItsDuplicateAutomaticPendingEntry() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget target = scene("selected");
        queue.fill(List.of(target), 4, 120, 0);
        queue.add(target, 0, 45);
        ShowcaseQueue.Entry explicitRepeat = queue.pending().getFirst();
        queue.start(queue.manualEntry(target, 60), 0);
        assertEquals(List.of(explicitRepeat), queue.pending());
        assertEquals(ShowcaseQueue.Source.MANUAL, queue.current().source());
        assertEquals(60, queue.current().durationSeconds());
    }

    @Test
    void adjustingCurrentSynchronizesElapsedTimeOnceThenChangesTotalAndRemaining() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseQueue.Entry entry = queue.manualEntry(scene("timed"), 60);
        queue.start(entry, 0);
        queue.tickScene(10_000, false);
        queue.adjust(entry.entryId(), 10, 15_000, false);
        assertEquals(70, queue.current().durationSeconds());
        assertEquals(55, queue.remainingSeconds());
        queue.tickScene(20_000, false);
        assertEquals(50, queue.remainingSeconds(), "the 5 seconds before adjustment must not be charged twice");
        queue.adjust(entry.entryId(), -10, 25_000, false);
        assertEquals(60, queue.current().durationSeconds());
        assertEquals(35, queue.remainingSeconds());
        queue.tickScene(30_000, false);
        assertEquals(30, queue.remainingSeconds());
        assertEquals(entry.entryId(), queue.current().entryId());
    }

    @Test
    void subtractingTenCanEndTheCurrentSceneWithoutRevivingIt() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseQueue.Entry entry = queue.manualEntry(scene("finishing"), 40);
        queue.start(entry, 0);
        queue.tickScene(35_000, false);
        queue.adjust(entry.entryId(), -10, 35_000, false);
        assertEquals(30, queue.current().durationSeconds());
        assertEquals(0, queue.remainingSeconds());
        assertTrue(queue.tickScene(35_000, false), "the controller can now continue the queue normally");
        assertThrows(IllegalArgumentException.class, () -> queue.adjust(entry.entryId(), 10, 35_000, false));
        assertEquals(0, queue.remainingSeconds());
    }

    @Test
    void adjustmentsWhilePausedDoNotConsumeTheMenusElapsedWallTime() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseQueue.Entry entry = queue.manualEntry(scene("paused"), 60);
        queue.start(entry, 0);
        queue.tickScene(10_000, false);
        queue.adjust(entry.entryId(), 10, 110_000, true);
        assertEquals(60, queue.remainingSeconds());
        queue.adjust(entry.entryId(), -10, 210_000, true);
        assertEquals(50, queue.remainingSeconds());
        queue.tickScene(211_000, false);
        assertEquals(49, queue.remainingSeconds());
    }

    @Test
    void pendingAdjustmentPreservesOrderAndRejectsDurationBoundaries() {
        ShowcaseQueue queue = new ShowcaseQueue();
        queue.add(scene("minimum"), 0, 30);
        queue.add(scene("maximum"), 1, 600);
        List<ShowcaseQueue.Entry> before = queue.pending();
        assertThrows(IllegalArgumentException.class, () -> queue.adjust(before.getFirst().entryId(), -10, 0, false));
        assertThrows(IllegalArgumentException.class, () -> queue.adjust(before.getLast().entryId(), 10, 0, false));
        assertEquals(before, queue.pending());
        queue.adjust(before.getFirst().entryId(), 10, 0, false);
        assertEquals(40, queue.pending().getFirst().durationSeconds());
        assertEquals(before.getFirst().entryId(), queue.pending().getFirst().entryId());
        assertEquals(before.getLast(), queue.pending().getLast());
        assertThrows(IllegalArgumentException.class, () -> queue.adjust(before.getFirst().entryId(), 20, 0, false));
    }

    @Test
    void entryIdsStayBoundToTheSameItemAcrossPollingAndNeverModifyItsReplacement() {
        ShowcaseQueue queue = new ShowcaseQueue();
        queue.add(scene("a"), 0, 120);
        queue.add(scene("b"), 1, 120);
        String b = queue.pending().getLast().entryId();
        queue.start(queue.poll(), 0);
        queue.adjust(b, 10, 0, false);
        assertEquals(120, queue.current().durationSeconds());
        assertEquals(130, queue.pending().getFirst().durationSeconds());
        queue.start(queue.poll(), 0);
        queue.adjust(b, -10, 0, false);
        assertEquals(120, queue.current().durationSeconds());
        queue.finishCurrent();
        ShowcaseQueue.Entry replacement = queue.manualEntry(scene("b"), 120);
        queue.start(replacement, 0);
        assertThrows(IllegalArgumentException.class, () -> queue.adjust(b, 10, 0, false));
        assertEquals(replacement, queue.current());
    }

    @Test
    void pendingPlayerDurationCanChangeButCurrentRequiresServerApproval() {
        ShowcaseQueue queue = new ShowcaseQueue();
        queue.add(player(), 0, 180);
        ShowcaseQueue.Entry pending = queue.pending().getFirst();
        queue.adjust(pending.entryId(), 10, 0, false);
        assertEquals(190, queue.pending().getFirst().durationSeconds());
        queue.start(queue.poll(), 0);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> queue.adjust(pending.entryId(), -10, 0, false));
        assertTrue(error.getMessage().contains("服务"));
        assertEquals(190, queue.current().durationSeconds());
        assertEquals(0, queue.remainingSeconds());
    }

    @Test
    void anAlreadyExpiredCurrentSceneCannotBeExtendedByALateButtonPress() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseQueue.Entry entry = queue.manualEntry(scene("expired"), 60);
        queue.start(entry, 0);
        assertThrows(IllegalArgumentException.class, () -> queue.adjust(entry.entryId(), 10, 61_000, false));
        assertEquals(60, queue.current().durationSeconds());
        assertEquals(0, queue.remainingSeconds());
        assertThrows(IllegalArgumentException.class, () -> queue.adjust("gone", -10, 61_000, false));
    }

    @Test
    void defaultPlayerPlansAreChosenOnceWithinThreeToEightMinutes() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(19));
        ShowcaseTarget target = player();
        int first = queue.defaultDuration(target, 120);
        queue.add(target, 0, first);
        for (int index = 0; index < 100; index++) {
            int planned = queue.defaultDuration(target, 120);
            assertTrue(planned >= 180 && planned <= 480);
            assertEquals(first, queue.pending().getFirst().durationSeconds());
        }
        assertThrows(IllegalArgumentException.class, () -> queue.manualEntry(target, 179));
        assertThrows(IllegalArgumentException.class, () -> queue.manualEntry(target, 481));
    }

    @Test
    void pendingPlayerAdjustmentsRespectTheThreeToEightMinuteBounds() {
        ShowcaseQueue queue = new ShowcaseQueue();
        queue.add(player(), 0, 180);
        queue.add(player(), 1, 480);
        List<ShowcaseQueue.Entry> before = queue.pending();
        assertThrows(IllegalArgumentException.class, () -> queue.adjust(before.getFirst().entryId(), -10, 0, false));
        assertThrows(IllegalArgumentException.class, () -> queue.adjust(before.getLast().entryId(), 10, 0, false));
        assertEquals(before, queue.pending());
        queue.adjust(before.getLast().entryId(), -10, 0, false);
        assertEquals(470, queue.pending().getLast().durationSeconds());
        assertEquals(before.getLast().entryId(), queue.pending().getLast().entryId());
    }

    @Test
    void serverPlayerApprovalUpdatesOnlyTheBoundCurrentEntry() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseQueue.Entry entry = queue.manualEntry(player(), 240);
        queue.start(entry, 0);
        queue.applyPlayerDuration(entry.entryId(), 250);
        assertEquals(250, queue.current().durationSeconds());
        assertEquals(entry.entryId(), queue.current().entryId());
        assertEquals(0, queue.remainingSeconds(), "only the server owns the player deadline");
        queue.finishCurrent();
        ShowcaseQueue.Entry replacement = queue.manualEntry(player(), 300);
        queue.start(replacement, 0);
        assertThrows(IllegalArgumentException.class, () -> queue.applyPlayerDuration(entry.entryId(), 260));
        assertEquals(replacement, queue.current());
        assertThrows(IllegalArgumentException.class, () -> queue.applyPlayerDuration(replacement.entryId(), 490));
    }
}
