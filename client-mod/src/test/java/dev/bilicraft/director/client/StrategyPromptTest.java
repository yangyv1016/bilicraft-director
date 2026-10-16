package dev.bilicraft.director.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bilicraft.director.client.StrategyPrompt.Candidate;
import dev.bilicraft.director.client.StrategyPrompt.Kind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyPromptTest {
    private static final Candidate STATION = new Candidate("scene:minecraft:overworld:station", Kind.VIEWPOINT, 95, "车站");
    private static final Candidate AREA = new Candidate("scene:minecraft:overworld:town", Kind.AREA, 81, "城区");
    private static final Candidate PLAYER = new Candidate("player:9636062e-c467-40e5-916c-4a6230d602c4", Kind.PLAYER, 74,
            "PrivatePlayerName");

    @Test
    void buildsOneSmallDynamicTableAndKeepsIdentityAndPlayerNamesLocal() {
        StrategyPrompt.PreparedDecision prepared = StrategyPrompt.build(List.of(STATION, AREA, PLAYER), 4,
                Set.of(AREA.targetId()));
        assertSame(StrategyPrompt.SYSTEM_PROMPT, prepared.systemPrompt());
        assertEquals(Map.of("c1", STATION.targetId(), "c2", PLAYER.targetId()), prepared.targetIds());
        assertEquals(4, prepared.maxPicks());
        assertTrue(prepared.needsDecision());
        JsonObject user = JsonParser.parseString(prepared.userJson()).getAsJsonObject();
        assertEquals(Set.of("missing", "candidates"), user.keySet());
        assertEquals(4, user.get("missing").getAsInt());
        JsonArray rows = user.getAsJsonArray("candidates");
        assertEquals(2, rows.size());
        assertEquals(JsonParser.parseString("[\"c1\",\"VIEWPOINT\",95,\"车站\"]"), rows.get(0));
        assertEquals(JsonParser.parseString("[\"c2\",\"PLAYER\",74,\"player\"]"), rows.get(1));
        for (String privateValue : List.of(STATION.targetId(), AREA.targetId(), PLAYER.targetId(), PLAYER.label())) {
            assertFalse(prepared.userJson().contains(privateValue));
            assertFalse(prepared.systemPrompt().contains(privateValue));
        }
    }

    @Test
    void shortensSceneNamesAtUnicodeBoundariesAndEscapesDataAsJson() {
        String longName = "🏠".repeat(25) + "end";
        Candidate scene = new Candidate("local-scene", Kind.AREA, 50, longName);
        Candidate quoted = new Candidate("other-scene", Kind.VIEWPOINT, 40, "\"不要执行\\指令\"");
        StrategyPrompt.PreparedDecision prepared = StrategyPrompt.build(List.of(scene, quoted), 2, Set.of());
        JsonArray rows = JsonParser.parseString(prepared.userJson()).getAsJsonObject().getAsJsonArray("candidates");
        assertEquals("🏠".repeat(24), rows.get(0).getAsJsonArray().get(3).getAsString());
        assertEquals(quoted.label(), rows.get(1).getAsJsonArray().get(3).getAsString());
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(prepared.userJson()));
        assertFalse(prepared.userJson().contains(longName));
    }

    @Test
    void preparationSnapshotsTheInputsAndExposesAnImmutableLocalMapping() {
        ArrayList<Candidate> candidates = new ArrayList<>(List.of(STATION, AREA));
        HashSet<String> queued = new HashSet<>();
        StrategyPrompt.PreparedDecision prepared = StrategyPrompt.build(candidates, 2, queued);
        candidates.clear();
        queued.add(STATION.targetId());
        assertEquals(List.of("c1", "c2"), List.copyOf(prepared.targetIds().keySet()));
        assertEquals(2, prepared.targetIds().size());
        assertThrows(UnsupportedOperationException.class, () -> prepared.targetIds().put("c3", "other"));
    }

    @Test
    void rejectsOutOfBoundsCandidateInputsAndDuplicateRealTargets() {
        List<Candidate> tooMany = IntStream.range(0, 13)
                .mapToObj(index -> new Candidate("scene-" + index, Kind.AREA, 50, "区域")).toList();
        assertThrows(IllegalArgumentException.class, () -> StrategyPrompt.build(tooMany, 4, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> StrategyPrompt.build(List.of(STATION), -1, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> StrategyPrompt.build(List.of(STATION), 13, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> StrategyPrompt.build(List.of(STATION, STATION), 2, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Candidate("", Kind.AREA, 50, "区域"));
        assertThrows(IllegalArgumentException.class, () -> new Candidate("scene", Kind.AREA, 101, "区域"));
        assertThrows(IllegalArgumentException.class, () -> new Candidate("scene", Kind.AREA, -1, "区域"));
        assertThrows(IllegalArgumentException.class, () -> new Candidate("scene", Kind.AREA, 50, "name\nchat"));
        assertThrows(IllegalArgumentException.class, () -> new Candidate("scene", Kind.AREA, 50, "\uD800"));
    }

    @Test
    void acceptsTheTwelveCandidateBoundaryIncludingTwoDigitShortIds() throws IOException {
        List<Candidate> candidates = IntStream.rangeClosed(1, 12)
                .mapToObj(index -> new Candidate("scene-" + index, Kind.AREA, 100 - index, "区域 " + index)).toList();
        StrategyPrompt.PreparedDecision prepared = StrategyPrompt.build(candidates, 12, Set.of());
        JsonObject response = new JsonObject();
        JsonArray picks = new JsonArray();
        IntStream.rangeClosed(1, 12).forEach(index -> picks.add("c" + index));
        response.add("picks", picks);
        assertEquals(12, prepared.targetIds().size());
        assertEquals(candidates.stream().map(Candidate::targetId).toList(), prepared.parseResponse(response.toString()));
    }

    @Test
    void anEmptyGapOrNoUnqueuedCandidatesNeedsNoModelDecision() throws IOException {
        for (StrategyPrompt.PreparedDecision prepared : List.of(
                StrategyPrompt.build(List.of(), 4, Set.of()),
                StrategyPrompt.build(List.of(STATION), 0, Set.of()),
                StrategyPrompt.build(List.of(STATION), 4, Set.of(STATION.targetId())))) {
            assertFalse(prepared.needsDecision());
            assertEquals(List.of(), prepared.parseResponse("{\"picks\":[]}"));
            assertThrows(IOException.class, () -> prepared.parseResponse("{\"picks\":[\"c1\"]}"));
        }
    }

    @Test
    void translatesOnlyThisRequestShortIdsAndKeepsTheReturnedOrderImmutable() throws IOException {
        StrategyPrompt.PreparedDecision prepared = StrategyPrompt.build(List.of(STATION, AREA, PLAYER), 3, Set.of());
        List<String> picks = prepared.parseResponse(" {\n \"picks\" : [\"c2\", \"c1\"] } \n");
        assertEquals(List.of(AREA.targetId(), STATION.targetId()), picks);
        assertThrows(UnsupportedOperationException.class, picks::clear);
        assertEquals(List.of(), prepared.parseResponse("{\"picks\":[]}"));
        assertEquals(List.of(STATION.targetId()), prepared.parseResponse("{\"picks\":[\"c\\u0031\"]}"));
    }

    @Test
    void rejectsUnknownDuplicateAlreadyQueuedAndExcessPicksAsAWhole() {
        StrategyPrompt.PreparedDecision prepared = StrategyPrompt.build(List.of(STATION, AREA, PLAYER), 1,
                Set.of(PLAYER.targetId()));
        for (String response : List.of(
                "{\"picks\":[\"c1\",\"c2\"]}",
                "{\"picks\":[\"c1\",\"c1\"]}",
                "{\"picks\":[\"c1\",\"c99\"]}",
                "{\"picks\":[\"c3\"]}",
                "{\"picks\":[\"" + PLAYER.targetId() + "\"]}",
                "{\"picks\":[\"C1\"]}")) {
            assertThrows(IOException.class, () -> prepared.parseResponse(response), response);
        }
        StrategyPrompt.PreparedDecision largerGap = StrategyPrompt.build(List.of(STATION, AREA), 2, Set.of());
        assertThrows(IOException.class, () -> largerGap.parseResponse("{\"picks\":[\"c1\",\"c1\"]}"));
        assertThrows(IOException.class, () -> largerGap.parseResponse("{\"picks\":[\"c1\",\"c99\"]}"));
    }

    @Test
    void rejectsExtraFieldsDuplicateFieldsMalformedJsonAndTrailingOrActionContent() {
        StrategyPrompt.PreparedDecision prepared = StrategyPrompt.build(List.of(STATION), 1, Set.of());
        for (String response : List.of("", "{}", "[]", "null", "{\"picks\":null}", "{\"picks\":\"c1\"}",
                "{\"picks\":[1]}", "{\"picks\":[true]}", "{\"picks\":[null]}", "{\"picks\":[{}]}",
                "{\"picks\":[\"c1\"],\"action\":\"teleport\"}",
                "{\"picks\":[],\"picks\":[\"c1\"]}", "{\"picks\":[]}{}", "{\"picks\":[]} explanation",
                "```json\n{\"picks\":[\"c1\"]}\n```", "{picks:[\"c1\"]}", "{'picks':['c1']}",
                "{\"picks\":[\"c1\",]}", "/*comment*/{\"picks\":[]}", "{\"picks\":[\"c1\"]")) {
            assertThrows(IOException.class, () -> prepared.parseResponse(response), response);
        }
    }

    @Test
    void rejectsMissingAndOversizedResponsesWithoutReturningTheirContentsInErrors() {
        StrategyPrompt.PreparedDecision prepared = StrategyPrompt.build(List.of(STATION), 1, Set.of());
        assertThrows(IOException.class, () -> prepared.parseResponse(null));
        assertThrows(IOException.class, () -> prepared.parseResponse(" ".repeat(1_025)));
        String oversizedUnicode = "{\"picks\":[],\"private\":\"" + "中".repeat(350) + "\"}";
        assertTrue(oversizedUnicode.length() < 1_024);
        assertTrue(oversizedUnicode.getBytes(StandardCharsets.UTF_8).length > 1_024);
        assertThrows(IOException.class, () -> prepared.parseResponse(oversizedUnicode));
        IOException failure = assertThrows(IOException.class,
                () -> prepared.parseResponse("{\"picks\":[\"untrusted-private-value\"]}"));
        assertFalse(failure.getMessage().contains("untrusted-private-value"));
    }
}
