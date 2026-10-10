package tw.tib.financisto.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.math.BigDecimal;

/**
 * {@code 🧾分割} 訊息的切欄位。
 *
 * 這條路與樣板路線一樣是靜默失敗（不記就是不記、不會叫），所以把「合法訊息每個欄位落點」
 * 與「每一種不合法都被擋下、理由講得出來」兩類釘住。加總不符一定要擋：分割父交易的未分配額
 * 必須為零，放過去就是一筆壞帳。
 */
public class SplitMessageTest {

    private static final String OK =
            "NotifBot 🧾分割｜支出｜5654｜乙銀行信用卡-配偶｜NeoMart｜1791605876000｜"
                    + "30=529:保鮮膜;11=857:起司球、蛋捲;3=464:奶茶、牛奶;76=639:益生菌;82=2155:蝦仁、魚片;102=1010:代買 鱸魚｜1/1";

    @Test
    public void parsesAllFields() {
        SplitMessage m = SplitMessage.parse(OK);
        assertFalse(m.income);
        assertEquals(new BigDecimal("5654"), m.total);
        assertEquals("乙銀行信用卡-配偶", m.accountName);
        assertEquals("NeoMart", m.note);
        assertEquals(Long.valueOf(1791605876000L), m.timestampMillis);
        assertEquals(6, m.parts.size());
        assertEquals(30, m.parts.get(0).categoryId);
        assertEquals(new BigDecimal("529"), m.parts.get(0).amount);
        assertEquals("保鮮膜", m.parts.get(0).note);
        assertEquals(102, m.parts.get(5).categoryId);
        assertEquals("代買 鱸魚", m.parts.get(5).note);
    }

    @Test
    public void incomeDirectionAndDecimals() {
        SplitMessage m = SplitMessage.parse("🧾分割｜收入｜100.5｜甲銀行存款｜｜｜1=50.25:a;2=50.25｜");
        assertTrue(m.income);
        assertEquals(new BigDecimal("100.5"), m.total);
        assertEquals("", m.note);
        assertNull("空時間戳＝沒帶", m.timestampMillis);
        assertEquals("", m.parts.get(1).note);
    }

    @Test
    public void partWithoutNoteAndZeroCategory() {
        SplitMessage m = SplitMessage.parse("🧾分割｜支出｜30｜甲銀行存款｜x｜｜0=10;5=20｜2/2");
        assertEquals(0, m.parts.get(0).categoryId);
        assertEquals("", m.parts.get(0).note);
    }

    @Test
    public void toleratesSpacesAroundSeparators() {
        SplitMessage m = SplitMessage.parse("🧾分割｜ 支出 ｜ 30 ｜ 甲銀行存款 ｜ x ｜ ｜ 1 = 10 : a ; 2 = 20 ｜");
        assertEquals("甲銀行存款", m.accountName);
        assertEquals(2, m.parts.size());
        assertEquals("a", m.parts.get(0).note);
    }

    @Test
    public void isSplitMessageOnlyLooksAtTrigger() {
        assertTrue(SplitMessage.isSplitMessage("NotifBot 🧾分割｜亂七八糟"));
        assertFalse(SplitMessage.isSplitMessage("NotifBot 🧾記帳｜支出｜250｜甲銀行信用卡｜17｜x｜1785761364401｜1/3"));
        assertFalse(SplitMessage.isSplitMessage(null));
    }

    @Test
    public void rejectsSumMismatch() {
        assertRejected("🧾分割｜支出｜100｜甲銀行存款｜x｜｜1=60;2=50｜", "加總");
    }

    @Test
    public void rejectsBadDirection() {
        assertRejected("🧾分割｜轉帳｜100｜甲銀行存款｜x｜｜1=100｜", "方向");
    }

    @Test
    public void rejectsMissingFields() {
        assertRejected("🧾分割｜支出｜100｜甲銀行存款｜1=100", "欄位數");
    }

    @Test
    public void rejectsEmptyAccountAndParts() {
        assertRejected("🧾分割｜支出｜100｜｜x｜｜1=100｜", "帳戶");
        assertRejected("🧾分割｜支出｜100｜甲銀行存款｜x｜｜｜", "沒有任何份");
    }

    @Test
    public void rejectsNonNumeric() {
        assertRejected("🧾分割｜支出｜1,000｜甲銀行存款｜x｜｜1=1000｜", "總額");
        assertRejected("🧾分割｜支出｜100｜甲銀行存款｜x｜abc｜1=100｜", "時間戳");
        assertRejected("🧾分割｜支出｜100｜甲銀行存款｜x｜｜a=100｜", "分類 id");
        assertRejected("🧾分割｜支出｜100｜甲銀行存款｜x｜｜1=-100｜", "正數");
        assertRejected("🧾分割｜支出｜100｜甲銀行存款｜x｜｜1:100｜", "份的格式");
    }

    private static void assertRejected(String body, String reasonContains) {
        try {
            SplitMessage.parse(body);
            fail("應該被擋下：" + body);
        } catch (IllegalArgumentException e) {
            assertTrue("理由應提到「" + reasonContains + "」，實際：" + e.getMessage(),
                    e.getMessage().contains(reasonContains));
        }
    }
}
