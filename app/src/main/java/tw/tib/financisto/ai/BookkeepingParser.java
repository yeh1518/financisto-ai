package tw.tib.financisto.ai;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 呼叫 OpenAI-相容 chat/completions（Structured Outputs strict schema），
 * 把一句話解析成 {@link ParsedTransaction}。所有 id 一律經清單驗證，
 * 模型永不無中生有 id（定稿 1，2026-07-14）。
 *
 * 同步方法，須在背景執行緒呼叫。
 */
public class BookkeepingParser {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 定稿 1 的 strict JSON schema。 */
    private static final String RESPONSE_SCHEMA =
            "{"
            + "\"name\":\"bookkeeping_extraction\","
            + "\"strict\":true,"
            + "\"schema\":{"
            +   "\"type\":\"object\",\"additionalProperties\":false,"
            +   "\"required\":[\"transaction_type\",\"amount\",\"account\",\"to_account\",\"category\",\"project\",\"note\",\"date\",\"time\",\"splits\"],"
            +   "\"properties\":{"
            +     "\"date\":{\"type\":[\"string\",\"null\"]},"
            +     "\"time\":{\"type\":[\"string\",\"null\"]},"
            +     "\"transaction_type\":{\"type\":[\"string\",\"null\"],"
            +       "\"enum\":[\"expense\",\"income\",\"transfer\",\"balance\",null]},"
            +     "\"amount\":{\"type\":[\"number\",\"null\"]},"
            +     "\"account\":{\"$ref\":\"#/$defs/pick\"},"
            +     "\"to_account\":{\"$ref\":\"#/$defs/pick\"},"
            +     "\"category\":{\"$ref\":\"#/$defs/pick\"},"
            +     "\"project\":{\"$ref\":\"#/$defs/pick\"},"
            +     "\"note\":{\"type\":[\"string\",\"null\"]},"
            +     "\"splits\":{\"type\":\"array\",\"items\":{"
            +       "\"type\":\"object\",\"additionalProperties\":false,"
            +       "\"required\":[\"category\",\"amount\",\"note\"],"
            +       "\"properties\":{"
            +         "\"category\":{\"$ref\":\"#/$defs/pick\"},"
            +         "\"amount\":{\"type\":[\"number\",\"null\"]},"
            +         "\"note\":{\"type\":[\"string\",\"null\"]}"
            +       "}"
            +     "}}"
            +   "},"
            +   "\"$defs\":{"
            +     "\"pick\":{\"type\":\"object\",\"additionalProperties\":false,"
            +       "\"required\":[\"id\",\"confidence\",\"alternatives\"],"
            +       "\"properties\":{"
            +         "\"id\":{\"type\":[\"integer\",\"null\"]},"
            +         "\"confidence\":{\"type\":\"number\"},"
            +         "\"alternatives\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}"
            +       "}"
            +     "}"
            +   "}"
            + "}"
            + "}";

    private static final String SYSTEM_PROMPT =
            "你是記帳解析器。從使用者一句話中解析記帳欄位，只回 JSON。\n"
            + "規則：\n"
            + "- id 只能從下方清單挑；挑不到就填 null，絕不自創 id、絕不用通用常識猜。\n"
            + "- amount 回口語主單位數字（120 元→120），判斷不出填 null。\n"
            + "- confidence 是你對該欄的把握 0~1；alternatives 放次可能的 id（沒有就 []）。\n"
            + "- transaction_type 四選一：\n"
            + "  * expense＝花錢。**這是預設值，拿不準一律填 expense。**\n"
            + "  * income＝進帳，**只在句中有明確的進帳訊號時才用**：薪水/獎金/退款/退錢/收到/\n"
            + "    入帳/賣掉/中獎/紅包/報帳下來 等。\n"
            + "    ⚠️ **沒有「買」「花」「付」這類動詞不代表是收入**。記帳的話絕大多數是花錢，\n"
            + "    只講「帳戶＋東西＋金額」也是花錢。\n"
            + "    例：「全家60」→ expense。「小孩學費3000」→ expense。「街口500」→ expense。\n"
            + "    反例：「薪水入帳五萬」→ income。「退款300」→ income。\n"
            + "  * transfer＝錢在自己兩個帳戶之間搬動。講法「A轉B金額」或「A轉到B金額」。\n"
            + "    account=轉出帳戶、to_account=轉入帳戶。例：「郵局轉中信5000」→ account=郵局, to_account=中信, amount=5000。\n"
            + "    句尾若有動作詞（如「儲值」）放進 note。例：「悠遊卡轉到街口500儲值」→ note=\"儲值\"。\n"
            + "  * balance＝在講帳戶「現在的結果餘額」，不是一筆變動。觸發詞：剩下 / 餘額 / 現在有。\n"
            + "    例：「中信剩下300」→ transaction_type=balance, account=中信, amount=300。\n"
            + "    ⚠️ balance 的 amount 是「新的餘額」不是變動金額。\n"
            + "    對比：「中信花了300」是變動→expense；「中信剩下300」是結果→balance。\n"
            + "- to_account 只有 transfer 才填，其他型別一律 null。\n"
            + "- 帳戶的 hint 欄＝該帳戶的口語別名／辨識提示。使用者的話是語音轉來的，人名這類專有\n"
            + "  名詞常被轉成同音錯字（例：「宥廷」→「有停」）。比對帳戶時把 name 與 hint 都納入，\n"
            + "  且**以讀音相近為準**：聽起來對得上就算對上，字面不同不是理由。\n"
            + "- 帳戶**優先選名稱完全相符**的那個。清單裡常有同名母帳戶與帶後綴的變體（如「富邦信用卡」\n"
            + "  與「富邦信用卡-老婆」）：使用者只講「富邦信用卡」就選那個**乾淨無後綴**的，帶後綴的變體\n"
            + "  放 alternatives；只有使用者明講後綴（講到「老婆」等）才選變體。不要自作主張挑更specific 的。\n"
            + "- balance / transfer 的 category：使用者**講出這筆的用途/分類時照填**，只報結果沒講用途才 null。\n"
            + "  balance 例：「街口剩下895，飲食買炸春捲」→ category=飲食（對到分類清單）、note=買炸春捲\n"
            + "  （分類詞進了 category，note 就剝掉它，只留品項描述）；「中信剩下300」沒講用途 → category=null。\n"
            + "  transfer 例：「台新轉旅遊儲蓄金3000住宿」→ category=住宿（轉入虛擬額度帳戶的轉帳會在\n"
            + "  統計中視為支出，分類有意義）。沒講就 null。\n"
            + "- 分類：使用者通常只講最底層或父層的「名稱」。以使用者說的詞直接對到 name 相同的節點"
            + "（葉或父皆可選）。名稱重複時用 path/語境選最合理的、另一個放 alternatives。"
            + "對不上任何名稱才用語義推斷。\n"
            + "  **具體品名要往上歸到它所屬的分類**，不要因為清單裡沒有那個品名就填 null：\n"
            + "  例——蘋果／香蕉／櫻桃／蓮霧／芭樂 等都屬「水果」；牛肉／豬肉／雞蛋 等屬對應的食材/肉類分類。\n"
            + "  只要清單裡有語義涵蓋它的分類（如「水果」），就選那個，別漏挑。品名本身另外進 note。\n"
            + "- note：把使用者這句話裡，**除了已對應到欄位（帳戶/分類/金額/日期/專案）的部分之外、\n"
            + "  剩下有意義的文字**當備註。原則不是逐字照抄，而是「抓重點」：\n"
            + "  * **去掉連接詞、語氣詞與口語贅字**——如 因此/然後/所以/就是/結果/反正/原因是/備註/\n"
            + "    幫我記／句尾的 啦/喔/啊/耶/吧/囉 之類；語音辨識殘留的重複或碎字也一併清掉。\n"
            + "  * 剝完若沒有實質內容剩下就 null（例：「中信500因此」→ note=null）。\n"
            + "  * 保留的是**有意義的品項或描述**，即使它跟你選的分類語義重複也要寫。\n"
            + "  * 例：「老婆的街口剩下1712元，原因是買水餃」→ 去掉「原因是」→ note=\"買水餃\"\n"
            + "    （清單沒有這個分類，是有意義的描述，就算分類選成「食材」也要寫）。\n"
            + "  * 反例：使用者只講「食材」而清單裡就有「食材」這個分類 → 那是在指定分類，note=null。\n"
            + "  * 判準：這段文字有沒有對到清單裡的名稱？沒有、且有意義 → 寫；是連接詞/語氣詞/贅字 → 丟。\n"
            + "- project（專案）：**少用欄位，只在使用者明確講到、且對得上專案清單裡的 name 時才填，否則一律 null**。\n"
            + "  判準同 note＝有沒有對到清單裡的專案名稱；不要因為語境「像」某專案就自己塞，也不要用常識推斷。\n"
            + "  例：清單有專案「日本旅遊」→「日本旅遊的午餐300」project=日本旅遊；「午餐300」project=null。\n"
            + "- splits（分割）：**一筆付款要拆成多個分類**時才用，把每一份放進 splits 陣列。\n"
            + "  觸發＝使用者在同一筆裡列出多個「分類＋金額」，如「好市多1500，食物1000、日用品500」、\n"
            + "  「這筆5000，房租3000、水電2000」。每份填 category（對到分類清單）、amount（該份金額，主單位）、\n"
            + "  note（該份的品名/描述，沒有就 null）。\n"
            + "  有 splits 時：頂層 category 填 null（父交易走分割）、頂層 amount 填總額或 null 皆可（app 以各份加總為準）。\n"
            + "  **一般只有單一分類的記帳，splits 一律空陣列 []，不要硬拆**。transfer / balance 不使用 splits（splits=[]）。\n"
            + "  例：「全家60」→ splits=[]。「好市多刷1500其中食物1000日用品500」→ splits=[{食物,1000},{日用品,500}]。\n"
            + "- date / time：**只在使用者明確講到時才填，沒講一律 null**（沒講＝用記帳當下的時間，不要自己猜）。\n"
            + "  * date 格式 `YYYY-MM-DD`。相對日期要依【現在時間】換算：今天 / 昨天 / 前天 / 上週三 / 上個月5號 /\n"
            + "    7月5日 / 這禮拜一 都算「明確講到日期」。\n"
            + "  * time 格式 `HH:MM`（24 小時制）。「早上八點」→08:00、「下午三點半」→15:30、「晚上七點」→19:00、\n"
            + "    「中午」→12:00。只講「早上／晚上」這種沒有具體鐘點的**不算**講到時間，time 填 null。\n"
            + "  * 只講日期沒講時間＝time 填 null（呼叫端會用當下時刻）；兩者都沒講就兩個都 null。\n"
            + "  * 例：「昨天買菜300」→ date=昨天的日期, time=null。「早上八點半買早餐50」→ date=null, time=08:30。\n"
            + "    「7月5號下午三點繳學費」→ date=2026-07-05, time=15:00。「買菜300」→ date=null, time=null。\n";

    private final AiPreferences prefs;
    private final EntityContextBuilder ctx;
    private final OkHttpClient client;
    /** 只為了寫 AiLog；null＝不記錄。 */
    private final Context logContext;

    public BookkeepingParser(AiPreferences prefs, EntityContextBuilder ctx) {
        this(prefs, ctx, null);
    }

    public BookkeepingParser(AiPreferences prefs, EntityContextBuilder ctx, Context logContext) {
        this.prefs = prefs;
        this.ctx = ctx;
        this.logContext = logContext;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build();
    }

    public static class ParseException extends Exception {
        public ParseException(String message) { super(message); }
        public ParseException(String message, Throwable cause) { super(message, cause); }
    }

    public ParsedTransaction parse(String userText) throws ParseException {
        return parse(userText, false, null);
    }

    public ParsedTransaction parse(String userText, boolean supplement) throws ParseException {
        return parse(userText, supplement, null);
    }

    /**
     * @param supplement       true＝對「已開啟的交易表單」做補充/修正：只回這句話講到的欄位，
     *                         其餘一律 null，由呼叫端只套用非 null 的槽（沒講到的欄位不動）。
     * @param formStateContext 補充模式下，目前表單「已填內容」的序列化描述（型別/帳戶/金額/分類/
     *                         各份分割…）。給模型當 context，讓它知道這筆現在長什麼樣，才能分辨
     *                         「再補一份分割」還是「改某個欄位」。null＝不帶（退回無 context 的補充）。
     */
    public ParsedTransaction parse(String userText, boolean supplement, String formStateContext) throws ParseException {
        try {
            return parseInternal(userText, supplement, formStateContext);
        } catch (ParseException e) {
            // 失敗的那次最需要留底：事後回想不起來當時講了什麼就無從改起
            record(userText, supplement, formStateContext, null, e.getMessage());
            throw e;
        }
    }

    /** 一次到位（音檔直解）的結果：表單欄位＋模型一併回報的逐字轉寫。 */
    public static class AudioParseResult {
        public final ParsedTransaction transaction;
        public final String transcript;
        AudioParseResult(ParsedTransaction transaction, String transcript) {
            this.transaction = transaction;
            this.transcript = transcript;
        }
    }

    /** 音檔直解的追加規則：要求先聽寫再解析，並把逐字稿回報在 transcript 欄。 */
    private static final String AUDIO_RULE =
            "\n【語音輸入】使用者的輸入是一段語音。先逐字聽寫，再依上述規則解析。\n"
            + "transcript 欄填完整逐字轉寫（繁體中文、台灣用語；剝掉語助詞但不改內容、不摘要）。\n"
            + "解析一律以你聽寫出的內容為準；聽不清楚的字依讀音給最可能的字。\n";

    /**
     * 一次到位：WAV 音檔直接進解析 prompt（chat/completions 的 input_audio + structured
     * outputs），一趟來回同時拿到轉寫與表單欄位。Gemini 走其 OpenAI 相容層、OpenAI 走
     * audio-preview 模型，請求形狀相同；端點/key/模型由 prefs.getDirect* 決定。
     */
    public AudioParseResult parseAudio(File wavFile, boolean supplement, String formStateContext)
            throws ParseException {
        try {
            return parseAudioInternal(wavFile, supplement, formStateContext);
        } catch (ParseException e) {
            record("(語音直解失敗，無轉寫)", supplement, formStateContext, null, e.getMessage());
            throw e;
        }
    }

    private AudioParseResult parseAudioInternal(File wavFile, boolean supplement, String formStateContext)
            throws ParseException {
        String key = prefs.getDirectParseKey();
        if (key == null || key.isEmpty()) {
            throw new ParseException("尚未設定該服務的 API key");
        }

        String requestJson;
        try {
            requestJson = buildAudioRequestBody(wavFile, supplement, formStateContext);
        } catch (JSONException e) {
            throw new ParseException("組請求失敗", e);
        } catch (IOException e) {
            throw new ParseException("讀錄音檔失敗：" + e.getMessage(), e);
        }

        String url = AiPreferences.llmBaseUrl(prefs.getDirectProvider()) + "/chat/completions";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + key)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(JSON, requestJson))
                .build();

        String responseBody;
        int code;
        try (Response response = client.newCall(request).execute()) {
            code = response.code();
            responseBody = response.body() != null ? response.body().string() : "";
        } catch (IOException e) {
            throw new ParseException("網路錯誤：" + e.getMessage(), e);
        }

        if (code < 200 || code >= 300) {
            throw new ParseException("API 回傳 " + code + "：" + shorten(responseBody));
        }

        try {
            String content = extractContent(responseBody);
            JSONObject data = new JSONObject(content);
            String transcript = data.isNull("transcript") ? "" : data.optString("transcript", "");
            record(transcript.isEmpty() ? "(語音直解，無轉寫)" : transcript,
                    supplement, formStateContext, content, null);
            return new AudioParseResult(toParsedTransaction(data), transcript);
        } catch (JSONException e) {
            throw new ParseException("解析回應失敗：" + e.getMessage(), e);
        }
    }

    private String buildAudioRequestBody(File wavFile, boolean supplement, String formStateContext)
            throws JSONException, IOException {
        String prompt = SYSTEM_PROMPT + AUDIO_RULE + (supplement ? SUPPLEMENT_RULE : "")
                + "\n" + nowContext() + "\n" + ctx.promptContext;
        if (supplement && formStateContext != null && !formStateContext.isEmpty()) {
            prompt += "\n" + formStateContext;
        }
        JSONObject system = new JSONObject().put("role", "system").put("content", prompt);

        JSONObject audio = new JSONObject()
                .put("data", readBase64(wavFile))
                .put("format", "wav");
        JSONArray userContent = new JSONArray()
                .put(new JSONObject().put("type", "input_audio").put("input_audio", audio));
        JSONObject user = new JSONObject().put("role", "user").put("content", userContent);
        JSONArray messages = new JSONArray().put(system).put(user);

        // schema 加上 transcript 欄（strict schema：required 也要補）
        JSONObject jsonSchema = new JSONObject(RESPONSE_SCHEMA);
        JSONObject schema = jsonSchema.getJSONObject("schema");
        schema.getJSONArray("required").put("transcript");
        schema.getJSONObject("properties")
                .put("transcript", new JSONObject().put("type", "string"));
        JSONObject responseFormat = new JSONObject()
                .put("type", "json_schema")
                .put("json_schema", jsonSchema);

        return new JSONObject()
                .put("model", prefs.getDirectParseModel())
                .put("temperature", 0)
                .put("messages", messages)
                .put("response_format", responseFormat)
                .toString();
    }

    private static String readBase64(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) throw new IOException("檔案讀取中斷");
                off += n;
            }
        }
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private ParsedTransaction parseInternal(String userText, boolean supplement, String formStateContext) throws ParseException {
        if (!prefs.isConfigured()) {
            throw new ParseException("尚未設定 API key");
        }
        String requestJson;
        try {
            requestJson = buildRequestBody(userText, supplement, formStateContext);
        } catch (JSONException e) {
            throw new ParseException("組請求失敗", e);
        }

        Request request = new Request.Builder()
                .url(prefs.getChatCompletionsUrl())
                .addHeader("Authorization", "Bearer " + prefs.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(JSON, requestJson))
                .build();

        String responseBody;
        int code;
        try (Response response = client.newCall(request).execute()) {
            code = response.code();
            responseBody = response.body() != null ? response.body().string() : "";
        } catch (IOException e) {
            throw new ParseException("網路錯誤：" + e.getMessage(), e);
        }

        if (code < 200 || code >= 300) {
            throw new ParseException("API 回傳 " + code + "：" + shorten(responseBody));
        }

        return parseResponse(responseBody, userText, supplement, formStateContext);
    }

    private void record(String userText, boolean supplement, String formStateContext, String rawContent, String error) {
        if (logContext != null) {
            AiLog.record(logContext, userText, supplement, formStateContext, rawContent, error, prefs.getModel());
        }
    }

    private static final String SUPPLEMENT_RULE =
            "\n【補充模式】使用者正在一張已填好的交易表單上，用這句話做補充或修正。\n"
            + "只回這句話明確講到的欄位；沒講到的一律填 null（呼叫端只會套用非 null 的欄位，\n"
            + "填 null 的欄位會維持使用者原本的內容）。例：「不對，是刷永豐」→ 只有 account 有值，\n"
            + "amount/category/project/note 全部 null。\n"
            + "transaction_type 一般填 null（維持原型別）；**但使用者若明確要改變交易型別就要回報新型別**：\n"
            + "「改成轉帳」「其實是轉帳給X」「轉到X」→transfer（並把轉入帳戶填進 to_account）；\n"
            + "「改成收入」「這是收入」→income；「改成支出」「其實是花費」→expense；\n"
            + "「剩下X」「餘額X」「現在有X」→balance（amount 填新餘額，可為信用卡的負值）。\n"
            + "沒有明確要改型別的指示就維持 null，不要自己臆測。⚠️ 此模式下「拿不準填 expense」的\n"
            + "預設規則**不適用**：部分修正（「金額改成500」「分類改餐飲」「備註加XX」）transaction_type\n"
            + "一律 null——填了 expense 會把使用者正在編的轉帳表單整個切掉。\n"
            + "若使用者要求把這筆拆成多個分類（如「拆成房租3000水電2000」），就在 splits 回報各份，其餘欄位維持 null。\n"
            + "\n下方附【目前表單已填內容】＝這筆交易現在的樣子。據此判斷這句話的意圖：\n"
            + "(a) 在現有交易上「再補一個品項＋金額」（目前是單一分類「美妝316」，這句說「還有270是咖啡」\n"
            + "    或「270咖啡」）＝要變成分割：把目前表單的金額與分類當成第一份、連同新的一起放進 splits\n"
            + "    （→[{美妝,316},{咖啡,270}]），其餘欄位一律 null。\n"
            + "(b) 目前已是分割、這句再加一份＝回報「含現有各份＋新份」的完整 splits。\n"
            + "(c) 只是修正某個既有欄位（金額/分類/帳戶/備註/型別…）＝只回被改的那一欄，splits 留空、其餘 null。\n"
            + "判準：這句話在「補上另一個品項＋金額」就走分割(a/b)；在「改掉原本某個值」就走修正(c)。\n"
            + "沒有【目前表單已填內容】區塊時，比照一般補充模式處理。\n";

    /** 相對日期（昨天／上週三）要換算就得先知道「現在」——每次呼叫都現算，不能寫死。 */
    private static String nowContext() {
        Calendar c = Calendar.getInstance();
        String[] week = {"日", "一", "二", "三", "四", "五", "六"};
        return String.format(Locale.US, "【現在時間】%1$tY-%1$tm-%1$td %1$tH:%1$tM（星期%2$s）\n",
                c, week[c.get(Calendar.DAY_OF_WEEK) - 1]);
    }

    private String buildRequestBody(String userText, boolean supplement, String formStateContext) throws JSONException {
        String prompt = SYSTEM_PROMPT + (supplement ? SUPPLEMENT_RULE : "")
                + "\n" + nowContext() + "\n" + ctx.promptContext;
        if (supplement && formStateContext != null && !formStateContext.isEmpty()) {
            prompt += "\n" + formStateContext;
        }
        JSONObject system = new JSONObject().put("role", "system")
                .put("content", prompt);
        JSONObject user = new JSONObject().put("role", "user").put("content", userText);
        JSONArray messages = new JSONArray().put(system).put(user);

        JSONObject responseFormat = new JSONObject()
                .put("type", "json_schema")
                .put("json_schema", new JSONObject(RESPONSE_SCHEMA));

        return new JSONObject()
                .put("model", prefs.getModel())
                .put("temperature", 0)
                .put("messages", messages)
                .put("response_format", responseFormat)
                .toString();
    }

    private ParsedTransaction parseResponse(String responseBody, String userText, boolean supplement,
                                            String formStateContext) throws ParseException {
        try {
            String content = extractContent(responseBody);
            record(userText, supplement, formStateContext, content, null);
            JSONObject data = new JSONObject(content);
            return toParsedTransaction(data);
        } catch (JSONException e) {
            throw new ParseException("解析回應失敗：" + e.getMessage(), e);
        }
    }

    /** chat/completions 回應 → message.content（choices/refusal/空內容檢查共用）。 */
    private static String extractContent(String responseBody) throws ParseException, JSONException {
        JSONObject root = new JSONObject(responseBody);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new ParseException("回應無 choices：" + shorten(responseBody));
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (!message.isNull("refusal")) {
            throw new ParseException("模型拒絕：" + message.optString("refusal"));
        }
        String content = message.optString("content", "");
        if (content.isEmpty()) {
            throw new ParseException("回應內容為空");
        }
        return content;
    }

    private ParsedTransaction toParsedTransaction(JSONObject data) {
        ParsedTransaction t = new ParsedTransaction();
        t.transactionType = data.isNull("transaction_type") ? null : data.optString("transaction_type", null);
        t.amount = data.isNull("amount") ? null : data.optDouble("amount");
        t.note = data.isNull("note") ? null : emptyToNull(data.optString("note", null));
        t.date = data.isNull("date") ? null : emptyToNull(data.optString("date", null));
        t.time = data.isNull("time") ? null : emptyToNull(data.optString("time", null));

        t.account = readPick(data.optJSONObject("account"), ctx.validAccountIds);
        t.toAccount = readPick(data.optJSONObject("to_account"), ctx.validAccountIds);
        t.category = readPick(data.optJSONObject("category"), ctx.validCategoryIds);
        t.project = readPick(data.optJSONObject("project"), ctx.validProjectIds);
        t.splits = readSplits(data.optJSONArray("splits"));
        return t;
    }

    /** 讀 splits 陣列：每份的 category 一律經清單驗證，金額不明的份直接略過（湊不成有效分割）。 */
    private List<ParsedTransaction.Split> readSplits(JSONArray arr) {
        List<ParsedTransaction.Split> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            ParsedTransaction.Split s = new ParsedTransaction.Split();
            s.category = readPick(o.optJSONObject("category"), ctx.validCategoryIds);
            s.amount = o.isNull("amount") ? null : o.optDouble("amount");
            s.note = o.isNull("note") ? null : emptyToNull(o.optString("note", null));
            out.add(s);
        }
        return out;
    }

    /** 讀一個 pick 並拿回清單驗證：id 不在清單內即視為 null。 */
    private ParsedTransaction.Pick readPick(JSONObject o, Set<Long> validIds) {
        ParsedTransaction.Pick pick = new ParsedTransaction.Pick();
        if (o == null) return pick;
        if (!o.isNull("id")) {
            long id = o.optLong("id", 0);
            if (validIds.contains(id)) {
                pick.id = id;
            }
        }
        pick.confidence = o.optDouble("confidence", 0);
        JSONArray alts = o.optJSONArray("alternatives");
        if (alts != null) {
            List<Long> valid = new ArrayList<>();
            for (int i = 0; i < alts.length(); i++) {
                long a = alts.optLong(i, 0);
                if (validIds.contains(a) && (pick.id == null || a != pick.id)) {
                    valid.add(a);
                }
            }
            pick.alternatives = new long[valid.size()];
            for (int i = 0; i < valid.size(); i++) pick.alternatives[i] = valid.get(i);
        }
        return pick;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s;
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
