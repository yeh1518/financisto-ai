package tw.tib.financisto.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class LlmCallTest {

    @Test
    public void adaptSchema_dropsTypeArrayOnlyWhereEnumIs() throws Exception {
        JSONObject in = new JSONObject("{\"type\":\"object\",\"properties\":{"
                + "\"t\":{\"type\":[\"string\",\"null\"],\"enum\":[\"expense\",\"income\",null]},"
                + "\"note\":{\"type\":[\"string\",\"null\"]},"
                + "\"pick\":{\"$ref\":\"#/$defs/pick\"}},"
                + "\"$defs\":{\"pick\":{\"type\":\"object\",\"properties\":{"
                + "\"k\":{\"type\":[\"string\",\"null\"],\"enum\":[\"a\",null]}}}}}");
        JSONObject out = LlmCall.adaptSchemaForAnthropic(in);
        JSONObject props = out.getJSONObject("properties");
        assertFalse(props.getJSONObject("t").has("type"));
        assertEquals(3, props.getJSONObject("t").getJSONArray("enum").length());
        assertTrue("沒有 enum 的 type 陣列要留著", props.getJSONObject("note").has("type"));
        assertFalse("巢狀的也要處理", out.getJSONObject("$defs").getJSONObject("pick")
                .getJSONObject("properties").getJSONObject("k").has("type"));
        assertEquals("#/$defs/pick", props.getJSONObject("pick").getString("$ref"));
    }

    @Test
    public void anthropicContent_skipsThinkingBlocks() throws Exception {
        String body = "{\"stop_reason\":\"end_turn\",\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"x\"},"
                + "{\"type\":\"text\",\"text\":\"{\\\"amount\\\":60}\"}]}";
        assertEquals("{\"amount\":60}", LlmCall.anthropicContent(body));
    }

    @Test
    public void anthropicContent_refusalAndTruncationAreReadable() throws Exception {
        for (String stop : new String[]{"refusal", "max_tokens"}) {
            try {
                LlmCall.anthropicContent("{\"stop_reason\":\"" + stop + "\",\"content\":[]}");
                fail(stop + " 應丟 Failure");
            } catch (LlmCall.Failure e) {
                assertTrue(e.getMessage(), e.getMessage().startsWith("模型"));
            }
        }
    }

    @Test
    public void reasoning_perModel() throws Exception {
        JSONObject body = new JSONObject(), oc = new JSONObject();
        LlmCall.applyReasoning(body, oc, "claude-sonnet-5-5");
        assertEquals("between_tools", body.getJSONObject("thinking").getString("type"));
        assertEquals("low", oc.getString("effort"));

        // Haiku 5.5 用預設（關思考實測明顯變差）；舊型號不收 effort，什麼都不送
        for (String m : new String[]{"claude-haiku-5-5", "claude-haiku-4-5", "claude-opus-5-5"}) {
            body = new JSONObject();
            oc = new JSONObject();
            LlmCall.applyReasoning(body, oc, m);
            assertFalse(m, body.has("thinking"));
            assertFalse(m, oc.has("effort"));
        }
    }
}
