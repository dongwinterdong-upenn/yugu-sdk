package com.shengzhiai.yugu.stcompat.internal;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** EngineSetting.setServerAddress mapping of DESIGN 6. */
public class ServerAddressTest {
    @After
    public void reset() {
        CompatConfig.setBaseUrlOverride(null);
    }

    @Test
    public void shengtongAddressesMapToDefault() {
        String def = "https://open.shengzhiai.com";
        String[] st = {null, "", "  ", "ws://api.stkouyu.com:8080", "wss://api.stkouyu.com", "ws://gray.stkouyu.com:8090",
            "api.stkouyu.com:8080", "https://stkouyu.com/x", "WS://API.STKOUYU.COM:8080/"};
        for (String s : st) {
            assertEquals(String.valueOf(s), def, ServerAddress.resolve(s));
        }
    }

    @Test
    public void otherAddressesAreUsed() {
        assertEquals("https://eval.example.com", ServerAddress.resolve("wss://eval.example.com/"));
        assertEquals("http://10.0.0.2:8080", ServerAddress.resolve("ws://10.0.0.2:8080"));
        assertEquals("https://x.example.com/yugu", ServerAddress.resolve("https://x.example.com/yugu//"));
        assertEquals("http://127.0.0.1:18900", ServerAddress.resolve(" http://127.0.0.1:18900 "));
        assertEquals("https://notstkouyu.com", ServerAddress.resolve("https://notstkouyu.com"));
    }

    @Test
    public void invalidAddresses() {
        String[] bad = {"ftp://x.example.com", "x.example.com", "https://", "https:///path"};
        for (String b : bad) {
            try {
                ServerAddress.resolve(b);
                fail("accepted " + b);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void overrideWins() {
        assertEquals("https://open.shengzhiai.com", ServerAddress.effectiveBase(null));
        assertEquals("http://a", ServerAddress.effectiveBase("http://a"));
        CompatConfig.setBaseUrlOverride("ws://127.0.0.1:9/");
        assertEquals("http://127.0.0.1:9", CompatConfig.baseUrlOverride());
        assertEquals("http://127.0.0.1:9", ServerAddress.effectiveBase("http://a"));
        CompatConfig.setBaseUrlOverride(" ");
        assertNull(CompatConfig.baseUrlOverride());
    }
}
