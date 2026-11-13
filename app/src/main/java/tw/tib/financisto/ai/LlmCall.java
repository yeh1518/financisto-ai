package tw.tib.financisto.ai;

import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 一次「給規則與一句話、拿回符合 schema 的 JSON」的呼叫，兩條路：
 *
 * <ul>
 *   <li>Google AI／OpenAI／Groq／自建服務：OpenAI 相容 {@code /chat/completions}＋{@code response_format}。</li>
 *   <li>Anthropic：Messages API（{@code /v1/messages}）＋{@code output_config.format}。
 *       系統提示拆兩塊：規則＋清單（不常變，標快取）與現在時間／表單狀態（每次變），
 *       連續記幾筆時第二筆起只付約一成的輸入費。</li>
 * </ul>
 *
 * 解析鏈（BookkeepingParser）與樣板產生（TemplateGenerator）共用；錯誤一律翻成人話
 * （{@link AiErrors}）。同步方法，須在背景執行緒呼叫。
 */
final class LlmCall {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    static final String ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    static final String ANTHROPIC_VERSION = "2026-10-21";

    /** 失敗原因已是人話，直接顯示給使用者。 */
    static final class Failure extends Exception {
        Failure(String message) { super(message); }
        Failure(String message, Throwable cause) { super(message, cause); }
    }

    final String provider;
    final String key;
    final String model;
    /** OpenAI 相容路徑的 API base（自建服務是使用者填的網址）；Anthropic 路徑不用。 */
    String baseUrl;
    /** OpenAI 相容路徑用的完整系統提示（維持既有組法，不因加了 Anthropic 而改動）。 */
    String systemFull;
    /** Anthropic 路徑：不常變的那塊（規則＋清單），會標快取。 */
    String systemStable;
    /** Anthropic 路徑：每次變的那塊（現在時間、表單狀態）；可為 null。 */
    String systemVolatile;
    String user;
    /** OpenAI 形狀的 {@code {name, strict, schema}}。 */
    String schemaWrapper;

    LlmCall(String provider, String key, String model) {
        this.provider = provider;
        this.key = key;
        this.model = model;
    }

    boolean isAnthropic() {
        return AiPreferences.PROVIDER_ANTHROPIC.equals(provider);
    }

    /** 送出並回傳模型的 JSON 內容字串。 */
    String run(OkHttpClient client) throws Failure {
        String body;
        try {
            body = isAnthropic() ? anthropicBody() : openAiBody();
        } catch (JSONException e) {
            throw new Failure("組請求失敗：" + e.getMessage(), e);
        }
        Request.Builder rb = new Request.Builder()
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(JSON, body));
        if (isAnthropic()) {
            rb.url(ANTHROPIC_MESSAGES_URL)
                    .addHeader("x-api-key", key)
                    .addHeader("anthropic-version", ANTHROPIC_VERSION);
        } else {
            rb.url((baseUrl != null ? baseUrl : AiPreferences.llmBaseUrl(provider)) + "/chat/completions");
            if (key != null && !key.isEmpty()) {                 // 自建服務的 key 選填
                rb.addHeader("Authorization", "Bearer " + key);
            }
        }

        String responseBody;
        int code;
        try (Response response = client.newCall(rb.build()).execute()) {
            code = response.code();
            responseBody = response.body() != null ? response.body().string() : "";
        } catch (IOException e) {
            throw new Failure(AiErrors.network(provider, e), e);
        }
        if (code < 200 || code >= 300) {
            throw new Failure(AiErrors.http(provider, model, code, responseBody));
        }
        try {
            return isAnthropic() ? anthropicContent(responseBody) : openAiContent(responseBody);
        } catch (JSONException e) {
            throw new Failure(unreadable(e.getMessage()), e);
        }
    }

    static String unreadable(String detail) {
        return "看不懂模型的回應（格式不對）。再試一次；常發生的話到 AI 設定換個模型。（" + detail + "）";
    }

    // ---------------- OpenAI 相容 ----------------

    private String openAiBody() throws JSONException {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", systemFull))
                .put(new JSONObject().put("role", "user").put("content", user));
        return new JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("messages", messages)
                .put("response_format", new JSONObject()
                        .put("type", "json_schema")
                        .put("json_schema", new JSONObject(schemaWrapper)))
                .toString();
    }

    /** chat/completions 回應 → message.content（choices／refusal／空內容檢查）。 */
    static String openAiContent(String responseBody) throws Failure, JSONException {
        JSONObject root = new JSONObject(responseBody);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Failure("模型沒有回任何內容。再試一次；常發生的話到 AI 設定換個模型。");
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (!message.isNull("refusal")) {
            throw new Failure("模型拒絕處理這句話：" + message.optString("refusal"));
        }
        String content = message.optString("content", "");
        if (content.isEmpty()) {
            throw new Failure("模型沒有回任何內容。再試一次；常發生的話到 AI 設定換個模型。");
        }
        return content;
    }

    // ---------------- Anthropic ----------------

    private String anthropicBody() throws JSONException {
        JSONArray system = new JSONArray().put(new JSONObject()
                .put("type", "text").put("text", systemStable)
                .put("cache_control", new JSONObject().put("type", "ephemeral")));
        if (systemVolatile != null && !systemVolatile.isEmpty()) {
            system.put(new JSONObject().put("type", "text").put("text", systemVolatile));
        }
        JSONObject schema = adaptSchemaForAnthropic(new JSONObject(schemaWrapper).getJSONObject("schema"));
        JSONObject outputConfig = new JSONObject()
                .put("format", new JSONObject().put("type", "json_schema").put("schema", schema));
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("max_tokens", 8000)
                .put("system", system)
                .put("messages", new JSONArray().put(
                        new JSONObject().put("role", "user").put("content", user)))
                .put("output_config", outputConfig);
        applyReasoning(body, outputConfig, model);
        return body.toString();
    }

    /**
     * 思考深度，依真實記帳句實測（60 句偏難樣本、對使用者最後存檔的交易計分）：
     * <ul>
     *   <li>Sonnet 5.5 關思考（{@code between_tools}＋effort low）正確率最高、也最快（約 2.3 秒）；
     *       開思考沒有更準，只更慢。</li>
     *   <li>Haiku 5.5 關思考明顯變差（分類常留空、分割全錯），預設的 adaptive＋medium 才夠用
     *       （約 3.7 秒）——所以不送任何設定，用它的預設。</li>
     *   <li>其他型號（舊的 Haiku 4.5 不收 effort、新型號規則未知）一律不送，避免 400。</li>
     * </ul>
     */
    static void applyReasoning(JSONObject body, JSONObject outputConfig, String model) throws JSONException {
        String m = model == null ? "" : model.toLowerCase(Locale.US);
        if (m.startsWith("claude-sonnet-5-5")) {
            body.put("thinking", new JSONObject().put("type", "between_tools"));
            outputConfig.put("effort", "low");
        }
    }

    /**
     * Anthropic 的 schema 驗證比 OpenAI 嚴：同一節點同時有 {@code enum} 與 type 陣列
     * （{@code "type":["string","null"],"enum":[...,null]}）會 400
     * 「Enum value 'expense' does not match declared type」。拿掉 type、留 enum 意思不變
     * （enum 本身就限定了值，含 null）。其餘照抄。
     */
    static JSONObject adaptSchemaForAnthropic(JSONObject node) throws JSONException {
        JSONObject out = new JSONObject();
        Iterator<String> keys = node.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            out.put(k, adaptValue(node.get(k)));
        }
        if (out.has("enum") && out.opt("type") instanceof JSONArray) {
            out.remove("type");
        }
        return out;
    }

    private static Object adaptValue(Object v) throws JSONException {
        if (v instanceof JSONObject) return adaptSchemaForAnthropic((JSONObject) v);
        if (v instanceof JSONArray) {
            JSONArray in = (JSONArray) v, out = new JSONArray();
            for (int i = 0; i < in.length(); i++) out.put(adaptValue(in.get(i)));
            return out;
        }
        return v;
    }

    /** Messages API 回應 → 文字區塊串起來的 JSON；先看 stop_reason，被拒或截斷就不讀內容。 */
    static String anthropicContent(String responseBody) throws Failure, JSONException {
        JSONObject root = new JSONObject(responseBody);
        String stop = root.optString("stop_reason", "");
        if ("refusal".equals(stop)) {
            throw new Failure("模型拒絕處理這句話（安全機制擋下）。換個說法再試，或到 AI 設定換個模型。");
        }
        if ("max_tokens".equals(stop)) {
            throw new Failure("模型的回應太長被截斷。再試一次；常發生的話到 AI 設定換個模型。");
        }
        JSONArray content = root.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) {
                    sb.append(block.optString("text", ""));
                }
            }
        }
        if (sb.length() == 0) {
            throw new Failure("模型沒有回任何內容。再試一次；常發生的話到 AI 設定換個模型。");
        }
        return sb.toString();
    }
}
