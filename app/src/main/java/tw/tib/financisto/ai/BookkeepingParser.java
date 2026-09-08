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
import java.util.Map;
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
            +   "\"required\":[\"transaction_type\",\"type_change_quote\",\"amount\",\"account\",\"to_account\",\"category\",\"project\",\"note\",\"date\",\"time\",\"splits\"],"
            +   "\"properties\":{"
            +     "\"date\":{\"type\":[\"string\",\"null\"]},"
            +     "\"time\":{\"type\":[\"string\",\"null\"]},"
            +     "\"transaction_type\":{\"type\":[\"string\",\"null\"],"
            +       "\"enum\":[\"expense\",\"income\",\"transfer\",\"balance\",null]},"
            +     "\"type_change_quote\":{\"type\":[\"string\",\"null\"]},"
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
            + "    例：「全家60」→ expense。「小孩學費3000」→ expense。「電子錢包500」→ expense。\n"
            + "    反例：「薪水入帳五萬」→ income。「退款300」→ income。\n"
            + "  * transfer＝錢在自己兩個帳戶之間搬動。講法「A轉B金額」或「A轉到B金額」。\n"
            + "    account=轉出帳戶、to_account=轉入帳戶。例：「郵政轉甲銀行5000」→ account=郵政, to_account=甲銀行, amount=5000。\n"
            + "    句尾若有動作詞（如「儲值」）放進 note。例：「悠遊卡轉到電子錢包500儲值」→ note=\"儲值\"。\n"
            + "  * balance＝在講帳戶「現在的結果餘額」，不是一筆變動。觸發詞：剩下 / 餘額 / 現在有。\n"
            + "    例：「甲銀行剩下300」→ transaction_type=balance, account=甲銀行, amount=300。\n"
            + "    ⚠️ balance 的 amount 是「新的餘額」不是變動金額。\n"
            + "    對比：「甲銀行花了300」是變動→expense；「甲銀行剩下300」是結果→balance。\n"
            + "- type_change_quote＝使用者原話中，**表達「要改這筆的型別或收支方向」的那一小段**，\n"
            + "  **原文照抄**（逐字，不要改寫、不要補字）；沒有這種表達就填 null。\n"
            + "  判準是語意不是用字：「其實是」「講錯了」「不是轉帳」「花了」「收200」「別人給我的」\n"
            + "  都可以是這段話——不必出現「收入」「支出」這種書面詞。\n"
            + "  只是補金額／帳戶／分類／備註＝null。例：轉帳表單上只講「丙銀行信用卡」（純粹改帳戶）\n"
            + "  → null。非補充模式一律 null。\n"
            + "- 帳戶的 hint 欄＝該帳戶的口語別名／辨識提示。使用者的話是語音轉來的，人名這類專有\n"
            + "  名詞常被轉成同音錯字（例：「宥廷」→「有停」）。比對帳戶時把 name 與 hint 都納入，\n"
            + "  且**以讀音相近為準**：聽起來對得上就算對上，字面不同不是理由。\n"
            + "- 帳戶**優先選名稱完全相符**的那個。清單裡常有同名母帳戶與帶後綴的變體（如「乙銀行信用卡」\n"
            + "  與「乙銀行信用卡-配偶」）：使用者只講「乙銀行信用卡」就選那個**乾淨無後綴**的，帶後綴的變體\n"
            + "  放 alternatives；只有使用者**講出那個後綴詞本身**才選變體。不要自作主張挑更specific 的。\n"
            + "- ⚠️ **帳戶名本身就是一般語詞時**（如「借款」「股票」「存款」「家中現金」「代幣買賣」\n"
            + "  「公司代墊款」）**，它在清單裡就是帳戶**：任何一個詞都先拿去比對帳戶清單的 name\n"
            + "  與 hint，對不上才輪到 note。例：「借款轉甲銀行9000」→ account=借款、to_account=甲銀行。\n"
            + "- transfer 的 account（轉出帳戶）**不因為那個帳戶「不像銀行」就放棄**：清單裡每一個都是\n"
            + "  合法的轉出/轉入帳戶，包含 ASSET / OTHER 這類代墊、信封袋、借款、儲值額度帳戶。\n"
            + "  轉出帳戶對不上時要再回頭掃一次清單，別直接填 null。\n"
            + "- balance / transfer 的 category：使用者**講出這筆的用途/分類時照填**，只報結果沒講用途才 null。\n"
            + "  balance 例：「電子錢包剩下895，飲食買炸春捲」→ category=飲食（對到分類清單）、note=買炸春捲\n"
            + "  （分類詞進了 category，note 就剝掉它，只留品項描述）；「甲銀行剩下300」沒講用途 → category=null。\n"
            + "  transfer 例：「丁銀行轉旅遊基金3000住宿」→ category=住宿（轉入虛擬額度帳戶的轉帳會在\n"
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
            + "  * 剝完若沒有實質內容剩下就 null（例：「甲銀行500因此」→ note=null）。\n"
            + "  * 保留的是**有意義的品項或描述**，即使它跟你選的分類語義重複也要寫。\n"
            + "  * 例：「配偶的電子錢包剩下1712元，原因是買水餃」→ 去掉「原因是」→ note=\"買水餃\"\n"
            + "    （清單沒有這個分類，是有意義的描述，就算分類選成「食材」也要寫）。\n"
            + "  * 反例：使用者只講「食材」而清單裡就有「食材」這個分類 → 那是在指定分類，note=null。\n"
            + "  * 判準：這段文字有沒有對到清單裡的名稱？沒有、且有意義 → 寫；是連接詞/語氣詞/贅字 → 丟。\n"
            + "- project（專案）：**極少用**。只在使用者講的詞對得上專案清單的 name 時才填，其餘一律 null\n"
            + "  （不要用語境或常識推斷）。\n"
            + "- splits（分割）：**一筆付款要拆成多個分類**時才用，把每一份放進 splits 陣列。\n"
            + "  觸發＝使用者在同一筆裡列出多個「分類＋金額」，如「好市多1500，食物1000、日用品500」、\n"
            + "  「這筆5000，房租3000、水電2000」。每份填 category（對到分類清單）、amount（該份金額，主單位）、\n"
            + "  note（該份的品名/描述，沒有就 null）。\n"
            + "  有 splits 時：頂層 category 填 null（父交易走分割）、頂層 amount 填總額或 null 皆可（app 以各份加總為準）。\n"
            + "  **一般只有單一分類的記帳，splits 一律空陣列 []，不要硬拆**。transfer / balance 不使用 splits（splits=[]）。\n"
            + "  例：「全家60」→ splits=[]。「好市多刷1500其中食物1000日用品500」→ splits=[{食物,1000},{日用品,500}]。\n"
            + "- date（`YYYY-MM-DD`）/ time（`HH:MM` 24 小時制）：**只在明確講到時才填，沒講一律 null**\n"
            + "  （沒講＝用記帳當下的時間，不要自己猜）。相對日期依【現在時間】換算（昨天／上週三／7月5日）。\n"
            + "  只講「早上／晚上」沒有具體鐘點**不算**講到時間；兩者各自獨立判斷。\n";

    private final AiPreferences prefs;
    private final EntityContextBuilder ctx;
    private final OkHttpClient client;
    /** 只為了寫 AiLog；null＝不記錄。 */
    private final Context logContext;
    /** 輸入來源標籤，見 {@link #inputSource(String)}。 */
    private String inputSource;

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

    /**
     * 這段文字**怎麼來的**（typed／辨識引擎標籤／+edited），只為寫進 AiLog 的 `stt` 欄。
     * 呼叫端才知道答案——parser 拿到的永遠只是一串字。沒設就不寫那一欄。
     */
    public BookkeepingParser inputSource(String source) {
        this.inputSource = source;
        return this;
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
            + "解析一律以你聽寫出的內容為準；聽不清楚的字依讀音給最可能的字。\n"
            + "⚠️ 聽寫完要**當成文字輸入重新處理一次**：把轉寫裡的每個詞逐一拿去比對帳戶／分類清單，\n"
            + "比對完才填欄位。不要因為忙著聽寫就跳過清單比對、把對得上帳戶的詞當成描述丟進 note。\n";

    /**
     * 一次到位：WAV 音檔直接進解析 prompt（chat/completions 的 input_audio + structured
     * outputs），一趟來回同時拿到轉寫與表單欄位。Gemini 走其 OpenAI 相容層、OpenAI 走
     * audio-preview 模型，請求形狀相同；端點/key/模型由 prefs.getDirect* 決定。
     */
    public AudioParseResult parseAudio(File wavFile, boolean supplement, String formStateContext)
            throws ParseException {
        // 一次到位＝音檔直送，來源不必問呼叫端：就是 direct 那顆模型自己聽的
        if (inputSource == null) inputSource = prefs.getSttLabel();
        AudioParseResult r;
        try {
            r = parseAudioInternal(wavFile, supplement, formStateContext);
        } catch (ParseException e) {
            record("(語音直解失敗，無轉寫)", supplement, formStateContext, null, e.getMessage());
            throw e;
        }
        return retryAsTextIfIncomplete(r, supplement, formStateContext);
    }

    /**
     * 音檔直解漏了關鍵欄位時，拿它自己聽出來的轉寫**再跑一次純文字解析**，只補空槽。
     *
     * 為什麼要這一趟：同一句話，音檔直解要一邊聽寫一邊比對清單，注意力被分掉，會把對得上
     * 帳戶清單的詞當成描述丟進 note；同一段轉寫走文字解析就對得出來（2026-08-05 實測，
     * 「借款轉甲銀行9000」直解 account=null/note=借款、重送 account=26）。使用者原本就是
     * 手動「回上一頁再送一次」在補這件事，這裡只是把它自動化。
     *
     * 代價＝不完整時多一次文字呼叫（約 +1~2 秒）；happy path 一次來回不變。
     * 補救失敗（沒設 LLM key、網路斷）就沉默沿用原結果——多跑一次不該反而把整筆擋掉。
     */
    private AudioParseResult retryAsTextIfIncomplete(AudioParseResult r, boolean supplement,
                                                     String formStateContext) {
        // 補充模式本來就只回「這句講到的欄位」，大量 null 是正常的，不能當成不完整
        if (supplement) return r;
        if (r.transcript == null || r.transcript.trim().isEmpty()) return r;
        if (!isIncomplete(r.transaction)) return r;

        String base = inputSource;
        try {
            inputSource = (base == null ? "" : base) + "+retry";
            ParsedTransaction second = parseInternal(r.transcript, false, formStateContext);
            return new AudioParseResult(fillEmptySlots(r.transaction, second), r.transcript);
        } catch (ParseException e) {
            return r;
        } finally {
            inputSource = base;
        }
    }

    /** 缺了會讓表單「填不完整、使用者得自己補」的欄位＝值得再問一次。 */
    private static boolean isIncomplete(ParsedTransaction t) {
        if (t.amount == null && !t.hasSplits()) return true;
        if (t.isTransfer() && !t.toAccount.resolved()) return true;
        return !t.account.resolved();
    }

    /**
     * 以直解結果為底，**只把空的槽**用重試結果補上；直解已經填好的一律不動
     * （直解聽得到語氣與停頓，不該被純文字的第二意見蓋掉）。
     */
    private static ParsedTransaction fillEmptySlots(ParsedTransaction base, ParsedTransaction extra) {
        // 「帳戶詞掉進 note」是這個失敗模式的招牌（account=null 而 note="借款"）。
        // 重試補上帳戶時，note 也改用重試的版本，免得帳戶名留在備註裡。
        boolean accountFilled = !base.account.resolved() && extra.account.resolved();

        if (base.transactionType == null) {
            base.transactionType = extra.transactionType;
            base.typeChange = extra.typeChange;
        }
        if (base.amount == null) base.amount = extra.amount;
        if (base.date == null) base.date = extra.date;
        if (base.time == null) base.time = extra.time;
        if (base.note == null || accountFilled) base.note = extra.note;
        if (!base.account.resolved()) base.account = extra.account;
        if (!base.toAccount.resolved()) base.toAccount = extra.toAccount;
        if (!base.category.resolved()) base.category = extra.category;
        if (!base.project.resolved()) base.project = extra.project;
        if (!base.hasSplits()) base.splits = extra.splits;
        return base;
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
            throw new ParseException(httpErrorMessage(code, responseBody));
        }

        try {
            String content = extractContent(responseBody);
            JSONObject data = new JSONObject(content);
            String transcript = data.isNull("transcript") ? "" : data.optString("transcript", "");
            record(transcript.isEmpty() ? "(語音直解，無轉寫)" : transcript,
                    supplement, formStateContext, content, null);
            return new AudioParseResult(toParsedTransaction(data, transcript), transcript);
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
            throw new ParseException(httpErrorMessage(code, responseBody));
        }

        return parseResponse(responseBody, userText, supplement, formStateContext);
    }

    private void record(String userText, boolean supplement, String formStateContext, String rawContent, String error) {
        if (logContext != null) {
            AiLog.record(logContext, userText, supplement, formStateContext, rawContent, error,
                    prefs.getModel(), inputSource, PROMPT_VERSION);
        }
    }

    private static final String SUPPLEMENT_RULE =
            "\n【補充模式】使用者正在一張已填好的交易表單上，用這句話做補充或修正。\n"
            + "只回這句話明確講到的欄位；沒講到的一律填 null（呼叫端只會套用非 null 的欄位，\n"
            + "填 null 的欄位會維持使用者原本的內容）。例：「不對，是刷庚銀行」→ 只有 account 有值，\n"
            + "amount/category/project/note 全部 null。\n"
            + "transaction_type 一般填 null（維持原型別）；**但使用者若明確要改變交易型別就要回報新型別**：\n"
            + "「改成轉帳」「其實是轉帳給X」「轉到X」→transfer（並把轉入帳戶填進 to_account）；\n"
            + "「改成收入」「這是收入」→income；「改成支出」「其實是花費」→expense；\n"
            + "「剩下X」「餘額X」「現在有X」→balance（amount 填新餘額，可為信用卡的負值）。\n"
            + "沒有明確要改型別的指示就維持 null，不要自己臆測。⚠️ 此模式下「拿不準填 expense」的\n"
            + "預設規則**不適用**：部分修正（「金額改成500」「分類改餐飲」「備註加XX」）transaction_type\n"
            + "一律 null——填了 expense 會把使用者正在編的轉帳表單整個切掉。\n"
            + "**回了 transaction_type 就要一併給 type_change_quote**（指出是哪句話讓你這樣判斷，\n"
            + "原文照抄）；沒改型別時 transaction_type=null 且 type_change_quote=null。\n"
            + "呼叫端會**回頭比對這段話是否真的出現在使用者原話裡**，再決定要不要換整張表單\n"
            + "（轉帳↔一般交易↔調整餘額），所以不能自己編一段。\n"
            + "若使用者要求把這筆拆成多個分類（如「拆成房租3000水電2000」），就在 splits 回報各份，其餘欄位維持 null。\n"
            + "\n下方附【目前表單已填內容】＝這筆交易現在的樣子。據此判斷這句話的意圖：\n"
            + "(a) 在現有交易上「再補一個品項＋金額」（目前是單一分類「美妝316」，這句說「還有270是咖啡」\n"
            + "    或「270咖啡」）＝要變成分割：把目前表單的金額與分類當成第一份、連同新的一起放進 splits\n"
            + "    （→[{美妝,316},{咖啡,270}]），其餘欄位一律 null。\n"
            + "(b) 目前已是分割、這句再加一份＝回報「含現有各份＋新份」的完整 splits。\n"
            + "(c) 只是修正某個既有欄位（金額/分類/帳戶/備註/型別…）＝只回被改的那一欄，splits 留空、其餘 null。\n"
            + "判準：這句話在「補上另一個品項＋金額」就走分割(a/b)；在「改掉原本某個值」就走修正(c)。\n"
            + "沒有【目前表單已填內容】區塊時，比照一般補充模式處理。\n";

    /**
     * 規則文字的指紋，寫進每一筆紀錄的 pv 欄。
     *
     * 涵蓋三段規則的**全部**（不分這次走的是哪一種模式），所以同一個建置內是常數：
     * 要回答的問題是「這批語料是哪一版規則跑出來的」，模式差異另有 stt / mode 欄可分。
     * 刻意不含帳戶/分類清單與【現在時間】——那些是資料、天天在變，混進來版本就失去意義。
     */
    static final String PROMPT_VERSION =
            AiLog.fingerprint(SYSTEM_PROMPT + AUDIO_RULE + SUPPLEMENT_RULE);

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
            return toParsedTransaction(data, userText);
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

    private ParsedTransaction toParsedTransaction(JSONObject data, String userText) {
        ParsedTransaction t = new ParsedTransaction();
        t.transactionType = data.isNull("transaction_type") ? null : data.optString("transaction_type", null);
        t.typeChange = resolveTypeChange(data, t, userText);
        t.amount = data.isNull("amount") ? null : data.optDouble("amount");
        t.note = data.isNull("note") ? null : emptyToNull(data.optString("note", null));
        t.date = data.isNull("date") ? null : emptyToNull(data.optString("date", null));
        t.time = data.isNull("time") ? null : emptyToNull(data.optString("time", null));

        t.account = readPick(data.optJSONObject("account"), ctx.validAccountIds);
        t.toAccount = readPick(data.optJSONObject("to_account"), ctx.validAccountIds);
        t.category = readPick(data.optJSONObject("category"), ctx.validCategoryIds);
        t.project = readPick(data.optJSONObject("project"), ctx.validProjectIds);
        t.splits = readSplits(data.optJSONArray("splits"));

        clearToAccountUnlessTransfer(t);
        applyAccountNamedInNote(t, ctx.accountsByName, userText);
        return t;
    }

    /**
     * 不是轉帳就不該有轉入帳戶。
     *
     * 這件事沒有任何判斷成分，一個 if 就能保證，本來卻是 prompt 裡的一條規則——
     * 規則寫在 prompt 就得跟其他十幾條搶模型的注意力，而且沒人驗它有沒有被遵守。
     *
     * type 為 null 時不動：補充模式的 null＝「型別不變」，這時使用者可能正在補轉入帳戶。
     */
    static void clearToAccountUnlessTransfer(ParsedTransaction t) {
        if (t.transactionType != null
                && !ParsedTransaction.TYPE_TRANSFER.equals(t.transactionType)) {
            t.toAccount = new ParsedTransaction.Pick();
        }
    }

    /**
     * 備註整段就是某個帳戶的名字時，把它放回帳戶欄。
     *
     * 這是實地反覆出現的失敗形狀：「借款轉甲銀行9000」解成 account=null / note="借款"，
     * 「丙銀行信用卡2383」更糟——note="丙銀行信用卡" 而帳戶挑了甲銀行信用卡。prompt 裡寫過
     * 「你填進 note 的字不該和某個帳戶的 name 相同」，模型照樣犯：判斷一個字串等不等於
     * 清單裡某個名字是程式的強項、模型的弱項，本來就不該託付給它。
     *
     * 敢覆蓋模型已經挑好的帳戶，是因為有原句佐證——**那個名字要真的出現在使用者說的話裡**
     * 才動（同 {@link #resolveTypeChange} 的原則：模型指出證據、程式驗證證據）。
     * 名字不在原句裡就只把備註清掉，不碰帳戶：那是模型自己生的字，沒有份量。
     *
     * @param userText 使用者原句（語音就是轉寫）；null＝沒有佐證可驗，只清備註
     */
    static void applyAccountNamedInNote(ParsedTransaction t, Map<String, Long> accountsByName,
                                        String userText) {
        if (t.note == null || accountsByName == null) return;
        Long named = accountsByName.get(t.note.trim());
        if (named == null) return;
        // 轉帳時備註若是轉入帳戶的名字，那只是重複，不能拿去蓋轉出帳戶
        boolean isToAccount = t.toAccount.resolved() && named.equals(t.toAccount.id);
        if (!isToAccount && userText != null
                && squeeze(userText).contains(squeeze(t.note))
                && !named.equals(t.account.id)) {
            ParsedTransaction.Pick p = new ParsedTransaction.Pick();
            p.id = named;
            p.confidence = 1;   // 名稱完全相符且原句佐證得到，比模型的猜測可信
            t.account = p;
        }
        t.note = null;
    }

    /**
     * 「這句話是不是真的在要求改型別」——用模型指出的原話片段（type_change_quote）判斷，
     * 而且**回頭驗那段話真的出現在使用者原句裡**才算數。
     *
     * 為什麼不用關鍵字比對：試過，太死。只有講出「收入／支出／轉帳」這種書面詞才會中，
     * 「講錯了其實是花了200」「幫人家買晚餐收200」這種意思到了但用詞不同的一律漏掉
     * （2026-08-01 實測）。反過來若放寬關鍵字，又擋不住模型自作主張。
     * 讓模型指出證據、程式驗證證據，兩邊各做自己擅長的事：判斷語意 vs 確認沒瞎編。
     *
     * 比對前把空白與標點去掉——語音轉出來的標點本來就不穩，不該因為一個逗號就判定造假。
     * 模型沒回這欄（沒吃 schema 的 provider）就退回舊行為＝有回型別就當要改。
     */
    private static boolean resolveTypeChange(JSONObject data, ParsedTransaction t, String userText) {
        if (t.transactionType == null) return false;
        if (!data.has("type_change_quote")) return true;          // 舊行為
        if (data.isNull("type_change_quote")) return false;
        String quote = squeeze(data.optString("type_change_quote", ""));
        if (quote.isEmpty()) return false;
        return squeeze(userText).contains(quote);
    }

    /** 去掉空白與常見標點，讓「原文照抄」的比對不會被標點差異卡住。 */
    private static String squeeze(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s，,。.、；;：:！!？?（）()「」\"'~-]", "");
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

    /**
     * 把 HTTP 錯誤翻成看得懂的話。429 特別處理：免費層（尤其 Groq，TPM 8000 而本 app
     * 每次請求約 5~6k tokens）很容易一分鐘只能跑一筆，直接顯示原始 JSON 只會讓人困惑。
     */
    static String httpErrorMessage(int code, String responseBody) {
        if (code == 429) {
            return "已達服務商的用量限制（每分鐘 token 上限），請稍候再試。"
                    + "免費方案較容易碰到，可到 AI 設定換 provider 或看說明。";
        }
        if (code == 401 || code == 403) {
            return "API key 無效或被拒（" + code + "）。請到 AI 設定確認 key。";
        }
        return "API 回傳 " + code + "：" + shorten(responseBody);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
