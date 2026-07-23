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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 通知滾動日誌（AI 產樣板用，定稿 2026-07-23）。
 *
 * NotificationCache 只存「還掛在通知欄」的通知，滑掉就沒了——事後想拿某則通知設樣板
 * 會撲空。這裡把「可能是記帳訊息」的通知持久化留 7 天，給「通知樣板解析」的列表當料，
 * 未來也是樣板失效偵測的基礎。
 *
 * 跟隨 {@link AiLog} 的慣例：JSONL 落在 app 私有 filesDir（通知內容含消費明細算隱私，
 * 不放外部儲存）；個人尺度不上資料庫。不進備份檔（不是帳務資料）。
 */
public class NotificationJournal {

    private static final String TAG = "NotificationJournal";
    private static final String FILE_NAME = "notification_journal.jsonl";
    /** 只留最近 MAX_ENTRIES 筆；行銷雜訊已被「含數字」前濾擋掉大半，這夠涵蓋 7 天。 */
    private static final int MAX_ENTRIES = 100;
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    /** 內文有數字才值得記——記帳訊息必含金額；純文字行銷推播直接略過。 */
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    private NotificationJournal() {}

    public static class Entry {
        public long at;
        public String pkg;
        public String title;
        public String body;
    }

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /**
     * 記一則通知。呼叫端（NotificationListener）在每次 onNotificationPosted 都呼叫，
     * 這裡自行判斷值不值得留：標題或內文空的、內文沒有數字的都略過。
     * 同標題+內文重複（同一則通知更新多次）也略過，避免灌爆。
     */
    public static void record(Context context, String pkg, String title, String body) {
        if (title == null || title.isEmpty() || body == null || body.isEmpty()) return;
        if (!HAS_DIGIT.matcher(body).find()) return;
        try {
            List<Entry> entries = read(context);
            for (Entry e : entries) {
                if (title.equals(e.title) && body.equals(e.body)) return;
            }
            JSONObject o = new JSONObject();
            o.put("at", System.currentTimeMillis());
            o.put("pkg", pkg == null ? "" : pkg);
            o.put("title", title);
            o.put("body", body);
            append(context, o.toString());
        } catch (JSONException e) {
            Log.e(TAG, "組通知紀錄失敗", e);
        }
    }

    /** 讀出日誌（過期的濾掉），最新在前。列表 UI 用。 */
    public static List<Entry> read(Context context) {
        List<Entry> result = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        for (String line : readLines(file(context))) {
            try {
                JSONObject o = new JSONObject(line);
                if (o.optLong("at") < cutoff) continue;
                Entry e = new Entry();
                e.at = o.optLong("at");
                e.pkg = o.optString("pkg");
                e.title = o.optString("title");
                e.body = o.optString("body");
                result.add(e);
            } catch (JSONException ignored) {}
        }
        java.util.Collections.reverse(result);
        return result;
    }

    private static synchronized void append(Context context, String line) {
        File f = file(context);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8")) {
            w.write(line);
            w.write("\n");
        } catch (IOException e) {
            Log.e(TAG, "寫通知紀錄失敗", e);
            return;
        }
        trim(f);
    }

    /** 修剪：砍過期的與超額的（留最新 MAX_ENTRIES 筆）。整檔重寫，量小無妨。 */
    private static void trim(File f) {
        List<String> lines = readLines(f);
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        List<String> fresh = new ArrayList<>();
        for (String line : lines) {
            try {
                if (new JSONObject(line).optLong("at") >= cutoff) fresh.add(line);
            } catch (JSONException ignored) {}
        }
        if (fresh.size() > MAX_ENTRIES) {
            fresh = fresh.subList(fresh.size() - MAX_ENTRIES, fresh.size());
        }
        if (fresh.size() == lines.size()) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8")) {
            for (String l : fresh) {
                w.write(l);
                w.write("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "修剪通知紀錄失敗", e);
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
            Log.e(TAG, "讀通知紀錄失敗", e);
        }
        return lines;
    }
}
