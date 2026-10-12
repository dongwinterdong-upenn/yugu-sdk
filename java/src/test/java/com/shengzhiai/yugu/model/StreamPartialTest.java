package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shengzhiai.yugu.testing.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading progress (2.1.0, CONTRACT 5.5) parsed from the frames recorded on the platform on 2026-10-12:
 * {@code result.progress} on both channels; the Shengtong-shaped incremental {@code result.words} of the
 * compat channel stays in the raw frame.
 */
class StreamPartialTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ZH_UNITS = "我是中国人我是中国人我们都是中国人中华民族是一家人";
    static final List<String> EN_UNITS = List.of("Nice", "to", "see", "you", "again", "I", "am", "a", "student", "and",
            "I", "like", "reading", "books", "every", "day");
    static final String[] COMPAT = {"ws_compat_sent_eval_cn_progress_frames.json",
        "ws_compat_sent_eval_cn_progress_misread_frames.json", "ws_compat_sent_eval_progress_frames.json"};

    /** The intermediate frames of a recorded fixture: {@code eof} 0 and no {@code event}, as StreamSession routes them. */
    static List<JsonNode> partialFrames(String fixture) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode item : Fixtures.json("fixtures/platform/" + fixture)) {
            JsonNode f = item.get("frame");
            if (!f.has("event") && f.path("eof").asInt(1) == 0) {
                out.add(f);
            }
        }
        return out;
    }

    static StreamPartial partial(JsonNode frame) {
        return new StreamPartial(frame.path("result").path("bytes").asLong(-1), frame);
    }

    static List<StreamPartial> parse(String fixture) {
        return partialFrames(fixture).stream().map(StreamPartialTest::partial).collect(Collectors.toList());
    }

    static String text(StreamPartial p) {
        return p.getProgress().stream().map(ReadingWord::getWord).collect(Collectors.joining());
    }

    static StreamPartial parse(String json, long bytes) throws Exception {
        return new StreamPartial(bytes, JSON.readTree(json));
    }

    /** Invariants of CONTRACT 5.5 that hold for every frame of one round, in order. */
    static void assertConsistentRound(String fixture, List<StreamPartial> ps, int total) {
        assertFalse(ps.isEmpty(), fixture);
        StreamPartial prev = null;
        for (StreamPartial p : ps) {
            String at = fixture + " " + p;
            assertTrue(p.hasProgress(), at);
            assertEquals(total, p.getTotalUnits(), at);
            assertEquals(total, p.getProgress().size(), at);
            assertEquals(p.getProgress().stream().filter(ReadingWord::isRead).count(), p.getReadUnits(), at);
            assertEquals(p.getProgress().stream().filter(ReadingWord::isMatched).count(), p.getMatchedUnits(), at);
            assertTrue(p.getBytes() > 0, at);
            for (int i = 0; i < total; i++) {
                ReadingWord w = p.getProgress().get(i);
                assertTrue(!w.isMatched() || w.isRead(), "matched implies read: " + at + " unit " + i);
                assertEquals(i < p.getReadUnits(), w.isRead(), "read is the prefix up to the reading position: " + at);
                if (prev != null) {
                    ReadingWord before = prev.getProgress().get(i);
                    assertEquals(before.getWord(), w.getWord(), "units are fixed for the round: " + at);
                    assertTrue(!before.isRead() || w.isRead(), "read never goes back: " + at + " unit " + i);
                    assertTrue(!before.isMatched() || w.isMatched(), "matched never goes back: " + at + " unit " + i);
                }
            }
            if (prev != null) {
                assertTrue(p.getReadUnits() >= prev.getReadUnits() && p.getMatchedUnits() >= prev.getMatchedUnits(), at);
                assertTrue(p.getBytes() >= prev.getBytes(), at);
            }
            prev = p;
        }
    }

    @Test
    void compatChineseFixture() {
        List<StreamPartial> ps = parse("ws_compat_sent_eval_cn_progress_frames.json");
        assertEquals(20, ps.size());
        assertConsistentRound("compat zh", ps, 25);
        StreamPartial first = ps.get(0);
        assertEquals(16000, first.getBytes());
        assertEquals(0, first.getReadUnits());
        assertEquals(0, first.getMatchedUnits());
        assertEquals(ZH_UNITS, text(first), "one unit per character, punctuation removed");
        StreamPartial last = ps.get(ps.size() - 1);
        assertEquals(177920, last.getBytes());
        assertEquals(25, last.getReadUnits());
        assertEquals(25, last.getMatchedUnits());
        assertTrue(last.getProgress().stream().allMatch(w -> w.isRead() && w.isMatched()));
        assertEquals(new ReadingWord("我", true, true), last.getProgress().get(0));
        assertEquals("StreamPartial{bytes=177920, readUnits=25, matchedUnits=25, totalUnits=25}", last.toString());
    }

    @Test
    void compatChineseMisreadFixture() {
        List<StreamPartial> ps = parse("ws_compat_sent_eval_cn_progress_misread_frames.json");
        assertEquals(24, ps.size());
        assertConsistentRound("compat zh misread", ps, 25);
        StreamPartial last = ps.get(ps.size() - 1);
        assertEquals(25, last.getReadUnits());
        assertEquals(24, last.getMatchedUnits());
        // the second sentence read 外 instead of 中: unit 7 was passed but did not match
        assertEquals(new ReadingWord("中", true, false), last.getProgress().get(7));
        for (int i = 0; i < 25; i++) {
            assertEquals(i != 7, last.getProgress().get(i).isMatched(), "unit " + i);
        }
        StreamPartial firstMisread = ps.stream().filter(p -> p.getReadUnits() > p.getMatchedUnits()).findFirst().orElseThrow();
        assertEquals(10, firstMisread.getReadUnits());
        assertEquals(9, firstMisread.getMatchedUnits());
        assertTrue(firstMisread.getProgress().get(7).isRead() && !firstMisread.getProgress().get(7).isMatched());
    }

    @Test
    void compatEnglishFixtureCountsWords() {
        List<StreamPartial> ps = parse("ws_compat_sent_eval_progress_frames.json");
        assertEquals(19, ps.size());
        assertConsistentRound("compat en", ps, 16);
        StreamPartial last = ps.get(ps.size() - 1);
        assertEquals(EN_UNITS, last.getProgress().stream().map(ReadingWord::getWord).collect(Collectors.toList()),
                "whitespace separated words, leading and trailing punctuation removed");
        assertEquals(16, last.getReadUnits());
        assertEquals(15, last.getMatchedUnits());
        // the partial recognition never matched "Nice": passed, not matched, the final result decides
        assertEquals(new ReadingWord("Nice", true, false), last.getProgress().get(0));
        StreamPartial early = ps.stream().filter(p -> p.getReadUnits() > 0).findFirst().orElseThrow();
        assertEquals(3, early.getReadUnits());
        assertEquals(2, early.getMatchedUnits());
        assertEquals(List.of(new ReadingWord("Nice", true, false), new ReadingWord("to", true, true), new ReadingWord("see", true, true),
                new ReadingWord("you", false, false)), early.getProgress().subList(0, 4));
    }

    @Test
    void nativeFixture() {
        List<StreamPartial> ps = parse("ws_native_sentence_progress_frames.json");
        assertEquals(20, ps.size());
        assertConsistentRound("native", ps, 25);
        assertEquals(ZH_UNITS, text(ps.get(0)));
        StreamPartial last = ps.get(ps.size() - 1);
        assertEquals(25, last.getReadUnits());
        assertEquals(25, last.getMatchedUnits());
        for (StreamPartial p : ps) {
            assertFalse(p.getRaw().path("result").has("words"), "native frames carry no Shengtong words");
            assertFalse(p.getRaw().has("recordId"));
        }
        // the final frame also has result.words (scores), but it has an event and eof 1, so it is no partial
        JsonNode fin = null;
        for (JsonNode item : Fixtures.json("fixtures/platform/ws_native_sentence_progress_frames.json")) {
            fin = item.get("frame");
        }
        assertEquals("result", fin.get("event").asText());
        assertEquals(1, fin.get("eof").asInt());
    }

    @Test
    void compatShengtongWordsStayInTheRawFrame() {
        for (String fixture : COMPAT) {
            StringBuilder concatenated = new StringBuilder();
            StreamPartial last = null;
            for (StreamPartial p : parse(fixture)) {
                JsonNode raw = p.getRaw();
                assertEquals("", raw.get("recordId").asText(), fixture);
                assertEquals("1.0.0", raw.path("result").path("kernel_version").asText(), fixture);
                JsonNode words = raw.path("result").path("words");
                assertTrue(words.isArray(), fixture);
                // the increment covers units [readUnits - words.length, readUnits) of the progress
                int from = p.getReadUnits() - words.size();
                for (int j = 0; j < words.size(); j++) {
                    ReadingWord unit = p.getProgress().get(from + j);
                    JsonNode w = words.get(j);
                    assertEquals(unit.getWord(), w.get("word").asText(), fixture + " " + p);
                    assertEquals(unit.isMatched() ? 0 : 1, w.path("scores").path("skipped").asInt(), fixture + " " + p);
                    assertEquals(unit.isMatched() ? 100 : 0, w.path("scores").path("overall").asInt(), fixture + " " + p);
                    concatenated.append(w.get("word").asText()).append(' ');
                }
                last = p;
            }
            StringBuilder read = new StringBuilder();
            for (ReadingWord u : last.getProgress().subList(0, last.getReadUnits())) {
                read.append(u.getWord()).append(' ');
            }
            assertEquals(read.toString(), concatenated.toString(), fixture + ": the increments add up to the units read");
        }
    }

    @Test
    void onlyResultProgressCarriesTheReadingState() throws Exception {
        // a Shengtong-shaped increment without progress is no progress frame
        StreamPartial wordsOnly = parse("{\"eof\":0,\"recordId\":\"\",\"result\":{\"kernel_version\":\"1.0.0\",\"words\":[{\"charType\":0,"
                + "\"scores\":{\"overall\":100,\"skipped\":0},\"word\":\"我\",\"word_parts\":[]}],\"bytes\":16000}}", 16000);
        assertFalse(wordsOnly.hasProgress());
        assertTrue(wordsOnly.getProgress().isEmpty());
        // a list shaped like progress but named words is ignored as well
        StreamPartial draft = parse("{\"eof\":0,\"result\":{\"bytes\":1,\"words\":[{\"word\":\"我\",\"read\":true,\"matched\":true}]}}", 1);
        assertFalse(draft.hasProgress());
        // both present: the reading state comes from progress, never from words
        StreamPartial both = parse("{\"eof\":0,\"recordId\":\"\",\"result\":{\"words\":[{\"word\":\"是\",\"scores\":{\"skipped\":1}}],"
                + "\"bytes\":32000,\"readUnits\":2,\"matchedUnits\":1,\"totalUnits\":3,\"progress\":[{\"word\":\"我\",\"read\":true,"
                + "\"matched\":true},{\"word\":\"是\",\"read\":true,\"matched\":false},{\"word\":\"谁\",\"read\":false,\"matched\":false}]}}", 32000);
        assertTrue(both.hasProgress());
        assertEquals(List.of(new ReadingWord("我", true, true), new ReadingWord("是", true, false), new ReadingWord("谁", false, false)),
                both.getProgress());
        assertEquals(1, both.getRaw().path("result").path("words").size(), "the raw increment is kept as sent");
    }

    @Test
    void frameWithoutProgressHasOnlyBytes() throws Exception {
        StreamPartial p = parse("{\"eof\":0,\"result\":{\"bytes\":16000}}", 16000);
        assertFalse(p.hasProgress());
        assertEquals(16000, p.getBytes());
        assertEquals(-1, p.getReadUnits());
        assertEquals(-1, p.getMatchedUnits());
        assertEquals(-1, p.getTotalUnits());
        assertTrue(p.getProgress().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> p.getProgress().add(new ReadingWord("x", true, true)));
        assertEquals("StreamPartial{bytes=16000}", p.toString());
        assertEquals(16000, p.getRaw().path("result").path("bytes").asInt());

        StreamPartial none = new StreamPartial(-1, null);
        assertFalse(none.hasProgress());
        assertEquals(-1, none.getReadUnits());
        assertTrue(none.getProgress().isEmpty());

        StreamPartial noResult = parse("{\"eof\":0}", -1);
        assertFalse(noResult.hasProgress());
        assertEquals(-1, noResult.getTotalUnits());
    }

    @Test
    void progressDecidesAndOddValuesAreTolerated() throws Exception {
        StreamPartial empty = parse("{\"eof\":0,\"result\":{\"bytes\":1,\"readUnits\":0,\"matchedUnits\":0,\"totalUnits\":0,\"progress\":[]}}", 1);
        assertTrue(empty.hasProgress(), "an empty progress array is still progress");
        assertEquals(0, empty.getTotalUnits());
        assertTrue(empty.getProgress().isEmpty());

        StreamPartial notArray = parse("{\"eof\":0,\"result\":{\"bytes\":1,\"readUnits\":3,\"progress\":null}}", 1);
        assertFalse(notArray.hasProgress());
        assertEquals(3, notArray.getReadUnits(), "counts are read as sent");

        StreamPartial odd = parse("{\"eof\":0,\"result\":{\"bytes\":1,\"readUnits\":\"2\",\"matchedUnits\":1.5,"
                + "\"totalUnits\":3,\"progress\":[{\"word\":\"a\",\"read\":true},{\"read\":true,\"matched\":true},\"x\"]}}", 1);
        assertTrue(odd.hasProgress());
        assertEquals(-1, odd.getReadUnits(), "not a number");
        assertEquals(-1, odd.getMatchedUnits(), "not an integer");
        assertEquals(3, odd.getTotalUnits());
        assertEquals(List.of(new ReadingWord("a", true, false), new ReadingWord("", true, true), new ReadingWord("", false, false)),
                odd.getProgress());
        assertThrows(UnsupportedOperationException.class, () -> odd.getProgress().clear());
    }

    @Test
    void readingWordIsAValue() {
        ReadingWord a = new ReadingWord("华", true, false);
        assertEquals("华", a.getWord());
        assertTrue(a.isRead());
        assertFalse(a.isMatched());
        assertEquals(a, a);
        assertEquals(a, new ReadingWord("华", true, false));
        assertEquals(a.hashCode(), new ReadingWord("华", true, false).hashCode());
        assertNotEquals(a, new ReadingWord("华", true, true));
        assertNotEquals(a, new ReadingWord("华", false, false));
        assertNotEquals(a, new ReadingWord("中", true, false));
        assertNotEquals(a, "华");
        assertNotEquals(a, null);
        assertEquals("ReadingWord{华, read=true, matched=false}", a.toString());
        ReadingWord n = new ReadingWord(null, false, false);
        assertEquals("", n.getWord());
        assertEquals(n, new ReadingWord("", false, false));
    }
}
