package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.Fixtures;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** DESIGN 6.2 envelopes. */
@RunWith(RobolectricTestRunner.class)
public class EnvelopeTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    public void successEnvelopeWrapsTheUnchangedResult() throws Exception {
        String body = Fixtures.text("spec/fixtures/platform/compat_sent.eval.cn.json");
        String json = Envelope.success(TOKEN, "ak", "u1", "今天天气很好", body, null, 1791460800123L);
        JSONObject e = new JSONObject(json);
        Iterator<String> keys = e.keys();
        String[] order = {"tokenId", "recordId", "applicationId", "userId", "refText", "eof", "dtLastResponse", "result"};
        for (String k : order) {
            assertEquals(k, keys.next());
        }
        assertFalse(keys.hasNext());
        assertEquals(TOKEN, e.getString("tokenId"));
        assertEquals("eval_7954d149c40a", e.getString("recordId"));
        assertEquals("ak", e.getString("applicationId"));
        assertEquals("u1", e.getString("userId"));
        assertEquals("今天天气很好", e.getString("refText"));
        assertEquals(1, e.getInt("eof"));
        assertTrue(e.getString("dtLastResponse").matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}:\\d{3}"));
        assertEquals(94.6, e.getJSONObject("result").getDouble("overall"), 1e-9);
        // byte for byte apart from the Shengtong-style keys added to sentences[].details[]
        assertOnlyAlignment(RawJson.members(body.trim()).get("result").trim(), resultText(json));
        assertFalse(e.has("params"));
        assertFalse(e.has("audioUrl"));
    }

    @Test
    public void paramsAndAudioUrl() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("refText", "hi");
        fields.put("attachAudioUrl", "1");
        fields.put("tokenId", "ignored");
        String params = Envelope.params("ak", null, 1791460800000L, "mp3", 16000, 1, "sent.eval", TOKEN, fields);
        String body = "{\"recordId\":\"eval_1\",\"eof\":1,\"result\":{\"overall\":80,\"audioUrl\":\"https://cdn/a.mp3\"}}";
        JSONObject e = new JSONObject(Envelope.success(TOKEN, "ak", null, null, body, params, 0));
        assertEquals("https://cdn/a.mp3", e.getString("audioUrl"));
        assertEquals("", e.getString("userId"));
        assertEquals("", e.getString("refText"));
        JSONObject p = e.getJSONObject("params");
        assertEquals("ak", p.getJSONObject("app").getString("applicationId"));
        assertEquals("1791460800", p.getJSONObject("app").getString("timestamp"));
        assertEquals("mp3", p.getJSONObject("audio").getString("audioType"));
        assertEquals(16000, p.getJSONObject("audio").getInt("sampleRate"));
        assertEquals(2, p.getJSONObject("audio").getInt("sampleBytes"));
        assertEquals("sent.eval", p.getJSONObject("request").getString("coreType"));
        assertEquals(TOKEN, p.getJSONObject("request").getString("tokenId"));
        assertEquals("1", p.getJSONObject("request").getString("attachAudioUrl"));
        assertFalse(json(params).contains("secret"));

        String top = "{\"recordId\":\"eval_2\",\"audioUrl\":\"https://cdn/b.mp3\",\"result\":{\"overall\":1}}";
        assertEquals("https://cdn/b.mp3", new JSONObject(Envelope.success(TOKEN, "ak", "u", "r", top, null, 0)).getString("audioUrl"));
        String empty = "{\"recordId\":\"eval_3\",\"audioUrl\":\"\",\"result\":{\"overall\":1}}";
        assertFalse(new JSONObject(Envelope.success(TOKEN, "ak", "u", "r", empty, null, 0)).has("audioUrl"));
        String noRecord = "{\"result\":{\"overall\":1}}";
        assertFalse(new JSONObject(Envelope.success(TOKEN, "ak", "u", "r", noRecord, null, 0)).has("recordId"));
        Envelope.params("ak", "u", 0, null, 16000, 1, null, null, null);
    }

    private static String json(String s) {
        return s;
    }

    /** Production: the compat endpoint returns no audioUrl even with attachAudioUrl=1, so the envelope has none. */
    @Test
    public void attachAudioUrlFixtureHasNoAudioUrl() throws Exception {
        String body = Fixtures.text("spec/fixtures/platform/compat_sent.eval.cn_attach_audio_url.json");
        String json = Envelope.success(TOKEN, "ak", "u", "今天天气很好", body, null, 0);
        JSONObject e = new JSONObject(json);
        assertFalse(e.has("audioUrl"));
        assertFalse(e.getJSONObject("result").has("audioUrl"));
        assertOnlyAlignment(RawJson.members(body.trim()).get("result").trim(), resultText(json));
    }

    /**
     * DESIGN 6 result-shape alignment: Shengtong parsers read sentences[].details[].overall, the
     * platform has details[].scores.overall. Every detail without overall gets overall and
     * pronunciation copied from scores, everything else stays as the platform sent it.
     */
    @Test
    public void paragraphWordDetailsGetShengtongStyleOverall() throws Exception {
        String body = Fixtures.text("spec/fixtures/platform/compat_para.eval.cn_word_detail.json");
        String json = Envelope.success(TOKEN, "ak", "u", "今天天气很好。我们一起去公园散步。", body, null, 0);
        JSONObject r = new JSONObject(json).getJSONObject("result");
        assertEquals(89.8, r.getDouble("overall"), 1e-9);
        org.json.JSONArray sentences = r.getJSONArray("sentences");
        assertEquals(2, sentences.length());
        assertEquals(6, sentences.getJSONObject(0).getJSONArray("details").length());
        assertEquals(9, sentences.getJSONObject(1).getJSONArray("details").length());
        int checked = 0;
        for (int i = 0; i < sentences.length(); i++) {
            org.json.JSONArray details = sentences.getJSONObject(i).getJSONArray("details");
            for (int j = 0; j < details.length(); j++) {
                JSONObject d = details.getJSONObject(j);
                // what the public Shengtong sample does, unconditionally
                int overall = d.getInt("overall");
                assertEquals(d.getJSONObject("scores").getInt("overall"), overall);
                assertEquals(d.getJSONObject("scores").getInt("pronunciation"), d.getInt("pronunciation"));
                d.getString("word");
                checked++;
            }
        }
        assertEquals(15, checked);
        JSONObject first = sentences.getJSONObject(0).getJSONArray("details").getJSONObject(0);
        assertEquals("今", first.getString("word"));
        assertEquals(76, first.getInt("overall"));
        assertOnlyAlignment(RawJson.members(body.trim()).get("result").trim(), resultText(json));
    }

    @Test
    public void alignmentEdgeCases() {
        String none = "{\"overall\":1,\"words\":[]}";
        assertTrue(none == Envelope.alignResult(none));
        String notArray = "{\"sentences\":{\"details\":[]}}";
        assertTrue(notArray == Envelope.alignResult(notArray));
        String odd = "{\"sentences\":[1,\"x\",{\"details\":7},{\"details\":[]},{\"details\":[2,{},{\"word\":\"a\"},"
                + "{\"word\":\"b\",\"scores\":5},{\"word\":\"c\",\"overall\":9,\"scores\":{\"overall\":1}}]}]}";
        assertTrue(odd == Envelope.alignResult(odd));
        String partial = "{\"sentences\":[{\"details\":[{\"word\":\"a\",\"scores\":{\"overall\":70.5}},"
                + "{\"word\":\"b\",\"pronunciation\":3,\"scores\":{\"overall\":60,\"pronunciation\":61}},"
                + "{\"word\":\"c\",\"scores\":{\"pronunciation\":40}}]}]}";
        assertEquals("{\"sentences\":[{\"details\":[{\"word\":\"a\",\"scores\":{\"overall\":70.5},\"overall\":70.5},"
                + "{\"word\":\"b\",\"pronunciation\":3,\"scores\":{\"overall\":60,\"pronunciation\":61},\"overall\":60},"
                + "{\"word\":\"c\",\"scores\":{\"pronunciation\":40},\"pronunciation\":40}]}]}", Envelope.alignResult(partial));
        String pretty = "{\n  \"sentences\": [\n    {\n      \"details\": [\n        { \"word\": \"a\", \"scores\": { \"overall\": 5 } }\n"
                + "      ]\n    }\n  ]\n}";
        assertEquals("{\n  \"sentences\": [\n    {\n      \"details\": [\n        { \"word\": \"a\", \"scores\": { \"overall\": 5 } ,\"overall\":5}\n"
                + "      ]\n    }\n  ]\n}", Envelope.alignResult(pretty));
        String broken = "{\"sentences\":[{\"details\":[{\"scores\":{\"overall\":1}";
        assertTrue(broken == Envelope.alignResult(broken));
        assertEquals("[1]", Envelope.alignResult("[1]"));
    }

    @Test
    public void prettyPrintedFixtureIsAligned() throws Exception {
        String body = Fixtures.text("spec/fixtures/platform/compat_sent_eval_cn.json");
        String raw = RawJson.members(body.trim()).get("result").trim();
        assertOnlyAlignment(raw, Envelope.alignResult(raw));
    }

    /** Result text inside the envelope. */
    private static String resultText(String envelope) {
        return RawJson.members(envelope).get("result").trim();
    }

    /**
     * The aligned text must equal the platform text except for inserted
     * {@code ,"overall":X[,"pronunciation":Y]} right before a closing brace, and every detail
     * without overall must carry the copied values.
     */
    private static void assertOnlyAlignment(String raw, String aligned) throws Exception {
        java.util.regex.Pattern ins = java.util.regex.Pattern.compile(
                "^\\s*,?\"(overall|pronunciation)\":[-0-9.eE+]+(,\"pronunciation\":[-0-9.eE+]+)?(?=\\s*})");
        int i = 0;
        int j = 0;
        int insertions = 0;
        while (i < raw.length() || j < aligned.length()) {
            if (i < raw.length() && j < aligned.length() && raw.charAt(i) == aligned.charAt(j)) {
                i++;
                j++;
                continue;
            }
            java.util.regex.Matcher m = ins.matcher(aligned.substring(j));
            assertTrue("unexpected difference at " + j + ": " + aligned.substring(j, Math.min(aligned.length(), j + 60)), m.find());
            j += m.end();
            insertions++;
        }
        JSONObject a = new JSONObject(aligned);
        JSONObject o = new JSONObject(raw);
        int expected = 0;
        org.json.JSONArray so = o.optJSONArray("sentences");
        for (int s = 0; so != null && s < so.length(); s++) {
            org.json.JSONArray dO = so.getJSONObject(s).optJSONArray("details");
            org.json.JSONArray dA = a.getJSONArray("sentences").getJSONObject(s).optJSONArray("details");
            for (int k = 0; dO != null && k < dO.length(); k++) {
                JSONObject od = dO.getJSONObject(k);
                JSONObject ad = dA.getJSONObject(k);
                if (!od.has("overall") && od.optJSONObject("scores") != null && od.getJSONObject("scores").has("overall")) {
                    expected++;
                    assertEquals(od.getJSONObject("scores").get("overall"), ad.get("overall"));
                    ad.remove("overall");
                    if (!od.has("pronunciation") && od.getJSONObject("scores").has("pronunciation")) {
                        assertEquals(od.getJSONObject("scores").get("pronunciation"), ad.get("pronunciation"));
                        ad.remove("pronunciation");
                    }
                }
            }
        }
        assertEquals(expected, insertions);
        assertEquals(o.toString(), a.toString());
    }


    @Test
    public void errorEnvelope() throws Exception {
        JSONObject e = new JSONObject(Envelope.error(TOKEN, 60003, "coreType 不支持", "ak"));
        Iterator<String> keys = e.keys();
        for (String k : new String[] {"tokenId", "errId", "error", "eof", "applicationId"}) {
            assertEquals(k, keys.next());
        }
        assertEquals(60003, e.getInt("errId"));
        assertEquals("coreType 不支持", e.getString("error"));
        assertEquals(1, e.getInt("eof"));
        JSONObject n = new JSONObject(Envelope.error(null, 20009, null, null));
        assertEquals("", n.getString("tokenId"));
        assertEquals("", n.getString("applicationId"));
    }

    @Test
    public void bodyWithoutResultIsRejected() {
        try {
            Envelope.success(TOKEN, "ak", "u", "r", "{\"code\":0}", null, 0);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void timestampFormat() {
        assertTrue(Envelope.timestamp(0).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}:000"));
    }
}
