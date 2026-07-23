package tw.tib.financisto.ai;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import tw.tib.financisto.service.SmsTransactionProcessor;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 從一則通知樣本產生原生「通知/簡訊樣板」（定稿 2026-07-23）。
 *
 * 定位：AI 只做「產樣板」這一次性工作，之後執行期回歸原生樣板機制——零 API 費、
 * 離線、隱私（LLM 只在生成當下看到這一則樣本）。
 *
 * 品質靠確定性驗證、不靠模型自覺：
 * 1. 原生引擎回測——產出的樣板用 {@link SmsTransactionProcessor#findTemplateMatches}
 *    對原樣本跑，抽出金額必須等於模型自己回報的樣本金額；
 * 2. 金額變異測試——把樣本金額換成不同位數再回測，仍須比中（防把金額寫死成固定位數）。
 * 驗證失敗帶錯誤訊息自動重試一次。
 *
 * 同步方法，須在背景執行緒呼叫。
 */
public class TemplateGenerator {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 變異測試用的替換金額：位數、千分位、小數都跟常見樣本不同。 */
    private static final String MUTATED_AMOUNT = "1,234.00";

    private static final String RESPONSE_SCHEMA =
            "{"
            + "\"name\":\"notification_template\","
            + "\"strict\":true,"
            + "\"schema\":{"
            +   "\"type\":\"object\",\"additionalProperties\":false,"
            +   "\"required\":[\"title_key\",\"template\",\"account_id\",\"category_id\",\"is_income\",\"sample_amount\",\"confidence\"],"
            +   "\"properties\":{"
            +     "\"title_key\":{\"type\":\"string\"},"
            +     "\"template\":{\"type\":\"string\"},"
            +     "\"account_id\":{\"type\":[\"integer\",\"null\"]},"
            +     "\"category_id\":{\"type\":[\"integer\",\"null\"]},"
            +     "\"is_income\":{\"type\":\"boolean\"},"
            +     "\"sample_amount\":{\"type\":\"string\"},"
            +     "\"confidence\":{\"type\":\"number\"}"
            +   "}"
            + "}"
            + "}";

    private static final String SYSTEM_PROMPT =
            "你是通知樣板產生器。給你一則手機通知（標題與內文），請產出一條能讓記帳 app 自動\n"
            + "解析同類通知的樣板。只回 JSON。\n"
            + "\n"
            + "樣板規則：\n"
            + "- 樣板是通知內文的「骨架」：固定不變的文字照抄，會變動的部分換成佔位符。\n"
            + "- 佔位符（一個樣板中每種最多一個）：\n"
            + "  {{p}} 金額（**必須有**，樣板沒有 {{p}} 就無效）\n"
            + "  {{a}} 卡號/帳號末四碼（樣本中有末四碼時**務必**用它取代那四位數字，\n"
            + "        app 會拿它自動對帳戶，一條樣板通吃多張卡）\n"
            + "  {{b}} 餘額（樣本有餘額欄時用）\n"
            + "  {{e}} 商家/收款人名稱\n"
            + "  {{t}} 要抓進備註的變動文字\n"
            + "  {{*}} 任意略過的變動文字（日期、時間、序號等不需要的部分）\n"
            + "- 內文開頭是「標題 + 空格」的前綴（app 比對時會這樣組），樣板也要涵蓋它：\n"
            + "  標題固定就照抄，標題會變就用 {{*}} 開頭。\n"
            + "- 日期時間用 {{*}} 略過（app 用收到通知的當下時間記帳，不需要抽日期）。\n"
            + "- 金額前後的幣別符號（NT$、$、元）是固定文字，留在樣板裡，不要包進 {{p}}。\n"
            + "- 寧可多用 {{*}} 保守涵蓋會變的部分，不要把可能變動的數字/文字寫死。\n"
            + "\n"
            + "欄位規則：\n"
            + "- title_key：通知標題（app 以它查找樣板；照抄樣本標題）。\n"
            + "- template：上述樣板。\n"
            + "- account_id：從【帳戶清單】挑這類通知該記到哪個帳戶；樣本有末四碼且對得上\n"
            + "  清單中帳戶的卡號提示就挑那個；對不到或不確定填 null（讓使用者自己選）。\n"
            + "- category_id：這類消費適合的分類，從【分類清單】挑；判斷不出填 null。\n"
            + "- is_income：這類通知是入帳（薪資/退款/轉入）為 true，消費/扣款為 false。\n"
            + "- sample_amount：這則樣本中的金額原文（含千分位逗號，不含幣別符號），\n"
            + "  app 會用它驗證樣板。\n"
            + "- confidence：你對樣板品質的把握 0~1。\n";

    private final AiPreferences prefs;
    private final EntityContextBuilder ctx;
    private final OkHttpClient client;

    public TemplateGenerator(AiPreferences prefs, EntityContextBuilder ctx) {
        this.prefs = prefs;
        this.ctx = ctx;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build();
    }

    public static class GenerateException extends Exception {
        public GenerateException(String message) { super(message); }
        public GenerateException(String message, Throwable cause) { super(message, cause); }
    }

    /** 生成結果：預填進樣板編輯器用。 */
    public static class GeneratedTemplate {
        public String titleKey;
        public String template;
        public Long accountId;     // null＝讓使用者選
        public Long categoryId;
        public boolean isIncome;
        public double confidence;
        /** 模型回報的樣本金額原文（驗證用，不進編輯器）。 */
        public String sampleAmount;
    }

    /**
     * 從通知樣本產樣板。驗證失敗會帶錯誤訊息重試一次，仍失敗就丟 GenerateException。
     *
     * @param title 通知標題（樣板的查找鍵）
     * @param body  通知內文——**含 title 前綴**的格式（與 NotificationListener 存進
     *              日誌、樣板引擎比對用的同一格式）
     */
    public GeneratedTemplate generate(String title, String body) throws GenerateException {
        String retryHint = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            GeneratedTemplate t = callModel(title, body, retryHint);
            String problem = validate(t, body);
            if (problem == null) return t;
            retryHint = problem;
        }
        throw new GenerateException("產出的樣板驗證失敗：" + retryHint);
    }

    /**
     * 確定性驗證（static、無外部依賴，可直接單元測試）。
     * @return null＝通過；否則回「哪裡不對」的說明（餵回下一次重試）。
     */
    static String validate(GeneratedTemplate t, String body) {
        if (t.template == null || !t.template.contains("{{p}}")) {
            return "樣板缺少金額佔位符 {{p}}";
        }
        String sampleAmount = t.sampleAmount == null ? "" : t.sampleAmount;
        // 1. 原生引擎回測：對原樣本比對，抽出的金額要等於模型回報的樣本金額
        String[] match = SmsTransactionProcessor.findTemplateMatches(t.template, body);
        if (match == null) {
            return "樣板比對不中原樣本內文（注意內文開頭有「標題+空格」前綴）";
        }
        String price = match[SmsTransactionProcessor.Placeholder.PRICE.ordinal()];
        if (price == null || !sameAmount(price, sampleAmount)) {
            return "樣板從樣本抽出的金額「" + price + "」不等於樣本金額「" + sampleAmount + "」";
        }
        // 2. 金額變異測試：換一個位數/格式都不同的金額，樣板仍須吃得下（防過擬合）。
        // 只換「獨立出現」的金額——前後不能貼著數字/逗點/小數點，否則「88」會把
        // 卡號末四碼「8842」也換爛，好樣板被誤殺（單元測試實抓）。
        String mutated = body.replaceAll(
                "(?<![\\d,.])" + java.util.regex.Pattern.quote(sampleAmount) + "(?![\\d,.])",
                java.util.regex.Matcher.quoteReplacement(MUTATED_AMOUNT));
        if (!mutated.equals(body)) {   // 原文找不到金額字串就跳過這關（已由第 1 關把守）
            String[] match2 = SmsTransactionProcessor.findTemplateMatches(t.template, mutated);
            String price2 = match2 == null ? null
                    : match2[SmsTransactionProcessor.Placeholder.PRICE.ordinal()];
            if (price2 == null || !sameAmount(price2, MUTATED_AMOUNT)) {
                return "金額換成 " + MUTATED_AMOUNT + " 後樣板比不中——金額部分可能被寫死，"
                        + "請確認 {{p}} 位置且不要把位數寫死";
            }
        }
        return null;
    }

    private static boolean sameAmount(String a, String b) {
        try {
            return new BigDecimal(a.replace(",", "").trim())
                    .compareTo(new BigDecimal(b.replace(",", "").trim())) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private GeneratedTemplate callModel(String title, String body, String retryHint)
            throws GenerateException {
        String key = prefs.getApiKey();
        if (key == null || key.isEmpty()) {
            throw new GenerateException("尚未設定 API key");
        }

        String requestJson;
        try {
            String system = SYSTEM_PROMPT + "\n" + ctx.promptContext;
            String user = "【通知標題】" + title + "\n【通知內文】" + body;
            if (retryHint != null) {
                user += "\n\n【上次產出的樣板驗證失敗】" + retryHint + "\n請修正後重新產出。";
            }
            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", system))
                    .put(new JSONObject().put("role", "user").put("content", user));
            requestJson = new JSONObject()
                    .put("model", prefs.getModel())
                    .put("temperature", 0)
                    .put("messages", messages)
                    .put("response_format", new JSONObject()
                            .put("type", "json_schema")
                            .put("json_schema", new JSONObject(RESPONSE_SCHEMA)))
                    .toString();
        } catch (JSONException e) {
            throw new GenerateException("組請求失敗", e);
        }

        Request request = new Request.Builder()
                .url(prefs.getChatCompletionsUrl())
                .addHeader("Authorization", "Bearer " + key)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(JSON, requestJson))
                .build();

        String responseBody;
        int code;
        try (Response response = client.newCall(request).execute()) {
            code = response.code();
            responseBody = response.body() != null ? response.body().string() : "";
        } catch (java.io.IOException e) {
            throw new GenerateException("網路錯誤：" + e.getMessage(), e);
        }
        if (code < 200 || code >= 300) {
            throw new GenerateException("API 回傳 " + code + "：" + shorten(responseBody));
        }

        try {
            JSONObject root = new JSONObject(responseBody);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new GenerateException("回應無 choices：" + shorten(responseBody));
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            if (!message.isNull("refusal")) {
                throw new GenerateException("模型拒絕：" + message.optString("refusal"));
            }
            JSONObject data = new JSONObject(message.optString("content", ""));

            GeneratedTemplate t = new GeneratedTemplate();
            t.titleKey = data.optString("title_key", title);
            t.template = data.optString("template", "");
            t.accountId = data.isNull("account_id") ? null : data.getLong("account_id");
            t.categoryId = data.isNull("category_id") ? null : data.getLong("category_id");
            t.isIncome = data.optBoolean("is_income", false);
            t.confidence = data.optDouble("confidence", 0);
            t.sampleAmount = data.optString("sample_amount", "");

            // id 清單驗證（同解析鏈原則：模型永不無中生有 id）
            if (t.accountId != null && !ctx.validAccountIds.contains(t.accountId)) t.accountId = null;
            if (t.categoryId != null && !ctx.validCategoryIds.contains(t.categoryId)) t.categoryId = null;
            return t;
        } catch (JSONException e) {
            throw new GenerateException("解析回應失敗：" + e.getMessage(), e);
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
