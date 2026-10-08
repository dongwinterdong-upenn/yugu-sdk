package com.shengzhiai.yugu.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Multipart assembly including the part headers and content types (DESIGN 5.2). */
class MultipartTest {

    @Test
    void nativeBodyLayoutIsExact() {
        byte[] audio = {1, 2, 3, (byte) 0xFF};
        String cfg = "{\"coreType\":\"sentence\",\"referenceText\":\"今天天气很好\"}";
        Multipart m = Multipart.builder().boundary("B0")
                .json("config", cfg)
                .file("audio", "a.wav", "audio/wav", audio)
                .build();
        String expectedHead = "--B0\r\n"
                + "Content-Disposition: form-data; name=\"config\"\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "\r\n"
                + cfg + "\r\n"
                + "--B0\r\n"
                + "Content-Disposition: form-data; name=\"audio\"; filename=\"a.wav\"\r\n"
                + "Content-Type: audio/wav\r\n"
                + "\r\n";
        byte[] head = expectedHead.getBytes(StandardCharsets.UTF_8);
        byte[] tail = "\r\n--B0--\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] expected = new byte[head.length + audio.length + tail.length];
        System.arraycopy(head, 0, expected, 0, head.length);
        System.arraycopy(audio, 0, expected, head.length, audio.length);
        System.arraycopy(tail, 0, expected, head.length + audio.length, tail.length);
        assertArrayEquals(expected, m.body());
        assertEquals("multipart/form-data; boundary=B0", m.contentType());
        assertEquals(2, m.parts().size());
        assertNull(m.parts().get(0).fileName(), "config part has no filename: it is a form parameter");
        assertEquals(Multipart.JSON_PART_TYPE, m.parts().get(0).contentType());
        assertEquals("a.wav", m.parts().get(1).fileName());
    }

    @Test
    void textFieldsHaveNoContentTypeAndNoFilename() {
        Multipart m = Multipart.builder().boundary("X").field("refText", "How are you").build();
        String body = new String(m.body(), StandardCharsets.UTF_8);
        assertEquals("--X\r\nContent-Disposition: form-data; name=\"refText\"\r\n\r\nHow are you\r\n--X--\r\n", body);
        assertNull(m.parts().get(0).contentType());
        assertEquals("refText", m.parts().get(0).name());
        assertArrayEquals("How are you".getBytes(StandardCharsets.UTF_8), m.parts().get(0).data());
    }

    @Test
    void namesAreEscapedAndFilenamesDefaulted() {
        Multipart m = Multipart.builder().boundary("X").file("audio", "a\"b\r\n.wav", "audio/wav", new byte[0])
                .file("img", null, "image/png", new byte[0]).build();
        String body = new String(m.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("filename=\"a%22b%0D%0A.wav\""), body);
        assertTrue(body.contains("name=\"img\"; filename=\"file\""), body);
    }

    @Test
    void randomBoundaryNeverOccursInTheContent() {
        Multipart a = Multipart.builder().field("x", "y").build();
        Multipart b = Multipart.builder().field("x", "y").build();
        assertTrue(a.boundary().startsWith("----YuguFormBoundary"));
        assertNotEquals(a.boundary(), b.boundary());
        assertEquals(-1, Multipart.indexOf(a.parts().get(0).data(), ("--" + a.boundary()).getBytes(StandardCharsets.US_ASCII)));
        assertEquals(2, Multipart.indexOf("abcd".getBytes(StandardCharsets.US_ASCII), "cd".getBytes(StandardCharsets.US_ASCII)));
    }
}
