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

import tw.tib.financisto.model.Transaction;

/**
 * 逐筆餘額（running_balance）在「同一時間有多筆」時的不變式。
 *
 * 帳戶總額與逐筆餘額是兩條各自維護的帳，對不上時畫面會跳「逐筆餘額似乎不準確」。維護逐筆餘額
 * 的 SQL 原本用 `datetime > ?` 決定「哪些列要跟著加減」，但這張表的排序鍵是
 * (datetime, transaction_id) 兩欄——同一時間的其他交易因此被漏掉，帳就歪了。
 *
 * 同一時間同帳戶兩筆並不罕見：AI 補充模式改型別是「建新筆＋刪原筆」而且刻意沿用原本的時間，
 * 每次都會撞上（2026-08-23 實地重現）；手動同秒記兩筆也會。
 *
 * 跑在裝置上是因為要真的 SQLite 與真的 DatabaseAdapter 流程；這幾條用畫面看不出來，
 * 只看得到事後那句「似乎不準確」。自己建帳戶、自己刪，用一段高位 id 避開既有資料。
 */
@RunWith(AndroidJUnit4.class)
public class RunningBalanceDbTest {

    private static final long BASE = 900100000L;
    private static final long ACCOUNT_ID = BASE + 1;
    /** 固定時間戳，不用當下時間——測試不該因為跑的時刻不同而有不同行為。 */
    private static final long T = 1700000000000L;

    private DatabaseAdapter db;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = new DatabaseAdapter(context);
        db.open();
        cleanUp();
        db.db().execSQL("INSERT INTO account (_id, title, type, currency_id, total_amount,"
                + " is_active, is_include_into_totals, sort_order, creation_date, last_transaction_date)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                new Object[]{ACCOUNT_ID, "測試帳戶", "CASH", currencyId(), 0L, 1L, 1L, 0L, 0L, 0L});
    }

    @After
    public void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        db.db().execSQL("DELETE FROM running_balance WHERE account_id = " + ACCOUNT_ID);
        db.db().execSQL("DELETE FROM transactions WHERE from_account_id = " + ACCOUNT_ID
                + " OR to_account_id = " + ACCOUNT_ID);
        db.db().execSQL("DELETE FROM account WHERE _id = " + ACCOUNT_ID);
    }

    private long currencyId() {
        try (Cursor c = db.db().rawQuery("SELECT _id FROM currency LIMIT 1", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    private long insertTx(long amount, long dateTime) {
        Transaction t = new Transaction();
        t.fromAccountId = ACCOUNT_ID;
        t.fromAmount = amount;
        t.dateTime = dateTime;
        return db.insertOrUpdate(t);
    }

    private long accountTotal() {
        try (Cursor c = db.db().rawQuery("SELECT total_amount FROM account WHERE _id=?",
                new String[]{String.valueOf(ACCOUNT_ID)})) {
            return c.moveToFirst() ? c.getLong(0) : Long.MIN_VALUE;
        }
    }

    private long lastRunningBalance() {
        try (Cursor c = db.db().rawQuery("SELECT balance FROM running_balance WHERE account_id=?"
                        + " ORDER BY datetime DESC, transaction_id DESC LIMIT 1",
                new String[]{String.valueOf(ACCOUNT_ID)})) {
            return c.moveToFirst() ? c.getLong(0) : Long.MIN_VALUE;
        }
    }

    /**
     * 兩條帳要一直對得上。
     *
     * 兩層都驗：(1) 最後一列＝帳戶總額，這是 app 那句「逐筆餘額似乎不準確」在檢查的東西；
     * (2) **每一列**都等於依 (datetime, transaction_id) 累加到該筆為止的金額——只驗最後一列
     * 會漏掉「中間某列算錯、但最後一列剛好對」的情況，而中間那幾列正是使用者在明細上看到的數字。
     */
    private void assertBooksAgree(String when) {
        assertEquals(when + "：逐筆餘額的最後一列應等於帳戶總額",
                accountTotal(), lastRunningBalance());
        long expected = 0;
        try (Cursor c = db.db().rawQuery(
                "SELECT t._id, t.from_amount, rb.balance FROM transactions t"
                        + " LEFT JOIN running_balance rb"
                        + " ON rb.transaction_id = t._id AND rb.account_id = t.from_account_id"
                        + " WHERE t.from_account_id = ? ORDER BY t.datetime, t._id",
                new String[]{String.valueOf(ACCOUNT_ID)})) {
            while (c.moveToNext()) {
                expected += c.getLong(1);
                assertEquals(when + "：交易 " + c.getLong(0) + " 那一列的逐筆餘額",
                        expected, c.getLong(2));
            }
        }
    }

    @Test
    public void twoTransactionsAtTheSameTimeStayInSync() {
        insertTx(-50000, T);
        insertTx(-30000, T);
        assertBooksAgree("同一時間記兩筆");
        assertEquals(-80000L, accountTotal());
    }

    /**
     * 這條是實地那個 bug 的最小重現：同一時間兩筆，刪掉**排在前面**那筆。
     * 修好之前，後面那筆的逐筆餘額不會跟著減，帳戶總額與逐筆餘額就從此差一個被刪的金額。
     */
    @Test
    public void deletingTheEarlierOfTwoAtTheSameTimeKeepsBooksInSync() {
        long first = insertTx(-50000, T);
        insertTx(-30000, T);

        db.deleteTransaction(first);

        assertBooksAgree("刪掉同一時間的前一筆");
        assertEquals(-30000L, accountTotal());
    }

    /** 刪後面那筆本來就沒事（沒有「更後面的列」要調整），一起釘住免得修法只顧一邊。 */
    @Test
    public void deletingTheLaterOfTwoAtTheSameTimeKeepsBooksInSync() {
        insertTx(-50000, T);
        long second = insertTx(-30000, T);

        db.deleteTransaction(second);

        assertBooksAgree("刪掉同一時間的後一筆");
        assertEquals(-50000L, accountTotal());
    }

    /** 對照組：時間不同時本來就是對的，確認修法沒把原本正常的情況弄壞。 */
    @Test
    public void transactionsAtDifferentTimesStayInSync() {
        long first = insertTx(-50000, T);
        insertTx(-30000, T + 60000);

        db.deleteTransaction(first);

        assertBooksAgree("刪掉較早的一筆");
        assertEquals(-30000L, accountTotal());
    }

    /** 在「已經有一筆」的同一時間插新筆，新筆的餘額基準不能取到排在自己後面的鄰居。 */
    @Test
    public void insertingAtTheSameTimeAsAnExistingOneKeepsBooksInSync() {
        insertTx(-50000, T);
        insertTx(-30000, T);
        insertTx(-20000, T);

        assertBooksAgree("同一時間第三筆");
        assertEquals(-100000L, accountTotal());
    }

    /**
     * 改時間讓一筆**舊 id** 撞上一筆**新 id** 的時間。
     *
     * 這條打的是另外半邊：算新列餘額時要取「排在自己前面那一列」，同樣得用
     * (datetime, transaction_id) 比。只比 datetime 的話，會把「同時間但排在自己後面」的鄰居
     * 當成前一筆，基準就取錯了——而這筆的 id 比鄰居小，正是會踩到的形狀。
     */
    @Test
    public void movingAnOlderTransactionOntoALaterOnesTimeKeepsBooksInSync() {
        long older = insertTx(-50000, T);
        insertTx(-30000, T + 60000);

        Transaction moved = db.getTransaction(older);
        moved.dateTime = T + 60000;
        db.insertOrUpdate(moved);

        assertBooksAgree("把舊 id 那筆的時間改到與新 id 那筆相同");
        assertEquals(-80000L, accountTotal());
    }
}
