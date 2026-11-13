package tw.tib.financisto.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * AI 記帳的 provider 設定與 key 存放（2026-07-20 重構為 per-provider 模型）。
 *
 * 各家 provider（Groq / OpenAI / Google AI / Anthropic）各自一把 key，加密存放
 * （EncryptedSharedPreferences，master key 落 Android Keystore）。
 * 語音（STT）與解析（LLM）各自選 provider：STT 多一個「內建」（Google 語音彈窗）；
 * LLM 的 Gemini 走其 OpenAI 相容層，解析鏈（BookkeepingParser）程式碼不變。
 * 端點 URL 全部設死在 provider 定義（不再開放自由填寫；要接自架 proxy 再議）。
 *
 * 舊版單一 key（SECURE_KEY_API_LEGACY）視為 OpenAI 的 key 讀取回退（既有舊設定就是
 * OpenAI），新存檔一律走 per-provider slot。
 */
public class AiPreferences {

    private static final String TAG = "AiPreferences";

    // --- provider id（存進 prefs 的值，不要改） ---
    public static final String PROVIDER_SYSTEM = "system";   // 僅 STT：內建 Google 語音彈窗
    public static final String PROVIDER_GROQ = "groq";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_GEMINI = "gemini";
    /** 只做解析（LLM）：Anthropic 不收音訊，不出現在語音辨識選單。走 Messages API（見 LlmCall）。 */
    public static final String PROVIDER_ANTHROPIC = "anthropic";
    /**
     * 只做解析：使用者自填網址的 OpenAI 相容服務（自架 llama.cpp／Ollama／vLLM、代理、其他家）。
     * key 選填（自架服務常不需要）；網址存一般設定（不是秘密），key 照樣加密。
     */
    public static final String PROVIDER_CUSTOM = "custom";
    /**
     * 僅 STT 選單：「辨識＋解析一次到位」——音檔直接進解析 prompt（通用模型聽得懂音訊），
     * 一趟來回出表單、transcript 由模型一併回報。Gemini 走其 OpenAI 相容層、OpenAI 走
     * audio-preview 模型，請求形狀相同（input_audio + structured outputs）。
     */
    public static final String PROVIDER_GEMINI_DIRECT = "gemini_direct";
    public static final String PROVIDER_OPENAI_DIRECT = "openai_direct";
    /** OpenAI 一次到位的預設音訊輸入模型（一般 gpt-4o-mini 不吃音訊）。 */
    public static final String DIRECT_MODEL_OPENAI = "gpt-4o-mini-audio-preview";
    /**
     * Anthropic 的預設解析模型。Sonnet 5.5（關思考）在真實記帳句上比 Haiku 5.5 準一點又快
     * （2.3 秒 vs 3.7 秒），代價是每句約 US$0.02、Haiku 約 US$0.001；想省錢在設定裡改選 Haiku。
     */
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5";

    /**
     * OpenAI 一次到位可選的音訊輸入模型白名單。/models 清單不標示哪顆吃音訊，直接開放整包
     * 會讓使用者選到不吃音訊的模型、要到呼叫失敗才發現，故手動維護這份安全清單。
     * （Gemini 不需要——其全系列模型皆吃音訊，走 /models 動態清單即可。）
     */
    public static final String[] OPENAI_DIRECT_MODELS = {
            "gpt-4o-mini-audio-preview",
            "gpt-4o-audio-preview",
    };

    /** 能做語音辨識的雲端 provider（STT 選單另外加「內建」在最前）。 */
    public static final String[] CLOUD_PROVIDERS = {PROVIDER_GEMINI, PROVIDER_GROQ, PROVIDER_OPENAI};

    /** 能做解析的 provider（解析服務選單）。 */
    public static final String[] LLM_PROVIDERS =
            {PROVIDER_GEMINI, PROVIDER_GROQ, PROVIDER_OPENAI, PROVIDER_ANTHROPIC, PROVIDER_CUSTOM};

    public static final String KEY_LLM_PROVIDER = "ai_llm_provider";
    public static final String KEY_MODEL = "ai_model";
    public static final String KEY_STT_PROVIDER = "ai_stt_provider";
    public static final String KEY_STT_MODEL = "ai_stt_model";
    public static final String KEY_CUSTOM_BASE_URL = "ai_custom_base_url";
    public static final String KEY_AUTO_SEND = "ai_auto_send";
    public static final String KEY_INTRO_SHOWN = "ai_intro_shown";
    public static final String KEY_SHORTCUT_ENTERS_APP = "ai_shortcut_enters_app";
    public static final String KEY_SHOW_PENDING_FILTER_BUTTON = "ai_show_pending_filter_button";
    public static final String KEY_FAB_SIZE_DP = "ai_fab_size_dp";
    public static final String KEY_FAB_TRANS_X = "ai_fab_trans_x";
    public static final String KEY_FAB_TRANS_Y = "ai_fab_trans_y";

    /** 浮動語音鈕邊長（dp）。 */
    public static final int FAB_SIZE_MIN = 36;
    public static final int FAB_SIZE_MAX = 80;
    public static final int FAB_SIZE_DEFAULT = 48;

    private static final String SECURE_FILE = "ai_secure_prefs";
    /** 舊版單一 LLM key；現在當 OpenAI key 的讀取回退。 */
    private static final String SECURE_KEY_API_LEGACY = "api_key";

    private final Context appContext;
    private final String llmProvider;
    private final String llmModel;
    private final String sttProvider;
    private final String sttModel;
    private final String customBaseUrl;

    private AiPreferences(Context appContext, String llmProvider, String llmModel,
                          String sttProvider, String sttModel, String customBaseUrl) {
        this.appContext = appContext;
        this.llmProvider = llmProvider;
        this.llmModel = llmModel;
        this.sttProvider = sttProvider;
        this.sttModel = sttModel;
        this.customBaseUrl = customBaseUrl;
    }

    public static AiPreferences load(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String llmProvider = trimOr(prefs.getString(KEY_LLM_PROVIDER, PROVIDER_OPENAI), PROVIDER_OPENAI);
        String llmModel = trimOr(prefs.getString(KEY_MODEL, ""), defaultLlmModel(llmProvider));
        String sttProvider = trimOr(prefs.getString(KEY_STT_PROVIDER, PROVIDER_SYSTEM), PROVIDER_SYSTEM);
        String sttModel = trimOr(prefs.getString(KEY_STT_MODEL, ""), defaultSttModel(sttProvider));
        return new AiPreferences(context.getApplicationContext(),
                llmProvider, llmModel, sttProvider, sttModel, getCustomBaseUrl(context));
    }

    private static String trimOr(String s, String def) {
        return (s == null || s.trim().isEmpty()) ? def : s.trim();
    }

    // ---------------- provider 定義（設死的端點與預設模型） ----------------

    /**
     * API base（/models 清單與 key 測試用；解析的 chat/completions 也接在它後面）。
     * Gemini 走它的 OpenAI 相容層；Anthropic 的解析走 /messages（見 LlmCall），/models 形狀相同。
     */
    public static String llmBaseUrl(String provider) {
        switch (provider) {
            case PROVIDER_ANTHROPIC: return "https://api.anthropic.com/v1";
            case PROVIDER_GROQ: return "https://api.groq.com/openai/v1";
            case PROVIDER_GEMINI: return "https://generativelanguage.googleapis.com/v1beta/openai";
            case PROVIDER_OPENAI:
            default: return "https://api.openai.com/v1";
        }
    }

    /** STT 端點：Groq/OpenAI＝OpenAI 相容 transcriptions；Gemini＝原生 base（模型組進 path）。 */
    public static String sttEndpointUrl(String provider) {
        switch (provider) {
            case PROVIDER_GROQ: return "https://api.groq.com/openai/v1/audio/transcriptions";
            case PROVIDER_GEMINI: return "https://generativelanguage.googleapis.com/v1beta";
            case PROVIDER_OPENAI:
            default: return "https://api.openai.com/v1/audio/transcriptions";
        }
    }

    public static String defaultLlmModel(String provider) {
        switch (provider) {
            case PROVIDER_GROQ: return "openai/gpt-oss-120b";      // Groq 上支援 structured outputs
            // 2026-07 端側/雲端平價評測：flash-lite 24/24 全對、最快又平價（見 on-device-llm-eval）
            case PROVIDER_GEMINI: return "gemini-3.1-flash-lite";
            // 選型理由見 DEFAULT_ANTHROPIC_MODEL 與 LlmCall.applyReasoning
            case PROVIDER_ANTHROPIC: return DEFAULT_ANTHROPIC_MODEL;
            case PROVIDER_CUSTOM: return "";                      // 沒有預設，從該服務的 /models 選
            case PROVIDER_OPENAI:
            default: return "gpt-4.1-mini";                       // OpenAI 平價中準度最佳（4o-mini 較弱）
        }
    }

    public static String defaultSttModel(String provider) {
        switch (provider) {
            case PROVIDER_GROQ: return "whisper-large-v3-turbo";
            case PROVIDER_GEMINI: return "gemini-3.1-flash-lite";
            case PROVIDER_OPENAI: return "gpt-4o-mini-transcribe";
            // 一次到位：STT 模型槽改存「音檔直解的解析模型」（見 getDirectParseModel）
            case PROVIDER_GEMINI_DIRECT: return defaultLlmModel(PROVIDER_GEMINI);
            case PROVIDER_OPENAI_DIRECT: return DIRECT_MODEL_OPENAI;
            default: return "";
        }
    }

    /** 把一次到位的 provider id 收斂回底層家（gemini_direct→gemini、openai_direct→openai）。 */
    public static String underlyingProvider(String provider) {
        if (PROVIDER_GEMINI_DIRECT.equals(provider)) return PROVIDER_GEMINI;
        if (PROVIDER_OPENAI_DIRECT.equals(provider)) return PROVIDER_OPENAI;
        return provider;
    }

    // ---------------- LLM（BookkeepingParser 介面維持不變） ----------------

    public String getModel() { return llmModel; }

    public String getLlmProvider() { return llmProvider; }

    public String getApiKey() { return getKey(appContext, llmProvider); }

    /** 解析用的 API base：自建服務是使用者填的網址，其他家是設死的。 */
    public String getLlmBaseUrl() {
        return PROVIDER_CUSTOM.equals(llmProvider) ? customBaseUrl : llmBaseUrl(llmProvider);
    }

    public boolean isConfigured() {
        return isProviderReady(appContext, llmProvider);
    }

    /** 這家能不能拿來解析：自建服務看有沒有填網址（key 選填），其他家看有沒有 key。 */
    public static boolean isProviderReady(Context context, String provider) {
        if (PROVIDER_CUSTOM.equals(provider)) return !getCustomBaseUrl(context).isEmpty();
        return hasKey(context, provider);
    }

    /** 某家的 API base（設定頁取 /models 用）。 */
    public static String baseUrl(Context context, String provider) {
        return PROVIDER_CUSTOM.equals(provider) ? getCustomBaseUrl(context) : llmBaseUrl(provider);
    }

    public static String getCustomBaseUrl(Context context) {
        String s = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(KEY_CUSTOM_BASE_URL, "");
        return s == null ? "" : s;
    }

    public static void saveCustomBaseUrl(Context context, String url) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(KEY_CUSTOM_BASE_URL, normalizeBaseUrl(url)).apply();
    }

    /**
     * 使用者貼的網址收斂成 base：去頭尾空白與結尾斜線；貼成完整端點（…/chat/completions、
     * …/models）也收回 base；沒寫協定補 http://（自架服務多半在內網、沒有憑證）。
     */
    static String normalizeBaseUrl(String url) {
        if (url == null) return "";
        String s = url.trim();
        if (s.isEmpty()) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        for (String tail : new String[]{"/chat/completions", "/models"}) {
            if (s.endsWith(tail)) s = s.substring(0, s.length() - tail.length());
        }
        if (!s.contains("://")) s = "http://" + s;
        return s;
    }

    // ---------------- STT ----------------

    /** true＝語音走雲端（自錄 WAV 上傳，含一次到位模式）；false＝內建 Google 語音彈窗。 */
    public boolean isCloudStt() {
        return !PROVIDER_SYSTEM.equals(sttProvider);
    }

    /** true＝一次到位：音檔直接進解析（跳過 STT→LLM 兩段串接）。Gemini／OpenAI 皆可。 */
    public boolean isDirectVoiceParse() {
        return PROVIDER_GEMINI_DIRECT.equals(sttProvider)
                || PROVIDER_OPENAI_DIRECT.equals(sttProvider);
    }

    /** 一次到位的底層 provider（gemini／openai），決定端點與 key。 */
    public String getDirectProvider() {
        return PROVIDER_OPENAI_DIRECT.equals(sttProvider) ? PROVIDER_OPENAI : PROVIDER_GEMINI;
    }

    /**
     * 一次到位用的解析模型。優先用使用者在設定頁明選的（存在 STT 模型槽，direct 模式該槽
     * 專用於此）；沒設才回退預設：OpenAI 用能吃音訊的 audio-preview、Gemini 沿用解析模型
     * 或其預設。（舊設定的 direct 使用者 STT 模型槽為空，會落到回退分支，行為不變。）
     */
    public String getDirectParseModel() {
        if (sttModel != null && !sttModel.trim().isEmpty()) return sttModel.trim();
        if (PROVIDER_OPENAI_DIRECT.equals(sttProvider)) return DIRECT_MODEL_OPENAI;
        return PROVIDER_GEMINI.equals(llmProvider) ? llmModel : defaultLlmModel(PROVIDER_GEMINI);
    }

    public String getDirectParseKey() {
        return getKey(appContext, getDirectProvider());
    }

    public String getSttProvider() { return sttProvider; }

    public String getSttModel() { return sttModel; }

    public String getSttApiKey() { return getKey(appContext, sttProvider); }

    /**
     * 辨識引擎標籤，寫進 AiLog 的 `stt` 欄（見 {@link AiLog#record}）。
     * 語料要能按「哪個引擎聽出來的」分層，才比較得出辨識落差；混著看只會得到假結論。
     */
    public String getSttLabel() {
        if (isDirectVoiceParse()) return "direct:" + getDirectProvider() + "/" + getDirectParseModel();
        if (!isCloudStt()) return PROVIDER_SYSTEM;
        return sttProvider + "/" + getSttModel();
    }

    public boolean isCloudSttConfigured() {
        String k = getSttApiKey();
        return isCloudStt() && k != null && !k.isEmpty();
    }

    // ---------------- per-provider key（加密存放） ----------------

    /** 讀某 provider 的 key；OpenAI 缺 slot 時回退舊版單一 key（升級不掉設定）。 */
    public static String getKey(Context context, String provider) {
        if (PROVIDER_SYSTEM.equals(provider)) return "";
        if (PROVIDER_GEMINI_DIRECT.equals(provider)) provider = PROVIDER_GEMINI;
        if (PROVIDER_OPENAI_DIRECT.equals(provider)) provider = PROVIDER_OPENAI;
        String k = readSecure(context, secureKeyName(provider));
        if ((k == null || k.isEmpty()) && PROVIDER_OPENAI.equals(provider)) {
            k = readSecure(context, SECURE_KEY_API_LEGACY);
        }
        return k;
    }

    public static boolean hasKey(Context context, String provider) {
        String k = getKey(context, provider);
        return k != null && !k.isEmpty();
    }

    public static void saveKey(Context context, String provider, String key) {
        saveSecure(context, secureKeyName(provider), key);
    }

    private static String secureKeyName(String provider) {
        return "key_" + provider;
    }

    // ---------------- 設定頁寫入 ----------------

    public static void saveSelections(Context context, String sttProvider, String sttModel,
                                      String llmProvider, String llmModel) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(KEY_STT_PROVIDER, sttProvider == null ? PROVIDER_SYSTEM : sttProvider)
                .putString(KEY_STT_MODEL, sttModel == null ? "" : sttModel.trim())
                .putString(KEY_LLM_PROVIDER, llmProvider == null ? PROVIDER_OPENAI : llmProvider)
                .putString(KEY_MODEL, llmModel == null ? "" : llmModel.trim())
                .apply();
    }

    /** 第一次用語音前的說明是否看過（看過就不再自動彈，改由設定頁按鈕叫出）。 */
    public static boolean isIntroShown(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(KEY_INTRO_SHOWN, false);
    }

    public static void saveIntroShown(Context context, boolean shown) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(KEY_INTRO_SHOWN, shown).apply();
    }

    /** 辨識完是否直接送解析（畫面上的開關；預設否＝停在文字讓使用者接著講/手動送）。 */
    /** 預設開（2026-08-02）：講完就是要記帳，停在文字框等你再按一次是多一步。 */
    public static boolean isAutoSend(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_AUTO_SEND, true);
    }

    public static void saveAutoSend(Context context, boolean autoSend) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(KEY_AUTO_SEND, autoSend).apply();
    }

    /**
     * 桌面捷徑收尾行為：true＝進到 App 對應畫面（沒完成→主畫面、完成→交易列表），
     * false（預設）＝回桌面。在 runtime 讀，改設定即時生效、不必重釘捷徑。
     */
    public static boolean isShortcutEntersApp(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(KEY_SHORTCUT_ENTERS_APP, false);
    }

    public static void saveShortcutEntersApp(Context context, boolean entersApp) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(KEY_SHORTCUT_ENTERS_APP, entersApp).apply();
    }

    /**
     * 交易列表底排要不要顯示「只看擱置」的切換鈕。放在 AI 設定而非原生設定頁，
     * 是因為它服務的是 AI／樣板自動記帳的收尾流程（自動記進來的交易是擱置，
     * 篩出來逐筆過目），不是原生記帳本身的偏好。
     */
    public static boolean isShowPendingFilterButton(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(KEY_SHOW_PENDING_FILTER_BUTTON, true);
    }

    public static void saveShowPendingFilterButton(Context context, boolean show) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(KEY_SHOW_PENDING_FILTER_BUTTON, show).apply();
    }

    /** 浮動語音鈕大小（dp），夾在 [FAB_SIZE_MIN, FAB_SIZE_MAX]。 */
    public static int getFabSizeDp(Context context) {
        int v = PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(KEY_FAB_SIZE_DP, FAB_SIZE_DEFAULT);
        return Math.max(FAB_SIZE_MIN, Math.min(FAB_SIZE_MAX, v));
    }

    public static void saveFabSizeDp(Context context, int dp) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(KEY_FAB_SIZE_DP, dp).apply();
    }

    /**
     * 浮動鈕的拖曳位移（px，相對右下預設位置）。存 px 是因為那正是 translation 的單位；
     * 拖曳時已夾在畫面內，所以不會存到跑出畫面的值。
     */
    public static float getFabTransX(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getFloat(KEY_FAB_TRANS_X, 0f);
    }

    public static float getFabTransY(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getFloat(KEY_FAB_TRANS_Y, 0f);
    }

    public static void saveFabTrans(Context context, float x, float y) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putFloat(KEY_FAB_TRANS_X, x)
                .putFloat(KEY_FAB_TRANS_Y, y)
                .apply();
    }

    private static void saveSecure(Context context, String key, String value) {
        try {
            securePrefs(context).edit().putString(key, value == null ? "" : value.trim()).apply();
        } catch (Exception ex) {
            Log.e(TAG, "無法寫入加密 key：" + key, ex);
        }
    }

    private static String readSecure(Context context, String key) {
        try {
            return securePrefs(context).getString(key, "");
        } catch (Exception ex) {
            Log.e(TAG, "無法讀取加密 key：" + key, ex);
            return "";
        }
    }

    // EncryptedSharedPreferences 每次重建都要走 Android Keystore（MasterKey），主執行緒
    // 一次可達百毫秒級；設定頁 onCreate 連讀多個 provider 的 key 就是「進設定頓一下」的主因。
    // 快取單一實例（process 級），並由 Application 啟動時在背景預熱，首次使用也不卡 UI。
    private static volatile SharedPreferences sSecurePrefs;

    /** 背景預熱 Keystore/EncryptedSharedPreferences（Application 啟動時呼叫；失敗靜默，用到時再重試）。 */
    public static void warmUpSecure(Context context) {
        final Context app = context.getApplicationContext();
        tw.tib.financisto.Application.getExecutor().execute(() -> {
            try {
                securePrefs(app);
            } catch (Exception ignored) {}
        });
    }

    private static SharedPreferences securePrefs(Context context) throws Exception {
        SharedPreferences cached = sSecurePrefs;
        if (cached != null) return cached;
        synchronized (AiPreferences.class) {
            if (sSecurePrefs != null) return sSecurePrefs;
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            sSecurePrefs = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    SECURE_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            return sSecurePrefs;
        }
    }
}
