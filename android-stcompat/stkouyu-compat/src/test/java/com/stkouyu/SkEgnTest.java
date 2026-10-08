package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.SkEgnEmulator;
import com.shengzhiai.yugu.stcompat.internal.Wav;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.FakeTransport;
import com.shengzhiai.yugu.stcompat.testing.Fixtures;
import com.shengzhiai.yugu.stcompat.testing.Main;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** The skegn C API emulation (handles, start, feed, stop, cancel, opt, provision). */
@RunWith(RobolectricTestRunner.class)
public class SkEgnTest extends CompatTestBase {
    private static final String CFG = "{\"appKey\":\"k\",\"secretKey\":\"s\",\"cloud\":{\"server\":\"ws://api.stkouyu.com:8080\",\"connectTimeout\":4,\"serverTimeout\":9}}";

    private final List<String> results = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> ids = Collections.synchronizedList(new ArrayList<String>());
    private volatile boolean offMain;

    private final SkEgn.skegn_callback cb = new SkEgn.skegn_callback() {
        @Override
        public int run(byte[] id, int type, byte[] data, int size) {
            if (!Main.onMain()) {
                offMain = true;
            }
            assertEquals(SkEgn.SKEGN_MESSAGE_TYPE_JSON, type);
            ids.add(new String(id, StandardCharsets.US_ASCII).trim());
            results.add(new String(data, 0, size, StandardCharsets.UTF_8));
            return 0;
        }
    };

    private static String param(String coreType, String refText) {
        return "{\"coreProvideType\":\"cloud\",\"app\":{\"userId\":\"u9\"},\"audio\":{\"audioType\":\"wav\",\"sampleRate\":16000,"
                + "\"channel\":1,\"sampleBytes\":2},\"request\":{\"coreType\":\"" + coreType + "\",\"refText\":\"" + refText
                + "\",\"paragraph_need_word_score\":1,\"slack\":0.2,\"phoneme_output\":true,\"realtime_feedback\":1,"
                + "\"getParam\":1,\"custom\":{\"a\":1},\"nothing\":null}}";
    }

    @Test
    public void fullEvaluation() throws Exception {
        long e = SkEgn.skegn_new(CFG, app);
        assertNotEquals(0, e);
        assertEquals(0, SkEgn.skegn_get_last_error());
        byte[] id = new byte[64];
        assertEquals(0, SkEgn.skegn_start(e, param("sent.eval.cn", "今天天气很好"), id, cb, app));
        String token = new String(id, StandardCharsets.US_ASCII).trim();
        assertTrue(token.matches("[0-9a-f]{32}"));
        byte[] pcm = Fixtures.pcm("spec/fixtures/audio/zh_short.wav");
        for (int i = 0; i < pcm.length; i += 640) {
            assertEquals(0, SkEgn.skegn_feed(e, java.util.Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + 640)),
                    Math.min(640, pcm.length - i)));
        }
        assertEquals(0, SkEgn.skegn_feed(e, null, 0));
        assertEquals(0, SkEgn.skegn_stop(e));
        assertEquals(-1, SkEgn.skegn_stop(e));
        assertEquals(SkEgnEmulator.SGN_FUNCTION_STOP_AND_STOP, SkEgn.skegn_get_last_error());
        assertEquals(-1, SkEgn.skegn_feed(e, new byte[2], 2));
        Main.await("callback", 5000, () -> !results.isEmpty());
        JSONObject r = new JSONObject(results.get(0));
        assertEquals(token, ids.get(0));
        assertEquals(token, r.getString("tokenId"));
        assertEquals("u9", r.getString("userId"));
        assertEquals(94.6, r.getJSONObject("result").getDouble("overall"), 1e-9);
        assertTrue(r.has("params"));
        FakeTransport.Call call = transport.calls().get(0);
        assertEquals("https://open.shengzhiai.com/sent.eval.cn", call.request.url);
        assertEquals(4000, call.request.connectTimeoutMs);
        assertEquals(9000, call.request.readTimeoutMs);
        assertEquals(token, call.header("Idempotency-Key"));
        String body = call.bodyText();
        assertTrue(body.contains("name=\"paragraph_need_word_score\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n1\r\n"));
        assertTrue(body.contains("name=\"phoneme_output\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n1\r\n"));
        assertTrue(body.contains("name=\"custom\""));
        assertTrue(!body.contains("name=\"realtime_feedback\"") && !body.contains("name=\"getParam\""));
        assertTrue(!offMain);
        byte[] traffic = new byte[128];
        int n = SkEgn.skegn_opt(e, SkEgn.SKEGN_OPT_GET_TRAFFIC, traffic, traffic.length);
        assertTrue(new String(traffic, 0, n, StandardCharsets.UTF_8).startsWith("{\"upload\":"));
        assertEquals(-1, SkEgn.skegn_stop(e));
        assertEquals(SkEgnEmulator.SGN_FUNCTION_STOP_BEFORE_START, SkEgn.skegn_get_last_error());
        assertEquals(0, SkEgn.skegn_delete(e));
        assertEquals(-1, SkEgn.skegn_delete(e));
        assertEquals(SkEgnEmulator.SGN_ENGINE_IS_NULL, SkEgn.skegn_get_last_error());
    }

    @Test
    public void localErrorsComeThroughTheCallback() throws Exception {
        long e = SkEgn.skegn_new(CFG, null);
        byte[] id = new byte[64];
        SkEgn.skegn_start(e, param("open.eval", "x"), id, cb, null);
        SkEgn.skegn_feed(e, Fixtures.tone(1200, 5000), 38400);
        SkEgn.skegn_stop(e);
        Main.await("first callback", 5000, () -> results.size() == 1);
        SkEgn.skegn_start(e, param("sent.eval", "x"), id, cb, null);
        SkEgn.skegn_stop(e);
        Main.await("second callback", 5000, () -> results.size() == 2);
        SkEgn.skegn_start(e, param("sent.eval", "x"), id, cb, null);
        SkEgn.skegn_feed(e, Fixtures.tone(300, 5000), 9600);
        SkEgn.skegn_stop(e);
        Main.await("three callbacks", 5000, () -> results.size() == 3);
        SkEgn.skegn_start(e, param("sent.eval", "x"), id, cb, null);
        SkEgn.skegn_feed(e, new byte[301 * 32000], 301 * 32000);
        SkEgn.skegn_stop(e);
        Main.await("four callbacks", 10000, () -> results.size() == 4);
        List<Integer> errIds = new ArrayList<>();
        for (String r : results) {
            errIds.add(new JSONObject(r).getInt("errId"));
        }
        Collections.sort(errIds);
        assertEquals(java.util.Arrays.asList(60002, 60003, 60005, 60009), errIds);
        assertEquals(0, transport.count());
    }

    @Test
    public void wavAndMp3Uploads() throws Exception {
        long e = SkEgn.skegn_new("{\"appKey\":\"k\",\"secretKey\":\"s\",\"server\":\"http://10.0.0.1:8080\"}", app);
        byte[] id = new byte[40];
        SkEgn.skegn_start(e, param("word.eval", "apple"), id, cb, app);
        byte[] wav = Fixtures.bytes("spec/fixtures/audio/en_apple.wav");
        SkEgn.skegn_feed(e, wav, wav.length);
        SkEgn.skegn_stop(e);
        Main.await("wav", 5000, () -> results.size() == 1);
        assertEquals("http://10.0.0.1:8080/word.eval", transport.calls().get(0).request.url);
        String mp3Param = "{\"audio\":{\"audioType\":\"mp3\"},\"request\":{\"coreType\":\"word.eval\",\"refText\":\"apple\"}}";
        SkEgn.skegn_start(e, mp3Param, id, cb, app);
        SkEgn.skegn_feed(e, new byte[2000], 2000);
        SkEgn.skegn_stop(e);
        Main.await("mp3", 5000, () -> results.size() == 2);
        assertTrue(transport.calls().get(1).bodyText().contains("Content-Type: audio/mpeg"));
        String otherParam = "{\"audio\":{\"audioType\":\"opus\"},\"request\":{\"coreType\":\"word.eval\",\"refText\":\"apple\"}}";
        SkEgn.skegn_start(e, otherParam, id, cb, app);
        SkEgn.skegn_feed(e, new byte[2000], 2000);
        SkEgn.skegn_stop(e);
        Main.await("opus", 5000, () -> results.size() == 3);
        assertTrue(transport.calls().get(2).bodyText().contains("Content-Type: application/octet-stream"));
    }

    @Test
    public void platformErrorsAndCancel() throws Exception {
        transport.respond(401, FakeTransport.error(2003, "签名验证失败"));
        long e = SkEgn.skegn_new(CFG, app);
        byte[] id = new byte[64];
        SkEgn.skegn_start(e, param("sent.eval", "hi"), id, cb, app);
        SkEgn.skegn_feed(e, Fixtures.tone(1200, 5000), 38400);
        SkEgn.skegn_stop(e);
        Main.await("error", 5000, () -> results.size() == 1);
        assertEquals(2003, new JSONObject(results.get(0)).getInt("errId"));
        transport.respond(200, "{\"recordId\":\"x\"}");
        SkEgn.skegn_start(e, param("sent.eval", "hi"), id, cb, app);
        SkEgn.skegn_feed(e, Fixtures.tone(1200, 5000), 38400);
        SkEgn.skegn_stop(e);
        Main.await("protocol", 5000, () -> results.size() == 2);
        assertEquals(90005, new JSONObject(results.get(1)).getInt("errId"));
        // cancel in flight: no callback
        transport.delay(3000);
        SkEgn.skegn_start(e, param("sent.eval", "hi"), id, cb, app);
        SkEgn.skegn_feed(e, Fixtures.tone(1200, 5000), 38400);
        SkEgn.skegn_stop(e);
        Main.await("in flight", 5000, () -> transport.count() == 3);
        assertEquals(0, SkEgn.skegn_cancel(e));
        Main.settle(200);
        assertEquals(2, results.size());
        // a new start after a stop waiting for its result cancels the old one
        SkEgn.skegn_start(e, param("sent.eval", "hi"), id, cb, app);
        SkEgn.skegn_feed(e, Fixtures.tone(1200, 5000), 38400);
        SkEgn.skegn_stop(e);
        Main.await("in flight 2", 5000, () -> transport.count() == 4);
        assertEquals(0, SkEgn.skegn_start(e, param("sent.eval", "hi"), id, cb, app));
        assertEquals(SkEgnEmulator.SGN_FUNCTION_WAIT_FOR_CALLBACK, SkEgn.skegn_get_last_error());
        assertEquals(-1, SkEgn.skegn_start(e, param("sent.eval", "hi"), id, cb, app));
        assertEquals(SkEgnEmulator.SGN_FUNCTION_START_AND_START, SkEgn.skegn_get_last_error());
        SkEgn.skegn_delete(e);
    }

    @Test
    public void callSequenceErrors() {
        assertEquals(0, SkEgn.skegn_new("not json", null));
        assertEquals(SkEgnEmulator.SGN_BADCFG, SkEgn.skegn_get_last_error());
        assertEquals(0, SkEgn.skegn_new("{\"appKey\":\"k\"}", null));
        assertEquals(0, SkEgn.skegn_new(null, null));
        assertEquals(0, SkEgn.skegn_new("{\"appKey\":\"k\",\"secretKey\":\"s\",\"server\":\"ftp://x\"}", null));
        long e = SkEgn.skegn_new(CFG, null);
        byte[] id = new byte[64];
        assertEquals(-1, SkEgn.skegn_feed(e, new byte[2], 2));
        assertEquals(SkEgnEmulator.SGN_FUNCTION_FEED_BEFORE_START, SkEgn.skegn_get_last_error());
        assertEquals(-1, SkEgn.skegn_stop(e));
        assertEquals(SkEgnEmulator.SGN_FUNCTION_STOP_BEFORE_START, SkEgn.skegn_get_last_error());
        assertEquals(-1, SkEgn.skegn_start(e, param("sent.eval", "x"), id, null, null));
        assertEquals(SkEgnEmulator.SGN_CALLBACK_IS_NULL, SkEgn.skegn_get_last_error());
        assertEquals(-1, SkEgn.skegn_start(e, param("sent.eval", "x"), null, cb, null));
        assertEquals(SkEgnEmulator.SGN_ID_IS_NULL, SkEgn.skegn_get_last_error());
        assertEquals(-1, SkEgn.skegn_start(e, "{", id, cb, null));
        assertEquals(SkEgnEmulator.SGN_BAD_PRAMA, SkEgn.skegn_get_last_error());
        assertEquals(-1, SkEgn.skegn_start(e, "{\"app\":{}}", id, cb, null));
        assertEquals(-1, SkEgn.skegn_start(e, null, id, cb, null));
        assertEquals(-1, SkEgn.skegn_start(999, param("sent.eval", "x"), id, cb, null));
        assertEquals(-1, SkEgn.skegn_feed(999, new byte[2], 2));
        assertEquals(-1, SkEgn.skegn_stop(999));
        assertEquals(-1, SkEgn.skegn_cancel(999));
        assertEquals(SkEgnEmulator.SGN_ENGINE_IS_NULL, SkEgn.skegn_get_last_error());
        assertEquals(0, SkEgn.skegn_start(e, param("sent.eval", "x"), id, cb, null));
        assertEquals(0, SkEgn.skegn_cancel(e));
        assertEquals(0, SkEgn.skegn_cancel(e));
        assertEquals(0, SkEgn.skegn_start(e, param("sent.eval", "x"), id, cb, null));
        assertEquals(0, SkEgn.skegn_delete(e));
    }

    @Test
    public void optDeviceIdAndProvision() {
        long e = SkEgn.skegn_new(CFG, app);
        byte[] buf = new byte[128];
        int n = SkEgn.skegn_opt(e, SkEgn.SKEGN_OPT_GET_VERSION, buf, buf.length);
        assertEquals("yugu-stkouyu-compat/2.0.0 (api 1.0.62)", new String(buf, 0, n, StandardCharsets.US_ASCII));
        assertEquals(0, buf[n]);
        n = SkEgn.skegn_opt(e, SkEgn.SKEGN_OPT_GET_MODULES, buf, buf.length);
        assertEquals("{\"modules\":[\"cloud\"]}", new String(buf, 0, n, StandardCharsets.US_ASCII));
        assertEquals(0, SkEgn.skegn_opt(e, SkEgn.SKEGN_OPT_SET_WIFI_STATUS, buf, buf.length));
        n = SkEgn.skegn_opt(e, SkEgn.SKEGN_OPT_GET_PROVISION, buf, buf.length);
        assertTrue(new String(buf, 0, n, StandardCharsets.UTF_8).contains("cloud mode"));
        n = SkEgn.skegn_opt(e, SkEgn.SKEGN_OPT_GET_SERIAL_NUMBER, buf, buf.length);
        assertTrue(new String(buf, 0, n, StandardCharsets.UTF_8).matches("\\{\"serialNumber\":\"[0-9a-f]{32}\"}"));
        n = SkEgn.skegn_opt(0, SkEgn.SKEGN_OPT_GET_TRAFFIC, buf, buf.length);
        assertEquals("{\"upload\":0,\"download\":0}", new String(buf, 0, n, StandardCharsets.US_ASCII));
        byte[] small = new byte[4];
        assertEquals(4, SkEgn.skegn_opt(e, SkEgn.SKEGN_OPT_GET_VERSION, small, 0));
        assertEquals(-1, SkEgn.skegn_opt(e, 99, buf, buf.length));
        assertEquals(-1, SkEgn.skegn_opt(e, SkEgn.SKEGN_OPT_GET_VERSION, null, 0));
        byte[] dev = new byte[64];
        assertEquals(0, SkEgn.skegn_get_device_id(dev, app));
        assertTrue(new String(dev, StandardCharsets.US_ASCII).trim().matches("[0-9a-f]{32}"));
        assertEquals(-1, SkEgn.skegn_get_device_id(null, app));
        assertEquals(0, SkEgn.skegn_update_provision("/p", "k", "s"));
        assertEquals(0, SkEgn.skegn_inquire_provision("/p", cb, app));
        assertEquals(-1, SkEgn.skegn_inquire_provision("/p", null, app));
        Main.idle();
        assertTrue(results.get(0).contains("\"provision\":\"cloud\""));
        assertEquals(1, SkEgn.SKEGN_MESSAGE_TYPE_JSON);
        assertEquals(2, SkEgn.SKEGN_MESSAGE_TYPE_BIN);
        assertEquals(Wav.HEADER_SIZE, 44);
        new SkEgn();
    }
}
