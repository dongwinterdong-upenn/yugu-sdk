package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.Fixtures;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** CompatErrIds and the generated ErrorTable agree with spec/errors.json. */
@RunWith(RobolectricTestRunner.class)
public class ErrorTableSyncTest {
    @Test
    public void compatErrIdsMatchSpec() throws Exception {
        JSONObject spec = new JSONObject(Fixtures.text("spec/errors.json"));
        JSONArray ids = spec.getJSONArray("compatErrIds");
        assertEquals(ids.length(), CompatErrIds.MESSAGES.size());
        for (int i = 0; i < ids.length(); i++) {
            JSONObject e = ids.getJSONObject(i);
            int id = e.getInt("errId");
            assertEquals(e.getString("message"), CompatErrIds.MESSAGES.get(id));
            assertEquals(e.getString("message"), CompatErrIds.message(id));
        }
        assertEquals("请求参数校验失败", CompatErrIds.message(40001));
        assertEquals("响应或帧无法解析", CompatErrIds.message(90005));
        assertEquals("error 12345", CompatErrIds.message(12345));
    }

    @Test
    public void generatedTableMatchesSpec() throws Exception {
        JSONObject spec = new JSONObject(Fixtures.text("spec/errors.json"));
        String[][] sections = {{"errors", "0"}, {"warnings", "1"}, {"local", "2"}};
        for (String[] s : sections) {
            JSONArray a = spec.getJSONArray(s[0]);
            java.util.Map<Integer, ErrorTable.Entry> table = s[1].equals("0") ? ErrorTable.ERRORS
                    : s[1].equals("1") ? ErrorTable.WARNINGS : ErrorTable.LOCAL;
            assertEquals(a.length(), table.size());
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                ErrorTable.Entry entry = table.get(e.getInt("code"));
                assertEquals(e.getString("name"), entry.name);
                assertEquals(e.getBoolean("retryable"), entry.retryable);
            }
        }
        assertTrue(ErrorTable.RETRYABLE_HTTP.length == 7);
    }
}
