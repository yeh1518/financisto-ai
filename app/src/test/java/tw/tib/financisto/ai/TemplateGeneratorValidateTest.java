package tw.tib.financisto.ai;

import static org.junit.Assert.assertEquals;
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

    /** 台新式通知的「商家在最後、後面沒有固定文字」版本，用來測 {{e}} 的結束標記規則。 */
    private static final String TAIL_BODY =
            "台新銀行 台新銀行 您尾號8842之信用卡於07/22 12:34消費NT$1,250，全聯福利中心";

    @Test
    public void payeeFollowedByAnyPlaceholderFails() {
        // {{e}} 後面直接接 {{*}}：regex 求最短匹配，「全聯福利中心」只會抓到「全」。
        // 比對本身照樣成功，所以這關必須獨立擋
        String problem = TemplateGenerator.validate(template(
                "台新銀行 台新銀行 您尾號{{a}}之信用卡於{{*}}消費NT${{p}}，{{e}}{{*}}",
                "1,250"), BODY);
        assertNotNull(problem);
        assertTrue(problem.contains("{{e}}"));
    }

    @Test
    public void payeeAtEndOfTemplateFails() {
        String problem = TemplateGenerator.validate(template(
                "台新銀行 台新銀行 您尾號{{a}}之信用卡於{{*}}消費NT${{p}}，{{e}}",
                "1,250"), TAIL_BODY);
        assertNotNull(problem);
        assertTrue(problem.contains("{{e}}"));
    }

    @Test
    public void payeeAnchoredByLiteralTextPasses() {
        // 有結束標記（「，感謝您的惠顧」）就抓得到完整商家名
        assertNull(TemplateGenerator.validate(template(
                "台新銀行 台新銀行 您尾號{{a}}之信用卡於{{*}}消費NT${{p}}，{{e}}，感謝您的惠顧",
                "1,250"), BODY));
    }

    @Test
    public void anyPlaceholderAtEndIsFine() {
        // {{*}} 本身不捕捉，擺在結尾沒問題——規則只針對會捕捉的那幾個
        assertNull(TemplateGenerator.validate(template(
                "台新銀行 台新銀行 您尾號{{a}}之信用卡於{{*}}消費NT${{p}}，{{*}}",
                "1,250"), BODY));
    }

    @Test
    public void degenerateCaptureDetectedForEachPlaceholder() {
        for (String ph : new String[]{"{{c}}", "{{e}}", "{{r}}", "{{t}}", "{{x}}"}) {
            assertEquals(ph, TemplateGenerator.findDegenerateCapture("固定{{p}}文字" + ph));
            assertEquals(ph, TemplateGenerator.findDegenerateCapture("固定{{p}}文字" + ph + "{{*}}"));
            assertNull(TemplateGenerator.findDegenerateCapture("固定{{p}}文字" + ph + "結尾標記"));
        }
    }

    // --- 空白修補：模型抄固定文字時會把空白正規化掉，實地兩次都死在這 ---

    /** 2026-08-11 實地案例：模型吃掉了「在 商家 刷卡。」前後兩個半形空格，整條比不中。 */
    private static final String CUBE_BODY =
            "國泰世華銀行 【刷卡通知】金額NT$580元\n"
            + "卡號末四碼1234於 2026/01/02 03:04在 測試商店股份有限公司 刷卡。"
            + "立即以點數即時折抵，最高可折抵消費金額30%，點擊查看消費明細";
    private static final String CUBE_TEMPLATE_AS_GENERATED =
            "國泰世華銀行 【刷卡通知】金額NT${{p}}元\n卡號末四碼{{a}}於 {{*}}在{{e}}刷卡。{{*}}";

    @Test
    public void generatedTemplateWithSwallowedSpacesFailsBeforeRepair() {
        assertNotNull(TemplateGenerator.validate(
                template(CUBE_TEMPLATE_AS_GENERATED, "580"), CUBE_BODY));
    }

    @Test
    public void whitespaceRepairMakesItMatch() {
        String fixed = TemplateGenerator.repairWhitespace(CUBE_TEMPLATE_AS_GENERATED, CUBE_BODY);
        assertNull(TemplateGenerator.validate(template(fixed, "580"), CUBE_BODY));
        // 商家要整段抓到，不能只剩一個字
        String[] match = tw.tib.financisto.service.SmsTransactionProcessor
                .findTemplateMatches(fixed, CUBE_BODY);
        assertNotNull(match);
        assertEquals("測試商店股份有限公司",
                match[tw.tib.financisto.service.SmsTransactionProcessor.Placeholder.PAYEE.ordinal()]);
        assertEquals("1234",
                match[tw.tib.financisto.service.SmsTransactionProcessor.Placeholder.ACCOUNT.ordinal()]);
    }

    @Test
    public void whitespaceRepairFixesFullWidthSpaces() {
        // 全形空白版本（「卡　　號」被模型正規化成「卡號」）
        String body = "信用卡消費通知 消費金額：990元\n卡　　號：末四碼5678\n商店名稱：測試電商\n授權碼：123456";
        String tpl = "信用卡消費通知 消費金額：{{p}}元\n卡號：末四碼{{a}}\n商店名稱：{{e}}\n授權碼：{{*}}";
        String fixed = TemplateGenerator.repairWhitespace(tpl, body);
        assertNull(TemplateGenerator.validate(template(fixed, "990"), body));
    }

    @Test
    public void whitespaceRepairLeavesGoodTemplateAlone() {
        String good = "台新銀行 台新銀行 您尾號{{a}}之信用卡於{{*}}消費NT${{p}}，{{e}}，感謝您的惠顧";
        assertEquals(good, TemplateGenerator.repairWhitespace(good, BODY));
    }

    @Test
    public void whitespaceRepairGivesUpWhenLiteralIsReallyAbsent() {
        // 固定文字根本不在原文裡（模型抄錯字，不只是空白）→ 原樣退回，交給驗證擋
        String tpl = "國泰世華銀行 信用卡消費{{p}}元";
        assertEquals(tpl, TemplateGenerator.repairWhitespace(tpl, BODY));
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
