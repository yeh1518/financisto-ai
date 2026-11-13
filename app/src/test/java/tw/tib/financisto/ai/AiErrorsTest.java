package tw.tib.financisto.ai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.junit.Test;

/** 各家真實錯誤格式 → 要對到「使用者該做什麼」那一類，且不能把原始 JSON 丟給使用者。 */
public class AiErrorsTest {

    private static final String G = AiPreferences.PROVIDER_GEMINI;
    private static final String O = AiPreferences.PROVIDER_OPENAI;
    private static final String A = AiPreferences.PROVIDER_ANTHROPIC;

    private static void has(String msg, String... parts) {
        for (String p : parts) {
            assertTrue("「" + msg + "」應含「" + p + "」", msg.contains(p));
        }
        assertFalse("不該把原始 JSON 丟出來：" + msg, msg.contains("{"));
    }

    @Test
    public void googlePrepaidCreditsDepleted_saysTopUp() {
        // 實際畫面上出現過的那則（外面包一層陣列）
        String body = "[{\n  \"error\": {\n    \"code\": 402,\n    \"message\": \"Your prepayment credits are depleted. "
                + "Please go to AI Studio at https://ai.studio/projects to manage your project and billing. "
                + "Learn more at https://ai.google.dev/gemini-api/docs/billing#prepay.\",\n"
                + "    \"status\": \"RESOURCE_EXHAUSTED\"\n  }\n}\n]";
        has(AiErrors.http(G, "gemini-3.1-flash-lite", 402, body),
                "Google AI 的額度用完了", "aistudio.google.com）儲值", "改用別家", "〔402 RESOURCE_EXHAUSTED〕");
    }

    @Test
    public void googleFreeTierDaily_saysResetsTomorrow() {
        String body = "{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota, please check your plan "
                + "and billing details. Quota exceeded for metric: generativelanguage.googleapis.com/"
                + "generate_content_free_tier_requests, limit: 250, model: gemini-3.1-flash-lite "
                + "[quotaId: GenerateRequestsPerDayPerProjectPerModel-FreeTier]\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
        has(AiErrors.http(G, null, 429, body), "今天的 Google AI 免費額度用完了", "隔天");
    }

    @Test
    public void googlePerMinuteQuota_saysWait() {
        String body = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded for metric: "
                + "generate_content_free_tier_requests, limit: 15 [quotaId: "
                + "GenerateRequestsPerMinutePerProjectPerModel-FreeTier]\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
        has(AiErrors.http(G, null, 429, body), "用量上限", "等一下");
    }

    @Test
    public void googleInvalidKeyIs400_stillSaysKey() {
        String body = "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\","
                + "\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}";
        has(AiErrors.http(G, null, 400, body), "API key 無效", "重新貼上");
    }

    @Test
    public void googleLeakedKey_saysMakeNewKey() {
        String body = "{\"error\":{\"code\":403,\"message\":\"Your API key was reported as leaked. "
                + "Please use another API key.\",\"status\":\"PERMISSION_DENIED\"}}";
        has(AiErrors.http(G, null, 403, body), "外洩", "重新產一把");
    }

    @Test
    public void unsupportedRegion_mentionsVpn() {
        String body = "{\"error\":{\"code\":400,\"message\":\"User location is not supported for the API use.\","
                + "\"status\":\"FAILED_PRECONDITION\"}}";
        has(AiErrors.http(G, null, 400, body), "所在地區", "VPN");
    }

    @Test
    public void openAiInsufficientQuota_isBillingNotRateLimit() {
        String body = "{\"error\":{\"message\":\"You exceeded your current quota, please check your plan and "
                + "billing details.\",\"type\":\"insufficient_quota\",\"param\":null,\"code\":\"insufficient_quota\"}}";
        has(AiErrors.http(O, null, 429, body), "OpenAI 的額度用完了", "儲值");
    }

    @Test
    public void openAiModelNotFound_namesTheModel() {
        String body = "{\"error\":{\"message\":\"The model `gpt-9` does not exist or you do not have access to it.\","
                + "\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}";
        has(AiErrors.http(O, "gpt-9", 404, body), "模型「gpt-9」", "重選模型");
    }

    @Test
    public void anthropicCreditTooLow_isBilling() {
        // Anthropic 的餘額不足是 400 invalid_request_error，不是 402
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":"
                + "\"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing "
                + "to upgrade or purchase credits.\"}}";
        has(AiErrors.http(A, "claude-sonnet-5-5", 400, body), "Anthropic 的額度用完了", "Claude Console");
    }

    @Test
    public void anthropicOverloaded_saysRetryLater() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
        has(AiErrors.http(A, null, 529, body), "暫時故障或太忙", "〔529 overloaded_error〕");
    }

    @Test
    public void anthropicBadKey() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";
        has(AiErrors.http(A, null, 401, body), "Anthropic 的 API key 無效");
    }

    @Test
    public void anthropicRateLimit_saysSlowDown() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":"
                + "\"Number of request tokens has exceeded your per-minute rate limit\"}}";
        has(AiErrors.http(A, null, 429, body), "太頻繁");
    }

    @Test
    public void htmlGatewayError_noRawHtml() {
        String msg = AiErrors.http(O, null, 502, "<html><body>Bad Gateway</body></html>");
        has(msg, "暫時故障或太忙", "〔502〕");
        assertFalse(msg.contains("<html>"));
    }

    @Test
    public void unknown400_keepsProviderMessageButShort() {
        String body = "{\"error\":{\"message\":\"something odd\",\"type\":\"invalid_request_error\"}}";
        has(AiErrors.http(O, null, 400, body), "OpenAI 不接受這次的請求：something odd");
    }

    @Test
    public void custom404_pointsAtMissingV1() {
        has(AiErrors.http(AiPreferences.PROVIDER_CUSTOM, "qwen", 404, "File Not Found"), "自建服務回 404", "/v1");
    }

    @Test
    public void customUnreachable_mentionsNetworkAndTailscale() {
        has(AiErrors.network(AiPreferences.PROVIDER_CUSTOM, new java.net.ConnectException("refused")),
                "連不上自建服務", "Tailscale");
    }

    @Test
    public void network() {
        has(AiErrors.network(G, new UnknownHostException("generativelanguage.googleapis.com")), "連不上網路");
        has(AiErrors.network(A, new SocketTimeoutException("timeout")), "Anthropic 太久沒有回應");
    }
}
