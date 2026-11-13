package tw.tib.financisto.ai;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 把 AI 服務回的錯誤翻成看得懂、知道下一步怎麼做的話。
 *
 * 各家錯誤格式不同：Google AI 回 {@code {"error":{"code":402,"status":"RESOURCE_EXHAUSTED",
 * "message":...}}}（有時外面再包一層陣列）、OpenAI／Groq 回 {@code error.type / error.code}、
 * Anthropic 回 {@code {"type":"error","error":{"type":"billing_error",...}}}。
 * 這裡先抽出 HTTP 狀態碼、狀態字、訊息，再依「使用者要做什麼」分類：
 * 儲值、換 key、換模型、等一下、換網路。原始狀態以〔402 RESOURCE_EXHAUSTED〕附在句尾，
 * 留在 AI 記錄裡事後查得到是哪一種。
 *
 * 純函式，不碰 Android API（單元測試直接打）。
 */
public final class AiErrors {

    private AiErrors() {}

    public static String providerName(String provider) {
        if (provider == null) return "AI 服務";
        switch (AiPreferences.underlyingProvider(provider)) {
            case AiPreferences.PROVIDER_GEMINI: return "Google AI";
            case AiPreferences.PROVIDER_GROQ: return "Groq";
            case AiPreferences.PROVIDER_OPENAI: return "OpenAI";
            case AiPreferences.PROVIDER_ANTHROPIC: return "Anthropic";
            case AiPreferences.PROVIDER_CUSTOM: return "自建服務";
            default: return provider;
        }
    }

    /** 帳單／後台在哪：額度用完、key 失效時要去的地方。 */
    static String consoleHint(String provider) {
        if (provider == null) return "該服務的後台";
        switch (AiPreferences.underlyingProvider(provider)) {
            case AiPreferences.PROVIDER_GEMINI: return "Google AI Studio（aistudio.google.com）";
            case AiPreferences.PROVIDER_GROQ: return "Groq 後台（console.groq.com）";
            case AiPreferences.PROVIDER_OPENAI: return "OpenAI 後台（platform.openai.com）";
            case AiPreferences.PROVIDER_ANTHROPIC: return "Claude Console（platform.claude.com）";
            case AiPreferences.PROVIDER_CUSTOM: return "自建服務的設定";
            default: return "該服務的後台";
        }
    }

    /**
     * HTTP 非 2xx → 人話。
     *
     * @param model 這次用的模型（「找不到模型」時點名用；可為 null）
     */
    public static String http(String provider, String model, int code, String body) {
        String p = providerName(provider);
        Info e = Info.parse(body);
        String m = e.message.toLowerCase(Locale.US);
        String tag = tag(code, e);

        // 1) 額度／餘額用完：要儲值或換一家，等多久都不會好
        if (code == 402 || "billing_error".equals(e.type) || "insufficient_quota".equals(e.code)
                || m.contains("credit balance is too low") || m.contains("credits are depleted")
                || m.contains("prepayment")) {
            // 「exceeded your current quota」不能當判準：Google 免費層的 429 也這樣寫，那是等一下就好
            return p + " 的額度用完了（帳戶沒有可用餘額）。到 " + consoleHint(provider)
                    + "儲值，或在 AI 設定改用別家服務。" + tag;
        }
        // 2) 地區不支援：常見於開著 VPN
        if (m.contains("location is not supported") || "unsupported_country_region_territory".equals(e.code)
                || m.contains("not available in your country") || m.contains("unsupported_country")) {
            return p + " 不對你目前的所在地區提供服務。有開 VPN 的話換個節點或先關掉再試。" + tag;
        }
        // 3) key 被判定外洩而停用：換 key 才會好
        if (m.contains("leaked")) {
            return p + " 判定這把 key 已外洩而停用。到 " + consoleHint(provider)
                    + "重新產一把，再到 AI 設定換上。" + tag;
        }
        // 4) key 無效（Google AI 的無效 key 是 400，不是 401）
        if (code == 401 || "authentication_error".equals(e.type) || "invalid_api_key".equals(e.code)
                || m.contains("api key not valid") || m.contains("api_key_invalid")
                || m.contains("incorrect api key") || m.contains("invalid x-api-key")
                || m.contains("invalid api key")) {
            return p + " 的 API key 無效（可能貼錯、被刪除或已停用）。到 AI 設定重新貼上 key。" + tag;
        }
        if (code == 403 || "permission_error".equals(e.type) || "PERMISSION_DENIED".equals(e.status)) {
            return p + " 拒絕這把 key 使用這項服務（權限不足或帳戶受限）。到 "
                    + consoleHint(provider) + "確認帳戶狀態。" + tag;
        }
        // 5) 用量上限：每日免費額度 vs 每分鐘速率
        if (code == 429 || "rate_limit_error".equals(e.type) || "RESOURCE_EXHAUSTED".equals(e.status)) {
            if (m.contains("per day") || m.contains("perday") || m.contains("daily")) {
                return "今天的 " + p + " 免費額度用完了，隔天會重置。急用可在 AI 設定換別家，或改用付費 key。" + tag;
            }
            if (m.contains("quota")) {
                return "已達 " + p + " 的用量上限（免費方案的上限較低）。等一下再試，或在 AI 設定換別家。" + tag;
            }
            return "用得太頻繁，" + p + " 要求放慢一點（每分鐘上限）。等幾十秒再試。" + tag;
        }
        // 6) 模型不存在／下架。自建服務的 404 多半是網址少了 /v1，先講這個
        if (code == 404 && AiPreferences.PROVIDER_CUSTOM.equals(provider)) {
            return "自建服務回 404：網址可能少了 /v1（例：http://主機:8080/v1），或模型名稱不對。到 AI 設定檢查。" + tag;
        }
        if (code == 404 || "not_found_error".equals(e.type) || "model_not_found".equals(e.code)
                || "NOT_FOUND".equals(e.status)) {
            String which = (model == null || model.isEmpty()) ? "這個模型" : "模型「" + model + "」";
            return p + " 找不到" + which + "（可能已下架或改名）。到 AI 設定重選模型。" + tag;
        }
        if (code == 413 || "request_too_large".equals(e.type)) {
            return "這次送出的內容太大（例如錄音太長）。縮短後再試。" + tag;
        }
        if (code == 408 || code == 504 || "DEADLINE_EXCEEDED".equals(e.status)) {
            return p + " 處理太久沒有回應。再試一次；常發生的話換個較快的模型。" + tag;
        }
        // 7) 對方暫時故障／太忙
        if (code >= 500 || "overloaded_error".equals(e.type) || "api_error".equals(e.type)
                || "UNAVAILABLE".equals(e.status) || "INTERNAL".equals(e.status)) {
            return p + " 那邊暫時故障或太忙。通常稍等一下再試就好；一直這樣可以先在 AI 設定換別家。" + tag;
        }
        String detail = e.message.isEmpty() ? shorten(body, 200) : shorten(e.message, 200);
        if (code == 400) {
            return p + " 不接受這次的請求：" + detail + tag;
        }
        return p + " 回傳錯誤：" + detail + tag;
    }

    /** 連線層的錯誤（沒拿到 HTTP 回應）→ 人話。 */
    public static String network(String provider, IOException ex) {
        String p = providerName(provider);
        if (AiPreferences.PROVIDER_CUSTOM.equals(provider)
                && (ex instanceof UnknownHostException || ex instanceof ConnectException
                    || ex instanceof NoRouteToHostException)) {
            return "連不上自建服務。確認那台機器開著、服務有在跑，手機也連得到"
                    + "（內網或 Tailscale 位址要先連上該網路）；網址在 AI 設定。";
        }
        if (ex instanceof UnknownHostException) {
            return "連不上網路（找不到 " + p + " 的伺服器）。確認手機有網路；開著 VPN 或省電模式的話也檢查一下。";
        }
        if (ex instanceof SocketTimeoutException
                || (ex instanceof InterruptedIOException && "timeout".equals(ex.getMessage()))) {
            return p + " 太久沒有回應（逾時）。網路慢或服務忙，再試一次。";
        }
        if (ex instanceof ConnectException || ex instanceof NoRouteToHostException) {
            return "連不上 " + p + " 的伺服器。確認網路後再試。";
        }
        if (ex instanceof SSLException) {
            return "跟 " + p + " 的安全連線失敗。連的是要先登入的公共 Wi-Fi 的話，先在瀏覽器完成登入再試。";
        }
        return "網路錯誤：" + ex.getMessage();
    }

    private static String tag(int code, Info e) {
        String s = e.status.isEmpty() ? (e.type.isEmpty() ? e.code : e.type) : e.status;
        return "〔" + code + (s.isEmpty() ? "" : " " + s) + "〕";
    }

    static String shorten(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** 從各家錯誤 JSON 抽出 status／type／code／message；不是 JSON 就全空。 */
    static final class Info {
        String status = "";   // Google：RESOURCE_EXHAUSTED、PERMISSION_DENIED…
        String type = "";     // Anthropic／OpenAI：billing_error、rate_limit_error…
        String code = "";     // OpenAI：insufficient_quota、invalid_api_key…（Google 的數字 code 不收）
        String message = "";

        static Info parse(String body) {
            Info info = new Info();
            if (body == null) return info;
            String b = body.trim();
            try {
                JSONObject root;
                if (b.startsWith("[")) {
                    JSONArray arr = new JSONArray(b);
                    root = arr.length() > 0 ? arr.optJSONObject(0) : null;
                } else if (b.startsWith("{")) {
                    root = new JSONObject(b);
                } else {
                    return info;
                }
                if (root == null) return info;
                Object err = root.opt("error");
                if (err instanceof JSONObject) {
                    JSONObject o = (JSONObject) err;
                    info.status = o.optString("status", "");
                    info.type = o.optString("type", "");
                    Object c = o.opt("code");
                    if (c instanceof String) info.code = (String) c;
                    info.message = o.optString("message", "");
                } else if (err instanceof String) {
                    info.message = (String) err;
                }
            } catch (Exception ignored) {
                // 不是 JSON：交給呼叫端用原文
            }
            return info;
        }
    }
}
