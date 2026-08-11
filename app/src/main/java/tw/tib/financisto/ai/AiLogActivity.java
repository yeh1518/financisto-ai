package tw.tib.financisto.ai;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import tw.tib.financisto.R;

/**
 * 解析紀錄檢視頁：看得到「我當時到底講了什麼、模型回了什麼」。
 *
 * 存了不看等於沒存——解析結果不對時要能當場翻出原句，那才是改 prompt 的依據。
 * 分享鈕是為了把原句撈出手機（貼回對話裡討論怎麼改）。
 */
public class AiLogActivity extends ComponentActivity {

    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_log);

        // edge-to-edge：理由同 AiInputActivity.applyWindowInsets
        View root = findViewById(R.id.ai_log_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        logText = findViewById(R.id.ai_log_text);
        ((Button) findViewById(R.id.ai_log_export)).setOnClickListener(v -> exportToBackupFolder());
        ((Button) findViewById(R.id.ai_log_share)).setOnClickListener(v -> share());
        ((Button) findViewById(R.id.ai_log_clear)).setOnClickListener(v -> {
            AiLog.clear(this);
            refresh();
        });
        refresh();
    }

    private void refresh() {
        String content = AiLog.readForDisplay(this);
        logText.setText(TextUtils.isEmpty(content) ? getString(R.string.ai_log_empty) : content);
    }

    /**
     * 手動把紀錄丟進備份資料夾（＝每日備份會自動做的同一件事）。
     * 留這顆鈕是為了「現在就想把剛剛那幾筆送到電腦上看」，不必等當天的自動備份。
     */
    private void exportToBackupFolder() {
        try {
            AiLog.exportToBackupFolder(this);
            Toast.makeText(this, getString(R.string.ai_log_exported, AiLog.SNAPSHOT_FILE_NAME),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.ai_log_export_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 純文字分享，不走 FileProvider：內容量是「幾百句話」等級，直接塞 EXTRA_TEXT 就好，
     * 省掉一組 provider 設定（而且 .ai 版與商店版的 authorities 衝突已經踩過一次）。
     */
    private void share() {
        String content = AiLog.readForDisplay(this);
        if (TextUtils.isEmpty(content)) {
            Toast.makeText(this, R.string.ai_log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(send, getString(R.string.ai_log_title)));
    }
}
