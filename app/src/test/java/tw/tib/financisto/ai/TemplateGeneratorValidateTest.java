package tw.tib.financisto.ai;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * TemplateGenerator.validate 的確定性驗證測試——這層是「LLM 產物的程式驗收」，
 * 是整個功能可靠度的守門員，必須有測試。
 */
public class TemplateGeneratorValidateTest {

    /** 台新式消費通知（末四碼＋金額＋商家）。body 含 title 前綴＝引擎實際吃的格式。 */
    private static final String BODY =
            "台新銀行 台新銀行 您尾號8842之信用卡於07/22 12:34消費NT$1,250，全聯福利中心，感謝您的惠顧";

    private static TemplateGenerator.GeneratedTemplate template(String tpl, String sampleAmount) {
        TemplateGenerator.GeneratedTemplate t = new TemplateGenerator.GeneratedTemplate();
        t.template = tpl;
        t.sampleAmount = sampleAmount;
        return t;
    }

    @Test
    public void goodTemplatePasses() {
        assertNull(TemplateGenerator.validate(template(
                "台新銀行 台新銀行 您尾號{{a}}之信用卡於{{*}}消費NT${{p}}，{{e}}，感謝您的惠顧",
                "1,250"), BODY));
    }

    @Test
    public void missingPricePlaceholderFails() {
        String problem = TemplateGenerator.validate(template(
                "台新銀行 {{*}}消費NT$1,250{{*}}", "1,250"), BODY);
        assertNotNull(problem);
        assertTrue(problem.contains("{{p}}"));
    }

    @Test
    public void templateNotMatchingBodyFails() {
        assertNotNull(TemplateGenerator.validate(template(
                "國泰世華 您的卡片消費NT${{p}}元", "1,250"), BODY));
    }

    @Test
    public void wrongExtractedAmountFails() {
        // {{p}} 放錯位置抓到末四碼 8842 而不是金額 → 金額比對擋下
        String problem = TemplateGenerator.validate(template(
                "台新銀行 台新銀行 您尾號{{p}}之信用卡於{{*}}消費NT${{*}}，感謝您的惠顧",
                "1,250"), BODY);
        assertNotNull(problem);
    }

    @Test
    public void overfittedDigitsFailMutationTest() {
        // 金額寫死成「1,250」字面值＋{{p}} 只掛在尾巴空字串上會過第一關嗎？
        // 構造：樣板把金額部分寫死，{{p}} 抓別的位置——變異測試要能擋下
        // 這裡用「{{p}} 能吃原金額但吃不下不同位數」較難直接構造（原生 regex 寬鬆），
        // 改驗證：樣板把金額寫死字面值時，第一關（回測抽不出金額）就會失敗。
        String problem = TemplateGenerator.validate(template(
                "台新銀行 台新銀行 您尾號{{a}}之信用卡於{{*}}消費NT$1,250，{{e}}，感謝您的惠顧",
                "1,250"), BODY);
        assertNotNull(problem);
    }

    @Test
    public void goodTemplateGeneralizesToDifferentAmount() {
        // 同一條樣板要吃得下不同位數/格式的金額（變異測試不誤殺好樣板）
        String body = BODY.replace("1,250", "88");
        assertNull(TemplateGenerator.validate(template(
                "台新銀行 台新銀行 您尾號{{a}}之信用卡於{{*}}消費NT${{p}}，{{e}}，感謝您的惠顧",
                "88"), body));
    }

    @Test
    public void lineStyleNotificationPasses() {
        // LINE 官方帳號式通知：標題＝銀行名，內文無末四碼、金額無千分位
        String body = "國泰世華銀行 國泰世華銀行 信用卡消費通知：您的信用卡於昨日消費新臺幣650元整";
        assertNull(TemplateGenerator.validate(template(
                "國泰世華銀行 國泰世華銀行 信用卡消費通知：您的信用卡於{{*}}消費新臺幣{{p}}元整",
                "650"), body));
    }
}
