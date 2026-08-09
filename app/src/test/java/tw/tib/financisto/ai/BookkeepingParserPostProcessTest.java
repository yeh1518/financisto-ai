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
        ACCOUNTS.put("玉山信用卡", 48L);
        ACCOUNTS.put("中信信用卡", 6L);
        ACCOUNTS.put("中信帳戶總覽", 5L);
        ACCOUNTS.put("身上現金", 8L);
        ACCOUNTS.put("郵局總覽", 1L);
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
        // 「借款轉中信帳號9000元」實測形狀：account 空著、帳戶名掉進 note
        ParsedTransaction t = tx(ParsedTransaction.TYPE_TRANSFER, null, 5L, "借款");
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "借款轉中信帳號9000元");
        assertEquals(Long.valueOf(26L), t.account.id);
        assertNull(t.note);
    }

    @Test
    public void accountNameInNoteOverridesWrongAccount() {
        // 「玉山信用卡2383」實測形狀：note=玉山信用卡，帳戶卻挑了中信信用卡
        ParsedTransaction t = tx(ParsedTransaction.TYPE_EXPENSE, 6L, null, "玉山信用卡");
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "玉山信用卡2383");
        assertEquals(Long.valueOf(48L), t.account.id);
        assertEquals(1.0, t.account.confidence, 0.0001);
        assertNull(t.note);
    }

    @Test
    public void accountNameNotInUtteranceOnlyStripsNote() {
        // 名字沒出現在原句裡＝模型自己生的字，沒有份量：清掉備註但不動帳戶
        ParsedTransaction t = tx(ParsedTransaction.TYPE_EXPENSE, 6L, null, "玉山信用卡");
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "刷卡2383");
        assertEquals(Long.valueOf(6L), t.account.id);
        assertNull(t.note);
    }

    @Test
    public void noteNamingTransferTargetDoesNotOverwriteSource() {
        // 轉帳時備註若是轉入帳戶的名字，那只是重複——拿去蓋轉出帳戶會把整筆轉帳弄反
        ParsedTransaction t = tx(ParsedTransaction.TYPE_TRANSFER, 1L, 5L, "中信帳戶總覽");
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "郵局轉中信帳戶總覽5000");
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
        BookkeepingParser.applyAccountNamedInNote(t, ACCOUNTS, "郵局轉身上現金3000元");
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
}
