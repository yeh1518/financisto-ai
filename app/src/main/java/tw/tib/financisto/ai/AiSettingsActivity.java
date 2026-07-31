package tw.tib.financisto.ai;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import tw.tib.financisto.R;
import tw.tib.financisto.activity.NotificationListActivity;

/**
 * AI 設定頁（2026-07-23 改為全選單式，Gary 定案）。
 *
 * 三條原則：
 * 1. **沒有儲存按鈕**——每個選擇當下就落地（key 本來就是彈窗即存）。
 * 2. **模型不能手打**——只能從該 provider 的 /models 抓回來的清單裡選，杜絕打錯字。
 * 3. **每項都是「點一列 → 彈選單」**——含浮動鈕大小（疊窗拖拉＋示意圖）與捷徑行為
 *    （原本是唯一的 Switch，字長還會尷尬換行）。
 *
 * 刻意用純 Activity + 手排 widget，不掛 androidx PreferenceFragment（理由同前版：
 * 避開 framework Material 主題缺 preferenceTheme 的崩潰，並完全掌控 key 的加密落地）。
 */
public class AiSettingsActivity extends ComponentActivity {

    // 目前選擇（改動即存，不留待儲存鈕）
    private String sttProvider;
    private String sttModel;
    private String llmProvider;
    private String llmModel;

    // 合併列：sttProviderValue／llmProviderValue 各顯示「provider \ model」
    private TextView sttProviderValue, llmProviderValue;
    private TextView fabSizeValue, shortcutBehaviorValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_settings);

        // edge-to-edge：理由同 AiInputActivity.applyWindowInsets
        View root = findViewById(R.id.ai_settings_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        AiPreferences prefs = AiPreferences.load(this);
        sttProvider = prefs.getSttProvider();
        sttModel = prefs.getSttModel();
        llmProvider = prefs.getLlmProvider();
        llmModel = prefs.getModel();

        bindKeyRow(AiPreferences.PROVIDER_GEMINI, R.id.ai_key_status_gemini, R.id.ai_key_edit_gemini);
        bindKeyRow(AiPreferences.PROVIDER_GROQ, R.id.ai_key_status_groq, R.id.ai_key_edit_groq);
        bindKeyRow(AiPreferences.PROVIDER_OPENAI, R.id.ai_key_status_openai, R.id.ai_key_edit_openai);

        sttProviderValue = findViewById(R.id.ai_stt_provider_value);
        llmProviderValue = findViewById(R.id.ai_llm_provider_value);
        fabSizeValue = findViewById(R.id.ai_fab_size_value);
        shortcutBehaviorValue = findViewById(R.id.ai_shortcut_behavior_value);

        // 合併列：點一下＝先選辨識方式／服務，再接著選模型（見 pickStt／pickLlm）
        findViewById(R.id.ai_stt_provider_row).setOnClickListener(v -> pickStt());
        findViewById(R.id.ai_llm_provider_row).setOnClickListener(v -> pickLlm());

        findViewById(R.id.ai_fab_size_row).setOnClickListener(v -> showFabSizeDialog());
        findViewById(R.id.ai_shortcut_behavior_row).setOnClickListener(v -> pickShortcutBehavior());
        findViewById(R.id.ai_pin_shortcut_row).setOnClickListener(v -> pinVoiceShortcut());
        findViewById(R.id.ai_notification_template_row).setOnClickListener(v -> {
            Intent intent = new Intent(this, NotificationListActivity.class);
            intent.putExtra(NotificationListActivity.EXTRA_PICK_FOR_TEMPLATE, true);
            startActivity(intent);
        });
        findViewById(R.id.ai_help_row).setOnClickListener(v -> AiIntroDialog.show(this));
        findViewById(R.id.ai_log_row).setOnClickListener(v ->
                startActivity(new Intent(this, AiLogActivity.class)));

        refreshAllValues();
    }

    // ================= 顯示值 =================

    private void refreshAllValues() {
        sttProviderValue.setText(sttCombinedValue());
        llmProviderValue.setText(llmCombinedValue());

        fabSizeValue.setText(getString(R.string.ai_fab_size, AiPreferences.getFabSizeDp(this)));
        shortcutBehaviorValue.setText(AiPreferences.isShortcutEntersApp(this)
                ? getString(R.string.ai_shortcut_behavior_app)
                : getString(R.string.ai_shortcut_behavior_home));
    }

    /** 辨識方式列的值：「辨識方式 \ 模型」；內建無模型只顯示辨識方式。 */
    private String sttCombinedValue() {
        if (AiPreferences.PROVIDER_SYSTEM.equals(sttProvider)) {
            return getString(R.string.ai_stt_builtin);
        }
        return sttProviderLabel(sttProvider) + " \\ " + modelOrUnset(sttModel);
    }

    /** 服務列的值：「服務 \ 模型」；沒設 key 提示去設。 */
    private String llmCombinedValue() {
        if (!AiPreferences.hasKey(this, llmProvider)) {
            return getString(R.string.ai_no_provider_key);
        }
        return displayName(llmProvider) + " \\ " + modelOrUnset(llmModel);
    }

    private String modelOrUnset(String model) {
        return TextUtils.isEmpty(model) ? getString(R.string.ai_model_unset) : model;
    }

    private void persistSelections() {
        AiPreferences.saveSelections(this, sttProvider, sttModel, llmProvider, llmModel);
        refreshAllValues();
    }

    private static String displayName(String provider) {
        switch (provider) {
            case AiPreferences.PROVIDER_GEMINI: return "Google AI";
            case AiPreferences.PROVIDER_GROQ: return "Groq";
            case AiPreferences.PROVIDER_OPENAI: return "OpenAI";
            default: return provider;
        }
    }

    private String sttProviderLabel(String provider) {
        if (AiPreferences.PROVIDER_SYSTEM.equals(provider)) return getString(R.string.ai_stt_builtin);
        if (AiPreferences.PROVIDER_GEMINI_DIRECT.equals(provider)) return getString(R.string.ai_stt_gemini_direct);
        if (AiPreferences.PROVIDER_OPENAI_DIRECT.equals(provider)) return getString(R.string.ai_stt_openai_direct);
        return displayName(provider);
    }

    // ================= provider 選單 =================

    /**
     * 辨識方式及模型：先選辨識方式（內建 ＋ 已有 key 的家，Gemini/OpenAI 多一個「語音直解」），
     * 選完接著跳模型清單（內建無模型就結束）。要只換模型＝再點一次、選同一個方式再挑新模型。
     */
    private void pickStt() {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ids.add(AiPreferences.PROVIDER_SYSTEM);
        names.add(getString(R.string.ai_stt_builtin));
        for (String p : AiPreferences.CLOUD_PROVIDERS) {
            if (!AiPreferences.hasKey(this, p)) continue;
            ids.add(p);
            names.add(displayName(p));
            if (AiPreferences.PROVIDER_GEMINI.equals(p)) {
                ids.add(AiPreferences.PROVIDER_GEMINI_DIRECT);
                names.add(getString(R.string.ai_stt_gemini_direct));
            } else if (AiPreferences.PROVIDER_OPENAI.equals(p)) {
                ids.add(AiPreferences.PROVIDER_OPENAI_DIRECT);
                names.add(getString(R.string.ai_stt_openai_direct));
            }
        }
        showChoice(R.string.ai_stt_engine, names, ids.indexOf(sttProvider), which -> {
            String picked = ids.get(which);
            if (!picked.equals(sttProvider)) {
                sttProvider = picked;
                sttModel = AiPreferences.defaultSttModel(picked);   // 換方式＝帶該方式預設模型
            }
            persistSelections();                                    // 先落地辨識方式
            if (!AiPreferences.PROVIDER_SYSTEM.equals(picked)) {
                pickModel(true);                                    // 內建無模型；其餘接著選模型
            }
        });
    }

    /**
     * 服務及模型：先選服務（已有 key 的家；一家都沒有就引導去設 key），選完接著跳模型清單。
     */
    private void pickLlm() {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String p : AiPreferences.CLOUD_PROVIDERS) {
            if (!AiPreferences.hasKey(this, p)) continue;
            ids.add(p);
            names.add(displayName(p));
        }
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.ai_no_provider_key, Toast.LENGTH_LONG).show();
            return;
        }
        showChoice(R.string.ai_llm_provider, names, ids.indexOf(llmProvider), which -> {
            String picked = ids.get(which);
            if (!picked.equals(llmProvider)) {
                llmProvider = picked;
                llmModel = AiPreferences.defaultLlmModel(picked);
            }
            persistSelections();
            pickModel(false);
        });
    }

    private void pickShortcutBehavior() {
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.ai_shortcut_behavior_home));
        names.add(getString(R.string.ai_shortcut_behavior_app));
        int cur = AiPreferences.isShortcutEntersApp(this) ? 1 : 0;
        showChoice(R.string.ai_shortcut_behavior_title, names, cur, which -> {
            AiPreferences.saveShortcutEntersApp(this, which == 1);
            refreshAllValues();
        });
    }

    private interface OnPicked { void pick(int which); }

    /** 單選清單彈窗：點了就套用並關閉（不需要「確定」）。 */
    private void showChoice(int titleId, List<String> names, int checked, OnPicked onPicked) {
        String[] arr = names.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(titleId)
                .setSingleChoiceItems(arr, checked, (d, which) -> {
                    d.dismiss();
                    onPicked.pick(which);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ================= 模型清單（只能選，不能打） =================

    /**
     * 點模型列＝去該 provider 抓 /models，回來讓使用者選；選完即存。
     * 不提供手打——打錯字的模型名要到實際呼叫失敗才會發現，代價太高。
     *
     * @param forStt true＝語音模型（whisper/transcribe 系；Gemini 全模型皆可聽音訊，列 gemini 系），
     *               false＝解析模型（黑名單粗篩掉非 LLM）。
     */
    private void pickModel(boolean forStt) {
        final String provider = forStt ? sttProvider : llmProvider;
        if (AiPreferences.PROVIDER_SYSTEM.equals(provider)) return;
        // OpenAI 一次到位：/models 不標示哪顆吃音訊，改給安全白名單，杜絕選到不吃音訊的模型
        if (AiPreferences.PROVIDER_OPENAI_DIRECT.equals(provider)) {
            pickFromList(Arrays.asList(AiPreferences.OPENAI_DIRECT_MODELS), forStt);
            return;
        }
        // 一次到位的 Gemini 收斂回 gemini 這家（端點、key、模型過濾都以底層家為準）
        final String realProvider = AiPreferences.underlyingProvider(provider);
        final String key = AiPreferences.getKey(this, provider);
        if (TextUtils.isEmpty(key)) {
            Toast.makeText(this, R.string.ai_no_provider_key, Toast.LENGTH_LONG).show();
            return;
        }
        final String url = AiPreferences.llmBaseUrl(realProvider) + "/models";

        Toast.makeText(this, R.string.ai_models_loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<String> all = requestModels(url, key);
                List<String> models = new ArrayList<>();
                for (String id : all) {
                    String clean = stripModelsPrefix(id);
                    boolean ok = forStt ? isSttModel(realProvider, clean) : isLlm(clean);
                    if (ok) models.add(clean);
                }
                Collections.sort(models);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (models.isEmpty()) {
                        Toast.makeText(this, R.string.ai_models_empty, Toast.LENGTH_LONG).show();
                        return;
                    }
                    String cur = forStt ? sttModel : llmModel;
                    showChoice(R.string.ai_models_pick, models, models.indexOf(cur), which -> {
                        if (forStt) sttModel = models.get(which);
                        else llmModel = models.get(which);
                        persistSelections();
                    });
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    Toast.makeText(this, getString(R.string.ai_models_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** 從固定清單選模型（一次到位的 OpenAI 白名單用，不打 /models）；選完即存。 */
    private void pickFromList(List<String> models, boolean forStt) {
        String cur = forStt ? sttModel : llmModel;
        showChoice(R.string.ai_models_pick, models, models.indexOf(cur), which -> {
            if (forStt) sttModel = models.get(which);
            else llmModel = models.get(which);
            persistSelections();
        });
    }

    /** Gemini 的 OpenAI 相容層回的 id 帶 models/ 前綴，存乾淨名（兩層 API 都收）。 */
    private static String stripModelsPrefix(String id) {
        return id.startsWith("models/") ? id.substring("models/".length()) : id;
    }

    /** 取 /models 原始 id 清單（不過濾）。也是 key 測試的探針（HTTP 200 ＝ key 有效）。 */
    private List<String> requestModels(String url, String key) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + key)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            List<String> out = new ArrayList<>();
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray("data");
            if (data == null) return out;
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id", "");
                if (!id.isEmpty()) out.add(id);
            }
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("回應非 JSON：" + e.getMessage());
        }
    }

    /**
     * 粗篩掉明顯不是對話 LLM 的模型。用黑名單而非白名單，才能相容各家 /models
     * （名稱五花八門）。誤放一兩個沒關係，選錯再換就是。
     */
    private static boolean isLlm(String id) {
        String s = id.toLowerCase(Locale.US);
        String[] block = {"embed", "whisper", "tts", "audio", "dall-e", "dalle", "image",
                "moderation", "rerank", "realtime", "transcribe", "speech", "search", "guard"};
        for (String b : block) {
            if (s.contains(b)) return false;
        }
        return true;
    }

    /** 語音模型篩選：whisper/transcribe 系；Gemini 是通用模型聽音訊，列 gemini 系（黑名單同篩）。 */
    private static boolean isSttModel(String provider, String id) {
        String s = id.toLowerCase(Locale.US);
        if (AiPreferences.PROVIDER_GEMINI.equals(provider)) {
            return s.contains("gemini") && isLlm(id);
        }
        return s.contains("whisper") || s.contains("transcribe");
    }

    // ================= API key 列 =================

    private void bindKeyRow(String provider, int statusId, int editId) {
        refreshKeyStatus(provider, statusId);
        findViewById(editId).setOnClickListener(v -> showKeyDialog(provider, statusId));
    }

    private void refreshKeyStatus(String provider, int statusId) {
        ((TextView) findViewById(statusId)).setText(AiPreferences.hasKey(this, provider)
                ? getString(R.string.ai_key_set) : getString(R.string.ai_key_unset));
    }

    /**
     * 編輯 key 的彈窗：輸入 → 儲存前先打該家 /models 測通（驗 key 有效，免費），
     * 通過才落地；失敗把錯誤顯示在彈窗裡、不關窗。自己接管按鈕才能做到「存失敗不關窗」
     * （AlertDialog 預設按了就 dismiss）。
     */
    private void showKeyDialog(String provider, int statusId) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);

        final EditText keyInput = new EditText(this);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setHint(R.string.ai_key_dialog_hint);
        box.addView(keyInput);

        final TextView status = new TextView(this);
        status.setTextSize(12);
        box.addView(status);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ai_key_dialog_title, displayName(provider)))
                .setView(box)
                .setPositiveButton(R.string.ai_save, null)   // null＝不自動 dismiss，下面接管
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String key = keyInput.getText().toString().trim();
            if (key.isEmpty()) {
                status.setText(R.string.ai_key_empty);
                return;
            }
            status.setText(R.string.ai_key_testing);
            setDialogBusy(dialog, keyInput, true);
            new Thread(() -> {
                try {
                    requestModels(AiPreferences.llmBaseUrl(provider) + "/models", key);
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        AiPreferences.saveKey(this, provider, key);
                        refreshKeyStatus(provider, statusId);
                        // 這家剛有 key＝解析可以改用它（原本可能還停在沒 key 的家）
                        if (!AiPreferences.hasKey(this, llmProvider)) {
                            llmProvider = provider;
                            llmModel = AiPreferences.defaultLlmModel(provider);
                            persistSelections();
                        } else {
                            refreshAllValues();
                        }
                        dialog.dismiss();
                        Toast.makeText(this, R.string.ai_key_saved, Toast.LENGTH_SHORT).show();
                    });
                } catch (final Exception e) {
                    runOnUiThread(() -> {
                        if (isFinishing() || !dialog.isShowing()) return;
                        status.setText(getString(R.string.ai_key_test_failed, e.getMessage()));
                        setDialogBusy(dialog, keyInput, false);
                    });
                }
            }).start();
        });
    }

    private static void setDialogBusy(AlertDialog dialog, EditText input, boolean busy) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
        input.setEnabled(!busy);
    }

    // ================= 浮動鈕大小（疊窗拖拉＋示意圖） =================

    /**
     * 疊窗內拖拉大小，示意鈕就畫在窗上跟著變（比照真鈕的圖示與底色）。
     * 設定頁本身不掛全 App 那顆功能鈕（會套娃，見 AiFloatingButton.EXCLUDED），
     * 所以示意只能自己畫一顆。放手即存，關窗就結束。
     */
    private void showFabSizeDialog() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        final TextView label = new TextView(this);
        box.addView(label);

        final SeekBar seek = new SeekBar(this);
        seek.setMax(AiPreferences.FAB_SIZE_MAX - AiPreferences.FAB_SIZE_MIN);
        box.addView(seek);

        // 示意鈕的容器：固定高度＝最大尺寸，鈕變大變小時窗不會跟著抽動
        FrameLayout previewBox = new FrameLayout(this);
        final ImageView preview = new ImageView(this);
        preview.setImageResource(R.drawable.ic_ai_mic);
        preview.setBackgroundResource(R.drawable.btn_ai_mic_circle);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setClickable(false);      // 只是示意大小，點了沒作用
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(0, 0);
        plp.gravity = Gravity.CENTER;
        previewBox.addView(preview, plp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) ((AiPreferences.FAB_SIZE_MAX + 24) * d));
        box.addView(previewBox, blp);

        final int[] current = {AiPreferences.getFabSizeDp(this)};
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                current[0] = AiPreferences.FAB_SIZE_MIN + progress;
                label.setText(getString(R.string.ai_fab_size, current[0]));
                int size = (int) (current[0] * d);
                int p = (int) (current[0] * 0.25f * d);
                ViewGroup.LayoutParams lp = preview.getLayoutParams();
                lp.width = size;
                lp.height = size;
                preview.setLayoutParams(lp);
                preview.setPadding(p, p, p, p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                AiPreferences.saveFabSizeDp(AiSettingsActivity.this, current[0]);
            }
        });
        seek.setProgress(current[0] - AiPreferences.FAB_SIZE_MIN);
        // setProgress 若與現值相同不會觸發回呼，手動補一次初始繪製
        label.setText(getString(R.string.ai_fab_size, current[0]));
        int size0 = (int) (current[0] * d);
        int pad0 = (int) (current[0] * 0.25f * d);
        plp.width = size0;
        plp.height = size0;
        preview.setLayoutParams(plp);
        preview.setPadding(pad0, pad0, pad0, pad0);

        new AlertDialog.Builder(this)
                .setTitle(R.string.ai_fab_size_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    AiPreferences.saveFabSizeDp(this, current[0]);
                    refreshAllValues();
                })
                .setOnDismissListener(dlg -> refreshAllValues())
                .show();
    }

    /**
     * 請系統把「語音記帳」捷徑釘到桌面。給 launcher 不支援長按 app 圖示拖捷徑的人用
     * （部分機型如此）。requestPinShortcut 需 API 26+，且要 launcher 支援釘選——
     * 不支援時系統直接回 false，這裡給個提示而非當掉。
     */
    private void pinVoiceShortcut() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            Toast.makeText(this, R.string.ai_pin_shortcut_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, R.string.ai_pin_shortcut_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        // 指中繼站而非語音頁：直接指語音頁會拿到「上次離開時的畫面」，理由見 VoiceEntryActivity
        Intent launch = new Intent(this, VoiceEntryActivity.class)
                .setAction(AiInputActivity.ACTION_VOICE_INPUT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "ai_voice_pinned")
                .setShortLabel(getString(R.string.ai_voice_shortcut_short))
                .setLongLabel(getString(R.string.ai_voice_shortcut_long))
                // 走 mipmap 的 adaptive 版（見 mipmap-anydpi-v26/ai_mic_shortcut.xml）：
                // 非 adaptive 的捷徑圖示會被 launcher 縮一號，排在 app 圖示旁邊看起來偏小
                .setIcon(Icon.createWithResource(this, R.mipmap.ai_mic_shortcut))
                .setIntent(launch)
                .build();
        try {
            sm.requestPinShortcut(shortcut, null);
        } catch (Exception e) {
            Toast.makeText(this, R.string.ai_pin_shortcut_unsupported, Toast.LENGTH_LONG).show();
        }
    }
}
