package tw.tib.financisto.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 調整餘額（balance）模式下，把模型回的 splits 對到「差額」上。
 *
 * 語義：使用者數完現金報出新餘額，再講花在哪——各份分的是**這次的變動（差額＝新餘額 − 目前餘額）**，
 * 不是新餘額。模型看不到目前餘額、算不出差額，所以 prompt 規定它只回聽到的、有講金額的份；
 * 「剩下的是食材」這種殘額份回 amount=null，差額扣掉已知份之後的餘數由這裡補上。
 *
 * 規則（2026-09-10 定）：
 * <ul>
 *   <li>有金額的份：取絕對值、符號跟差額一致（花掉錢＝負、多出來＝正）。</li>
 *   <li>第一個 amount=null 的份＝殘額份：拿「差額 − 已知份加總」，**帶號照建**，餘數為 0 才不建。
 *       餘數與差額反號有兩種可能：口誤（講 300 但只少了 252），或真的有錢進來（「花了 500 買菜，
 *       剩下的是幫人收的錢」＝支出 500 ＋ 收入 300）——程式分不出來，所以不替使用者丟掉，
 *       照他講的建出來、由表單端跳提醒讓他看見（2026-09-10 實測兩種都出現過）。</li>
 *   <li>第二個以後的 null 份沒有東西可分，略過。</li>
 *   <li>全部都有金額但加總 ≠ 差額 → 照回各份、不自動平：盤點對不起來正是要暴露的資訊。</li>
 * </ul>
 * 純計算、不碰 Android，三個入口（一句話記帳、補充模式切到調整餘額、調整餘額表單上補講）共用。
 */
public final class BalanceSplitPlanner {

    /** 一份分割子項：分類 id（0＝未分類）、帶號 minor units 金額、備註（可為 null）。 */
    public static final class Share {
        public final long categoryId;
        public final long amountMinor;
        public final String note;

        Share(long categoryId, long amountMinor, String note) {
            this.categoryId = categoryId;
            this.amountMinor = amountMinor;
            this.note = note;
        }
    }

    private BalanceSplitPlanner() {
    }

    /**
     * @param spoken     模型回的各份（category 已經過清單驗證；amount 為主單位、可為 null）
     * @param deltaMinor 差額＝新餘額 − 目前餘額（minor units、帶號）
     * @param scale      帳戶幣別的小數位數
     * @return 可直接建成分割子項的清單；湊不出任何有效份回空清單（呼叫端當成不是分割）
     */
    public static List<Share> plan(List<ParsedTransaction.Split> spoken, long deltaMinor, int scale) {
        List<Share> out = new ArrayList<>();
        if (spoken == null || spoken.isEmpty()) return out;

        int sign = deltaMinor > 0 ? 1 : -1;      // 差額 0 也當支出方向，跟表單預設一致
        double factor = Math.pow(10, scale);
        long known = 0;
        ParsedTransaction.Split remainderShare = null;
        for (ParsedTransaction.Split s : spoken) {
            if (s.amount == null) {
                if (remainderShare == null) remainderShare = s;   // 只認第一個殘額份
                continue;
            }
            long minor = sign * Math.round(Math.abs(s.amount) * factor);
            known += minor;
            out.add(new Share(categoryOf(s), minor, s.note));
        }
        if (remainderShare != null) {
            long remainder = deltaMinor - known;
            // 帶號照建：反號（已知份超過差額）可能是口誤也可能是真的有收入，交給表單端提醒、不在這裡丟
            if (remainder != 0) {
                out.add(new Share(categoryOf(remainderShare), remainder, remainderShare.note));
            }
        }
        return out;
    }

    private static long categoryOf(ParsedTransaction.Split s) {
        return s.category != null && s.category.resolved() ? s.category.id : 0;
    }
}
