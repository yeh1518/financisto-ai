package tw.tib.financisto.ai;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型解析結果（定稿 1，2026-07-14）。
 *
 * 5 槽 happy path：金額 / 帳戶 / 分類 / 專案 / 備註 + 交易型別。
 * 每個 id 槽都帶信心與備選；id 一律經 app 端拿回清單驗證，模型永不無中生有 id。
 */
public class ParsedTransaction {

    public static final String TYPE_EXPENSE = "expense";
    public static final String TYPE_INCOME = "income";
    public static final String TYPE_TRANSFER = "transfer";
    public static final String TYPE_BALANCE = "balance";

    /** "expense" | "income" | "transfer" | "balance" | null */
    public String transactionType;

    /**
     * 補充模式專用：使用者這句話**有沒有明確表達要改型別/收支方向**。
     * 由模型指出原話中的證據片段、程式驗證那段話真的存在（見 BookkeepingParser.resolveTypeChange），
     * 不是關鍵字比對——關鍵字太死，意思到了但用詞不同的講法會全部漏掉。
     *
     * 用途只有一個——決定要不要**換掉整張表單**（轉帳↔一般交易↔調整餘額）。那是破壞性操作，
     * 曾發生「在轉帳表單上只補講一個帳戶名，模型順手回 expense，整張轉帳被切成支出」。
     * 純粹的收入↔支出方向不看這欄（同一張表單、就地翻符號，成本低且模型判得準）。
     */
    public boolean typeChange;

    /**
     * 口語主單位金額（如 120 或 120.5）；判斷不出為 null。
     * expense/income/transfer＝變動金額；**balance＝講出來的「新餘額」**（結果，非變動）。
     */
    public Double amount;

    /** expense/income/balance＝該帳戶；transfer＝轉出帳戶。 */
    public Pick account = new Pick();
    /** 僅 transfer 使用＝轉入帳戶。 */
    public Pick toAccount = new Pick();
    public Pick category = new Pick();
    public Pick project = new Pick();

    /** 從話語提取的關鍵詞，非整句複製；可為 null。 */
    public String note;

    /**
     * 分割明細：一筆付款拆成多個分類時每份一項；沒拆＝空。
     * 有 splits 時父交易走 SPLIT，金額以各份加總為準（頂層 amount 僅參考）。
     * 只用於 expense/income（transfer/balance 不拆）。
     */
    public List<Split> splits = new ArrayList<>();

    public boolean hasSplits() {
        return splits != null && !splits.isEmpty();
    }

    /** 明確講到的日期，格式 YYYY-MM-DD；沒講為 null。 */
    public String date;
    /** 明確講到的時間，格式 HH:MM（24 小時制）；沒講為 null。 */
    public String time;

    /**
     * 講出來的日期/時間 → epoch millis。規則（2026-07-17 定案）：
     * <ul>
     *   <li>日期＋時間都講 → 就用那個日期那個時間</li>
     *   <li>只講日期 → **那個日期 + 當下時刻**</li>
     *   <li>只講時間 → 今天 + 那個時間</li>
     *   <li>都沒講 → 回 null＝不指定，由呼叫端沿用預設（現在）</li>
     * </ul>
     * 解析不出來也回 null——寧可用現在，也不要塞一個錯的日期進帳。
     */
    public Long resolveDateTimeMillis() {
        if (date == null && time == null) return null;
        Calendar c = Calendar.getInstance();       // 起點＝今天 + 現在時刻
        if (date != null) {
            Matcher m = DATE_RE.matcher(date.trim());
            if (!m.matches()) return null;
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            if (y < 2000 || y > 2100 || mo < 1 || mo > 12 || d < 1 || d > 31) return null;
            c.set(Calendar.YEAR, y);
            c.set(Calendar.MONTH, mo - 1);
            c.set(Calendar.DAY_OF_MONTH, d);
        }
        if (time != null) {
            Matcher m = TIME_RE.matcher(time.trim());
            if (!m.matches()) return null;
            int h = Integer.parseInt(m.group(1));
            int mi = Integer.parseInt(m.group(2));
            if (h > 23 || mi > 59) return null;
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, mi);
        }
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static final Pattern DATE_RE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern TIME_RE = Pattern.compile("(\\d{1,2}):(\\d{2})");

    public boolean isTransfer() {
        return TYPE_TRANSFER.equalsIgnoreCase(transactionType);
    }

    public boolean isBalance() {
        return TYPE_BALANCE.equalsIgnoreCase(transactionType);
    }

    public boolean isIncome() {
        return TYPE_INCOME.equalsIgnoreCase(transactionType);
    }

    public boolean isExpense() {
        // 預設當支出（最常見）
        return !isIncome() && !isTransfer() && !isBalance();
    }

    /** 分割中的一份：分類 + 該份金額（口語主單位）+ 該份的品名/描述。 */
    public static class Split {
        public Pick category = new Pick();
        /** 該份金額，口語主單位；判斷不出為 null（呼叫端會略過金額不明的份）。 */
        public Double amount;
        /** 該份的品名/描述；可為 null。 */
        public String note;
    }

    /** 單一 id 槽的解析結果。 */
    public static class Pick {
        /** 驗證後有效的 id；對不到清單為 null。 */
        public Long id;
        /** 0~1 把握度。 */
        public double confidence;
        /** 次可能 id（已驗證）。 */
        public long[] alternatives = new long[0];

        public boolean resolved() {
            return id != null && id != 0;
        }
    }
}
