package tw.tib.financisto.ai;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 套件名 → 使用者看得懂的 app 名稱。
 *
 * ⚠️ targetSdk 30+ 的 package visibility 下，對任意套件呼叫 {@code getApplicationInfo}
 * 會丟 NameNotFoundException——即使通知是它送來的也一樣（listener 沒有豁免）。解法是在
 * manifest 的 {@code <queries>} 宣告 MAIN/LAUNCHER intent，讓所有「有桌面圖示的 app」
 * 可見；銀行、支付、社群 app 全都符合。**不加 QUERY_ALL_PACKAGES**：那是為了列舉整台
 * 手機的權限，這裡只要翻譯名字。
 *
 * 查不到就退回套件名——顯示 {@code com.foo.bar} 難看但仍然可辨識，而且它同時也是
 * {@link NotificationFilter} 的 {@code app:} 規則要寫的值，並不是廢資訊。
 *
 * 快取：列表每次重建都會問到同一批套件，而 PackageManager 查詢跨 process。
 */
public class AppLabels {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private AppLabels() {}

    public static String of(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String cached = CACHE.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        try {
            PackageManager pm = context.getPackageManager();
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (l != null && l.length() > 0) label = l.toString();
        } catch (Exception ignored) {
            // 沒裝了／不可見／不是 app（系統元件）——退回套件名
        }
        CACHE.put(pkg, label);
        return label;
    }
}
