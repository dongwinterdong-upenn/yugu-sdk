package com.stkouyu.util.httputil;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpUtilTest {
    private interface Check {
        void run();
    }

    private static void throwsIae(String expectedMessage, Check c) {
        try {
            c.run();
            fail("no exception, expected " + expectedMessage);
        } catch (IllegalArgumentException e) {
            assertEquals(expectedMessage, e.getMessage());
        }
    }

    @Test
    public void args() {
        new Args();
        Args.check(true, "x");
        Args.check(true, "x %s", "a", "b");
        Args.check(true, "x %s", (Object) "a");
        throwsIae("bad", () -> Args.check(false, "bad"));
        throwsIae("bad a b", () -> Args.check(false, "bad %s %s", "a", "b"));
        throwsIae("bad 1", () -> Args.check(false, "bad %s", (Object) 1));
        String s = Args.notNull("v", "Name");
        assertEquals("v", s);
        throwsIae("Name may not be null", () -> Args.notNull(null, "Name"));
        String e = Args.notEmpty("v", "Name");
        assertEquals("v", e);
        throwsIae("Name may not be null", () -> Args.notEmpty((String) null, "Name"));
        throwsIae("Name may not be empty", () -> Args.notEmpty("", "Name"));
        assertEquals("v", Args.notBlank("v", "Name"));
        throwsIae("Name may not be null", () -> Args.notBlank(null, "Name"));
        throwsIae("Name may not be blank", () -> Args.notBlank(" ", "Name"));
        assertEquals("v", Args.containsNoBlanks("v", "Name"));
        throwsIae("Name may not be null", () -> Args.containsNoBlanks(null, "Name"));
        throwsIae("Name may not be empty", () -> Args.containsNoBlanks("", "Name"));
        throwsIae("Name may not contain blanks", () -> Args.containsNoBlanks("a b", "Name"));
        List<String> l = new ArrayList<>();
        l.add("x");
        List<String> back = Args.notEmpty(l, "L");
        assertSame(l, back);
        throwsIae("L may not be null", () -> Args.notEmpty((List<String>) null, "L"));
        throwsIae("L may not be empty", () -> Args.notEmpty(Collections.<String>emptyList(), "L"));
        assertEquals(1, Args.positive(1, "n"));
        assertEquals(1L, Args.positive(1L, "n"));
        throwsIae("n may not be negative or zero", () -> Args.positive(0, "n"));
        throwsIae("n may not be negative or zero", () -> Args.positive(0L, "n"));
        assertEquals(0, Args.notNegative(0, "n"));
        assertEquals(0L, Args.notNegative(0L, "n"));
        throwsIae("n may not be negative", () -> Args.notNegative(-1, "n"));
        throwsIae("n may not be negative", () -> Args.notNegative(-1L, "n"));
    }

    @Test
    public void constsAndEncoding() {
        new Consts();
        new EncodingUtils();
        assertEquals(13, Consts.CR);
        assertEquals(10, Consts.LF);
        assertEquals(32, Consts.SP);
        assertEquals(9, Consts.HT);
        assertEquals(StandardCharsets.UTF_8, Consts.UTF_8);
        assertEquals(StandardCharsets.US_ASCII, Consts.ASCII);
        assertEquals(StandardCharsets.ISO_8859_1, Consts.ISO_8859_1);
        byte[] b = "中文".getBytes(StandardCharsets.UTF_8);
        assertEquals("中文", EncodingUtils.getString(b, "UTF-8"));
        assertEquals("文", EncodingUtils.getString(b, 3, 3, "UTF-8"));
        assertEquals(new String(b), EncodingUtils.getString(b, "no-such-charset"));
        assertArrayEquals(b, EncodingUtils.getBytes("中文", "UTF-8"));
        assertArrayEquals("x".getBytes(), EncodingUtils.getBytes("x", "no-such-charset"));
        assertArrayEquals(new byte[] {'a', 'b'}, EncodingUtils.getAsciiBytes("ab"));
        assertEquals("ab", EncodingUtils.getAsciiString(new byte[] {'a', 'b'}));
        assertEquals("b", EncodingUtils.getAsciiString(new byte[] {'a', 'b'}, 1, 1));
        throwsIae("Input may not be null", () -> EncodingUtils.getAsciiString(null));
        throwsIae("Charset may not be empty", () -> EncodingUtils.getBytes("x", ""));
    }

    @Test
    public void textUtils() {
        new TextUtils();
        assertTrue(TextUtils.isEmpty(null));
        assertTrue(TextUtils.isEmpty(""));
        assertFalse(TextUtils.isEmpty(" "));
        assertTrue(TextUtils.isBlank(null));
        assertTrue(TextUtils.isBlank(" \t"));
        assertFalse(TextUtils.isBlank(" x"));
        assertFalse(TextUtils.containsBlanks(null));
        assertFalse(TextUtils.containsBlanks("ab"));
        assertTrue(TextUtils.containsBlanks("a b"));
    }
}
