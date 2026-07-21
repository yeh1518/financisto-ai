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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.util.ArrayList;
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

/**
 * AI 設定頁（2026-07-20 重構為 per-provider）。
 *
 * 結構：三家 API key 各自管理（編輯彈窗、**存前必打 /models 測通**）→ 語音辨識
 * （內建/雲端 provider 選單＋模型）→ AI 解析（provider 選單＋模型）。
 * 選單只列「已有 key」的 provider——沒 key 的選了也不能用，不如不出現。
 * key 由彈窗即存；provider/模型選擇由底部「儲存」寫入。
 *
 * 刻意用純 Activity + 手排 widget，不掛 androidx PreferenceFragment（理由同前版：
 * 避開 framework Material 主題缺 preferenceTheme 的崩潰，並完全掌控 key 的加密落地）。
 */
public class AiSettingsActivity extends ComponentActivity {

    private Spinner sttProviderSpinner;
    private Spinner llmProviderSpinner;
    private EditText sttModelText;
    private EditText llmModelText;
    private View sttModelRow;

    /** 各 spinner 目前列出的 provider id（與顯示名同 index；LLM 無可用時為空清單）。 */
    private final List<String> sttProviderIds = new ArrayList<>();
    private final List<String> llmProviderIds = new ArrayList<>();

    /** 上一次選中的 provider——換 provider 才帶預設模型，初始復原不動使用者存的模型。 */
    private String lastSttProvider;
    private String lastLlmProvider;

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

        sttProviderSpinner = findViewById(R.id.ai_stt_provider_spinner);
        llmProviderSpinner = findViewById(R.id.ai_llm_provider_spinner);
        sttModelText = findViewById(R.id.ai_stt_model);
        llmModelText = findViewById(R.id.ai_model);
        sttModelRow = findViewById(R.id.ai_stt_model_row);

        bindKeyRow(AiPreferences.PROVIDER_GEMINI, R.id.ai_key_status_gemini, R.id.ai_key_edit_gemini);
        bindKeyRow(AiPreferences.PROVIDER_GROQ, R.id.ai_key_status_groq, R.id.ai_key_edit_groq);
        bindKeyRow(AiPreferences.PROVIDER_OPENAI, R.id.ai_key_status_openai, R.id.ai_key_edit_openai);

        AiPreferences prefs = AiPreferences.load(this);
        lastSttProvider = prefs.getSttProvider();
        lastLlmProvider = prefs.getLlmProvider();
        sttModelText.setText(prefs.isCloudStt() ? prefs.getSttModel() : "");
        llmModelText.setText(prefs.getModel());
        rebuildProviderSpinners();

        findViewById(R.id.ai_stt_fetch_models_button).setOnClickListener(v -> fetchModels(true));
        findViewById(R.id.ai_fetch_models_button).setOnClickListener(v -> fetchModels(false));
        ((Button) findViewById(R.id.ai_save_button)).setOnClickListener(v -> save());
        findViewById(R.id.ai_log_button).setOnClickListener(v ->
                startActivity(new Intent(this, AiLogActivity.class)));
        findViewById(R.id.ai_pin_shortcut_button).setOnClickListener(v -> pinVoiceShortcut());

        Switch shortcutEntersAppSwitch = findViewById(R.id.ai_shortcut_enters_app);
        shortcutEntersAppSwitch.setChecked(AiPreferences.isShortcutEntersApp(this));
        shortcutEntersAppSwitch.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                AiPreferences.saveShortcutEntersApp(this, checked));

        setupFabSizeSeek();
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

    private static String displayName(String provider) {
        switch (provider) {
            case AiPreferences.PROVIDER_GEMINI: return "Google AI";
            case AiPreferences.PROVIDER_GROQ: return "Groq";
            case AiPreferences.PROVIDER_OPENAI: return "OpenAI";
            default: return provider;
        }
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
                        rebuildProviderSpinners();
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

    // ================= provider 選單 =================

    /**
     * 重建兩個 provider 選單：STT＝內建＋已有 key 的家；LLM＝已有 key 的家
     * （沒半家就放「先設定 key」占位並鎖住）。key 新存成功後即時重建。
     */
    private void rebuildProviderSpinners() {
        // --- STT ---
        sttProviderIds.clear();
        List<String> sttNames = new ArrayList<>();
        sttProviderIds.add(AiPreferences.PROVIDER_SYSTEM);
        sttNames.add(getString(R.string.ai_stt_builtin));
        for (String p : AiPreferences.CLOUD_PROVIDERS) {
            if (AiPreferences.hasKey(this, p)) {
                sttProviderIds.add(p);
                sttNames.add(displayName(p));
                // Gemini / OpenAI 是通用模型，多一個「辨識＋解析一次到位」（跳過兩段串接、更快）
                if (AiPreferences.PROVIDER_GEMINI.equals(p)) {
                    sttProviderIds.add(AiPreferences.PROVIDER_GEMINI_DIRECT);
                    sttNames.add(getString(R.string.ai_stt_gemini_direct));
                } else if (AiPreferences.PROVIDER_OPENAI.equals(p)) {
                    sttProviderIds.add(AiPreferences.PROVIDER_OPENAI_DIRECT);
                    sttNames.add(getString(R.string.ai_stt_openai_direct));
                }
            }
        }
        setupSpinner(sttProviderSpinner, sttNames, sttProviderIds.indexOf(lastSttProvider),
                pos -> onSttProviderPicked(sttProviderIds.get(pos)));
        onSttProviderVisibility(lastSttProvider);

        // --- LLM ---
        llmProviderIds.clear();
        List<String> llmNames = new ArrayList<>();
        for (String p : AiPreferences.CLOUD_PROVIDERS) {
            if (AiPreferences.hasKey(this, p)) {
                llmProviderIds.add(p);
                llmNames.add(displayName(p));
            }
        }
        if (llmProviderIds.isEmpty()) {
            llmNames.add(getString(R.string.ai_no_provider_key));
            setupSpinner(llmProviderSpinner, llmNames, 0, null);
            llmProviderSpinner.setEnabled(false);
        } else {
            llmProviderSpinner.setEnabled(true);
            setupSpinner(llmProviderSpinner, llmNames, llmProviderIds.indexOf(lastLlmProvider),
                    pos -> onLlmProviderPicked(llmProviderIds.get(pos)));
        }
    }

    private interface OnPicked { void pick(int position); }

    private void setupSpinner(Spinner spinner, List<String> names, int selectIndex, OnPicked onPicked) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, selectIndex));
        spinner.setOnItemSelectedListener(onPicked == null ? null : new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onPicked.pick(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** 換 STT provider：帶入該家預設模型（同 provider 的初始回呼不動使用者存的值）。 */
    private void onSttProviderPicked(String provider) {
        if (!provider.equals(lastSttProvider)) {
            lastSttProvider = provider;
            sttModelText.setText(AiPreferences.defaultSttModel(provider));
        }
        onSttProviderVisibility(provider);
    }

    private void onSttProviderVisibility(String provider) {
        // 內建沒有模型；一次到位的模型固定（Gemini 跟解析區走、OpenAI 用 audio-preview），皆不顯示模型列
        boolean showModel = !AiPreferences.PROVIDER_SYSTEM.equals(provider)
                && !AiPreferences.PROVIDER_GEMINI_DIRECT.equals(provider)
                && !AiPreferences.PROVIDER_OPENAI_DIRECT.equals(provider);
        sttModelRow.setVisibility(showModel ? View.VISIBLE : View.GONE);
    }

    private void onLlmProviderPicked(String provider) {
        if (!provider.equals(lastLlmProvider)) {
            lastLlmProvider = provider;
            llmModelText.setText(AiPreferences.defaultLlmModel(provider));
        }
    }

    // ================= 模型清單 =================

    /**
     * 抓當前選中 provider 的 /models 清單讓使用者用選的。
     * @param forStt true＝語音模型（whisper/transcribe 系；Gemini 全模型皆可聽音訊，列 gemini 系），
     *               false＝解析模型（黑名單粗篩掉非 LLM）。
     */
    private void fetchModels(boolean forStt) {
        final String provider = forStt
                ? sttProviderIds.get(sttProviderSpinner.getSelectedItemPosition())
                : (llmProviderIds.isEmpty() ? null
                        : llmProviderIds.get(llmProviderSpinner.getSelectedItemPosition()));
        if (provider == null || AiPreferences.PROVIDER_SYSTEM.equals(provider)) {
            Toast.makeText(this, R.string.ai_no_provider_key, Toast.LENGTH_SHORT).show();
            return;
        }
        final String key = AiPreferences.getKey(this, provider);
        if (TextUtils.isEmpty(key)) {
            Toast.makeText(this, R.string.ai_no_provider_key, Toast.LENGTH_SHORT).show();
            return;
        }
        final String url = AiPreferences.llmBaseUrl(provider) + "/models";

        Toast.makeText(this, R.string.ai_models_loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<String> all = requestModels(url, key);
                List<String> models = new ArrayList<>();
                for (String id : all) {
                    String clean = stripModelsPrefix(id);
                    boolean ok = forStt ? isSttModel(provider, clean) : isLlm(clean);
                    if (ok) models.add(clean);
                }
                Collections.sort(models);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (models.isEmpty()) {
                        Toast.makeText(this, R.string.ai_models_empty, Toast.LENGTH_LONG).show();
                    } else {
                        showModelPicker(models, forStt ? sttModelText : llmModelText);
                    }
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
     * （名稱五花八門）。誤放一兩個沒關係，使用者仍可手動改。
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

    private void showModelPicker(List<String> models, EditText target) {
        String[] arr = models.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.ai_models_pick)
                .setItems(arr, (dialog, which) -> target.setText(arr[which]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ================= 其他既有設定 =================

    private ImageView fabPreview;

    /**
     * 浮動語音鈕大小：SeekBar 值域 [MIN, MAX]，即時更新標籤、放手才存。
     * 設定頁本身**不掛**全 App 那顆功能鈕（會套娃，見 AiFloatingButton.EXCLUDED），
     * 改在調整大小時顯示一顆**只示意大小、點了沒作用**的預覽鈕（樣式/位置比照真的那顆）。
     */
    private void setupFabSizeSeek() {
        TextView label = findViewById(R.id.ai_fab_size_label);
        SeekBar seek = findViewById(R.id.ai_fab_size_seek);
        seek.setMax(AiPreferences.FAB_SIZE_MAX - AiPreferences.FAB_SIZE_MIN);
        int cur = AiPreferences.getFabSizeDp(this);
        seek.setProgress(cur - AiPreferences.FAB_SIZE_MIN);
        label.setText(getString(R.string.ai_fab_size, cur));
        ensureFabPreview();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int dp = AiPreferences.FAB_SIZE_MIN + progress;
                label.setText(getString(R.string.ai_fab_size, dp));
                showFabPreview(dp);          // 改大小才出現、示意大小
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                showFabPreview(AiPreferences.FAB_SIZE_MIN + s.getProgress());
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                AiPreferences.saveFabSizeDp(AiSettingsActivity.this,
                        AiPreferences.FAB_SIZE_MIN + s.getProgress());
            }
        });
    }

    /** 建立示意用預覽鈕（半透明、右下角、比照真鈕樣式；不可點）。 */
    private void ensureFabPreview() {
        if (fabPreview != null) return;
        float d = getResources().getDisplayMetrics().density;
        fabPreview = new ImageView(this);
        fabPreview.setImageResource(R.drawable.ic_ai_mic);
        fabPreview.setBackgroundResource(R.drawable.btn_ai_mic_circle);
        fabPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fabPreview.setAlpha(0.6f);
        fabPreview.setClickable(false);      // 只是示意大小、點了沒作用（觸控穿透）
        fabPreview.setFocusable(false);
        fabPreview.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, 0);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = (int) (16 * d);
        lp.bottomMargin = (int) (88 * d);    // 同真鈕：在下排工具列上方
        addContentView(fabPreview, lp);
    }

    private void showFabPreview(int sizeDp) {
        if (fabPreview == null) return;
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (sizeDp * d);
        int pad = (int) (sizeDp * 0.25f * d);
        ViewGroup.LayoutParams lp = fabPreview.getLayoutParams();
        lp.width = size;
        lp.height = size;
        fabPreview.setLayoutParams(lp);
        fabPreview.setPadding(pad, pad, pad, pad);
        fabPreview.setVisibility(View.VISIBLE);
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
        Intent launch = new Intent(this, AiInputActivity.class)
                .setAction(AiInputActivity.ACTION_VOICE_INPUT);
        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "ai_voice_pinned")
                .setShortLabel(getString(R.string.ai_voice_shortcut_short))
                .setLongLabel(getString(R.string.ai_voice_shortcut_long))
                .setIcon(Icon.createWithResource(this, R.drawable.ic_ai_mic_shortcut))
                .setIntent(launch)
                .build();
        try {
            sm.requestPinShortcut(shortcut, null);
        } catch (Exception e) {
            Toast.makeText(this, R.string.ai_pin_shortcut_unsupported, Toast.LENGTH_LONG).show();
        }
    }

    /** provider/模型選擇落地（key 不歸這裡管，彈窗即存）。 */
    private void save() {
        String sttProvider = sttProviderIds.get(sttProviderSpinner.getSelectedItemPosition());
        String sttModel = sttModelText.getText().toString().trim();
        if (sttModel.isEmpty()) sttModel = AiPreferences.defaultSttModel(sttProvider);

        // LLM 沒半家有 key＝維持原設定不動（占位項不能存）
        String llmProvider;
        String llmModel;
        if (llmProviderIds.isEmpty()) {
            AiPreferences prefs = AiPreferences.load(this);
            llmProvider = prefs.getLlmProvider();
            llmModel = prefs.getModel();
        } else {
            llmProvider = llmProviderIds.get(llmProviderSpinner.getSelectedItemPosition());
            llmModel = llmModelText.getText().toString().trim();
            if (llmModel.isEmpty()) llmModel = AiPreferences.defaultLlmModel(llmProvider);
        }

        AiPreferences.saveSelections(this, sttProvider, sttModel, llmProvider, llmModel);
        Toast.makeText(this, R.string.ai_settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
