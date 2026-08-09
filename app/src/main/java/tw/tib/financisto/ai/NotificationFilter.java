package tw.tib.financisto.ai;

import android.content.Context;
import android.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「產樣板」通知列表的關鍵字排除清單。
 *
 * 通知欄裡真正跟記帳有關的只佔一小撮，其餘是限時動態、行銷推播、同步狀態。挑一則通知
 * 來產樣板時要在這堆東西裡翻，很難用；而且 {@link NotificationJournal} 只留 100 筆，
 * 雜訊會把真正的銀行通知擠掉——所以這份清單**兩邊都用**：日誌不記、列表不顯示。
 *
 * 存成一整段文字（一行一個關鍵字）而不是結構化清單：使用者要的是「打開來看一眼、
 * 順手改幾行」，純文字最好編輯，也最好從對話裡貼進貼出。
 *
 * 比對＝忽略大小寫的子字串，對 title 與 body 都比。不做 regex：這是自己維護的排除清單，
 * 寫錯一個字元就整條失效的東西不值得。
 */
public class NotificationFilter {

    public static final String KEY = "ai_notification_filter";

    private NotificationFilter() {}

    public static String loadRaw(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, "");
    }

    public static void saveRaw(Context context, String raw) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(KEY, raw == null ? "" : raw).apply();
    }

    /** 有效關鍵字：去掉空白行與 `#` 開頭的註解行（讓人能在清單裡寫「為什麼擋這個」）。 */
    public static List<String> keywords(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String k = line.trim();
            if (k.isEmpty() || k.startsWith("#")) continue;
            out.add(k);
        }
        return out;
    }

    /** @return true＝這則通知該被擋掉。keywords 為空就一律不擋。 */
    public static boolean matches(List<String> keywords, String title, String body) {
        if (keywords == null || keywords.isEmpty()) return false;
        String hay = ((title == null ? "" : title) + "\n" + (body == null ? "" : body))
                .toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (hay.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static boolean matches(Context context, String title, String body) {
        return matches(keywords(loadRaw(context)), title, body);
    }

    /**
     * 「增加過濾」時預先填進編輯器的那一行——挑最可能成為關鍵字的東西給人改。
     *
     * 通知標題通常就是發送者（「pt._cat 和另外 2 人」「no-reply」），刪掉尾巴就是好關鍵字；
     * 標題空的才退而用內文第一行。整段內文不適合當預設值：太長，而且擋太寬。
     */
    public static String suggestKeyword(String title, String body) {
        if (title != null && !title.trim().isEmpty()) return title.trim();
        if (body == null) return "";
        String first = body.split("\n", 2)[0].trim();
        return first;
    }
}
