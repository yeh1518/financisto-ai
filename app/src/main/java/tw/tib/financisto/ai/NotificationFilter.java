package tw.tib.financisto.ai;

import android.content.Context;
import android.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「產樣板」通知列表的排除清單。
 *
 * 通知欄裡真正跟記帳有關的只佔一小撮，其餘是限時動態、行銷推播、同步狀態。挑一則通知
 * 來產樣板時要在這堆東西裡翻，很難用；而且 {@link NotificationJournal} 只留 100 筆，
 * 雜訊會把真正的銀行通知擠掉——所以這份清單**兩邊都用**：日誌不記、列表不顯示。
 *
 * 存成一整段文字（一行一個規則）而不是結構化清單：使用者要的是「打開來看一眼、
 * 順手改幾行」，純文字最好編輯，也最好從對話裡貼進貼出。兩種行：
 *
 * <ul>
 *   <li>{@code app:com.instagram.android} — 排掉整個 app。行尾可以接 {@code # 註記}
 *       （套件名不含空白也不含 {@code #}，所以切在第一個空白或 {@code #} 是安全的），
 *       一鍵排除時就靠這個把 app 名稱寫進去給人看懂。</li>
 *   <li>其餘＝關鍵字，對 title 與 body 做忽略大小寫的子字串比對。</li>
 * </ul>
 *
 * 關鍵字擋不乾淨的東西（IG 限時動態的「和另外 2 人」照樣有數字）才是 100 筆額度的
 * 主要殺手，整個 app 排掉才守得住——這是 app 規則存在的理由，不只是省打字。
 *
 * 不做 regex：這是自己維護的排除清單，寫錯一個字元就整條失效的東西不值得。
 */
public class NotificationFilter {

    public static final String KEY = "ai_notification_filter";

    private static final String APP_PREFIX = "app:";

    private NotificationFilter() {}

    public static String loadRaw(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, "");
    }

    public static void saveRaw(Context context, String raw) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(KEY, raw == null ? "" : raw).apply();
    }

    /**
     * 有效關鍵字：去掉空白行、{@code #} 開頭的註解行（讓人能在清單裡寫「為什麼擋這個」）
     * 與 {@code app:} 行。
     *
     * app 行必須排除在關鍵字之外，否則套件名會**同時**變成一條子字串規則——擋到的東西
     * 就不是使用者以為的那些了。
     */
    public static List<String> keywords(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : lines(raw)) {
            if (isAppRule(line)) continue;
            out.add(line);
        }
        return out;
    }

    /** 要排除的套件名（小寫）。 */
    public static Set<String> packages(String raw) {
        Set<String> out = new HashSet<>();
        for (String line : lines(raw)) {
            if (!isAppRule(line)) continue;
            String pkg = packageOf(line);
            if (!pkg.isEmpty()) out.add(pkg);
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

    /**
     * @return true＝這則通知的來源 app 被整個排除了。
     *
     * pkg 空的一律不擋：拿不到來源（很舊的日誌紀錄、抓不到套件名）不該被無聲吃掉。
     */
    public static boolean matchesPackage(Set<String> packages, String pkg) {
        if (packages == null || packages.isEmpty()) return false;
        if (pkg == null || pkg.isEmpty()) return false;
        return packages.contains(pkg.toLowerCase(Locale.ROOT));
    }

    /** 兩種規則一起套（呼叫端只有一則通知要判斷時用這個）。 */
    public static boolean matches(Context context, String pkg, String title, String body) {
        String raw = loadRaw(context);
        return matchesPackage(packages(raw), pkg)
                || matches(keywords(raw), title, body);
    }

    /**
     * 把一個 app 加進排除清單（已經在裡面就原樣回傳）。
     *
     * @param label 給人看的 app 名稱，寫成行尾註記；空的就不寫
     * @return 新的清單原文（呼叫端負責存回去）
     */
    public static String addPackage(String raw, String pkg, String label) {
        if (pkg == null || pkg.trim().isEmpty()) return raw == null ? "" : raw;
        String base = raw == null ? "" : raw;
        if (packages(base).contains(pkg.trim().toLowerCase(Locale.ROOT))) return base;
        String prefix = base.isEmpty() || base.endsWith("\n") ? base : base + "\n";
        String line = APP_PREFIX + pkg.trim();
        if (label != null && !label.trim().isEmpty() && !label.trim().equals(pkg.trim())) {
            line += "  # " + label.trim();
        }
        return prefix + line + "\n";
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

    /** 去掉空白行與註解行後的每一行規則（尚未分類）。 */
    private static List<String> lines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            out.add(t);
        }
        return out;
    }

    /**
     * 只看前綴、不管後面有沒有東西：{@code app:} 單獨一行要當成**無效行**
     * （keywords 不收、packages 也收不到），不能掉回去變成一條擋 "app:" 的關鍵字。
     */
    private static boolean isAppRule(String line) {
        return line.length() >= APP_PREFIX.length()
                && line.substring(0, APP_PREFIX.length()).equalsIgnoreCase(APP_PREFIX);
    }

    /** {@code app:com.x  # 註記} → {@code com.x}（小寫）。 */
    private static String packageOf(String line) {
        String v = line.substring(APP_PREFIX.length()).trim();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isWhitespace(c) || c == '#') {
                v = v.substring(0, i);
                break;
            }
        }
        return v.trim().toLowerCase(Locale.ROOT);
    }
}
