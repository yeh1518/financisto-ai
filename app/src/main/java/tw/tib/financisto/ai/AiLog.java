package tw.tib.financisto.ai;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 把每次送去解析的話與模型回覆存下來，當作日後調整 prompt 的依據。
 *
 * 為什麼要留：解析結果不對時（例如金額莫名變成收入），事後根本回想不起來當時到底怎麼講的，
 * 沒有原句就無從改起。存的是「送進 parser 的文字」而非音檔——那才是 prompt 真正吃到的輸入。
 *
 * 落地在 app 私有 filesDir（不是 EncryptedSharedPreferences，這裡沒有金鑰；
 * 但也不放外部儲存，記帳內容算隱私）。JSONL 一行一筆，超過上限就砍最舊的。
 */
public class AiLog {

    private static final String TAG = "AiLog";
    private static final String FILE_NAME = "ai_log.jsonl";
    /** 超過就修剪，只留最後 MAX_ENTRIES 筆。個人用量下這夠追溯很久。 */
    private static final int MAX_ENTRIES = 300;
    private static final long TRIM_THRESHOLD_BYTES = 256 * 1024;

    private AiLog() {}

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /**
     * 記一次解析。所有欄位都可為 null——失敗的那次尤其要記，那是最需要追的。
     *
     * @param utterance  送進 parser 的原句
     * @param supplement 是否為表單內補充模式
     * @param formState  補充模式帶給模型的「目前表單已填內容」context（沒有就 null）
     * @param rawContent 模型回的 JSON 字串（成功才有）
     * @param error      失敗訊息（成功為 null）
     * @param model      解析模型（LLM）
     * @param stt        這句話**怎麼來的**：`typed`／`system`（內建語音）／`<provider>/<model>`
     *                   （雲端辨識）／`direct:<provider>/<model>`（一次到位）／後綴 `+edited`
     *                   ＝辨識完又手改過。沒有這欄的話，換過辨識引擎之後語料就混成一團、
     *                   無法分層比較（2026-07-29 補；此日之前的紀錄沒有這欄，分析時要排除）。
     */
    public static void record(Context context, String utterance, boolean supplement,
                              String formState, String rawContent, String error,
                              String model, String stt) {
        try {
            JSONObject o = new JSONObject();
            o.put("at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            o.put("said", utterance == null ? "" : utterance);
            if (model != null) o.put("model", model);
            if (stt != null) o.put("stt", stt);
            if (supplement) o.put("mode", "supplement");
            if (formState != null && !formState.isEmpty()) o.put("form", formState);
            if (rawContent != null) o.put("got", rawContent);
            if (error != null) o.put("error", error);
            append(context, o.toString());
        } catch (JSONException e) {
            Log.e(TAG, "組紀錄失敗", e);
        }
    }

    private static synchronized void append(Context context, String line) {
        File f = file(context);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8")) {
            w.write(line);
            w.write("\n");
        } catch (IOException e) {
            Log.e(TAG, "寫紀錄失敗", e);
            return;
        }
        if (f.length() > TRIM_THRESHOLD_BYTES) {
            trim(f);
        }
    }

    /** 只留最後 MAX_ENTRIES 筆。整檔重寫，個人尺度的量不值得為此上資料庫。 */
    private static void trim(File f) {
        List<String> lines = readLines(f);
        if (lines.size() <= MAX_ENTRIES) return;
        List<String> keep = lines.subList(lines.size() - MAX_ENTRIES, lines.size());
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8")) {
            for (String l : keep) {
                w.write(l);
                w.write("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "修剪紀錄失敗", e);
        }
    }

    private static List<String> readLines(File f) {
        List<String> lines = new ArrayList<>();
        if (!f.exists()) return lines;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.trim().isEmpty()) lines.add(l);
            }
        } catch (IOException e) {
            Log.e(TAG, "讀紀錄失敗", e);
        }
        return lines;
    }

    /** 給檢視畫面用：最新的排最前面，人看得懂的排版。 */
    public static String readForDisplay(Context context) {
        List<String> lines = readLines(file(context));
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            try {
                JSONObject o = new JSONObject(lines.get(i));
                sb.append(o.optString("at"));
                if (o.has("model")) sb.append("  [").append(o.optString("model")).append(']');
                if (o.has("stt")) sb.append("  [聽:").append(o.optString("stt")).append(']');
                sb.append('\n');
                sb.append("說：").append(o.optString("said")).append('\n');
                if (o.has("mode")) sb.append("（補充模式）\n");
                if (o.has("form")) sb.append(o.optString("form")).append('\n');
                if (o.has("error")) {
                    sb.append("失敗：").append(o.optString("error")).append('\n');
                } else if (o.has("got")) {
                    sb.append("解析：").append(o.optString("got")).append('\n');
                }
            } catch (JSONException e) {
                sb.append(lines.get(i)).append('\n');   // 壞掉的行原樣顯示，總比吞掉好
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static void clear(Context context) {
        File f = file(context);
        if (f.exists() && !f.delete()) {
            Log.e(TAG, "清除紀錄失敗");
        }
    }
}
