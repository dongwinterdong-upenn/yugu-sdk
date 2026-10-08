package com.shengzhiai.yugu.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shengzhiai.yugu.errors.ErrorTable;
import com.shengzhiai.yugu.errors.YuguErrors;
import com.shengzhiai.yugu.errors.YuguException;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON helpers on one shared, thread safe mapper. */
public final class Json {
    /** Shared mapper; unknown properties are ignored. */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final JsonFactory FACTORY = MAPPER.getFactory();

    private Json() {
    }

    /**
     * @param value object
     * @return JSON text
     * @throws YuguException 90010 when the object cannot be serialised
     */
    public static String write(Object value) throws YuguException {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw YuguErrors.local(ErrorTable.INVALID_ARGUMENT, "cannot serialise JSON: " + e.getMessage(), e);
        }
    }

    /**
     * @param text JSON text
     * @return tree
     * @throws YuguException {@code ProtocolViolationException} 90005 when the text is not JSON
     */
    public static JsonNode read(String text) throws YuguException {
        try {
            JsonNode n = MAPPER.readTree(text);
            if (n == null || n.isMissingNode()) {
                throw YuguErrors.local(ErrorTable.PROTOCOL_ERROR, "empty JSON", null);
            }
            return n;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw YuguErrors.local(ErrorTable.PROTOCOL_ERROR, "response is not JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * @param bytes UTF-8 JSON
     * @return tree
     * @throws YuguException 90005 when the bytes are not JSON
     */
    public static JsonNode read(byte[] bytes) throws YuguException {
        return read(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Writes a JSON object whose numbers use plain decimal notation: no exponent, no trailing
     * {@code .0}. The signature of JSON bodies is computed over the same literal text.
     *
     * @param fields ordered fields
     * @return JSON text
     */
    public static String writePlainObject(Map<String, Object> fields) {
        StringWriter w = new StringWriter();
        try (JsonGenerator g = FACTORY.createGenerator(w)) {
            writeValue(g, fields);
        } catch (IOException e) {
            throw YuguErrors.local(ErrorTable.INVALID_ARGUMENT, "cannot serialise JSON: " + e.getMessage(), e);
        }
        return w.toString();
    }

    private static void writeValue(JsonGenerator g, Object v) throws IOException {
        if (v == null) {
            g.writeNull();
        } else if (v instanceof String) {
            g.writeString((String) v);
        } else if (v instanceof Boolean) {
            g.writeBoolean((Boolean) v);
        } else if (v instanceof Number) {
            g.writeNumber(plainNumber((Number) v));
        } else if (v instanceof Map) {
            g.writeStartObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                g.writeFieldName(String.valueOf(e.getKey()));
                writeValue(g, e.getValue());
            }
            g.writeEndObject();
        } else if (v instanceof Collection) {
            g.writeStartArray();
            for (Object o : (Collection<?>) v) {
                writeValue(g, o);
            }
            g.writeEndArray();
        } else {
            g.writeString(String.valueOf(v));
        }
    }

    /**
     * Plain decimal text of a number: {@code 50}, {@code 0.2}, {@code 0.0000001}.
     *
     * @param n number
     * @return text
     */
    public static String plainNumber(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte || n instanceof BigInteger) {
            return n.toString();
        }
        BigDecimal d;
        if (n instanceof BigDecimal) {
            d = (BigDecimal) n;
        } else {
            double x = n.doubleValue();
            if (Double.isNaN(x) || Double.isInfinite(x)) {
                throw YuguErrors.local(ErrorTable.INVALID_ARGUMENT, "number is not finite: " + x, null);
            }
            d = BigDecimal.valueOf(x);
        }
        if (d.signum() == 0) {
            return "0";
        }
        return d.stripTrailingZeros().toPlainString();
    }

    /**
     * Signature parameters of a JSON body: the top level fields whose value is a non-null scalar, as
     * the literal text {@link #writePlainObject(Map)} writes.
     *
     * @param fields body fields
     * @return parameters to sign
     */
    public static Map<String, String> scalarFields(Map<String, Object> fields) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String) {
                m.put(e.getKey(), (String) v);
            } else if (v instanceof Number) {
                m.put(e.getKey(), plainNumber((Number) v));
            } else if (v instanceof Boolean) {
                m.put(e.getKey(), v.toString());
            }
        }
        return m;
    }
}
