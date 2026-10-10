package tw.tib.financisto.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.Transaction;
import tw.tib.financisto.model.TransactionStatus;

/**
 * {@code 🧾分割} 訊息 → 一筆分割父交易＋各份子交易，真的寫進 DB 的形狀。
 *
 * 要驗的不是切欄位（那在 JVM 測試），而是「父金額＝總額且為負、子交易帶 parent_id、
 * 分類與備註各自落位、不存在的分類 id 留空、帳戶對不到整筆不記」——這些都要過 DB 才算數。
 */
@RunWith(AndroidJUnit4.class)
public class SplitTransactionDbTest {
    private static final long ACCOUNT_ID = 910000001L;
    private static final String ACCOUNT_TITLE = "乙銀行信用卡-配偶";

    private DatabaseAdapter db;
    private long catA, catB;
    /** 測試 DB 可能一個幣別都沒有；帳戶的 ORM 查詢會 join 幣別，沒有就查不到帳戶 */
    private long createdCurrencyId;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = new DatabaseAdapter(context);
        db.open();
        cleanUp();
        long currencyId = currencyId();
        if (currencyId == 0) {
            tw.tib.financisto.model.Currency c = new tw.tib.financisto.model.Currency();
            c.name = "TST";
            c.title = "測試幣";
            c.symbol = "T";
            createdCurrencyId = db.saveOrUpdate(c);
            currencyId = createdCurrencyId;
        }
        db.db().execSQL("INSERT INTO account (_id, title, type, currency_id, total_amount,"
                        + " is_active, is_include_into_totals, sort_order, creation_date, last_transaction_date)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                new Object[]{ACCOUNT_ID, ACCOUNT_TITLE, "CREDIT_CARD", currencyId, 0L, 1L, 1L, 0L, 0L, 0L});
        Category a = new Category();
        a.title = "分割測試A";
        catA = db.insertOrUpdate(a, new ArrayList<>());
        Category b = new Category();
        b.title = "分割測試B";
        catB = db.insertOrUpdate(b, new ArrayList<>());
    }

    @After
    public void tearDown() {
        cleanUp();
        if (catA > 0) db.deleteCategory(catA);
        if (catB > 0) db.deleteCategory(catB);
        if (createdCurrencyId > 0) db.deleteCurrency(createdCurrencyId);
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

    private String body(String parts) {
        return "NotifBot 🧾分割｜支出｜5654｜" + ACCOUNT_TITLE + "｜NeoMart｜1791605876000｜" + parts + "｜1/1 ";
    }

    @Test
    public void createsParentWithChildren() {
        Transaction t = new SplitTransactionProcessor(db).process(null, "NotifBot",
                body(catA + "=1539:保鮮膜、鱸魚;" + catB + "=857:零食;0=3258"), TransactionStatus.UR);
        assertNotNull("應該記成一筆", t);

        Transaction parent = db.getTransaction(t.id);
        assertEquals(ACCOUNT_ID, parent.fromAccountId);
        assertEquals(-565400L, parent.fromAmount);
        assertEquals(Category.SPLIT_CATEGORY_ID, parent.categoryId);
        assertEquals("NeoMart", parent.note);
        assertEquals(1791605876000L, parent.dateTime);
        assertEquals(TransactionStatus.UR, parent.status);
        assertEquals(0, parent.isTemplate);

        assertNotNull(parent.splits);
        assertEquals(3, parent.splits.size());
        long sum = 0;
        for (Transaction c : parent.splits) {
            assertEquals(t.id, c.parentId);
            assertEquals(ACCOUNT_ID, c.fromAccountId);
            assertEquals(parent.dateTime, c.dateTime);
            sum += c.fromAmount;
        }
        assertEquals("子交易加總＝父金額（未分配額為零）", parent.fromAmount, sum);
        assertEquals(catA, parent.splits.get(0).categoryId);
        assertEquals("保鮮膜、鱸魚", parent.splits.get(0).note);
        assertEquals(catB, parent.splits.get(1).categoryId);
        assertEquals(0, parent.splits.get(2).categoryId);
    }

    @Test
    public void unknownCategoryLeftEmptyButStillRecorded() {
        Transaction t = new SplitTransactionProcessor(db).process(null, "NotifBot",
                body("999999999=5654:x"), TransactionStatus.UR);
        assertNotNull(t);
        Transaction parent = db.getTransaction(t.id);
        assertEquals(1, parent.splits.size());
        assertEquals(0, parent.splits.get(0).categoryId);
        assertEquals(-565400L, parent.fromAmount);
    }

    @Test
    public void unknownAccountRecordsNothing() {
        Transaction t = new SplitTransactionProcessor(db).process(null, "NotifBot",
                "NotifBot 🧾分割｜支出｜100｜不存在的帳戶｜x｜｜1=100｜", TransactionStatus.UR);
        assertNull(t);
    }

    @Test
    public void invalidMessageRecordsNothing() {
        Transaction t = new SplitTransactionProcessor(db).process(null, "NotifBot",
                body(catA + "=100;" + catB + "=200"), TransactionStatus.UR);   // 加總 ≠ 總額
        assertNull(t);
        try (Cursor c = db.db().rawQuery("SELECT COUNT(*) FROM transactions WHERE from_account_id=?",
                new String[]{String.valueOf(ACCOUNT_ID)})) {
            c.moveToFirst();
            assertEquals(0, c.getLong(0));
        }
    }
}
