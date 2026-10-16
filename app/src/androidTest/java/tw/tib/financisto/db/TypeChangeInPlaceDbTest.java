package tw.tib.financisto.db;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import tw.tib.financisto.activity.FormSwitch;
import tw.tib.financisto.model.Transaction;

/**
 * 既有交易改型別（交易↔轉帳）是「原地更新那一筆」：同一張表裡兩者只差 to_account_id，
 * id 不變，帳戶總額與逐筆餘額由 updateTransaction 依新舊兩版重算。
 *
 * 這裡驗的是重算對不對：改型別前後，兩個帳戶的總額與每一列逐筆餘額都要跟交易本身對得上，
 * 而且改過去再改回來要完全回到原狀。用畫面看不出來，只看得到事後那句「逐筆餘額似乎不準確」。
 * 自己建帳戶、自己刪，用一段高位 id 避開既有資料。
 */
@RunWith(AndroidJUnit4.class)
public class TypeChangeInPlaceDbTest {

    private static final long BASE = 900200000L;
    private static final long A = BASE + 1;
    private static final long B = BASE + 2;
    private static final long T = 1700000000000L;

    private DatabaseAdapter db;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = new DatabaseAdapter(context);
        db.open();
        cleanUp();
        insertAccount(A, "甲帳戶");
        insertAccount(B, "乙帳戶");
    }

    @After
    public void tearDown() {
        cleanUp();
    }

    private void insertAccount(long id, String title) {
        db.db().execSQL("INSERT INTO account (_id, title, type, currency_id, total_amount,"
                        + " is_active, is_include_into_totals, sort_order, creation_date, last_transaction_date)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                new Object[]{id, title, "CASH", currencyId(), 0L, 1L, 1L, 0L, 0L, 0L});
    }

    private void cleanUp() {
        for (long id : new long[]{A, B}) {
            db.db().execSQL("DELETE FROM running_balance WHERE account_id = " + id);
            db.db().execSQL("DELETE FROM transactions WHERE from_account_id = " + id
                    + " OR to_account_id = " + id);
            db.db().execSQL("DELETE FROM account WHERE _id = " + id);
        }
    }

    private long currencyId() {
        try (Cursor c = db.db().rawQuery("SELECT _id FROM currency LIMIT 1", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    private long expense(long account, long amount, long dateTime) {
        Transaction t = new Transaction();
        t.fromAccountId = account;
        t.fromAmount = amount;
        t.dateTime = dateTime;
        return db.insertOrUpdate(t);
    }

    private long total(long account) {
        try (Cursor c = db.db().rawQuery("SELECT total_amount FROM account WHERE _id=?",
                new String[]{String.valueOf(account)})) {
            return c.moveToFirst() ? c.getLong(0) : Long.MIN_VALUE;
        }
    }

    private int transactionCount() {
        try (Cursor c = db.db().rawQuery("SELECT COUNT(*) FROM transactions WHERE from_account_id IN (?,?)"
                        + " OR to_account_id IN (?,?)",
                new String[]{String.valueOf(A), String.valueOf(B), String.valueOf(A), String.valueOf(B)})) {
            return c.moveToFirst() ? c.getInt(0) : -1;
        }
    }

    /** 帳戶的每一列逐筆餘額＝依 (datetime, _id) 累加到該筆為止的金額，最後一列＝帳戶總額。 */
    private void assertBooksAgree(long account, String when) {
        long expected = 0;
        int rows = 0;
        try (Cursor c = db.db().rawQuery(
                "SELECT t._id, CASE WHEN t.from_account_id = ? THEN t.from_amount ELSE t.to_amount END,"
                        + " rb.balance FROM transactions t"
                        + " LEFT JOIN running_balance rb ON rb.transaction_id = t._id AND rb.account_id = ?"
                        + " WHERE (t.from_account_id = ? OR t.to_account_id = ?) AND t.parent_id = 0"
                        + " ORDER BY t.datetime, t._id",
                new String[]{String.valueOf(account), String.valueOf(account),
                        String.valueOf(account), String.valueOf(account)})) {
            while (c.moveToNext()) {
                expected += c.getLong(1);
                rows++;
                assertEquals(when + "：帳戶 " + account + " 交易 " + c.getLong(0) + " 那一列的逐筆餘額",
                        expected, c.getLong(2));
            }
        }
        assertEquals(when + "：帳戶 " + account + " 的總額", expected, total(account));
        try (Cursor c = db.db().rawQuery("SELECT COUNT(*) FROM running_balance WHERE account_id=?",
                new String[]{String.valueOf(account)})) {
            c.moveToFirst();
            assertEquals(when + "：帳戶 " + account + " 不該留下對不到交易的逐筆餘額", rows, c.getInt(0));
        }
    }

    /** 表單切換存檔時做的事：拿原筆的 id，帶著新型別的欄位 insertOrUpdate。 */
    private void saveInPlace(long id, long from, long to, long fromAmount, long toAmount) {
        Transaction t = db.getTransaction(id);
        t.fromAccountId = from;
        t.toAccountId = to;
        t.fromAmount = fromAmount;
        t.toAmount = toAmount;
        assertEquals(id, db.insertOrUpdate(t));
    }

    @Test
    public void expenseBecomesTransferAndBackInPlace() {
        expense(A, -10000, T - 1000);
        long id = expense(A, -500, T);
        expense(B, -200, T + 1000);
        int count = transactionCount();

        saveInPlace(id, A, B, -500, 500);
        assertEquals("原地更新、不多一筆", count, transactionCount());
        assertEquals(-10500, total(A));
        assertEquals(300, total(B));
        assertBooksAgree(A, "支出改成轉帳");
        assertBooksAgree(B, "支出改成轉帳");

        saveInPlace(id, A, 0, -500, 0);
        assertEquals(-10500, total(A));
        assertEquals(-200, total(B));
        assertBooksAgree(A, "改回支出");
        assertBooksAgree(B, "改回支出");
    }

    @Test
    public void incomeBecomesTransferIntoTheSameAccount() {
        long id = expense(A, 800, T);
        saveInPlace(id, B, A, -800, 800);       // 收入切轉帳：帳戶在轉入側
        assertEquals(800, total(A));
        assertEquals(-800, total(B));
        assertBooksAgree(A, "收入改成轉入");
        assertBooksAgree(B, "收入改成轉入");
    }

    /**
     * 既有交易切到調整餘額、報出數到的餘額：目前餘額要扣掉這筆自己（FormSwitch.balanceExcluding），
     * 存檔後帳戶總額要剛好等於報的數。
     */
    @Test
    public void existingExpenseRestatedThroughBalanceAdjust() {
        expense(A, -10000, T - 1000);
        long id = expense(A, -150, T);
        Transaction old = db.getTransaction(id);
        long base = FormSwitch.balanceExcluding(total(A), A,
                old.fromAccountId, old.toAccountId, old.fromAmount, old.toAmount);
        long counted = -10170;
        saveInPlace(id, A, 0, counted - base, 0);
        assertEquals(-170, db.getTransaction(id).fromAmount);
        assertEquals(counted, total(A));
        assertBooksAgree(A, "既有支出用數到的餘額修正");
    }

    @Test
    public void existingTransferRestatedOnItsDestination() {
        Transaction t = new Transaction();
        t.fromAccountId = A;
        t.toAccountId = B;
        t.fromAmount = -500;
        t.toAmount = 500;
        t.dateTime = T;
        long id = db.insertOrUpdate(t);
        Transaction old = db.getTransaction(id);
        long base = FormSwitch.balanceExcluding(total(B), B,
                old.fromAccountId, old.toAccountId, old.fromAmount, old.toAmount);
        saveInPlace(id, B, 0, 450 - base, 0);    // 乙數到 450：這筆變成乙的一筆 +450，甲的 −500 退回
        assertEquals(450, total(B));
        assertEquals(0, total(A));
        assertBooksAgree(A, "既有轉帳在轉入側修正");
        assertBooksAgree(B, "既有轉帳在轉入側修正");
    }

    /** 帳戶從轉入側換到轉出側：轉帳 乙→甲 改成甲的收入。逐筆餘額那一列不能被當成舊的轉入列刪掉。 */
    @Test
    public void transferIntoAnAccountBecomesItsIncome() {
        Transaction t = new Transaction();
        t.fromAccountId = B;
        t.toAccountId = A;
        t.fromAmount = -800;
        t.toAmount = 800;
        t.dateTime = T;
        long id = db.insertOrUpdate(t);
        expense(A, -100, T + 1000);

        saveInPlace(id, A, 0, 800, 0);
        assertEquals(700, total(A));
        assertEquals(0, total(B));
        assertBooksAgree(A, "轉入改成收入");
        assertBooksAgree(B, "轉入改成收入");
    }

    @Test
    public void transferBecomesExpenseOnTheSourceAccount() {
        Transaction t = new Transaction();
        t.fromAccountId = A;
        t.toAccountId = B;
        t.fromAmount = -700;
        t.toAmount = 700;
        t.dateTime = T;
        long id = db.insertOrUpdate(t);
        expense(B, -50, T);                      // 同一時間另一筆：撞同秒的那條路也一起驗

        saveInPlace(id, A, 0, -700, 0);
        assertEquals(-700, total(A));
        assertEquals(-50, total(B));
        assertBooksAgree(A, "轉帳改成支出");
        assertBooksAgree(B, "轉帳改成支出");
    }
}
