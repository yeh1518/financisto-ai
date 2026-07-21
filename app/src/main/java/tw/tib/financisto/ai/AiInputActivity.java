package tw.tib.financisto.ai;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tw.tib.financisto.R;
import tw.tib.financisto.activity.AbstractTransactionActivity;
import tw.tib.financisto.activity.MainActivity;
import tw.tib.financisto.activity.TransactionActivity;
import tw.tib.financisto.activity.TransferActivity;
import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.model.Account;
import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.Transaction;

import java.util.List;

/**
 * AI 一句話記帳輸入頁（功能 1）。
 *
 * 語音走**標準 Google 元件** {@link RecognizerIntent}：斷句、靜音判斷、錄音中動畫全交給
 * 系統的語音輸入彈窗，app 不自己維護辨識狀態機。自搓的跨重啟累積版試過幾輪都會在「停久被
 * 中斷、再按繼續」時蓋掉前文，索性回歸標準（Gary 定案 2026-07-16）。
 *
 * 每按一次麥克風＝一段辨識，回來的文字**接在輸入框現有內容後面**（游標移到最後），
 * 所以接著講會自然累加、永不覆蓋。是否辨識完直接送解析由畫面上的開關決定（記在 prefs）。
 *
 * 送解析後：一句話 → OpenAI 解析（背景執行緒）→ 寫一筆 draft template → 用 duplicate
 * prefill 拉起原生 {@link TransactionActivity}（繼承全部驗證/存檔）→ 回來刪 draft。
 */
public class AiInputActivity extends ComponentActivity {

    /** 由 Blotter 傳入的預設帳戶（帳戶 blotter 時），模型解析不出帳戶時當退路。 */
    public static final String ACCOUNT_ID_EXTRA = "accountId";
    /** true = 一進畫面就直接開語音辨識（點麥克風圖示的入口用）。 */
    public static final String START_VOICE_EXTRA = "startVoice";
    /**
     * 桌面捷徑用的 action（見 xml/shortcuts.xml 與設定頁的「加語音捷徑到桌面」）。
     * 走 action 而非 extra：shortcuts.xml 的 &lt;intent&gt; 不支援自訂 extra，
     * 而 app 內部啟動一律用不帶 action 的 explicit intent，兩者天然分得開。
     */
    public static final String ACTION_VOICE_INPUT = "tw.tib.financisto.ai.action.VOICE_INPUT";

    private static final int REQ_CONFIRM = 3001;
    private static final int REQ_VOICE = 3002;
    private static final int REQ_MIC_PERMISSION = 3003;

    private DatabaseAdapter db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private EditText inputText;
    private Button parseButton;
    private ProgressBar progress;
    private TextView statusText;
    private Switch autoSendSwitch;

    // --- 雲端模式就地錄音狀態（內建模式用不到這些） ---
    private ImageButton voiceButton;
    private TextView voiceLabel;
    private Button voiceCancelButton;
    private Button voiceRetryButton;
    private WavAudioRecorder recorder;
    private java.io.File recWav;
    private java.io.File retryWav;      // 辨識/解析失敗保留的錄音，「重試」重送
    private boolean recording = false;
    private boolean voiceWorking = false;
    private long recStartedAt;
    private final android.os.Handler recHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 麥克風動畫的縮放基準（閒置與動畫起點都用它，開錄不會突然縮一下）與峰值擺幅。 */
    private static final float MIC_BASE_SCALE = 0.7f;
    private static final float MIC_PULSE_RANGE = 0.55f;
    /** 錄音動畫的平滑後縮放值（低通，免得跟著峰值抖）。 */
    private float recPulse = MIC_BASE_SCALE;
    private final Runnable recTicker = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long sec = (System.currentTimeMillis() - recStartedAt) / 1000;
            voiceLabel.setText(getString(R.string.ai_voice_recording_label,
                    String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60)));
            float target = MIC_BASE_SCALE
                    + (recorder != null ? recorder.getAmplitude() / 32767f : 0f) * MIC_PULSE_RANGE;
            recPulse += (target - recPulse) * 0.5f;
            voiceButton.setScaleX(recPulse);
            voiceButton.setScaleY(recPulse);
            recHandler.postDelayed(this, 80);
        }
    };

    private long defaultAccountId = -1;
    private long pendingDraftId = -1;
    /** 由桌面捷徑（ACTION_VOICE_INPUT）啟動＝可套用「完成後進 App」的收尾路由。 */
    private boolean launchedFromShortcut = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_input);

        db = new DatabaseAdapter(this);
        db.open();

        defaultAccountId = getIntent().getLongExtra(ACCOUNT_ID_EXTRA, -1);

        applyWindowInsets(findViewById(R.id.ai_root));

        inputText = findViewById(R.id.ai_input_text);
        parseButton = findViewById(R.id.ai_parse_button);
        progress = findViewById(R.id.ai_progress);
        statusText = findViewById(R.id.ai_status);
        autoSendSwitch = findViewById(R.id.ai_auto_send);

        autoSendSwitch.setChecked(AiPreferences.isAutoSend(this));
        autoSendSwitch.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                AiPreferences.saveAutoSend(this, checked));

        parseButton.setOnClickListener(v -> onParse());
        findViewById(R.id.ai_settings_button).setOnClickListener(v ->
                startActivity(new Intent(this, AiSettingsActivity.class)));
        voiceButton = findViewById(R.id.ai_voice_button);
        voiceLabel = findViewById(R.id.ai_voice_label);
        voiceCancelButton = findViewById(R.id.ai_voice_cancel);
        voiceRetryButton = findViewById(R.id.ai_voice_retry);
        // 閒置麥克風就顯示成動畫基準大小，開錄不會突然縮一下（Gary 調校 2026-07-20）
        voiceButton.setScaleX(MIC_BASE_SCALE);
        voiceButton.setScaleY(MIC_BASE_SCALE);
        voiceButton.setOnClickListener(v -> startVoice());
        voiceCancelButton.setOnClickListener(v -> cancelRecording());
        voiceRetryButton.setOnClickListener(v -> {
            if (retryWav != null && !voiceWorking) {
                voiceRetryButton.setVisibility(View.GONE);
                java.io.File wav = retryWav;
                retryWav = null;
                processVoice(AiPreferences.load(this), wav);
            }
        });
        findViewById(R.id.ai_input_clear).setOnClickListener(v -> {
            inputText.setText("");
            statusText.setText("");
            inputText.requestFocus();
        });

        launchedFromShortcut = ACTION_VOICE_INPUT.equals(getIntent().getAction());

        // 捷徑啟動時攔返回鍵，才能把「返回＝回 App 主畫面」的收尾路由套上（見 finishAndRoute）。
        // 非捷徑（App 內啟動）不攔：預設 finish 就回到原本的呼叫畫面，本來就在 App 裡。
        if (launchedFromShortcut) {
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() { finishAndRoute(false); }
            });
        }

        // 麥克風入口 / 桌面捷徑進來的：直接開語音彈窗
        boolean startVoice = getIntent().getBooleanExtra(START_VOICE_EXTRA, false) || launchedFromShortcut;
        if (savedInstanceState == null && startVoice) {
            startVoice();
        }

        // 測試掛鉤（僅 debug）：帶 ai_test_text 就直接解析並寫進 AiLog，不彈任何 UI。
        // 給自動化模型/prompt 對照用——adb input 打不了中文，改用 intent 的 --es 傳 CJK。
        if (tw.tib.financisto.BuildConfig.DEBUG) {
            String testText = getIntent().getStringExtra("ai_test_text");
            if (!TextUtils.isEmpty(testText)) {
                runTestParse(testText);
            }
        }
    }

    /** debug 測試掛鉤：只跑解析 + 記 log，不預填、不啟動任何畫面。 */
    private void runTestParse(String text) {
        final AiPreferences prefs = AiPreferences.load(this);
        if (!prefs.isConfigured()) return;
        executor.execute(() -> {
            try {
                EntityContextBuilder ctx = EntityContextBuilder.build(db);
                new BookkeepingParser(prefs, ctx, getApplicationContext()).parse(text);
            } catch (Exception ignored) {}
        });
    }

    /**
     * 收尾。捷徑啟動且設定為「完成後進 App」時，導到 App 對應畫面而非回桌面：
     * completed＝交易列表(blotter, tab 1)、未完成＝主畫面(accounts, tab 0)。
     * 其餘情況（App 內啟動、或設定為回桌面）就單純 finish。
     */
    private void finishAndRoute(boolean completed) {
        if (launchedFromShortcut && AiPreferences.isShortcutEntersApp(this)) {
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra(MainActivity.GO_TO_SCREEN, completed ? 1 : 0);
            startActivity(i);
        }
        finish();
    }

    /**
     * targetSdk 35+ 在 Android 15 起強制 edge-to-edge：不吃 insets 的話，內容會被
     * 畫到狀態列/ActionBar 底下（實機上表現為文字往上疊到標題列）。
     * 若 decor 已消化過 insets，這裡收到的就是 0，不會重複加 padding。
     */
    private void applyWindowInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /** 給全 App 浮動鈕（AiFloatingButton）在本頁呼叫＝啟動麥克風。 */
    public void startVoiceFromFab() {
        startVoice();
    }

    /**
     * 語音入口：雲端模式＝就地錄音（點麥克風開錄/完成，不疊視窗）；
     * 內建模式＝標準 Google 語音彈窗。
     */
    private void startVoice() {
        AiPreferences prefs = AiPreferences.load(this);
        if (prefs.isCloudStt()) {
            toggleInlineVoice(prefs);
            return;
        }
        startSystemVoice();
    }

    // ---------------- 雲端模式：就地錄音（不疊視窗，Gary 定案 2026-07-20） ----------------

    /**
     * 點大麥克風的雲端行為：閒置→開錄（轉紅＋隨音量縮放＋計時）；錄音中→完成、送辨識/解析。
     * 辨識/解析中點了不動作。
     */
    private void toggleInlineVoice(AiPreferences prefs) {
        if (voiceWorking) return;
        if (recording) {
            finishRecording(prefs);
        } else {
            startInlineRecording(prefs);
        }
    }

    private void startInlineRecording(AiPreferences prefs) {
        if (!prefs.isCloudSttConfigured()) {
            Toast.makeText(this, R.string.ai_stt_not_configured, Toast.LENGTH_LONG).show();
            return;
        }
        if (!VoiceCaptureActivity.hasMicPermission(this)) {
            VoiceCaptureActivity.requestMicPermission(this, REQ_MIC_PERMISSION);
            return;
        }
        discardRetryWav();      // 開新錄音＝放棄上一段失敗保留的
        recWav = new java.io.File(getCacheDir(), "stt_" + System.currentTimeMillis() + ".wav");
        recorder = new WavAudioRecorder(recWav);
        try {
            recorder.start();
        } catch (java.io.IOException e) {
            Toast.makeText(this, getString(R.string.ai_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        recording = true;
        RecordCue.playStart();
        voiceButton.setSelected(true);          // 錄音中＝紅（btn_ai_mic_circle 的 state_selected）
        voiceCancelButton.setVisibility(View.VISIBLE);
        voiceRetryButton.setVisibility(View.GONE);
        statusText.setText("");
        recStartedAt = System.currentTimeMillis();
        recHandler.post(recTicker);
    }

    private void finishRecording(AiPreferences prefs) {
        stopRecordingUi();
        try {
            recorder.stop();
        } catch (java.io.IOException e) {
            Toast.makeText(this, getString(R.string.ai_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        processVoice(prefs, recWav);
    }

    private void cancelRecording() {
        stopRecordingUi();
        if (recorder != null) recorder.cancel();
        statusText.setText("");
    }

    /** 收錄音狀態的共同 UI 復原（紅圈/縮放/取消鈕/計時 label）。 */
    private void stopRecordingUi() {
        recording = false;
        recPulse = MIC_BASE_SCALE;
        voiceButton.setSelected(false);
        voiceButton.setScaleX(MIC_BASE_SCALE);
        voiceButton.setScaleY(MIC_BASE_SCALE);
        voiceLabel.setText(R.string.ai_voice_tap_to_talk);
        voiceCancelButton.setVisibility(View.INVISIBLE);
    }

    /**
     * 錄好的 WAV 送後端：一次到位＝音檔直接進解析、轉寫進輸入框、直接預填表單；
     * 純轉寫＝文字回輸入框、走與標準路徑相同的自動送出。失敗保留錄音檔給「重試」
     * 重送同一段（長語句因網路失敗白講最傷）；重新點麥克風＝重講（丟掉保留檔）。
     */
    private void processVoice(AiPreferences prefs, java.io.File wav) {
        voiceWorking = true;
        setBusy(true);
        voiceButton.setEnabled(false);
        voiceButton.setAlpha(0.4f);
        final boolean direct = prefs.isDirectVoiceParse();
        statusText.setText(direct ? getString(R.string.ai_parsing) : getString(R.string.ai_rec_transcribing));
        executor.execute(() -> {
            try {
                if (direct) {
                    EntityContextBuilder ctx = EntityContextBuilder.build(db);
                    BookkeepingParser.AudioParseResult r =
                            new BookkeepingParser(prefs, ctx, getApplicationContext())
                                    .parseAudio(wav, false, null);
                    runOnUiThread(() -> {
                        finishVoiceWork();
                        wav.delete();
                        appendVoiceText(r.transcript);   // 轉寫留框：看得到聽成什麼、要改字重送也行
                        statusText.setText(R.string.ai_recognized);
                        launchPrefill(r.transaction, ctx);
                    });
                } else {
                    final String text = new SpeechTranscriber(prefs).transcribe(wav);
                    runOnUiThread(() -> {
                        finishVoiceWork();
                        wav.delete();
                        appendVoiceText(text);
                        if (AiPreferences.isAutoSend(this)) {
                            onParse();
                        } else {
                            statusText.setText("");
                            inputText.requestFocus();
                        }
                    });
                }
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    finishVoiceWork();
                    statusText.setText(getString(R.string.ai_failed, e.getMessage()));
                    retryWav = wav;
                    voiceRetryButton.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void finishVoiceWork() {
        voiceWorking = false;
        setBusy(false);
        voiceButton.setEnabled(true);
        voiceButton.setAlpha(1f);
    }

    private void discardRetryWav() {
        if (retryWav != null) {
            retryWav.delete();
            retryWav = null;
        }
        voiceRetryButton.setVisibility(View.GONE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startVoice();
            } else {
                Toast.makeText(this, R.string.ai_stt_need_mic, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 開標準 Google 語音輸入彈窗；回來在 onActivityResult 接文字。 */
    private void startSystemVoice() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ai_voice_prompt));
        // 加長等待（標準 hint；Google 可能無視，尤其 COMPLETE_SILENCE）。min 6s＝不會太早截，
        // 靜音 5s＝停頓思考不被切。代價：講完也會多等幾秒才收工。想微調可日後做成設定。
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6000);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000);
        try {
            startActivityForResult(intent, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.ai_voice_unavailable, Toast.LENGTH_LONG).show();
            inputText.requestFocus();
        }
    }

    /** 把辨識到的文字接到輸入框現有內容後面（游標移到最後），永不覆蓋。 */
    private void appendVoiceText(String text) {
        if (TextUtils.isEmpty(text)) return;
        String existing = inputText.getText().toString();
        String combined = TextUtils.isEmpty(existing) ? text : existing + " " + text;
        inputText.setText(combined);
        inputText.setSelection(combined.length());
    }

    @Override
    protected void onResume() {
        super.onResume();
        AiPreferences prefs = AiPreferences.load(this);
        if (!prefs.isConfigured()) {
            statusText.setText(R.string.ai_not_configured);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 離開畫面（點設定/返回/切走）時就取消錄音——不在背景偷錄，也切斷
        // 「設定→浮動鈕→又開一個錄音畫面」無限套娃的其中一環。
        if (recording) {
            cancelRecording();
        }
    }

    private void onParse() {
        final String text = inputText.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, R.string.ai_empty_input, Toast.LENGTH_SHORT).show();
            return;
        }
        final AiPreferences prefs = AiPreferences.load(this);
        if (!prefs.isConfigured()) {
            Toast.makeText(this, R.string.ai_not_configured, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, AiSettingsActivity.class));
            return;
        }

        setBusy(true);
        statusText.setText(R.string.ai_parsing);

        executor.execute(() -> {
            try {
                EntityContextBuilder ctx = EntityContextBuilder.build(db);
                BookkeepingParser parser = new BookkeepingParser(prefs, ctx, getApplicationContext());
                ParsedTransaction result = parser.parse(text);
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText(R.string.ai_recognized);
                    launchPrefill(result, ctx);
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText(getString(R.string.ai_failed, e.getMessage()));
                });
            }
        });
    }

    /** 依解析出的型別，分派到對應的原生畫面。 */
    private void launchPrefill(ParsedTransaction t, EntityContextBuilder ctx) {
        long accountId = t.account.resolved() ? t.account.id
                : (ctx.validAccountIds.contains(defaultAccountId) ? defaultAccountId : 0);

        if (t.isBalance()) {
            launchBalanceAdjust(t, ctx, accountId);
        } else {
            launchDraftShuttle(t, ctx, accountId);
        }
    }

    /**
     * 把解析出的 splits 轉成 split 子交易（每份一筆）。金額不明的份直接略過。
     * 正負號依父交易型別（income 為正、其餘為負）；帳戶/日期等由 DB 的 insertSplits 從父補齊。
     */
    private List<Transaction> buildSplitChildren(ParsedTransaction t, EntityContextBuilder ctx, long accountId) {
        java.util.List<Transaction> children = new java.util.ArrayList<>();
        boolean income = t.isIncome();
        for (ParsedTransaction.Split s : t.splits) {
            if (s.amount == null) continue;                 // 金額不明＝湊不成有效份
            Transaction c = new Transaction();
            c.fromAccountId = accountId;
            long minor = toMinorUnits(s.amount, accountId, ctx);
            c.fromAmount = income ? minor : -minor;
            if (s.category.resolved()) c.categoryId = s.category.id;
            if (!TextUtils.isEmpty(s.note)) c.note = s.note;
            children.add(c);
        }
        return children;
    }

    /** 金額主單位 → minor units（依該帳戶幣別 scale；帳戶未知退 2）。 */
    private long toMinorUnits(double majorAmount, long accountId, EntityContextBuilder ctx) {
        int scale = accountId > 0 && ctx.accountScale.containsKey(accountId)
                ? ctx.accountScale.get(accountId) : 2;
        return Math.round(Math.abs(majorAmount) * Math.pow(10, scale));
    }

    /**
     * 「中信剩下300」→ 原生的調整餘額模式：帶入目前餘額 + 講出來的新餘額，
     * 差額由 TransactionActivity 自己算。這條路不需要 draft row。
     */
    private void launchBalanceAdjust(ParsedTransaction t, EntityContextBuilder ctx, long accountId) {
        if (accountId <= 0) {
            statusText.setText(R.string.ai_balance_need_account);
            return;
        }
        Account account = db.getAccount(accountId);
        if (account == null) {
            statusText.setText(R.string.ai_balance_need_account);
            return;
        }
        Intent intent = new Intent(this, TransactionActivity.class);
        intent.putExtra(AbstractTransactionActivity.ACCOUNT_ID_EXTRA, accountId);
        intent.putExtra(TransactionActivity.CURRENT_BALANCE_EXTRA, account.totalAmount);
        putDateTimeIfSpoken(intent, t);
        if (t.amount != null) {
            // 餘額可能是負的（如信用卡），這裡不取絕對值後硬給正號
            long newBalance = toMinorUnits(t.amount, accountId, ctx);
            intent.putExtra(TransactionActivity.NEW_BALANCE_EXTRA, t.amount < 0 ? -newBalance : newBalance);
        }
        startActivityForResult(intent, REQ_CONFIRM);
    }

    /**
     * expense / income / transfer：寫一筆 draft template，用 duplicate prefill 拉起
     * 原生編輯頁（繼承全部驗證/存檔），回來再刪 draft。
     */
    private void launchDraftShuttle(ParsedTransaction t, EntityContextBuilder ctx, long accountId) {
        Transaction draft = new Transaction();
        draft.isTemplate = 1;
        Long spoken = t.resolveDateTimeMillis();
        draft.dateTime = spoken != null ? spoken : System.currentTimeMillis();
        draft.fromAccountId = accountId;

        boolean isTransfer = t.isTransfer() && t.toAccount.resolved();
        if (isTransfer) {
            draft.toAccountId = t.toAccount.id;
        }

        // 專案/備註不受分割影響，先套
        if (t.project.resolved()) draft.projectId = t.project.id;
        if (!TextUtils.isEmpty(t.note)) draft.note = t.note;

        // 分割：一筆拆多個分類（只用於 expense/income，不用於 transfer）。有有效份就走 SPLIT 父交易。
        List<Transaction> splitChildren = (!isTransfer && t.hasSplits())
                ? buildSplitChildren(t, ctx, accountId) : null;

        if (splitChildren != null && !splitChildren.isEmpty()) {
            draft.categoryId = Category.SPLIT_CATEGORY_ID;
            draft.splits = splitChildren;
            long total = 0;
            for (Transaction c : splitChildren) total += c.fromAmount;
            draft.fromAmount = total;             // 父金額＝各份加總，保證 unsplit=0
        } else {
            if (t.amount != null) {
                long minor = toMinorUnits(t.amount, accountId, ctx);
                if (isTransfer) {
                    // 慣例（由真實資料驗證）：轉出為負、轉入為正
                    draft.fromAmount = -minor;
                    draft.toAmount = toMinorUnits(t.amount, draft.toAccountId, ctx);
                } else {
                    // 只有明確 income 才正號。⚠️ 不能用 isExpense()：type=transfer 但轉入帳戶
                    // 沒對到清單時會走到這裡（退成一般交易），isExpense() 為 false → 誤成收入號。
                    draft.fromAmount = t.isIncome() ? minor : -minor;
                }
            }
            if (t.category.resolved()) draft.categoryId = t.category.id;
        }

        try {
            pendingDraftId = db.insertOrUpdate(draft);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.ai_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this,
                isTransfer ? TransferActivity.class : TransactionActivity.class);
        intent.putExtra(AbstractTransactionActivity.TRAN_ID_EXTRA, pendingDraftId);
        intent.putExtra(AbstractTransactionActivity.DUPLICATE_EXTRA, true);
        // 強制非 template（draft 本身是 template，不加這行會讓存出的正式交易也變 template）
        intent.putExtra(AbstractTransactionActivity.TEMPLATE_EXTRA, 0);
        putDateTimeIfSpoken(intent, t);
        startActivityForResult(intent, REQ_CONFIRM);
    }

    /**
     * 有講日期/時間才帶 DATETIME_EXTRA。沒講就不帶＝維持原本「用記帳當下時間」。
     * ⚠️ draft 自己的 dateTime 不夠用：duplicate prefill 會把它重設，只有這個 extra 壓得住
     * （見 AbstractTransactionActivity 的 isDuplicate 分支）。
     */
    private void putDateTimeIfSpoken(Intent intent, ParsedTransaction t) {
        Long spoken = t.resolveDateTimeMillis();
        if (spoken != null) {
            intent.putExtra(AbstractTransactionActivity.DATETIME_EXTRA, (long) spoken);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty() && !TextUtils.isEmpty(r.get(0))) {
                    appendVoiceText(r.get(0));
                    // 開關開了才辨識完直接送；否則停在文字，讓使用者接著講或按解析
                    if (AiPreferences.isAutoSend(this)) {
                        onParse();
                    } else {
                        inputText.requestFocus();
                    }
                    return;
                }
            }
            // 沒辨識出東西：提示一下，使用者自己再按一次（Gary 的第 1 點）
            Toast.makeText(this, R.string.ai_voice_nothing, Toast.LENGTH_SHORT).show();
            inputText.requestFocus();
            return;
        }
        if (requestCode == REQ_CONFIRM) {
            cleanupDraft();
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, R.string.ai_saved, Toast.LENGTH_SHORT).show();
                finishAndRoute(true);
            } else {
                // 使用者取消：留在本頁，可改字重試
                statusText.setText("");
                inputText.selectAll();
            }
        }
    }

    private void cleanupDraft() {
        if (pendingDraftId > 0) {
            try {
                db.deleteTransaction(pendingDraftId);
            } catch (Exception ignored) {}
            pendingDraftId = -1;
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        parseButton.setEnabled(!busy);
        inputText.setEnabled(!busy);
    }

    @Override
    protected void onDestroy() {
        if (recording && recorder != null) recorder.cancel();
        discardRetryWav();
        cleanupDraft();
        executor.shutdownNow();
        if (db != null) db.close();
        super.onDestroy();
    }
}
