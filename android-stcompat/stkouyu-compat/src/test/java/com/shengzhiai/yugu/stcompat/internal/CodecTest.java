package com.shengzhiai.yugu.stcompat.internal;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class CodecTest {
    @Test
    public void base64MatchesJdk() {
        String[] samples = {"", "f", "fo", "foo", "foob", "fooba", "foobar", "中文，标点。", "\u0000ÿ\u0001"};
        for (String s : samples) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            assertEquals(s, java.util.Base64.getEncoder().encodeToString(b), Codec.base64(b));
        }
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        assertEquals(java.util.Base64.getEncoder().encodeToString(all), Codec.base64(all));
    }

    @Test
    public void hexAndDigest() {
        assertEquals("00ff10", Codec.hex(new byte[] {0, (byte) 255, 16}));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d",
                Codec.hex(Codec.digest("SHA-1", "abc".getBytes(StandardCharsets.UTF_8))));
    }

    @Test(expected = IllegalStateException.class)
    public void unknownDigest() {
        Codec.digest("NOPE-1", new byte[0]);
    }

    @Test
    public void tokenIdIs32LowerHex() {
        String a = Codec.newTokenId();
        String b = Codec.newTokenId();
        assertTrue(a.matches("^[0-9a-f]{32}$"));
        assertNotEquals(a, b);
        assertTrue(Codec.randomHex(8).matches("^[0-9a-f]{16}$"));
    }

    @Test
    public void emptyAndBlank() {
        assertTrue(Codec.isEmpty(null));
        assertTrue(Codec.isEmpty(""));
        assertFalse(Codec.isEmpty(" "));
        assertTrue(Codec.isBlank(null));
        assertTrue(Codec.isBlank(" \t\n"));
        assertFalse(Codec.isBlank(" a "));
    }
}
