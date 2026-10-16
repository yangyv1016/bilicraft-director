package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShowcaseQueueTest {
    @Test
    void playedTargetsStayOutOfLocalAndAiFillsWithinTheRoundButManualRepeatsRemainAllowed() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(4));
        ShowcaseTarget scene = scene("played");
        ShowcaseTarget player = player();
        queue.start(queue.manualEntry(scene, 120), 0);
        queue.markPlayed(scene.id());
        queue.finishCurrent();
        queue.start(queue.manualEntry(player, 180), 0);
        queue.markPlayed(player.id());
        queue.clear(); // Stopping or changing worlds must not reset the round's played list.
        assertFalse(queue.automaticCandidateAvailable(scene.id(), 86_400_000));
        assertFalse(queue.automaticCandidateAvailable(player.id(), 86_400_000));
        queue.fill(List.of(scene, player), 4, 120, 86_400_000);
        assertTrue(queue.pending().isEmpty());
        assertEquals(0, queue.appendAutomatic(List.of(scene, player), 4, 120, 86_400_000));
        queue.add(scene, 0, 60);
        queue.start(queue.poll(), 86_400_000);
        assertEquals(scene, queue.current().target());
        assertEquals(ShowcaseQueue.Source.MANUAL, queue.current().source());
        queue.resetSession();
        assertNull(queue.current());
        assertTrue(queue.pending().isEmpty());
        assertTrue(queue.automaticCandidateAvailable(scene.id(), 86_400_001));
        assertTrue(queue.automaticCandidateAvailable(player.id(), 86_400_001));
        assertEquals(2, queue.appendAutomatic(List.of(scene, player), 4, 120, 86_400_001));
    }

    @Test
    void failedArrivalOrPlayerNoticeDoesNotCountAsPlayed() {
        ShowcaseQueue queue = new ShowcaseQueue();
        for (ShowcaseTarget target : List.of(scene("unreached"), player())) {
            queue.start(queue.manualEntry(target, queue.defaultDuration(target, 120)), 0);
            queue.finishCurrent();
            assertTrue(queue.automaticCandidateAvailable(target.id(), 1));
        }
    }

    @Test
    void markingPlayedRemovesOnlyAutomaticCopiesAndRejectsLateAiChoices() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget target = scene("just-played");
        queue.fill(List.of(target), 4, 120, 0);
        queue.add(target, 0, 60);
        ShowcaseQueue.Entry manual = queue.pending().getFirst();
        queue.markPlayed(target.id());
        assertEquals(List.of(manual), queue.pending());
        assertEquals(0, queue.appendAutomatic(List.of(target), 4, 120, 1));
        queue.markPlayed(target.id());
        assertEquals(List.of(manual), queue.pending());
    }

    @Test
    void sameSceneIdInAnotherWorldIsStillAnUnplayedTarget() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget overworld = scene("station");
        ShowcaseTarget nether = new ShowcaseTarget.Scene(new Message.SceneView("station", "station", Message.SceneKind.VIEWPOINT,
                "minecraft:the_nether", 0, 80, 0, 6));
        queue.markPlayed(overworld.id());
        assertFalse(queue.automaticCandidateAvailable(overworld.id(), 0));
        assertTrue(queue.automaticCandidateAvailable(nether.id(), 0));
    }

    @Test
    void randomRefillVisitsEveryAvailableSceneOnceBeforeAnExplicitRoundChange() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(42));
        List<ShowcaseTarget> candidates = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> scene("scene-" + index)).toList();
        java.util.ArrayList<String> played = new java.util.ArrayList<>();
        queue.fill(candidates, 4, 120, 0);
        while (queue.peek() != null) {
            queue.start(queue.poll(), 0);
            played.add(queue.current().target().id());
            queue.markPlayed(queue.current().target().id());
            queue.finishCurrent();
            queue.fill(candidates, 4, 120, 0);
        }
        assertEquals(40, played.size());
        assertEquals(40, new java.util.HashSet<>(played).size());
        assertNotEquals(candidates.stream().map(ShowcaseTarget::id).toList(), played);
        assertTrue(queue.sampleAutomaticCandidates(candidates, 12, 0).isEmpty());
    }

    @Test
    void multipleRoundsVisitEachPointOnceAndNeverRepeatAcrossTheBoundary() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(42));
        List<ShowcaseTarget> candidates = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> scene("scene-" + index)).toList();
        String last = null;
        java.util.ArrayList<List<String>> rounds = new java.util.ArrayList<>();
        for (int round = 0; round < 4; round++) {
            if (round == 0) queue.fill(candidates, 4, 120, 0);
            else {
                assertTrue(queue.beginNextRound(candidates, 120, 0));
                assertNotEquals(last, queue.peek().target().id());
                // AI may try to put the previous last shot first; it can only append after the seeded entry.
                queue.appendAutomatic(candidates, 4, 120, 0);
                assertNotEquals(last, queue.peek().target().id());
            }
            java.util.ArrayList<String> played = new java.util.ArrayList<>();
            while (queue.peek() != null) {
                queue.start(queue.poll(), 0);
                last = queue.current().target().id();
                played.add(last);
                queue.markPlayed(last);
                assertFalse(queue.beginNextRound(candidates, 120, 0));
                queue.finishCurrent();
                queue.fill(candidates, 4, 120, 0);
            }
            assertEquals(20, played.size());
            assertEquals(20, new java.util.HashSet<>(played).size());
            rounds.add(played);
        }
        assertNotEquals(rounds.get(0), rounds.get(1));
    }

    @Test
    void exhaustedRoundWaitsForCurrentAndManualQueueAndDoesNotMistakeAnEmptyPoolForCompletion() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget point = scene("point");
        queue.start(queue.manualEntry(point, 60), 0);
        queue.markPlayed(point.id());
        assertFalse(queue.beginNextRound(List.of(point), 120, 0));
        queue.finishCurrent();
        queue.add(point, 0, 90);
        List<ShowcaseQueue.Entry> manual = queue.pending();
        assertFalse(queue.beginNextRound(List.of(point), 120, 0));
        assertEquals(manual, queue.pending());
        queue.clear();
        assertFalse(queue.beginNextRound(List.of(), 120, 0));
        assertFalse(queue.automaticCandidateAvailable(point.id(), 0));
        ShowcaseTarget failed = scene("failed");
        queue.suppress(failed.id(), 30_000);
        assertFalse(queue.beginNextRound(List.of(point, failed), 120, 1));
        assertFalse(queue.automaticCandidateAvailable(point.id(), 1));
        queue.fill(List.of(point, failed), 4, 120, 30_000);
        assertEquals(failed, queue.peek().target());
    }

    @Test
    void roundChangeRetainsBackoffAndWaitsRatherThanRepeatingTheLastShotWhileOthersAreSuppressed() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget first = scene("first");
        ShowcaseTarget last = scene("last");
        queue.markPlayed(first.id());
        queue.markPlayed(last.id());
        queue.suppress(first.id(), 30_000);
        assertFalse(queue.beginNextRound(List.of(first, last), 120, 29_999));
        assertFalse(queue.automaticCandidateAvailable(last.id(), 29_999));
        queue.suppressAutomatic(last.id(), 60_000);
        assertTrue(queue.beginNextRound(List.of(first, last), 120, 30_000));
        assertEquals(first, queue.peek().target());
        assertFalse(queue.automaticCandidateAvailable(last.id(), 59_999));
        assertTrue(queue.automaticCandidateAvailable(last.id(), 60_000));
    }

    @Test
    void singlePointCanLoopButAnEmptyOrEntirelySuppressedPoolCannotResetProgress() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget only = scene("only");
        queue.markPlayed(only.id());
        queue.suppressAutomatic(only.id(), 30_000);
        assertFalse(queue.beginNextRound(List.of(only), 60, 29_999));
        assertTrue(queue.beginNextRound(List.of(only), 60, 30_000));
        assertEquals(only, queue.peek().target());
        assertEquals(60, queue.peek().durationSeconds());
        assertEquals(ShowcaseQueue.Source.AUTO, queue.peek().source());
    }

    @Test
    void newCandidatesArePlayedBeforeRolloverAndOtherWorldProgressSurvivesIt() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget point = scene("point");
        ShowcaseTarget fresh = scene("fresh");
        ShowcaseTarget nether = new ShowcaseTarget.Scene(new Message.SceneView("point", "point", Message.SceneKind.VIEWPOINT,
                "minecraft:the_nether", 0, 80, 0, 6));
        queue.markPlayed(nether.id());
        queue.markPlayed(point.id());
        assertFalse(queue.beginNextRound(List.of(point, fresh), 120, 0));
        queue.fill(List.of(point, fresh), 4, 120, 0);
        assertEquals(fresh, queue.peek().target());
        queue.start(queue.poll(), 0);
        queue.markPlayed(fresh.id());
        queue.finishCurrent();
        assertTrue(queue.beginNextRound(List.of(point, fresh), 120, 0));
        assertEquals(point, queue.peek().target());
        assertFalse(queue.automaticCandidateAvailable(nether.id(), 0));
    }

    @Test
    void cyclingScenesNeverClearsAPlayersCooldownOrAllowsAiToBypassIt() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(9));
        ShowcaseTarget first = scene("first");
        ShowcaseTarget last = scene("last");
        ShowcaseTarget player = player();
        queue.markPlayed(player.id());
        queue.suppress(player.id(), 30 * 60_000);
        queue.markPlayed(first.id());
        queue.markPlayed(last.id());
        assertTrue(queue.beginNextRound(List.of(first, last, player), 120, 1));
        assertEquals(first, queue.peek().target());
        assertEquals(0, queue.appendAutomatic(List.of(player), 4, 120, 1));
        assertFalse(queue.sampleAutomaticCandidates(List.of(first, last, player), 12, 1).contains(player));
        assertTrue(queue.isSuppressed(player.id(), 30 * 60_000 - 1));
        assertFalse(queue.isSuppressed(player.id(), 30 * 60_000));
        assertTrue(queue.automaticCandidateAvailable(player.id(), 30 * 60_000));
    }

    @Test
    void aiOffersSampleTheWholeEligiblePoolRatherThanTheFirstTwelveNames() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(42));
        List<ShowcaseTarget> candidates = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> scene("scene-" + index)).toList();
        queue.markPlayed(candidates.getFirst().id());
        queue.add(candidates.get(1), 0, 120);
        List<ShowcaseTarget> offered = queue.sampleAutomaticCandidates(candidates, 12, 0);
        assertEquals(12, offered.size());
        assertEquals(12, offered.stream().map(ShowcaseTarget::id).distinct().count());
        assertTrue(offered.stream().anyMatch(target -> candidates.indexOf(target) >= 12));
        assertFalse(offered.contains(candidates.getFirst()));
        assertFalse(offered.contains(candidates.get(1)));
        assertNotEquals(offered, queue.sampleAutomaticCandidates(candidates, 12, 0));
        assertEquals(1, queue.pending().size(), "offering candidates must not modify the queue");
    }

    @Test
    void waitingForPlayerServiceDoesNotConsumeTheHeadEntry() {
        ShowcaseQueue queue = new ShowcaseQueue(new Random(4));
        assertNull(queue.peek());
        queue.add(player(), 0, 180);
        queue.add(scene("after-player"), 1, 90);
        ShowcaseQueue.Entry waiting = queue.pending().getFirst();

        assertSame(waiting, queue.peek());
        assertSame(waiting, queue.peek());
        assertEquals(2, queue.pending().size());
        assertEquals(waiting, queue.poll());
        assertEquals("after-player", queue.peek().target().name());
    }

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
        return new ShowcaseTarget.Player(new Message.PlayerSummary(UUID.randomUUID(), "player", "minecraft:overworld",
                Message.PlayerAvailability.AVAILABLE));
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
    void automaticPolicyChangeRemovesOnlyAutomaticPendingEntriesAndKeepsManualDuplicatesInOrder() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseTarget outside = scene("outside");
        ShowcaseTarget allowed = scene("allowed");
        queue.appendAutomatic(List.of(outside, allowed), 4, 120, 0);
        ShowcaseQueue.Entry allowedEntry = queue.pending().getLast();
        queue.add(outside, 0, 60);
        ShowcaseQueue.Entry manualFirst = queue.pending().getFirst();
        queue.add(outside, queue.pending().size(), 90);
        ShowcaseQueue.Entry manualLast = queue.pending().getLast();

        assertNull(queue.pruneAutomatic(target -> target.id().equals(allowed.id())));

        assertEquals(List.of(manualFirst, allowedEntry, manualLast), queue.pending());
        assertEquals(List.of(60, 120, 90), queue.pending().stream().map(ShowcaseQueue.Entry::durationSeconds).toList());
    }

    @Test
    void automaticPolicyChangeEndsAnIneligibleCurrentEntryAndReturnsItForRuntimeCleanup() {
        ShowcaseQueue queue = new ShowcaseQueue();
        queue.appendAutomatic(List.of(scene("outside")), 4, 120, 0);
        ShowcaseQueue.Entry automatic = queue.poll();
        queue.start(automatic, 0);
        queue.tickScene(10_000, false);
        queue.add(automatic.target(), 0, 45);
        ShowcaseQueue.Entry manualRepeat = queue.pending().getFirst();

        assertEquals(automatic, queue.pruneAutomatic(target -> false));

        assertNull(queue.current());
        assertEquals(0, queue.remainingSeconds());
        assertFalse(queue.tickScene(20_000, false));
        assertEquals(List.of(manualRepeat), queue.pending());
        assertNull(queue.pruneAutomatic(target -> false), "ended entries must not trigger runtime cleanup twice");
    }

    @Test
    void automaticPolicyChangeKeepsAnEligibleCurrentEntryAndItsElapsedPlaybackTime() {
        ShowcaseQueue queue = new ShowcaseQueue();
        queue.appendAutomatic(List.of(scene("allowed")), 4, 120, 0);
        ShowcaseQueue.Entry automatic = queue.poll();
        queue.start(automatic, 0);
        queue.tickScene(10_000, false);

        assertNull(queue.pruneAutomatic(target -> true));

        assertEquals(automatic, queue.current());
        assertEquals(110, queue.remainingSeconds());
        queue.tickScene(20_000, false);
        assertEquals(100, queue.remainingSeconds());
    }

    @Test
    void automaticPolicyChangeNeverTestsOrStopsExplicitManualEntries() {
        ShowcaseQueue queue = new ShowcaseQueue();
        ShowcaseQueue.Entry manual = queue.manualEntry(scene("current"), 120);
        queue.start(manual, 0);
        queue.tickScene(10_000, false);
        queue.add(scene("pending"), 0, 90);
        List<ShowcaseQueue.Entry> pending = queue.pending();

        assertNull(queue.pruneAutomatic(target -> {
            fail("manual choices must not consult the automatic patrol policy");
            return false;
        }));

        assertEquals(manual, queue.current());
        assertEquals(110, queue.remainingSeconds());
        assertEquals(pending, queue.pending());
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
