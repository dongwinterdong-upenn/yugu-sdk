package com.shengzhiai.yugu.stcompat.internal;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RawJsonTest {
    @Test
    public void membersKeepRawText() {
        String json = " {\"a\": 1.0 , \"b\":{\"x\":[1,{\"y\":\"}\"}],\"z\":\"q\\\"\"},\"c\":\"t\\u00e9\\n\",\"d\":null,\"e\":true,\"f\":[]} ";
        Map<String, String> m = RawJson.members(json);
        assertEquals("1.0", m.get("a"));
        assertEquals("{\"x\":[1,{\"y\":\"}\"}],\"z\":\"q\\\"\"}", m.get("b"));
        assertEquals("té\n", RawJson.unquote(m.get("c")));
        assertEquals("null", m.get("d"));
        assertEquals("true", m.get("e"));
        assertEquals("[]", m.get("f"));
        assertEquals(6, m.size());
    }

    @Test
    public void emptyObject() {
        assertTrue(RawJson.members("{}").isEmpty());
    }

    @Test
    public void rejectsBadInput() {
        String[] bad = {"", "[1]", "{\"a\":}", "{\"a\":1", "{\"a\":1}x", "{\"a\" 1}", "{\"a\":bogus}", "{a:1}"};
        for (String b : bad) {
            try {
                RawJson.members(b);
                fail("accepted " + b);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        try {
            RawJson.members(null);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void quoteEscapes() {
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0001\\u2028\"", RawJson.quote("a\"b\\c\n\r\t\b\f\u0001 "));
        assertEquals("null", RawJson.quote(null));
        String s = "中文，标点。/<>";
        assertEquals(s, RawJson.unquote(RawJson.quote(s)));
        assertEquals("\b\f\r\t/", RawJson.unquote("\"\\b\\f\\r\\t\\/\""));
    }

    @Test
    public void unquoteAndToLong() {
        assertNull(RawJson.unquote("12"));
        assertNull(RawJson.unquote(null));
        assertFalse(RawJson.isString("1"));
        assertEquals(40001, RawJson.toLong("40001", 0));
        assertEquals(42, RawJson.toLong("\"42\"", 0));
        assertEquals(3, RawJson.toLong("3.0", 0));
        assertEquals(-1, RawJson.toLong("3.5", -1));
        assertEquals(-1, RawJson.toLong("\"abc\"", -1));
        assertEquals(7, RawJson.toLong(null, 7));
    }
}
