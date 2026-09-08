package tw.tib.financisto.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import tw.tib.financisto.service.SmsTransactionProcessor;

/**
 * TemplateGenerator.trimTail 的測試，外加「精簡樣板吃得下同一家銀行的多種文案」這個設計目標的
 * 回歸測試。
 *
 * 背景：樣板原本是整段照抄通知內文，把銀行的免責聲明、客服電話、分期利率一起寫進比對條件——
 * 銀行改一個字整條就失效（實地案例：某家的刷卡通知有三種寫法，兩條長樣板各只吃一種）。
 * 引擎是用 find() 找子字串、不要求涵蓋整則通知，所以最後一個欄位之後的文字本來就沒有比對價值。
 *
 * 銀行名與商家名一律用中性等價物（本 repo 不放真實金融機構名），但保留原案例要示範的性質：
 * 同一家銀行多種文案、長尾免責聲明、中段的變動敘述。
 */
public class TemplateGeneratorTrimTailTest {

    /** 長尾型通知：末四碼＋金額＋商家，後面接一大段免責聲明。body 含 title 前綴＝引擎吃的格式。 */
    private static final String BODY_LONG_TAIL =
            "丙銀行 丙銀行貴賓您好，末四碼4321感謝您使用信用卡刷卡台幣1,838元，商店名稱:測試商行，"
            + "實際商店名稱請以信用卡帳單列示為準，實際請款金額以帳單所列為準如有疑問請撥打卡片背面"
            + "服務專線，謝謝！";

    /** 模型照抄整段產出的樣板（長尾全部寫死）。 */
    private static final String VERBATIM_TEMPLATE =
            "丙銀行 丙銀行貴賓您好，末四碼{{a}}感謝{{*}}刷卡台幣{{p}}元，商店名稱:{{e}}，"
            + "實際商店名稱請以信用卡帳單列示為準，實際請款金額以帳單所列為準如有疑問請撥打卡片背面"
            + "服務專線，謝謝！";

    private static TemplateGenerator.GeneratedTemplate template(String tpl, String sampleAmount) {
        TemplateGenerator.GeneratedTemplate t = new TemplateGenerator.GeneratedTemplate();
        t.template = tpl;
        t.sampleAmount = sampleAmount;
        return t;
    }

    @Test
    public void trimsLongDisclaimerAfterLastField() {
        String trimmed = TemplateGenerator.trimTail(VERBATIM_TEMPLATE);
        // {{e}} 後面只留一個逗號當結束標記，免責聲明整段丟掉
        assertTrue(trimmed, trimmed.endsWith("商店名稱:{{e}}，"));
        assertTrue(trimmed.length() < VERBATIM_TEMPLATE.length());
    }

    @Test
    public void trimmedTemplateStillMatchesTheSample() {
        String trimmed = TemplateGenerator.trimTail(VERBATIM_TEMPLATE);
        assertNull(TemplateGenerator.validate(template(trimmed, "1,838"), BODY_LONG_TAIL));
    }

    @Test
    public void trimmedTemplateKeepsExtractingTheMerchant() {
        String trimmed = TemplateGenerator.trimTail(VERBATIM_TEMPLATE);
        String[] m = SmsTransactionProcessor.findTemplateMatches(trimmed, BODY_LONG_TAIL);
        assertNotNull(m);
        // 裁切最怕把 {{e}} 的結束標記一起砍掉——那會退化成只抓一個字元（「測」）
        assertEquals("測試商行",
                m[SmsTransactionProcessor.Placeholder.PAYEE.ordinal()]);
    }

    @Test
    public void trailingAnyPlaceholderIsDroppedWithItsTail() {
        String tpl = "丙銀行 末四碼{{a}}刷卡台幣{{p}}元，商店名稱:{{e}}，{{*}}";
        String trimmed = TemplateGenerator.trimTail(tpl);
        assertTrue(trimmed, trimmed.endsWith("商店名稱:{{e}}，"));
        // 丟掉尾端的 {{*}} 不能讓 {{e}} 變成退化捕捉
        assertNull(TemplateGenerator.findDegenerateCapture(trimmed));
    }

    @Test
    public void shortTemplateIsLeftAlone() {
        String tpl = "丙銀行 信用卡消費{{p}}元通知";
        assertEquals(tpl, TemplateGenerator.trimTail(tpl));
    }

    @Test
    public void templateWithoutPlaceholderIsLeftAlone() {
        String tpl = "丙銀行 這條沒有佔位符";
        assertEquals(tpl, TemplateGenerator.trimTail(tpl));
    }

    /**
     * 設計目標的回歸測試：一條「只留錨點與貼身定界字」的精簡樣板，要吃得下同一家銀行的三種文案。
     * 長樣板做不到這件事——中段把「感謝您使用信用卡刷卡」寫死了，銀行換成「於{時間}刷」就掉了。
     */
    @Test
    public void leanTemplateMatchesEveryWordingOfTheSameBank() {
        String lean = "丙銀行{{*}}末四碼{{a}}{{*}}台幣{{p}}元，商店名稱:{{e}}，";
        String[] bodies = {
                BODY_LONG_TAIL,
                // 改版後的文案：多一個「您」、「感謝…刷卡」換成「於{時間}刷」、尾巴換成分期廣告
                "丙銀行 丙銀行貴賓您好，您末四碼4321於08/22 13:18刷台幣5,278元，商店名稱:另一商行，"
                        + "若需分期可詳客服，最高24期，謹慎理財信用至上",
                // 另一種完全不同的開頭
                "丙銀行 丙銀行信用卡末四碼4321刷卡通知1150819_20:57金額台幣1,838元，商店名稱:第三商行，"
                        + "實際請款金額以帳單所列為準",
        };
        for (String body : bodies) {
            String[] m = SmsTransactionProcessor.findTemplateMatches(lean, body);
            assertNotNull("比不中：" + body, m);
            assertNotNull("沒抽到金額：" + body,
                    m[SmsTransactionProcessor.Placeholder.PRICE.ordinal()]);
            assertNotNull("沒抽到商家：" + body,
                    m[SmsTransactionProcessor.Placeholder.PAYEE.ordinal()]);
        }
    }

    /** 長樣板吃不下改版後的文案——這是精簡的動機，寫成測試免得未來又改回整段照抄。 */
    @Test
    public void verbatimTemplateMissesTheRewordedNotification() {
        String reworded = "丙銀行 丙銀行貴賓您好，您末四碼4321於08/22 13:18刷台幣5,278元，"
                + "商店名稱:另一商行，若需分期可詳客服";
        assertNull(SmsTransactionProcessor.findTemplateMatches(VERBATIM_TEMPLATE, reworded));
    }
}
