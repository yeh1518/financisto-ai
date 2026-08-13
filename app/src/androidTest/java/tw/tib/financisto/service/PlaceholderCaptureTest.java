package tw.tib.financisto.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import tw.tib.financisto.service.SmsTransactionProcessor.Placeholder;

/**
 * Runs on a device/emulator on purpose: Android's java.util.regex is ICU-backed and its
 * character classes are always Unicode, which differs from a desktop JVM. A desktop unit
 * test would give the wrong answer about what these placeholders capture.
 *
 * What matters here is not whether the template matches, but what it captures — a wrong
 * capture is worse than no match, because the account lookup then silently fails.
 */
@RunWith(AndroidJUnit4.class)
public class PlaceholderCaptureTest {

    private static final String TEMPLATE = "transfer {{p}} to {{x}} done";

    private static String captureTransferTo(String accountTitle) {
        String[] match = SmsTransactionProcessor.findTemplateMatches(
                TEMPLATE, "transfer 100 to " + accountTitle + " done");
        assertNotNull("template did not match for: " + accountTitle, match);
        return match[Placeholder.TRANSFER_TO_ACCOUNT_NAME.ordinal()];
    }

    @Test
    public void capturesAsciiAccountTitle() {
        assertEquals("NeoBank", captureTransferTo("NeoBank"));
    }

    @Test
    public void capturesCjkAccountTitle() {
        assertEquals("身上現金", captureTransferTo("身上現金"));
        assertEquals("甲銀行信用卡", captureTransferTo("甲銀行信用卡"));
        assertEquals("Digi帳戶", captureTransferTo("Digi帳戶"));
    }

    /**
     * A hyphen is not a word character in any Unicode mode, so with (\w+?) the whole
     * template fails to match and no transaction is created at all. This has nothing to
     * do with the script the title is written in — plain ASCII titles break the same way.
     */
    @Test
    public void capturesAccountTitleWithHyphen() {
        assertEquals("Visa-Gold", captureTransferTo("Visa-Gold"));
        assertEquals("郵政帳戶-配偶", captureTransferTo("郵政帳戶-配偶"));
        assertEquals("乙銀行存款-配偶", captureTransferTo("乙銀行存款-配偶"));
    }

    /** Same for parentheses, which are punctuation rather than word characters. */
    @Test
    public void capturesAccountTitleWithParentheses() {
        assertEquals("Cash(Joint)", captureTransferTo("Cash(Joint)"));
        assertEquals("(存款)", captureTransferTo("(存款)"));
        assertEquals("(旅遊基金)", captureTransferTo("(旅遊基金)"));
    }

    // --- {{k}}（分類 id）---

    /** Finn 記帳訊息的支出樣板：分類錨在帳戶正後方、備註之前。 */
    private static final String BOOKKEEPING_TEMPLATE =
            "🧾記帳｜支出｜{{p}}｜{{c}}｜{{k}}｜{{t}}｜{{g}}｜";

    private static String[] bookkeeping(String categoryField, String note) {
        String[] match = SmsTransactionProcessor.findTemplateMatches(BOOKKEEPING_TEMPLATE,
                "Finn 🧾記帳｜支出｜250｜甲銀行信用卡｜" + categoryField + "｜" + note
                        + "｜1785761364401｜1/3");
        assertNotNull("template did not match for category field: " + categoryField, match);
        return match;
    }

    @Test
    public void capturesCategoryIdAndLeavesOtherFieldsIntact() {
        String[] m = bookkeeping("17", "全家超商 晚餐便當");
        assertEquals("17", m[Placeholder.CATEGORY_ID.ordinal()]);
        assertEquals("250", m[Placeholder.PRICE.ordinal()]);
        assertEquals("甲銀行信用卡", m[Placeholder.ACCOUNT_NAME.ordinal()]);
        assertEquals("全家超商 晚餐便當", m[Placeholder.TEXT.ordinal()]);
        assertEquals("1785761364401", m[Placeholder.TIMESTAMP_MILLIS.ordinal()]);
    }

    /** 0＝不指定分類，要照樣比中（引擎那端才決定「0 就不設」）。 */
    @Test
    public void capturesZeroAsUnspecified() {
        assertEquals("0", bookkeeping("0", "ATM 提款")[Placeholder.CATEGORY_ID.ordinal()]);
    }

    /**
     * ICU 的 \d 抓 Unicode Nd，所以全形數字也會被 (\d{1,9}) 吃到。這個測試存在的意義是
     * **確認它在 Android 上真的會被抓到**——桌面 JVM 的 \d 是 ASCII-only，答案不一樣。
     * 抓到之後怎麼解讀是 parseCategoryId 的事（照數值解，見 CategoryPlaceholderTest）。
     */
    @Test
    public void capturesFullWidthDigitsOnAndroid() {
        assertEquals("１７", bookkeeping("１７", "全形數字")[Placeholder.CATEGORY_ID.ordinal()]);
    }
}
