package tw.tib.financisto.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 已處理過的記帳通知指紋——防同一則通知被記成兩筆。
 *
 * 原本的去重靠 {@code NotificationCache}：只比對「同一個通知 key 的前後內文」，而且 listener
 * 一斷線就整個清空（{@code onListenerDisconnected}）。實地踩到：2026-08-20 同一則國泰刷卡通知
 * 在 71 秒內記成兩筆（_id 23385 / 23386）——listener 重綁後 cache 是空的，同一則通知再被投遞
 * 一次就又記一筆。APK 更新後會重綁（見 {@code requestRebindIfGranted}），所以這不是罕見時機。
 *
 * 改用「內文指紋」持久化比對：銀行通知的內文含卡號末四碼與到分鐘的授權時間，兩筆**真的**消費
 * 幾乎不可能產生逐字相同的內文（同分鐘、同商家、同金額——那連人也分不出來是兩筆）。所以
 * 「內文一模一樣」當重複來擋，比「同帳戶同金額」那種近似規則安全得多：後者會誤殺真的連續兩筆
 * 同額消費（買兩杯一樣的咖啡）。
 *
 * 存 SharedPreferences（筆數少、要跨 process 與重啟活著）；超過 {@link #MAX_AGE_MS} 或
 * {@link #MAX_ENTRIES} 就淘汰，不需要人清。指紋是 SHA-256 前 16 個 hex——不存原文，
 * 這個檔沒有消費明細。
 */
public class ProcessedNotificationLog {

    private static final String TAG = "ProcessedNotifLog";
    private static final String PREFS = "processed_notifications";
    private static final String KEY = "entries";
    /** 同一則通知被重新投遞多半在幾秒到幾分鐘內；一天的窗夠寬，又不會擋到明天的同額消費。 */
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_ENTRIES = 200;

    private ProcessedNotificationLog() {}

    /**
     * 記下這則通知並回報它是不是新的。
     *
     * @return true＝沒見過（可以處理）；false＝窗內見過同樣內文（重複投遞，別再記一筆）
     */
    public static synchronized boolean markIfNew(Context context, String body) {
        if (context == null || body == null || body.isEmpty()) return true;
        String fp = fingerprint(body);
        if (fp == null) return true;                 // 算不出指紋就不擋（寧可重複也不要漏記）
        long now = System.currentTimeMillis();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray kept = new JSONArray();
        boolean seen = false;
        try {
            JSONArray stored = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < stored.length(); i++) {
                JSONObject o = stored.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at", 0);
                if (now - at > MAX_AGE_MS) continue;         // 過期淘汰
                if (fp.equals(o.optString("fp"))) seen = true;
                kept.put(o);
            }
        } catch (JSONException e) {
            Log.w(TAG, "corrupt log, starting over", e);     // 壞了就從頭記，不擋記帳
            kept = new JSONArray();
            seen = false;
        }
        if (seen) {
            Log.i(TAG, "duplicate notification ignored, fp=" + fp);
            return false;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("fp", fp);
            o.put("at", now);
            kept.put(o);
        } catch (JSONException e) {
            return true;
        }
        // 超量時砍最舊的（陣列是按加入順序，前面就是舊的）
        int drop = kept.length() - MAX_ENTRIES;
        JSONArray out = new JSONArray();
        for (int i = 0; i < kept.length(); i++) {
            if (i < drop) continue;
            out.put(kept.opt(i));
        }
        prefs.edit().putString(KEY, out.toString()).apply();
        return true;
    }

    /** SHA-256 前 16 個 hex。只存指紋不存原文，這個偏好檔就不含消費明細。 */
    static String fingerprint(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(body.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            Log.w(TAG, "cannot hash notification body", e);
            return null;
        }
    }
}
