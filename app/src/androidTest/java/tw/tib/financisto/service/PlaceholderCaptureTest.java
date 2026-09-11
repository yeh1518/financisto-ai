package tw.tib.financisto.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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

    // --- {{e}}（商家/收款人）---

    /** 信用卡消費通知的常見形狀：商家名夾在「商店名稱：」與下一行的「授權碼：」之間。 */
    private static final String MERCHANT_TEMPLATE =
            "信用卡消費通知 消費金額：{{p}}元{{*}}末四碼{{a}}{{*}}商店名稱：{{e}}\n授權碼：";

    private static String captureMerchant(String merchant) {
        String[] match = SmsTransactionProcessor.findTemplateMatches(MERCHANT_TEMPLATE,
                "信用卡消費通知 消費金額：4000元\n卡　　號：末四碼1234\n"
                        + "授權時間：2026/08/31 10:00\n商店名稱：" + merchant + "\n授權碼：000123");
        assertNotNull("template did not match for merchant: " + merchant, match);
        return match[Placeholder.PAYEE.ordinal()];
    }

    @Test
    public void capturesMerchantWithoutSpace() {
        assertEquals("甲壽保費", captureMerchant("甲壽保費"));
        assertEquals("SHOPFAST", captureMerchant("SHOPFAST"));
        assertEquals("丁購物股份有限公司", captureMerchant("丁購物股份有限公司"));
    }

    /**
     * A space is not punctuation but it is not \S either, so with (\S+?) the whole
     * template fails to match and the notification is dropped without a trace. English
     * merchant names routinely contain spaces — this is the common case, not an edge one.
     */
    @Test
    public void capturesMerchantWithSpace() {
        assertEquals("ALPHA THEATRES", captureMerchant("ALPHA THEATRES"));
        assertEquals("SHOPFAST TW", captureMerchant("SHOPFAST TW"));
        assertEquals("A1 MART 城中店", captureMerchant("A1 MART 城中店"));
    }

    /** 非貪婪＋定界字：停在第一個定界字，後面的內容不可以被吃進來。 */
    @Test
    public void stopsAtTheFirstAnchor() {
        assertEquals("A B", captureMerchant("A B"));
        String[] match = SmsTransactionProcessor.findTemplateMatches(
                "金額NT${{p}}元{{*}}在{{e}} 刷卡。",
                "丙銀行 【刷卡通知】金額NT$205元 \n"
                        + "卡號末四碼5678於 2026/08/17 13:57在SHOPFAST TW 刷卡。立即查看消費明細");
        assertNotNull("template did not match", match);
        assertEquals("SHOPFAST TW", match[Placeholder.PAYEE.ordinal()]);
    }

    /**
     * 樣板以 {{e}} 收尾（沒有結束標記）：以前只抓到一個字元（上游 issue #149 的形狀），現在補到行尾。
     * 第二行的文字不會被吃進來；通知就結束在收款人時也要拿到整個名字。
     */
    @Test
    public void payeeAtTemplateEndCapturesToEndOfLine() {
        String[] match = SmsTransactionProcessor.findTemplateMatches(
                "金額NT${{p}}元{{*}}在{{e}}",
                "丙銀行 【刷卡通知】金額NT$205元 \n卡號末四碼5678於 2026/08/17 13:57在SHOPFAST TW\n立即查看消費明細");
        assertNotNull("template did not match", match);
        assertEquals("SHOPFAST TW", match[Placeholder.PAYEE.ordinal()]);
        // （樣板一定要有 {{p}} 才會被當成樣板，見 findPlaceholderIndexes）
        match = SmsTransactionProcessor.findTemplateMatches("金額{{p}}元在{{e}}", "丙銀行 金額205元在NeoShop");
        assertNotNull("template did not match at end of message", match);
        assertEquals("NeoShop", match[Placeholder.PAYEE.ordinal()]);
    }

    /**
     * 結束標記在同一行出現不只一次（「，」後面接一長串免責聲明）：非貪婪停在第一個，收款人才是
     * 商家名；貪婪會一路吃到最後一個「，」，把整段免責聲明塞進收款人。這是台灣銀行刷卡通知最常見
     * 的形狀，也是 TemplateGenerator.trimTail 只留一個界字當錨的前提。
     */
    @Test
    public void payeeStopsAtTheFirstDelimiterEvenWithALongTail() {
        String[] match = SmsTransactionProcessor.findTemplateMatches(
                "丙銀行 {{*}}末四碼{{a}}{{*}}台幣{{p}}元，商店名稱:{{e}}，",
                "丙銀行 丙銀行信用卡末四碼4321刷卡通知1150819_20:57金額台幣1,838元，商店名稱:測試商行，"
                        + "實際商店名稱請以信用卡帳單列示為準，實際請款金額以帳單所列為準如有疑問請撥打卡片背面服務專線，謝謝！");
        assertNotNull("template did not match", match);
        assertEquals("測試商行", match[Placeholder.PAYEE.ordinal()]);
    }

    /**
     * {{e}} 不跨行。這正是它用 ([^\r\n]+?) 而不是 (.+?) 的理由：pattern 是用 DOTALL 編的，
     * (.+?) 會在定界字只出現在後面幾行時把整段連換行一起吞進收款人。定界字沒出現在同一行
     * 就該比不中——寧可不記，也不要記出一個橫跨兩行的收款人。
     */
    @Test
    public void payeeNeverCrossesLines() {
        assertNull(SmsTransactionProcessor.findTemplateMatches(
                "消費金額：{{p}}元\n商店名稱：{{e}}授權碼：",
                "消費金額：4000元\n商店名稱：甲壽保費\n授權碼：000123"));
    }

    // --- {{k}}（分類 id）---

    /** NotifBot 記帳訊息的支出樣板：分類錨在帳戶正後方、備註之前。 */
    private static final String BOOKKEEPING_TEMPLATE =
            "🧾記帳｜支出｜{{p}}｜{{c}}｜{{k}}｜{{t}}｜{{g}}｜";

    private static String[] bookkeeping(String categoryField, String note) {
        String[] match = SmsTransactionProcessor.findTemplateMatches(BOOKKEEPING_TEMPLATE,
                "NotifBot 🧾記帳｜支出｜250｜甲銀行信用卡｜" + categoryField + "｜" + note
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
