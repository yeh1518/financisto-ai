package tw.tib.financisto.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 從 prompt 搬進程式的兩條規則的測試。
 *
 * 搬過來的意義就在這裡：規則寫在 prompt 時沒有任何東西驗它有沒有被遵守，只能等出錯了
 * 從紀錄裡撈；寫成程式就能在這裡把每一種形狀釘死。
 */
public class BookkeepingParserPostProcessTest {

    private static final Map<String, Long> ACCOUNTS = new HashMap<>();
    static {
        ACCOUNTS.put("借款", 26L);
        ACCOUNTS.put("丙銀行信用卡", 48L);
        ACCOUNTS.put("甲銀行信用卡", 6L);
        ACCOUNTS.put("甲銀行存款", 5L);
        ACCOUNTS.put("身上現金", 8L);
        ACCOUNTS.put("郵政帳戶", 1L);
    }

    private static ParsedTransaction tx(String type, Long account, Long toAccount, String note) {
        ParsedTransaction t = new ParsedTransaction();
        t.transactionType = type;
        if (account != null) {
            t.account.id = account;
            t.account.confidence = 0.9;
        }
        if (toAccount != null) {
            t.toAccount.id = toAccount;
            t.toAccount.confidence = 0.9;
        }
        t.note = note;
        return t;
    }

    // --- to_account 只有 transfer 才有意義 ---

    @Test
    public void toAccountClearedOnExpense() {
        ParsedTransaction t = tx(ParsedTransaction.TYPE_EXPENSE, 8L, 5L, null);
        BookkeepingParser.clearToAccountUnlessTransfer(t);
        assertFalse(t.toAccount.resolved());
    }

    @Test
    public void toAccountKeptOnTransfer() {
        ParsedTransaction t = tx(ParsedTransaction.TYPE_TRANSFER, 1L, 5L, null);
        BookkeepingParser.clearToAccountUnlessTransfer(t);
        assertEquals(Long.valueOf(5L), t.toAccount.id);
    }

    @Test
    public void toAccountKeptWhenTypeUnchanged() {
        // 補充模式的 type=null＝「型別不變」，這時使用者可能正在補轉入帳戶，不能清掉
        ParsedTransaction t = tx(null, null, 5L, null);
        BookkeepingParser.clearToAccountUnlessTransfer(t);
        assertEquals(Long.valueOf(5L), t.toAccount.id);
    }

    // --- 備註被填成帳戶名 ---

    @Test
    public void accountNameInNoteFillsEmptyAccount() {
        // 「借款轉甲銀行帳號9000元」實測形狀：account 空著、帳戶名掉進 note
        ParsedTransaction t = tx(ParsedTransaction.TYPE_TRANSFER, null, 5L, "借款");
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "借款轉甲銀行帳號9000元");
        assertEquals(Long.valueOf(26L), t.account.id);
        assertNull(t.note);
    }

    @Test
    public void accountNameInNoteOverridesWrongAccount() {
        // 「丙銀行信用卡2383」實測形狀：note=丙銀行信用卡，帳戶卻挑了甲銀行信用卡
        ParsedTransaction t = tx(ParsedTransaction.TYPE_EXPENSE, 6L, null, "丙銀行信用卡");
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "丙銀行信用卡2383");
        assertEquals(Long.valueOf(48L), t.account.id);
        assertEquals(1.0, t.account.confidence, 0.0001);
        assertNull(t.note);
    }

    @Test
    public void accountNameNotInUtteranceOnlyStripsNote() {
        // 名字沒出現在原句裡＝模型自己生的字，沒有份量：清掉備註但不動帳戶
        ParsedTransaction t = tx(ParsedTransaction.TYPE_EXPENSE, 6L, null, "丙銀行信用卡");
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "刷卡2383");
        assertEquals(Long.valueOf(6L), t.account.id);
        assertNull(t.note);
    }

    @Test
    public void noteNamingTransferTargetDoesNotOverwriteSource() {
        // 轉帳時備註若是轉入帳戶的名字，那只是重複——拿去蓋轉出帳戶會把整筆轉帳弄反
        ParsedTransaction t = tx(ParsedTransaction.TYPE_TRANSFER, 1L, 5L, "甲銀行存款");
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "郵政轉甲銀行存款5000");
        assertEquals(Long.valueOf(1L), t.account.id);
        assertEquals(Long.valueOf(5L), t.toAccount.id);
        assertNull(t.note);
    }

    @Test
    public void redundantNoteMatchingChosenAccountIsStripped() {
        ParsedTransaction t = tx(ParsedTransaction.TYPE_EXPENSE, 8L, null, "身上現金");
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "身上現金415晚餐");
        assertEquals(Long.valueOf(8L), t.account.id);
        assertNull(t.note);
    }

    @Test
    public void realNoteIsUntouched() {
        ParsedTransaction t = tx(ParsedTransaction.TYPE_EXPENSE, 8L, null, "晚餐");
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "身上現金415晚餐");
        assertEquals("晚餐", t.note);
    }

    @Test
    public void partialAccountNameIsNotTouched() {
        // 「轉身上現金」不等於任何帳戶名——只有整段相符才算，避免誤殺有意義的描述
        ParsedTransaction t = tx(ParsedTransaction.TYPE_TRANSFER, 1L, 8L, "轉身上現金");
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "郵政轉身上現金3000元");
        assertEquals("轉身上現金", t.note);
        assertEquals(Long.valueOf(1L), t.account.id);
    }

    @Test
    public void nullNoteAndNullUtteranceAreSafe() {
        ParsedTransaction t = tx(ParsedTransaction.TYPE_EXPENSE, 8L, null, null);
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, null);
        assertNull(t.note);
        ParsedTransaction t2 = tx(ParsedTransaction.TYPE_EXPENSE, 6L, null, "借款");
        BookkeepingParser.applyAccountNamedInNote(t2, ACCOUNTS, null);
        assertEquals(Long.valueOf(6L), t2.account.id);   // 沒有原句可佐證就不動帳戶
        assertNull(t2.note);
        assertTrue(true);
    }

    // ---- 調整餘額＋分割要有「餘額字眼＋數字」佐證（requireBalanceCueForSplits）----

    private static ParsedTransaction balanceWithSplits(double amount, Double... shares) {
        ParsedTransaction t = tx(ParsedTransaction.TYPE_BALANCE, 8L, null, null);
        t.amount = amount;
        for (Double s : shares) {
            ParsedTransaction.Split sp = new ParsedTransaction.Split();
            sp.amount = s;
            t.splits.add(sp);
        }
        return t;
    }

    /** 實地誤判的形狀：「剩下的是食材」是其餘那一份，不是餘額。 */
    @Test
    public void remainderShareIsNotABalance() {
        ParsedTransaction t = balanceWithSplits(500, 200.0, null);
        BookkeepingParser.requireBalanceCueForSplits(t, "身上現金500塊其中200塊是午餐剩下的是食材", false);
        assertEquals(ParsedTransaction.TYPE_EXPENSE, t.transactionType);
        assertEquals(300.0, t.splits.get(1).amount, 0.001);       // 其餘那份補成總額扣掉已知份
    }

    @Test
    public void thenTheRestIsNotABalanceEither() {
        ParsedTransaction t = balanceWithSplits(800, 200.0, 300.0, null);
        BookkeepingParser.requireBalanceCueForSplits(t, "身上現金800元200元午餐，零食300然後剩下食材", false);
        assertEquals(ParsedTransaction.TYPE_EXPENSE, t.transactionType);
        assertEquals(300.0, t.splits.get(2).amount, 0.001);
    }

    /** 真的在報餘額：餘額字眼後面接著那個數字，維持調整餘額、殘額份留給 app 用差額補。 */
    @Test
    public void realBalanceWithSplitsStays() {
        String[] said = {
                "現金剩下752其中80是早餐剩下的是食材",
                "配偶身上剩下602 89是早餐其他的是西藥",
                "配偶街口50飲料，剩下3613",
                "現金餘額363午餐拉麵",
                "剩餘金額666早餐50其他食材",
                "現在有500其中200午餐",
        };
        for (String s : said) {
            ParsedTransaction t = balanceWithSplits(500, 50.0, null);
            BookkeepingParser.requireBalanceCueForSplits(t, s, false);
            assertEquals(s, ParsedTransaction.TYPE_BALANCE, t.transactionType);
            assertNull(s, t.splits.get(1).amount);
        }
    }

    @Test
    public void onlySplitsAreChecked() {
        // 沒分割的餘額判斷沒有出過問題，交給模型（「現在現金250」照舊是調整餘額）
        ParsedTransaction t = balanceWithSplits(250);
        BookkeepingParser.requireBalanceCueForSplits(t, "現在現金250", false);
        assertEquals(ParsedTransaction.TYPE_BALANCE, t.transactionType);
    }

    @Test
    public void supplementOnABalanceFormIsLeftAlone() {
        // 在調整餘額表單上補講一份：回 balance 是在描述原本的型別，不是要求切換
        ParsedTransaction t = balanceWithSplits(3613, 50.0, 120.0);
        BookkeepingParser.requireBalanceCueForSplits(t, "配偶街口120飲料", true);
        assertEquals(ParsedTransaction.TYPE_BALANCE, t.transactionType);
        // 補充模式明確要求改成調整餘額、卻沒有餘額佐證 → 一樣當支出
        ParsedTransaction t2 = balanceWithSplits(500, 200.0, null);
        t2.typeChange = true;
        BookkeepingParser.requireBalanceCueForSplits(t2, "500其中200午餐剩下的是食材", true);
        assertEquals(ParsedTransaction.TYPE_EXPENSE, t2.transactionType);
    }

    @Test
    public void noUtteranceMeansNoEvidenceToCheck() {
        ParsedTransaction t = balanceWithSplits(500, 200.0, null);
        BookkeepingParser.requireBalanceCueForSplits(t, "", false);
        assertEquals(ParsedTransaction.TYPE_BALANCE, t.transactionType);
    }

    @Test
    public void remainderLeftOpenWhenItCannotBeWorkedOut() {
        ParsedTransaction t = balanceWithSplits(500, null, null);   // 兩份都沒講金額
        BookkeepingParser.requireBalanceCueForSplits(t, "身上現金500早餐跟食材", false);
        assertEquals(ParsedTransaction.TYPE_EXPENSE, t.transactionType);
        assertNull(t.splits.get(0).amount);
        assertNull(t.splits.get(1).amount);
    }
}
