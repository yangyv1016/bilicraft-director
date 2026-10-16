package dev.bilicraft.ai.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiStrategySessionTest {
    @Test
    void pendingAndFailuresStillEnforceOneGlobalRequestPerSixtySeconds() {
        AiStrategySession session = new AiStrategySession();
        var request = session.begin(1_000, 1, 1, "world", AiSettings.Provider.DEEPSEEK);
        assertNotNull(request);
        assertNull(session.begin(1_001, 1, 1, "world", AiSettings.Provider.OPENAI));
        assertTrue(session.finish(request, null));
        assertEquals(1, session.unreportedRequests);
        assertNull(session.begin(60_999, 2, 2, "other", AiSettings.Provider.OPENAI));
        assertNotNull(session.begin(61_000, 2, 2, "other", AiSettings.Provider.OPENAI));
        assertEquals(2, session.requests);
    }

    @Test
    void manualStopWorldChangesAndProviderChangesInvalidateOldEpochWithoutResettingCooldown() {
        AiStrategySession session = new AiStrategySession();
        var stale = session.begin(10, 1, 1, "old-world", AiSettings.Provider.DEEPSEEK);
        session.invalidate();
        assertFalse(session.pending());
        assertNull(session.begin(59_999, 2, 2, "new-world", AiSettings.Provider.OPENAI));
        var fresh = session.begin(60_010, 2, 2, "new-world", AiSettings.Provider.OPENAI);
        assertNotNull(fresh);
        assertNotEquals(stale.epoch(), fresh.epoch());
        assertFalse(session.finish(stale, new AiHttpClient.Completion("{}", new AiHttpClient.Usage(20, 3, 23, true), true)));
        assertTrue(session.pending(), "stale result cannot release the new request owner");
        assertEquals(23, session.totalTokens, "a stale request can still have incurred provider usage");
        assertTrue(session.finish(fresh, null));
    }

    @Test
    void successfulUsageIsSessionOnlyAndMissingReportsAreNeverClaimedAsZeroCost() {
        AiStrategySession session = new AiStrategySession();
        var first = session.begin(100, 1, 1, "world", AiSettings.Provider.DEEPSEEK);
        assertTrue(session.finish(first, new AiHttpClient.Completion("{}", new AiHttpClient.Usage(100, 12, 112, true), true)));
        var next = session.begin(60_100, 1, 1, "world", AiSettings.Provider.DEEPSEEK);
        assertTrue(session.finish(next, new AiHttpClient.Completion("{}", new AiHttpClient.Usage(0, 0, 0, false), true)));
        assertEquals(2, session.requests);
        assertEquals(100, session.promptTokens);
        assertEquals(12, session.completionTokens);
        assertEquals(112, session.totalTokens);
        assertEquals(1, session.unreportedRequests);
        assertEquals(60, session.nextRequestInSeconds(60_100));
    }
}
