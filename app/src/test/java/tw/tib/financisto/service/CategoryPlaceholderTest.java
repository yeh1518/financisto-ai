package tw.tib.financisto.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import tw.tib.financisto.service.SmsTransactionProcessor.Placeholder;

/**
 * {@code {{k}}}（分類 id）的切分與解析。
 *
 * 這條路的失敗全都是靜默的——比不中就完全不記、抓歪就記錯分類，兩種都不會叫。所以把
 * 「欄位順序」與「奇怪輸入」兩類形狀釘住。真正的 regex 捕捉行為另有 device 端測試
 * （{@code androidTest} 的 PlaceholderCaptureTest）：Android 的 regex 是 ICU 實作，
 * 桌面 JVM 對 {@code \d} 的答案不一樣。
 */
public class CategoryPlaceholderTest {

    /** Finn 記帳訊息的支出樣板（分類在帳戶之後、備註之前）。 */
    private static final String EXPENSE_TEMPLATE =
            "🧾記帳｜支出｜{{p}}｜{{c}}｜{{k}}｜{{t}}｜{{g}}｜";

    private static String[] match(String template, String body) {
        return SmsTransactionProcessor.findTemplateMatches(template, body);
    }

    private static String captured(String[] m, Placeholder p) {
        return m[p.ordinal()];
    }

    @Test
    public void splitsAllFields() {
        String[] m = match(EXPENSE_TEMPLATE,
                "Finn 🧾記帳｜支出｜250｜中信信用卡｜17｜全家超商 晚餐便當｜1785761364401｜1/3");
        assertNotNull("樣板沒比中", m);
        assertEquals("250", captured(m, Placeholder.PRICE));
        assertEquals("中信信用卡", captured(m, Placeholder.ACCOUNT_NAME));
        assertEquals("17", captured(m, Placeholder.CATEGORY_ID));
        assertEquals("全家超商 晚餐便當", captured(m, Placeholder.TEXT));
        assertEquals("1785761364401", captured(m, Placeholder.TIMESTAMP_MILLIS));
    }

    /**
     * ⚠️ 備註**不得含分隔符 `｜`後面接純數字**——這是格式的硬約束，訊息產生端要保證。
     *
     * {@code {{t}}} 是 lazy 的 {@code (.*?)}、{@code {{g}}} 是 {@code (\d{1,13})}，備註裡
     * 出現「｜99」這種段落時，lazy 的備註會在第一個分隔符就收手，而時間戳欄位「剛好」滿足於
     * 那段數字——於是整排欄位平移，時間戳變成 99（＝1970 年）。**比不中還糟：它會成功記帳，
     * 只是日期完全錯。**
     *
     * 這個洞與分類欄無關、加 {{k}} 之前就存在（現行三條樣板同樣中槍），釘在這裡是因為
     * 加了分類欄之後純數字段落變得更可能出現（分類線索本來就寫在備註裡）。
     */
    @Test
    public void noteWithNumericSegmentShiftsLaterFields() {
        String[] m = match(EXPENSE_TEMPLATE,
                "Finn 🧾記帳｜支出｜250｜中信信用卡｜17｜發票｜99｜1785761364401｜1/3");
        assertNotNull(m);
        assertEquals("17", captured(m, Placeholder.CATEGORY_ID));   // 分類仍正確（錨在帳戶後）
        assertEquals("發票", captured(m, Placeholder.TEXT));         // 備註被切斷
        assertEquals("99", captured(m, Placeholder.TIMESTAMP_MILLIS)); // 時間戳被那段數字吃掉
    }

    /**
     * 分類錨在帳戶正後方（而不是備註後面）的理由：備註是自由文字，把嚴格的數字欄位擺在它
     * 後面等於讓分類的正確性取決於備註內容。錨在前面，分類永遠只由它自己那一格決定。
     */
    @Test
    public void categoryStaysCorrectEvenWhenNoteBreaksLaterFields() {
        String riskyOrder = "🧾記帳｜支出｜{{p}}｜{{c}}｜{{t}}｜{{k}}｜{{g}}｜";
        String[] bad = match(riskyOrder,
                "Finn 🧾記帳｜支出｜250｜中信信用卡｜發票｜99｜17｜1785761364401｜1/3");
        assertNotNull(bad);
        assertEquals("99", captured(bad, Placeholder.CATEGORY_ID));  // 本意是 17 → 記錯分類
        assertEquals("17", captured(bad, Placeholder.TIMESTAMP_MILLIS));

        // 正式順序下，同樣的壞備註至少不會污染分類
        String[] good = match(EXPENSE_TEMPLATE,
                "Finn 🧾記帳｜支出｜250｜中信信用卡｜17｜發票｜99｜1785761364401｜1/3");
        assertNotNull(good);
        assertEquals("17", captured(good, Placeholder.CATEGORY_ID));
    }

    @Test
    public void emptyNoteStillSplits() {
        String[] m = match(EXPENSE_TEMPLATE,
                "Finn 🧾記帳｜支出｜99.5｜身上現金｜42｜｜1785761364401｜1/1");
        assertNotNull(m);
        assertEquals("42", captured(m, Placeholder.CATEGORY_ID));
        assertEquals("", captured(m, Placeholder.TEXT));
    }

    /** 備註含分隔符但**不是**純數字段落時，回溯救得回來——危險的只有數字（見下）。 */
    @Test
    public void noteContainingNonNumericSeparatorIsFine() {
        String[] m = match(EXPENSE_TEMPLATE,
                "Finn 🧾記帳｜支出｜250｜中信信用卡｜17｜A｜B｜1785761364401｜1/3");
        assertNotNull(m);
        assertEquals("17", captured(m, Placeholder.CATEGORY_ID));
        assertEquals("A｜B", captured(m, Placeholder.TEXT));
    }

    /** 括號帳戶名（信封袋帳戶）與分類欄並存。 */
    @Test
    public void parenthesisedAccountNameWithCategory() {
        String[] m = match(EXPENSE_TEMPLATE,
                "Finn 🧾記帳｜支出｜1200｜(旅遊儲蓄金)｜8｜機票訂金｜1785761364401｜1/1");
        assertNotNull(m);
        assertEquals("(旅遊儲蓄金)", captured(m, Placeholder.ACCOUNT_NAME));
        assertEquals("8", captured(m, Placeholder.CATEGORY_ID));
    }

    /** 轉帳樣板：目標帳戶與分類同時存在時的順序。 */
    @Test
    public void transferTemplateWithCategory() {
        String[] m = match("🧾記帳｜轉帳｜{{p}}｜{{c}}｜{{x}}｜{{k}}｜{{t}}｜{{g}}｜",
                "Finn 🧾記帳｜轉帳｜5000｜中信帳戶總覽｜身上現金｜0｜ATM 提款｜1785769151816｜1/2");
        assertNotNull(m);
        assertEquals("中信帳戶總覽", captured(m, Placeholder.ACCOUNT_NAME));
        assertEquals("身上現金", captured(m, Placeholder.TRANSFER_TO_ACCOUNT_NAME));
        assertEquals("0", captured(m, Placeholder.CATEGORY_ID));
        assertEquals("ATM 提款", captured(m, Placeholder.TEXT));
    }

    /** 收入樣板（與支出只差字面）也要切得對——三條樣板都是實際要設進 app 的字串。 */
    @Test
    public void incomeTemplateSplitsAllFields() {
        String[] m = match("🧾記帳｜收入｜{{p}}｜{{c}}｜{{k}}｜{{t}}｜{{g}}｜",
                "Finn 🧾記帳｜收入｜32000｜中信帳戶總覽｜71｜八月薪資｜1785761364401｜1/1");
        assertNotNull(m);
        assertEquals("32000", captured(m, Placeholder.PRICE));
        assertEquals("中信帳戶總覽", captured(m, Placeholder.ACCOUNT_NAME));
        assertEquals("71", captured(m, Placeholder.CATEGORY_ID));
        assertEquals("八月薪資", captured(m, Placeholder.TEXT));
    }

    /**
     * 跨樣板互斥：方向不是引擎讀得到的欄位，而是樣板上的一個布林（is_income），所以
     * 「這筆是收是支」完全靠**哪一條樣板比中**。三條樣板同標題（Finn%）、引擎逐條試、
     * 只有第一條產出交易的生效——互斥若不成立，正負號就會看運氣。
     */
    @Test
    public void expenseAndIncomeTemplatesAreMutuallyExclusive() {
        String expenseTpl = EXPENSE_TEMPLATE;
        String incomeTpl = "🧾記帳｜收入｜{{p}}｜{{c}}｜{{k}}｜{{t}}｜{{g}}｜";
        String transferTpl = "🧾記帳｜轉帳｜{{p}}｜{{c}}｜{{x}}｜{{k}}｜{{t}}｜{{g}}｜";

        String expenseMsg = "Finn 🧾記帳｜支出｜250｜中信信用卡｜17｜全家超商｜1785761364401｜1/1";
        String incomeMsg = "Finn 🧾記帳｜收入｜32000｜中信帳戶總覽｜71｜八月薪資｜1785761364401｜1/1";
        String transferMsg = "Finn 🧾記帳｜轉帳｜5000｜中信帳戶總覽｜身上現金｜0｜ATM 提款｜1785761364401｜1/1";

        assertNotNull(match(expenseTpl, expenseMsg));
        assertNull(match(incomeTpl, expenseMsg));
        assertNull(match(transferTpl, expenseMsg));

        assertNotNull(match(incomeTpl, incomeMsg));
        assertNull(match(expenseTpl, incomeMsg));
        assertNull(match(transferTpl, incomeMsg));

        assertNotNull(match(transferTpl, transferMsg));
        assertNull(match(expenseTpl, transferMsg));
        assertNull(match(incomeTpl, transferMsg));
    }

    /** 沒有 {{k}} 的既有樣板不受影響——加佔位符不能動到已經在跑的樣板。 */
    @Test
    public void templateWithoutCategoryPlaceholderIsUnaffected() {
        String[] m = match("🧾記帳｜支出｜{{p}}｜{{c}}｜{{t}}｜{{g}}｜",
                "Finn 🧾記帳｜支出｜250｜中信信用卡｜全家超商 晚餐便當｜1785761364401｜1/3");
        assertNotNull(m);
        assertEquals("250", captured(m, Placeholder.PRICE));
        assertEquals("全家超商 晚餐便當", captured(m, Placeholder.TEXT));
        assertNull(captured(m, Placeholder.CATEGORY_ID));
    }

    // --- parseCategoryId：抓到的字串轉 id ---

    @Test
    public void parsesPlainId() {
        assertEquals(17L, SmsTransactionProcessor.parseCategoryId("17"));
        assertEquals(107L, SmsTransactionProcessor.parseCategoryId(" 107 "));
    }

    @Test
    public void zeroAndBlankMeanUnspecified() {
        assertEquals(0L, SmsTransactionProcessor.parseCategoryId("0"));
        assertEquals(0L, SmsTransactionProcessor.parseCategoryId(""));
        assertEquals(0L, SmsTransactionProcessor.parseCategoryId(null));
    }

    /**
     * ICU 的 {@code \d} 抓的是 Unicode Nd 類別，所以 {@code (\d{1,9})} 會吃到全形或
     * 其他書寫系統的數字。{@code Long.parseLong} 走 {@code Character.digit}、對這些一樣
     * 解得出數值，所以結果是「照數值解讀」而不是壞掉——這是安全的方向，釘住它免得
     * 有人日後把 parse 改嚴而變成靜默不指定分類。
     */
    @Test
    public void unicodeDigitsResolveToTheirNumericValue() {
        assertEquals(17L, SmsTransactionProcessor.parseCategoryId("１７"));
        assertEquals(3L, SmsTransactionProcessor.parseCategoryId("٣"));
    }

    /** 真的不是數字時要退成「不指定分類」，不能讓整筆交易掉在例外裡不見。 */
    @Test
    public void nonNumericDegradesToUnspecified() {
        assertEquals(0L, SmsTransactionProcessor.parseCategoryId("飲食"));
        assertEquals(0L, SmsTransactionProcessor.parseCategoryId("12a"));
    }
}
