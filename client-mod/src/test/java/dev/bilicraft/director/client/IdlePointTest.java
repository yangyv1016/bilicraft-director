package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IdlePointTest {
    private static final String WORLD = "minecraft:overworld";

    @Test
    void nonFiniteCoordinatesAndInvalidDirectionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new IdlePoint(WORLD, Double.NaN, 80, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new IdlePoint(WORLD, 0, Double.POSITIVE_INFINITY, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new IdlePoint(WORLD, 0, 80, 30_000_001, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new IdlePoint(WORLD, 0, 80, 0, Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new IdlePoint(WORLD, 0, 80, 0, 0, -91));
    }

    @Test
    void idlePointUsesSceneTimingAutomaticQueueAndSessionPlaybackRules() {
        IdlePoint point = new IdlePoint(WORLD, 1, 80, 2, 90, 30);
        ShowcaseTarget target = new ShowcaseTarget.Scene(point.scene());
        assertEquals("[挂机点] 挂机点", target.label());
        ShowcaseQueue queue = new ShowcaseQueue();
        queue.fill(List.of(target), 4, 60, 0);
        assertEquals(ShowcaseQueue.Source.AUTO, queue.peek().source());
        queue.start(queue.poll(), 0);
        assertFalse(queue.tickScene(30_000, true), "arrival/menu time does not consume the idle shot");
        assertFalse(queue.tickScene(89_999, false));
        assertTrue(queue.tickScene(90_000, false));
        queue.markPlayed(target.id());
        queue.finishCurrent();
        queue.fill(List.of(target), 4, 60, 100_000);
        assertTrue(queue.pending().isEmpty());
        assertEquals(0, queue.appendAutomatic(List.of(target), 4, 60, 100_000));
        queue.add(target, 0, 30);
        assertEquals(ShowcaseQueue.Source.MANUAL, queue.peek().source());
        queue.resetSession();
        assertEquals(1, queue.appendAutomatic(List.of(target), 4, 60, 100_001));
    }
}
