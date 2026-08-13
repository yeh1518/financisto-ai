package tw.tib.financisto.ai;

import android.content.Context;

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

    /**
     * ⚠️ 這份清單是刻意手寫的、不是從 {@code Placeholder} enum 生成——引擎支援的佔位符
     * 不等於「該給這個模型用的」。特別是 {@code {{k}}}（分類 id）：它是給「自己產生訊息、
     * 讀得到帳本」的來源用的（見 docs/Telegram通知記帳-定稿.md），銀行通知裡不可能出現
     * 分類 id，寫進 prompt 只會讓模型硬掰一個數字進去。**新增引擎佔位符時不要順手加到這裡。**
     */
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
            + "- {{e}}／{{t}}／{{c}}／{{r}}／{{x}} 這幾個**後面一定要接固定文字當結束標記**，\n"
            + "  不可以直接接 {{*}}，也不可以放在樣板最後——那樣只會抓到一個字元。\n"
            + "  例：商家後面接的是換行與「授權碼：」，就寫 商店名稱：{{e}}\\n授權碼：{{*}}。\n"
            + "  找不到合適的結束標記，就整段用 {{*}} 略過、不要用這些佔位符。\n"
            + "- 固定文字要**逐字照抄樣本**，包括全形空白（例如「卡　　號」中間是兩個全形空格）、\n"
            + "  換行與只有空白的空行。抄歪一個字整條樣板就比不中。\n"
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

    private final Context context;
    private final AiPreferences prefs;
    private final EntityContextBuilder ctx;
    private final OkHttpClient client;

    /** @param context 只用來寫 {@link AiLog}（每次嘗試都留痕跡）；null＝不記錄。 */
    public TemplateGenerator(Context context, AiPreferences prefs, EntityContextBuilder ctx) {
        this.context = context;
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
        GeneratedTemplate last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            GeneratedTemplate t;
            try {
                t = callModel(title, body, retryHint);
            } catch (GenerateException e) {
                // 呼叫失敗（網路／HTTP／回應格式）也要留紀錄，否則事後分不出
                // 「模型寫不好」與「根本沒問到模型」
                log(body, attempt, null, null, e.getMessage());
                throw e;
            }
            last = t;
            // 先把抄歪的空白修回原文的樣子，再驗證——空白是模型最常抄錯、也是程式最容易
            // 修對的東西，為了它多耗一次 API 重試不划算
            String raw = t.template;
            t.template = repairWhitespace(t.template, body);
            String problem = validate(t, body);
            log(body, attempt, t.template
                            + (t.template.equals(raw) ? "" : "\n（空白已依原文修正，模型原本產出：" + raw + "）"),
                    describe(t), problem);
            if (problem == null) return t;
            retryHint = problem;
        }
        // 訊息帶上最後那條樣板：對話框是使用者唯一看得到的東西，只講「驗證失敗」
        // 沒辦法判斷是模型抄歪了哪一段
        throw new GenerateException("產出的樣板驗證失敗：" + retryHint
                + "\n\n最後產出的樣板：\n" + (last == null ? "(無)" : last.template));
    }

    /** 產樣板規則的指紋，寫進紀錄的 pv 欄（同 {@link BookkeepingParser#PROMPT_VERSION} 的用意）。 */
    static final String PROMPT_VERSION = AiLog.fingerprint(SYSTEM_PROMPT);

    private void log(String body, int attempt, String template, String fields, String problem) {
        if (context != null) {
            AiLog.recordTemplate(context, body, attempt, template, fields, problem,
                    prefs.getModel(), PROMPT_VERSION);
        }
    }

    /** 樣板以外的欄位摘要，寫進 AiLog——帳戶對不對得上是這個功能最常出錯的地方。 */
    private static String describe(GeneratedTemplate t) {
        return "account_id=" + t.accountId + " category_id=" + t.categoryId
                + " is_income=" + t.isIncome + " sample_amount=" + t.sampleAmount
                + " confidence=" + t.confidence;
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
        // 1.5 非貪婪捕捉的退化：{{e}} 這類是 (\S+?)，後面若直接接 {{*}}（.*?）或就是樣板
        // 結尾，regex 求最短匹配 → 只抓得到一個字元（Nintendo 抓成 N）。比對照樣「成功」，
        // 所以前一關擋不住，得單獨檢查。2026-08-09 實地踩到。
        String degenerate = findDegenerateCapture(t.template);
        if (degenerate != null) {
            return "佔位符 " + degenerate + " 後面直接接 {{*}} 或就是樣板結尾，這樣只會抓到一個字元。"
                    + "請在它後面補上樣本中緊接著的固定文字當結束標記（例如換行後的下一個欄位名）；"
                    + "找不到合適的結束標記就不要用這個佔位符。";
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

    /** 非貪婪捕捉且需要「後面的固定文字」當結束標記的佔位符。{{u}} 是貪婪的，不在此列。 */
    private static final String[] NEEDS_ANCHOR = {"{{c}}", "{{e}}", "{{r}}", "{{t}}", "{{x}}"};

    /** @return 退化的那個佔位符，沒有就 null。 */
    static String findDegenerateCapture(String template) {
        for (String ph : NEEDS_ANCHOR) {
            int i = template.indexOf(ph);
            if (i < 0) continue;
            String rest = template.substring(i + ph.length());
            if (rest.isEmpty() || rest.startsWith("{{*}}")) return ph;
        }
        return null;
    }

    /** 切開樣板的佔位符與固定文字，兩者都保留。 */
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{\\{[a-z*]\\}\\}");

    /**
     * 把模型抄歪的空白修回原文的樣子。
     *
     * 模型很擅長挑出「哪裡是變動的」，很不擅長逐字複製——特別是中文之間的半形空格與全形空白，
     * 它會順手正規化掉。少一個空格整條樣板就比不中，因為 {{e}} 這類是 (\S+?)，吃不下空白。
     * 實地兩次都死在這裡（2026-08-09 的全形「卡　　號」、2026-08-11 的「在 商家 刷卡。」）。
     *
     * 作法：把樣板切成「固定文字」與「佔位符」，每段固定文字拿去原文裡用**忽略空白**的方式
     * 定位，找到就換成原文那一段的實際字元（連同緊鄰的空白一起吃進來，讓空白落在固定文字裡
     * 而不是佔位符的地盤）。定位不到就整段放棄、回傳原樣板，交給既有的驗證去擋。
     *
     * 修完仍要跑完整驗證：這裡放寬只是為了「找到位置」，對不對由 {@link #validate} 說了算。
     */
    static String repairWhitespace(String template, String body) {
        if (template == null || body == null) return template;
        java.util.regex.Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        int last = 0;          // 樣板上的游標
        int cursor = 0;        // 原文上的游標：固定文字必須依序出現
        while (true) {
            boolean found = m.find();
            String literal = template.substring(last, found ? m.start() : template.length());
            if (!literal.isEmpty()) {
                int[] span = locate(literal, body, cursor);
                if (span == null) return template;      // 有一段對不上就別修了，避免越修越糟
                out.append(body, span[0], span[1]);
                cursor = span[1];
            }
            if (!found) break;
            out.append(m.group());
            last = m.end();
        }
        return out.toString();
    }

    /**
     * 在 body 的 from 之後找這段固定文字（忽略字元之間的空白），回傳 {起,迄}——
     * 並把緊鄰前後的空白一起圈進來。空白留在固定文字裡，佔位符才不必去吃它。
     */
    /**
     * 空白的字元類。**不能只寫 `\s`**：Java 的 `\s` 是 [ \t\n\x0B\f\r]，不含全形空白 U+3000
     * 也不含 NBSP——而「卡　　號」這種全形空白正是模型最愛抄歪、也最需要修的東西。
     */
    private static final String SPACES = "[\\s\\u00A0\\u3000]*";

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || c == ' ' || c == '　';
    }

    private static int[] locate(String literal, String body, int from) {
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (isSpace(c)) continue;
            if (p.length() > 0) p.append(SPACES);
            p.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        if (p.length() == 0) return new int[]{from, from};   // 整段都是空白
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(p.toString()).matcher(body);
        if (!m.find(from)) return null;
        int s = m.start(), e = m.end();
        while (s > from && isSpace(body.charAt(s - 1))) s--;
        while (e < body.length() && isSpace(body.charAt(e))) e++;
        return new int[]{s, e};
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
            throw new GenerateException(BookkeepingParser.httpErrorMessage(code, responseBody));
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
