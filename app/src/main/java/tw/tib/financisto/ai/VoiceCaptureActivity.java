package tw.tib.financisto.ai;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import tw.tib.financisto.R;

/**
 * 補充模式的雲端錄音頁——與主語音頁同款大麥克風，**不疊視窗**（2026-07-20 定案）。
 *
 * 進頁即開錄（點浮動鈕就是要講話了，少一次點擊），點大麥克風＝完成。
 * 沒有靜音自動截斷，錄到按停為止。
 *
 * 兩種模式（EXTRA_MODE）：
 * - {@link #MODE_TRANSCRIBE}：停錄後就地 STT，成功把文字放 EXTRA_TEXT 回傳；
 *   失敗顯示錯誤＋「重試」重送同一段（錄音不丟）。
 * - {@link #MODE_RECORD}：停錄立即把 WAV 路徑放 EXTRA_WAV_PATH 回傳（一次到位模式：
 *   解析要就地套表單，由呼叫端拿檔自己跑 parseAudio；檔在 cacheDir，用完呼叫端刪）。
 *
 * 取消/back＝RESULT_CANCELED（刪檔）。權限由呼叫端先把關（hasMicPermission）。
 */
public class VoiceCaptureActivity extends ComponentActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_PROMPT = "prompt";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_WAV_PATH = "wavPath";
    public static final int MODE_TRANSCRIBE = 0;
    public static final int MODE_RECORD = 1;

    private int mode = MODE_TRANSCRIBE;

    private WavAudioRecorder recorder;
    private File wavFile;
    private boolean recording = false;
    private boolean finished = false;

    private ImageButton micButton;
    private TextView promptText;
    private TextView labelText;
    private ProgressBar busySpinner;
    private Button retryButton;

    // 縮放基準與擺幅，與主語音頁一致（AiInputActivity.MIC_BASE_SCALE / MIC_PULSE_RANGE）
    private static final float MIC_BASE_SCALE = 0.7f;
    private static final float MIC_PULSE_RANGE = 0.55f;

    private long startedAt;
    private float pulse = MIC_BASE_SCALE;   // 平滑後的縮放值（低通，免得跟著峰值抖）
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long sec = (System.currentTimeMillis() - startedAt) / 1000;
            labelText.setText(getString(R.string.ai_voice_recording_label,
                    String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)));
            float target = MIC_BASE_SCALE
                    + (recorder != null ? recorder.getAmplitude() / 32767f : 0f) * MIC_PULSE_RANGE;
            pulse += (target - pulse) * 0.5f;
            micButton.setScaleX(pulse);
            micButton.setScaleY(pulse);
            handler.postDelayed(this, 80);
        }
    };

    public static boolean hasMicPermission(Activity activity) {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestMicPermission(Activity activity, int requestCode) {
        activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, requestCode);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_capture);

        // edge-to-edge：理由同 AiInputActivity.applyWindowInsets
        View root = findViewById(R.id.vc_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_TRANSCRIBE);
        micButton = findViewById(R.id.vc_mic);
        promptText = findViewById(R.id.vc_prompt);
        labelText = findViewById(R.id.vc_label);
        busySpinner = findViewById(R.id.vc_busy);
        retryButton = findViewById(R.id.vc_retry);

        micButton.setScaleX(MIC_BASE_SCALE);
        micButton.setScaleY(MIC_BASE_SCALE);

        String prompt = getIntent().getStringExtra(EXTRA_PROMPT);
        promptText.setText(prompt != null ? prompt : getString(R.string.ai_voice_prompt));

        micButton.setOnClickListener(v -> onMicTap());
        retryButton.setOnClickListener(v -> {
            retryButton.setVisibility(View.GONE);
            transcribe();
        });
        findViewById(R.id.vc_cancel).setOnClickListener(v -> cancel());

        startRecording();
    }

    private void startRecording() {
        wavFile = new File(getCacheDir(), "stt_" + System.currentTimeMillis() + ".wav");
        recorder = new WavAudioRecorder(wavFile);
        try {
            recorder.start();
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.ai_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        recording = true;
        RecordCue.playStart();
        micButton.setSelected(true);      // 錄音中＝紅
        startedAt = System.currentTimeMillis();
        handler.post(ticker);
    }

    /** 點麥克風：錄音中＝完成；失敗待重試狀態＝重新開錄（丟掉上一段）。 */
    private void onMicTap() {
        if (recording) {
            stopAndDeliver();
        } else if (retryButton.getVisibility() == View.VISIBLE) {
            wavFile.delete();
            retryButton.setVisibility(View.GONE);
            promptText.setText(getIntent().getStringExtra(EXTRA_PROMPT) != null
                    ? getIntent().getStringExtra(EXTRA_PROMPT) : getString(R.string.ai_voice_prompt));
            startRecording();
        }
    }

    private void stopAndDeliver() {
        recording = false;
        pulse = MIC_BASE_SCALE;
        micButton.setSelected(false);
        micButton.setScaleX(MIC_BASE_SCALE);
        micButton.setScaleY(MIC_BASE_SCALE);
        try {
            recorder.stop();
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.ai_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (mode == MODE_RECORD) {
            finished = true;
            Intent data = new Intent().putExtra(EXTRA_WAV_PATH, wavFile.getAbsolutePath());
            setResult(RESULT_OK, data);
            finish();       // 檔案交給呼叫端，用完由它刪
            return;
        }
        transcribe();
    }

    private void transcribe() {
        labelText.setText(R.string.ai_rec_transcribing);
        setBusy(true);
        final AiPreferences prefs = AiPreferences.load(this);
        new Thread(() -> {
            try {
                final String text = new SpeechTranscriber(prefs).transcribe(wavFile);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    finished = true;
                    wavFile.delete();
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_TEXT, text));
                    finish();
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    // 失敗保留錄音，「重試」重送；點麥克風則重講一段
                    setBusy(false);
                    labelText.setText("");
                    promptText.setText(getString(R.string.ai_failed, e.getMessage()));
                    retryButton.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private void setBusy(boolean busy) {
        busySpinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        micButton.setEnabled(!busy);
        micButton.setAlpha(busy ? 0.4f : 1f);
    }

    private void cancel() {
        finished = true;
        if (recording && recorder != null) {
            // cancel() 內部 join 寫入執行緒（最多 3 秒）——丟獨立 thread，不卡主執行緒
            final WavAudioRecorder r = recorder;
            recorder = null;
            new Thread(r::cancel, "wav-cancel").start();
        } else if (wavFile != null) {
            wavFile.delete();
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    protected void onDestroy() {
        // back 手勢等不經 cancel() 的關閉：錄音一定停、沒交付的檔不留
        if (!finished) {
            if (recording && recorder != null) {
                final WavAudioRecorder r = recorder;
                recorder = null;
                new Thread(r::cancel, "wav-cancel").start();   // 不在主執行緒 join
            } else if (wavFile != null) {
                wavFile.delete();
            }
        }
        super.onDestroy();
    }
}
