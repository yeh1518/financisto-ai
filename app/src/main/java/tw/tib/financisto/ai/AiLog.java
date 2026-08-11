package tw.tib.financisto.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import tw.tib.financisto.BuildConfig;
import tw.tib.financisto.export.Export;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.OutputStream;
import java.security.MessageDigest;
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
    /**
     * 匯出到備份資料夾用的檔名（固定，每次覆蓋）。電腦端的 ingest 認這個名字。
     *
     * 副檔名跟著 mime type 用 .json 而不是內容其實更貼切的 .jsonl：SAF 建檔時會依 mime
     * 自己補副檔名，取名 ai-log.jsonl 實際會落地成 ai-log.jsonl.json（2026-08-10 實證）。
     * 內容仍是一行一個 JSON 物件。
     */
    public static final String EXPORT_FILE_NAME = "ai-log.json";
    /**
     * 完整快照的檔名。與上面那個滾動附加的檔**分開**：那個只增不減（電腦端語料庫的來源），
     * 快照是整份覆蓋，兩者共用一個檔的話，一次快照就會把累積的歷史砍到只剩手機留的那 1000 筆。
     * 快照的用途是補洞——滾動附加曾經失敗、或換手機重裝之後把現有的補回去。
     */
    public static final String SNAPSHOT_FILE_NAME = "ai-log-full.json";
    /** 記住這兩個檔的 Uri，下次直接寫它，不必再靠檔名去找。 */
    private static final String KEY_EXPORT_URI = "ai_log_export_uri";
    private static final String KEY_SNAPSHOT_URI = "ai_log_snapshot_uri";
    /**
     * 超過就修剪，只留最後 MAX_ENTRIES 筆。
     * 2026-07-31 從 300 筆／256KB 放寬到 1000 筆／1MB：偶發的解析錯誤事後才想追，
     * 300 筆只撐得住一週多，回頭看時證據已經被捲掉了（一筆約 600 bytes，1000 筆 ≈ 600KB，
     * 落在 app 私有目錄，這點空間換得回可追溯性）。
     */
    private static final int MAX_ENTRIES = 1000;
    private static final long TRIM_THRESHOLD_BYTES = 1024 * 1024;

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
                              String model, String stt, String promptVersion) {
        try {
            JSONObject o = new JSONObject();
            o.put("at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            o.put("said", utterance == null ? "" : utterance);
            stampVersion(o, promptVersion);
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

    /**
     * 記一次「產樣板」（{@link TemplateGenerator}）。**每一次嘗試各記一筆**，含被驗證打回的那次。
     *
     * 為什麼與 {@link #record} 分開：兩者要留的東西不一樣。解析要留的是原句與模型回的 JSON；
     * 產樣板要留的是**模型寫出來的那條樣板**與**確定性驗證為什麼把它打回**——沒有這兩樣，
     * 事後只剩使用者轉述的一句「驗證失敗」，根本判斷不出是模型把固定文字抄歪了、還是驗證太嚴。
     * （2026-08-09 補：在此之前產樣板完全不留痕跡，一則通知產不出樣板的原因無法事後追。）
     *
     * @param notification 送進模型的通知內文（含 title 前綴，＝驗證回測用的同一份）
     * @param attempt      第幾次嘗試，從 1 起算
     * @param template     模型產出的樣板（呼叫模型就失敗時為 null）
     * @param fields       樣板的其他欄位摘要（帳戶／分類／收支／樣本金額／把握度）
     * @param error        這次為什麼不算數（驗證問題或呼叫失敗）；null＝通過
     */
    public static void recordTemplate(Context context, String notification, int attempt,
                                      String template, String fields, String error, String model,
                                      String promptVersion) {
        try {
            JSONObject o = new JSONObject();
            o.put("at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            o.put("kind", "template");
            o.put("said", notification == null ? "" : notification);
            o.put("attempt", attempt);
            stampVersion(o, promptVersion);
            if (model != null) o.put("model", model);
            if (template != null) o.put("got", template);
            if (fields != null) o.put("fields", fields);
            if (error != null) o.put("error", error);
            append(context, o.toString());
        } catch (JSONException e) {
            Log.e(TAG, "組樣板紀錄失敗", e);
        }
    }

    /**
     * 每一筆都蓋上「這是哪一版跑出來的」：
     * <ul>
     *   <li>{@code pv}＝當次實際送出的 prompt 指紋（不含帳戶/分類清單——那是資料不是規則，
     *       Gary 新增一個帳戶不該讓版本跳號）。改 prompt 一個字就會變。</li>
     *   <li>{@code av}＝建置時間，{@code sha}＝建置當下的 commit（解析程式那半的版本）。</li>
     * </ul>
     * 沒有這三欄，語料只能證明「模型某天答錯了」，無法回答「換掉那條規則之後有沒有變好」。
     */
    private static void stampVersion(JSONObject o, String promptVersion) throws JSONException {
        if (promptVersion != null) o.put("pv", promptVersion);
        o.put("av", BuildConfig.VERSION_NAME);
        o.put("sha", BuildConfig.GIT_SHA);
    }

    /**
     * prompt 指紋：SHA-1 前 8 碼。取 hash 而不是流水號，是因為流水號要靠人記得改——
     * 改了 prompt 忘了改號碼，語料就整批標錯，比沒有版本還糟。
     */
    public static String fingerprint(String prompt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(prompt.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "nohash";
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
        mirrorToBackupFolder(context, line);
        if (f.length() > TRIM_THRESHOLD_BYTES) {
            trim(f);
        }
    }

    /**
     * 每寫一筆就同步附加到備份資料夾那份，讓語料不必等每日備份、也不必手動按鈕。
     *
     * 附加而不是整檔重寫：手機這邊只留 {@value #MAX_ENTRIES} 筆、超過就捲掉，備份資料夾那份
     * 卻是電腦端語料庫的來源，**不能跟著捲**。所以那個檔只增不減，也因此絕不能被完整快照
     * 覆蓋——快照另外寫 {@link #SNAPSHOT_FILE_NAME}。
     *
     * 一律吞掉例外：這是研究素材，不值得為了它讓一筆記帳失敗。寫不進去也還有每日的完整快照
     * 可以補洞（電腦端 ingest 會去重）。
     */
    private static void mirrorToBackupFolder(Context context, String line) {
        try {
            Uri target = resolveTarget(context, EXPORT_FILE_NAME, KEY_EXPORT_URI);
            if (target == null) return;
            // "wa"＝append。少了 a 就會從頭覆蓋，等於每次只剩最後一行
            try (OutputStream os = context.getContentResolver().openOutputStream(target, "wa");
                 Writer w = new OutputStreamWriter(os, "UTF-8")) {
                w.write(line);
                w.write("\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "同步到備份資料夾失敗（不影響記帳）", e);
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
                if ("template".equals(o.optString("kind"))) {
                    sb.append("  [產樣板 第").append(o.optInt("attempt", 1)).append("次]\n");
                    sb.append("通知：").append(o.optString("said")).append('\n');
                    if (o.has("got")) sb.append("樣板：").append(o.optString("got")).append('\n');
                    if (o.has("fields")) sb.append("欄位：").append(o.optString("fields")).append('\n');
                    sb.append(o.has("error")
                            ? "失敗：" + o.optString("error") + "\n" : "驗證通過\n");
                    sb.append('\n');
                    continue;
                }
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

    /**
     * 匯出到「備份資料夾」——與每日 .backup 同一個 SAF 目錄，因此走同一條 Syncthing 通道
     * 自動落到電腦上，不必再手動分享。
     *
     * 為什麼寫 JSONL 原始格式而不是畫面上那份人看的排版：電腦端要拿它當語料累積與回歸比對，
     * 人看的排版得靠 regex 反解、多一個欄位就可能解錯。人看的那份留給畫面與分享鈕。
     *
     * 固定檔名覆蓋（不是每天一個新檔）：手機這邊只負責提供「最新全量」，累積與去重是電腦端
     * 的事——手機端的紀錄本來就有 {@value #MAX_ENTRIES} 筆上限，留幾份舊檔也補不回被捲掉的。
     *
     * @return 寫出去的檔案 Uri
     */
    public static Uri exportToBackupFolder(Context context) throws Exception {
        Uri target = resolveTarget(context, SNAPSHOT_FILE_NAME, KEY_SNAPSHOT_URI);
        if (target == null) {
            throw new IllegalStateException("尚未設定備份資料夾");
        }
        // "wt"＝truncate 後重寫；少了 t 會變成疊寫，舊內容的尾巴會留在後面
        try (OutputStream os = context.getContentResolver().openOutputStream(target, "wt");
             Writer w = new OutputStreamWriter(os, "UTF-8")) {
            for (String line : readLines(file(context))) {
                w.write(line);
                w.write("\n");
            }
        }
        return target;
    }

    /**
     * 取得備份資料夾裡那個檔（沒有就建），並把 Uri 記起來。
     *
     * 認 Uri 而不是認檔名：SAF 建檔時可能依 mime 補副檔名，落地的名字未必等於我們給的名字，
     * 靠名字找會每次都找不到、每次都新建一個「ai-log (1)、(2)…」（2026-08-10 實證）。
     *
     * @return null＝還沒設定備份資料夾
     */
    private static Uri resolveTarget(Context context, String name, String prefKey) {
        String folder = Export.getBackupFolder(context);
        if (folder == null || folder.isEmpty()) return null;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String saved = prefs.getString(prefKey, null);
        if (saved != null) {
            Uri u = Uri.parse(saved);
            if (exists(context, u)) return u;
        }
        Uri tree = Uri.parse(folder);
        Uri dir = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        Uri target = findChild(context, tree, dir, name);
        if (target == null) {
            try {
                target = DocumentsContract.createDocument(
                        context.getContentResolver(), dir, "application/json", name);
            } catch (java.io.FileNotFoundException e) {
                Log.e(TAG, "備份資料夾不存在或已失去授權", e);
                return null;
            }
        }
        if (target != null) {
            prefs.edit().putString(prefKey, target.toString()).apply();
        }
        return target;
    }

    /** 記住的那個檔還在不在（使用者可能自己刪了或換了備份資料夾）。 */
    private static boolean exists(Context context, Uri uri) {
        try (android.database.Cursor c = context.getContentResolver().query(
                uri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    /** SAF 沒有「依名字取檔」的 API，只能列子項目找——找不到回 null（代表要新建）。 */
    private static Uri findChild(Context context, Uri tree, Uri dir, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(dir));
        try (android.database.Cursor c = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "找備份資料夾內既有檔案失敗", e);
        }
        return null;
    }

    public static void clear(Context context) {
        File f = file(context);
        if (f.exists() && !f.delete()) {
            Log.e(TAG, "清除紀錄失敗");
        }
    }
}
