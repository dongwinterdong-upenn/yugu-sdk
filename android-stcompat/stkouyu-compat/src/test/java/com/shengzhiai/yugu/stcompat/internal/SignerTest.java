package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.Fixtures;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Every case of spec/fixtures/sign/vectors.json (DESIGN 5.2). */
@RunWith(RobolectricTestRunner.class)
public class SignerTest {
    @Test
    public void matchesEverySharedVector() throws Exception {
        JSONObject spec = new JSONObject(Fixtures.text("spec/fixtures/sign/vectors.json"));
        JSONArray cases = spec.getJSONArray("cases");
        assertTrue(cases.length() >= 7);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            JSONObject p = c.getJSONObject("params");
            Map<String, String> params = new LinkedHashMap<>();
            Iterator<String> keys = p.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                params.put(k, p.isNull(k) ? null : p.getString(k));
            }
            String name = c.getString("name");
            assertEquals(name, c.getString("payload"), Signer.payload(params));
            assertEquals(name, c.getString("signature"), Signer.sign(params, c.getString("secret")));
        }
    }

    @Test
    public void nullParamsSignTheEmptySet() {
        assertEquals("", Signer.payload(null));
        assertEquals("q5Q0Pi4Q+j7PNBaI2X6mF6yMN8+9u202Riw462d2xjc=", Signer.sign(null, "test_secret_key_123"));
    }
}
