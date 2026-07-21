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
 * 三家 provider（Groq / OpenAI / Google AI）各自一把 key，加密存放
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
    /**
     * 僅 STT 選單：「辨識＋解析一次到位」——音檔直接進解析 prompt（通用模型聽得懂音訊），
     * 一趟來回出表單、transcript 由模型一併回報。Gemini 走其 OpenAI 相容層、OpenAI 走
     * audio-preview 模型，請求形狀相同（input_audio + structured outputs）。
     */
    public static final String PROVIDER_GEMINI_DIRECT = "gemini_direct";
    public static final String PROVIDER_OPENAI_DIRECT = "openai_direct";
    /** OpenAI 一次到位固定用的音訊輸入模型（一般 gpt-4o-mini 不吃音訊）。 */
    public static final String DIRECT_MODEL_OPENAI = "gpt-4o-mini-audio-preview";

    /** 三家雲端 provider（STT 選單另外加「內建」在最前）。 */
    public static final String[] CLOUD_PROVIDERS = {PROVIDER_GEMINI, PROVIDER_GROQ, PROVIDER_OPENAI};

    public static final String KEY_LLM_PROVIDER = "ai_llm_provider";
    public static final String KEY_MODEL = "ai_model";
    public static final String KEY_STT_PROVIDER = "ai_stt_provider";
    public static final String KEY_STT_MODEL = "ai_stt_model";
    public static final String KEY_AUTO_SEND = "ai_auto_send";
    public static final String KEY_SHORTCUT_ENTERS_APP = "ai_shortcut_enters_app";
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

    private AiPreferences(Context appContext, String llmProvider, String llmModel,
                          String sttProvider, String sttModel) {
        this.appContext = appContext;
        this.llmProvider = llmProvider;
        this.llmModel = llmModel;
        this.sttProvider = sttProvider;
        this.sttModel = sttModel;
    }

    public static AiPreferences load(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String llmProvider = trimOr(prefs.getString(KEY_LLM_PROVIDER, PROVIDER_OPENAI), PROVIDER_OPENAI);
        String llmModel = trimOr(prefs.getString(KEY_MODEL, ""), defaultLlmModel(llmProvider));
        String sttProvider = trimOr(prefs.getString(KEY_STT_PROVIDER, PROVIDER_SYSTEM), PROVIDER_SYSTEM);
        String sttModel = trimOr(prefs.getString(KEY_STT_MODEL, ""), defaultSttModel(sttProvider));
        return new AiPreferences(context.getApplicationContext(),
                llmProvider, llmModel, sttProvider, sttModel);
    }

    private static String trimOr(String s, String def) {
        return (s == null || s.trim().isEmpty()) ? def : s.trim();
    }

    // ---------------- provider 定義（設死的端點與預設模型） ----------------

    /** OpenAI 相容 base（LLM 與 /models 清單用；Gemini 走它的 OpenAI 相容層）。 */
    public static String llmBaseUrl(String provider) {
        switch (provider) {
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
            case PROVIDER_GROQ: return "openai/gpt-oss-120b";   // Groq 上支援 structured outputs
            case PROVIDER_GEMINI: return "gemini-3.5-flash";    // 2.5 已被 Google 提早停用（2026-07）
            case PROVIDER_OPENAI:
            default: return "gpt-4o-mini";
        }
    }

    public static String defaultSttModel(String provider) {
        switch (provider) {
            case PROVIDER_GROQ: return "whisper-large-v3-turbo";
            case PROVIDER_GEMINI: return "gemini-3.5-flash";
            case PROVIDER_OPENAI: return "gpt-4o-mini-transcribe";
            default: return "";
        }
    }

    // ---------------- LLM（BookkeepingParser 介面維持不變） ----------------

    public String getModel() { return llmModel; }

    public String getLlmProvider() { return llmProvider; }

    public String getApiKey() { return getKey(appContext, llmProvider); }

    public boolean isConfigured() {
        String k = getApiKey();
        return k != null && !k.isEmpty();
    }

    /** chat/completions 完整 endpoint。 */
    public String getChatCompletionsUrl() {
        return llmBaseUrl(llmProvider) + "/chat/completions";
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
     * 一次到位用的解析模型：OpenAI 固定用 audio-preview（一般模型不吃音訊）；
     * Gemini 解析端本來就選 Gemini 就用它設的模型，否則用 Gemini 預設。
     */
    public String getDirectParseModel() {
        if (PROVIDER_OPENAI_DIRECT.equals(sttProvider)) return DIRECT_MODEL_OPENAI;
        return PROVIDER_GEMINI.equals(llmProvider) ? llmModel : defaultLlmModel(PROVIDER_GEMINI);
    }

    public String getDirectParseKey() {
        return getKey(appContext, getDirectProvider());
    }

    public String getSttProvider() { return sttProvider; }

    public String getSttModel() { return sttModel; }

    public String getSttApiKey() { return getKey(appContext, sttProvider); }

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

    /** 辨識完是否直接送解析（畫面上的開關；預設否＝停在文字讓使用者接著講/手動送）。 */
    public static boolean isAutoSend(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_AUTO_SEND, false);
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
