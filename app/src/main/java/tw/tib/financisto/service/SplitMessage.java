package tw.tib.financisto.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「一筆拆多個分類」的通知訊息（自家 bot 發的，與 {@code 🧾記帳} 同一家族）。
 *
 * <pre>
 * 🧾分割｜方向｜總額｜帳戶｜備註｜時間戳｜份清單｜序號
 * 🧾分割｜支出｜5654｜乙銀行信用卡-配偶｜NeoMart｜1791605876000｜30=529:保鮮膜;11=857:零食;3=464:飲料｜1/1
 * </pre>
 *
 * <ul>
 *   <li>方向：{@code 支出} / {@code 收入}。轉帳不支援（分割只對支出／收入有意義）。</li>
 *   <li>總額、各份金額：正數，無千分位，小數用 {@code .}。各份加總必須等於總額，
 *       否則整則無效——Financisto 的分割父交易要求未分配額為零，寬鬆處理只會做出壞帳。</li>
 *   <li>份：{@code 分類id=金額[:備註]}，用 {@code ;} 分隔；分類 id 為 0 表示不指定。</li>
 *   <li>備註（整筆與各份）不得含全形 {@code ｜}；各份備註另不得含 {@code ;}。</li>
 *   <li>時間戳：epoch 毫秒；空白＝記帳當下。序號只為了讓同批內文互異，不解析。</li>
 * </ul>
 *
 * 這不走樣板引擎（regex 填空表達不了不定數量的份），由程式直接切欄位。訊息格式是自家
 * 定的，所以可以嚴格：欄位數不對、金額不是數字、加總不符都算無效，由呼叫端記進解析紀錄。
 */
public final class SplitMessage {
    public static final String TRIGGER = "🧾分割";
    private static final String SEP = "｜";

    public static final class Part {
        public final long categoryId;
        public final BigDecimal amount;
        public final String note;

        Part(long categoryId, BigDecimal amount, String note) {
            this.categoryId = categoryId;
            this.amount = amount;
            this.note = note;
        }
    }

    public final boolean income;
    public final BigDecimal total;
    public final String accountName;
    public final String note;
    /** null＝訊息沒帶時間 */
    public final Long timestampMillis;
    public final List<Part> parts;

    private SplitMessage(boolean income, BigDecimal total, String accountName, String note,
                         Long timestampMillis, List<Part> parts) {
        this.income = income;
        this.total = total;
        this.accountName = accountName;
        this.note = note;
        this.timestampMillis = timestampMillis;
        this.parts = Collections.unmodifiableList(parts);
    }

    /** 這段內文是不是分割訊息（只看標記，不保證合法）。 */
    public static boolean isSplitMessage(String body) {
        return body != null && body.contains(TRIGGER);
    }

    /**
     * 解析。內文可帶通知標題前綴（引擎吃到的格式是「標題 空格 內文」），從標記處開始切。
     *
     * @throws IllegalArgumentException 格式不對時，訊息說明是哪裡不對（給解析紀錄用）
     */
    public static SplitMessage parse(String body) {
        if (body == null) throw new IllegalArgumentException("空內文");
        int at = body.indexOf(TRIGGER);
        if (at < 0) throw new IllegalArgumentException("沒有 " + TRIGGER + " 標記");
        String s = body.substring(at + TRIGGER.length());
        // 分隔符兩側不留空白是規格，但多一個空白不該讓整筆消失
        String[] f = s.split(SEP, -1);
        // f[0] 是標記後、第一個 ｜ 前的字（應為空）；之後依序：方向 總額 帳戶 備註 時間戳 份清單 [序號]
        if (f.length < 7) throw new IllegalArgumentException("欄位數不足：" + (f.length - 1) + " 欄，需要 6 欄");
        String direction = f[1].trim();
        boolean income;
        if ("支出".equals(direction)) income = false;
        else if ("收入".equals(direction)) income = true;
        else throw new IllegalArgumentException("方向只能是 支出/收入：" + direction);

        BigDecimal total = parseAmount(f[2].trim(), "總額");
        String account = f[3].trim();
        if (account.isEmpty()) throw new IllegalArgumentException("帳戶空白");
        String note = f[4].trim();
        String ts = f[5].trim();
        Long timestamp = null;
        if (!ts.isEmpty()) {
            try {
                timestamp = Long.parseLong(ts);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("時間戳不是數字：" + ts);
            }
            if (timestamp <= 0) throw new IllegalArgumentException("時間戳不合理：" + ts);
        }

        List<Part> parts = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (String raw : f[6].split(";")) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("份的格式應為 分類id=金額[:備註]：" + p);
            long categoryId;
            try {
                categoryId = Long.parseLong(p.substring(0, eq).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("分類 id 不是數字：" + p);
            }
            if (categoryId < 0) throw new IllegalArgumentException("分類 id 不合理：" + p);
            String rest = p.substring(eq + 1);
            // 份內備註用半形冒號分隔；金額本身不會含冒號，所以切第一個就對
            int colon = rest.indexOf(':');
            String amountText = (colon < 0 ? rest : rest.substring(0, colon)).trim();
            String partNote = colon < 0 ? "" : rest.substring(colon + 1).trim();
            BigDecimal amount = parseAmount(amountText, "份金額");
            parts.add(new Part(categoryId, amount, partNote));
            sum = sum.add(amount);
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("沒有任何份");
        if (sum.compareTo(total) != 0) {
            throw new IllegalArgumentException("各份加總 " + sum.toPlainString() + " ≠ 總額 " + total.toPlainString());
        }
        return new SplitMessage(income, total, account, note, timestamp, parts);
    }

    private static BigDecimal parseAmount(String text, String what) {
        if (text.isEmpty()) throw new IllegalArgumentException(what + "空白");
        BigDecimal v;
        try {
            v = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + "不是數字：" + text);
        }
        if (v.signum() <= 0) throw new IllegalArgumentException(what + "必須是正數：" + text);
        return v;
    }
}
