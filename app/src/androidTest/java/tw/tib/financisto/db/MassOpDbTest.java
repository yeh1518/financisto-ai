package tw.tib.financisto.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.Project;

/**
 * 批次改分類／專案／備註的資料層不變式。
 *
 * 跑在裝置上是因為要真的 SQLite（這幾個方法就是 SQL），桌面單元測試碰不到。用 UI 驗這幾條
 * 也遠不如直接斷言——分割交易被跳過、回傳列數對不對，都是點畫面看不出來的東西。
 *
 * 自己插自己刪，用一段高位 id 避開既有資料；每條測試都在真實 DB 上跑，但只碰自己那幾列。
 */
@RunWith(AndroidJUnit4.class)
public class MassOpDbTest {

    /** 遠離真實資料的 id 區段。 */
    private static final long BASE = 900000000L;
    private static final long PLAIN_A = BASE + 1;
    private static final long PLAIN_B = BASE + 2;
    private static final long SPLIT_PARENT = BASE + 10;
    private static final long SPLIT_CHILD_1 = BASE + 11;
    private static final long SPLIT_CHILD_2 = BASE + 12;

    /** 測試用的分類／專案 id：只寫進 transactions 的欄位，不必真的存在也驗得出行為。 */
    private static final long CAT_OLD = BASE + 100;
    private static final long CAT_NEW = BASE + 101;
    private static final long PROJ_NEW = BASE + 200;

    private DatabaseAdapter db;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = new DatabaseAdapter(context);
        db.open();
        cleanUp();
        insert(PLAIN_A, CAT_OLD, 0, "原有備註", 0);
        insert(PLAIN_B, CAT_OLD, 0, null, 0);
        insert(SPLIT_PARENT, Category.SPLIT_CATEGORY_ID, 0, "分割父", 0);
        insert(SPLIT_CHILD_1, CAT_OLD, 0, "子一", SPLIT_PARENT);
        insert(SPLIT_CHILD_2, CAT_OLD, 0, "子二", SPLIT_PARENT);
    }

    @After
    public void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        db.db().execSQL("DELETE FROM transactions WHERE _id >= " + BASE
                + " OR parent_id >= " + BASE);
    }

    private void insert(long id, long categoryId, long projectId, String note, long parentId) {
        db.db().execSQL("INSERT INTO transactions"
                + " (_id, from_account_id, to_account_id, category_id, project_id, note,"
                + "  from_amount, to_amount, datetime, status, parent_id, is_template)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                new Object[]{id, 1L, 0L, categoryId, projectId, note, -100L, 0L, 1L, "PN",
                        parentId, 0L});
    }

    private long longOf(long id, String column) {
        try (Cursor c = db.db().rawQuery(
                "SELECT " + column + " FROM transactions WHERE _id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? c.getLong(0) : Long.MIN_VALUE;
        }
    }

    private String stringOf(long id, String column) {
        try (Cursor c = db.db().rawQuery(
                "SELECT " + column + " FROM transactions WHERE _id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    // --- 分類 ---

    /**
     * 改分類要跳過分割交易的**兩邊**：父交易的分類是分割佔位符（改掉分割結構就壞了），
     * 子交易各有自己的分類（全部蓋成同一個等於把分割的意義抹掉）。
     */
    @Test
    public void setCategorySkipsSplitParentAndChildren() {
        long[] ids = {PLAIN_A, PLAIN_B, SPLIT_PARENT};
        int affected = db.updateCategoryForSelectedTransactions(ids, CAT_NEW);

        assertEquals("只有兩筆一般交易該被改到", 2, affected);
        assertEquals(CAT_NEW, longOf(PLAIN_A, "category_id"));
        assertEquals(CAT_NEW, longOf(PLAIN_B, "category_id"));
        assertEquals("分割父交易的分類不能被動到",
                Category.SPLIT_CATEGORY_ID, longOf(SPLIT_PARENT, "category_id"));
        assertEquals("分割子交易的分類不能被連帶蓋掉",
                CAT_OLD, longOf(SPLIT_CHILD_1, "category_id"));
        assertEquals(CAT_OLD, longOf(SPLIT_CHILD_2, "category_id"));
    }

    /** 「回傳列數 < 選取數」就是呼叫端要跟使用者講「跳過幾筆」的依據。 */
    @Test
    public void affectedCountIsTheBasisForReportingSkips() {
        long[] onlySplit = {SPLIT_PARENT};
        assertEquals(0, db.updateCategoryForSelectedTransactions(onlySplit, CAT_NEW));
    }

    @Test
    public void clearCategorySetsNoCategory() {
        long[] ids = {PLAIN_A};
        assertEquals(1, db.updateCategoryForSelectedTransactions(ids, Category.NO_CATEGORY_ID));
        assertEquals(Category.NO_CATEGORY_ID, longOf(PLAIN_A, "category_id"));
    }

    // --- 專案 ---

    /** 專案是整筆的屬性，分割子交易本來也從父交易繼承，所以這裡**要**連帶改。 */
    @Test
    public void setProjectAlsoAppliesToSplitChildren() {
        long[] ids = {PLAIN_A, SPLIT_PARENT};
        int affected = db.updateProjectForSelectedTransactions(ids, PROJ_NEW);

        assertEquals("兩筆選取 + 兩個子交易", 4, affected);
        assertEquals(PROJ_NEW, longOf(PLAIN_A, "project_id"));
        assertEquals(PROJ_NEW, longOf(SPLIT_PARENT, "project_id"));
        assertEquals(PROJ_NEW, longOf(SPLIT_CHILD_1, "project_id"));
        assertEquals(PROJ_NEW, longOf(SPLIT_CHILD_2, "project_id"));
    }

    @Test
    public void clearProjectSetsNoProject() {
        long[] ids = {PLAIN_A};
        db.updateProjectForSelectedTransactions(ids, PROJ_NEW);
        db.updateProjectForSelectedTransactions(ids, Project.NO_PROJECT_ID);
        assertEquals(Project.NO_PROJECT_ID, longOf(PLAIN_A, "project_id"));
    }

    // --- 備註 ---

    /**
     * 備註是**附加**不是覆寫——覆寫會把自動記帳帶進來的商家／品項抹掉，那是抹了就沒了的東西。
     * 原本沒備註的直接填上、不留前導空白。
     */
    @Test
    public void appendNoteKeepsExistingTextAndFillsEmptyOnes() {
        long[] ids = {PLAIN_A, PLAIN_B};
        assertEquals(2, db.appendNoteToSelectedTransactions(ids, "8月對帳"));

        assertEquals("原有備註 8月對帳", stringOf(PLAIN_A, "note"));
        assertEquals("8月對帳", stringOf(PLAIN_B, "note"));
    }

    /** 附加兩次就該有兩段，不會互相取代——這是「附加」的定義。 */
    @Test
    public void appendNoteIsCumulative() {
        long[] ids = {PLAIN_B};
        db.appendNoteToSelectedTransactions(ids, "甲");
        db.appendNoteToSelectedTransactions(ids, "乙");
        assertEquals("甲 乙", stringOf(PLAIN_B, "note"));
    }

    /** 備註只寫在選取的那幾列，不進分割子交易（否則同一句話會出現在每一份上）。 */
    @Test
    public void appendNoteDoesNotTouchSplitChildren() {
        long[] ids = {SPLIT_PARENT};
        assertEquals(1, db.appendNoteToSelectedTransactions(ids, "標記"));
        assertEquals("分割父 標記", stringOf(SPLIT_PARENT, "note"));
        assertEquals("子一", stringOf(SPLIT_CHILD_1, "note"));
        assertEquals("子二", stringOf(SPLIT_CHILD_2, "note"));
    }

    // --- 邊界 ---

    @Test
    public void emptySelectionChangesNothing() {
        assertEquals(0, db.updateCategoryForSelectedTransactions(new long[0], CAT_NEW));
        assertEquals(0, db.updateProjectForSelectedTransactions(null, PROJ_NEW));
        assertEquals(0, db.appendNoteToSelectedTransactions(new long[0], "x"));
        assertEquals(CAT_OLD, longOf(PLAIN_A, "category_id"));
    }

    /** 樣板不能被批次動到（是 is_template=0 那個條件在守）。 */
    @Test
    public void templatesAreNeverTouched() {
        long templateId = BASE + 500;
        db.db().execSQL("INSERT INTO transactions"
                + " (_id, from_account_id, to_account_id, category_id, project_id, note,"
                + "  from_amount, to_amount, datetime, status, parent_id, is_template)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                new Object[]{templateId, 1L, 0L, CAT_OLD, 0L, "樣板", -100L, 0L, 1L, "PN", 0L, 1L});
        assertEquals(0, db.updateCategoryForSelectedTransactions(new long[]{templateId}, CAT_NEW));
        assertEquals(CAT_OLD, longOf(templateId, "category_id"));
    }

    /** 超過一個分批（100 筆）也要全部套到——分批是實作細節，不該漏掉尾巴。 */
    @Test
    public void appliesAcrossBucketBoundary() {
        long[] ids = new long[250];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = BASE + 1000 + i;
            insert(ids[i], CAT_OLD, 0, null, 0);
        }
        assertEquals(250, db.updateCategoryForSelectedTransactions(ids, CAT_NEW));
        assertEquals(CAT_NEW, longOf(ids[0], "category_id"));
        assertEquals(CAT_NEW, longOf(ids[99], "category_id"));
        assertEquals(CAT_NEW, longOf(ids[100], "category_id"));
        assertEquals(CAT_NEW, longOf(ids[249], "category_id"));
    }

    @Test
    public void nonExistentIdsAreHarmless() {
        assertEquals(0, db.updateCategoryForSelectedTransactions(
                new long[]{BASE + 999999}, CAT_NEW));
        assertNull(stringOf(BASE + 999999, "note"));
    }
}
