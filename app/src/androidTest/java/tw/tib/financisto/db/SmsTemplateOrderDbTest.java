package tw.tib.financisto.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import tw.tib.financisto.model.SmsTemplate;

/**
 * 候選樣板的比對順位。
 *
 * 跑在裝置上是因為順位就是那句 SQL，而且牽涉 schema 的預設值與 ORM 的 insert 行為——三者
 * 加起來的結果只有真的 SQLite 答得準。順位不是排版問題：{@code SmsTransactionProcessor}
 * 是「第一條比中且建得成就收工」，排在後面的樣板等於不存在，所以順位錯＝樣板靜默失效，
 * 不會有任何錯誤訊息。
 *
 * 自己插自己刪，用一段高位 id 與一個不可能撞到的 title 避開真實資料。
 */
@RunWith(AndroidJUnit4.class)
public class SmsTemplateOrderDbTest {

    private static final long BASE = 900000000L;
    private static final String TITLE = "ZZ 測試銀行 ZZ";

    /** 長度刻意不同：同順位時由 length(template) desc 決勝。 */
    private static final long LONG_ID = BASE + 1;
    private static final String LONG_TPL = "ZZ 測試銀行 ZZ 消費{{p}}元 末四碼{{a}} 商店{{e}} 授權碼：";
    private static final long SHORT_ID = BASE + 2;
    private static final String SHORT_TPL = "ZZ 測試銀行 ZZ 消費{{p}}元{{*}}商店{{e}}，";
    private static final long OTHER_ID = BASE + 3;
    private static final String OTHER_TPL = "ZZ 測試銀行 ZZ {{p}}元{{*}}店{{e}}。";

    private DatabaseAdapter db;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = new DatabaseAdapter(context);
        db.open();
        cleanUp();
        // sort_order 一律 0：還原過備份的資料庫就是這個狀態（該欄不進備份，還原時走原生
        // insert 吃 `not null default 0`），也是這些測試要守的真實起點
        insertRestored(LONG_ID, LONG_TPL);
        insertRestored(SHORT_ID, SHORT_TPL);
        insertRestored(OTHER_ID, OTHER_TPL);
    }

    @After
    public void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        db.db().execSQL("DELETE FROM sms_template WHERE _id >= " + BASE);
    }

    /** 模擬「還原備份後」的列：sort_order 是預設值 0，不是 app 內新增時的 max+1。 */
    private void insertRestored(long id, String template) {
        db.db().execSQL("INSERT INTO sms_template"
                + " (_id, title, template, category_id, payee_id, project_id,"
                + "  account_id, to_account_id, is_income, sort_order)"
                + " VALUES (?,?,?,?,?,?,?,?,?,0)",
                new Object[]{id, TITLE, template, 0L, 0L, 0L, -1L, -1L, 0L});
    }

    private long sortOrderOf(long id) {
        try (Cursor c = db.db().rawQuery(
                "SELECT sort_order FROM sms_template WHERE _id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? c.getLong(0) : Long.MIN_VALUE;
        }
    }

    private long[] orderedIds() {
        List<SmsTemplate> list = db.getSmsTemplatesByPkgTitle(null, TITLE);
        long[] ids = new long[list.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = list.get(i).id;
        return ids;
    }

    /** 基準行為：sort_order 同分（＝還原後的常態）時，長的排前面。 */
    @Test
    public void tiedSortOrderFallsBackToLongestFirst() {
        long[] ids = orderedIds();
        assertEquals(3, ids.length);
        assertEquals("最長的該在最前", LONG_ID, ids[0]);
        assertEquals("最短的該在最後", OTHER_ID, ids[2]);
    }

    /**
     * 置頂要真的贏過那堆 sort_order=0 的既有樣板——所以它必須是負數。
     * 給 0 只會跟大家同分、退回長度決勝；給正數更糟，直接排到全部後面。
     */
    @Test
    public void movedToTopBeatsRestoredTemplates() {
        db.moveSmsTemplateToTop(SHORT_ID);

        long so = sortOrderOf(SHORT_ID);
        assertTrue("置頂後 sort_order 必須是負的，才排得到那堆 0 前面，實際=" + so, so < 0);
        assertEquals("置頂的樣板該排第一", SHORT_ID, orderedIds()[0]);
    }

    /** 後置頂的要壓過先置頂的：每次都取當前最小值再減一。 */
    @Test
    public void latestMoveToTopWins() {
        db.moveSmsTemplateToTop(SHORT_ID);
        db.moveSmsTemplateToTop(OTHER_ID);

        long[] ids = orderedIds();
        assertEquals("最後置頂的排第一", OTHER_ID, ids[0]);
        assertEquals("先置頂的排第二", SHORT_ID, ids[1]);
        assertEquals("沒動過的沉到最後", LONG_ID, ids[2]);
    }

    /**
     * 置頂必須在 saveOrUpdate 之後單獨下。{@code EntityManager} 在 insert 時看到
     * sort_order {@code <= 0} 會擅自改成 {@code max + 1}（＝排到最後），所以「存檔前先把
     * sortOrder 設成負數」不但沒用，還正好觸發那段把它踢到隊尾。
     */
    @Test
    public void presettingNegativeSortOrderBeforeSaveDoesNotWork() {
        SmsTemplate t = new SmsTemplate();
        t.title = TITLE;
        t.template = "ZZ 測試銀行 ZZ 存檔前設負數{{p}}元{{*}}店{{e}}。";
        // description / note 在 schema 上是 not null，走 ORM 存檔時得自己給值
        t.description = "";
        t.note = "";
        t.sortOrder = -99;
        long id = db.saveOrUpdate(t);
        try {
            long so = sortOrderOf(id);
            assertTrue("ORM 會把 <=0 的順位改成 max+1，實際=" + so, so > 0);
            assertEquals("所以它會排在最後，不是最前", id, orderedIds()[3]);

            db.moveSmsTemplateToTop(id);
            assertEquals("存完再置頂才有效", id, orderedIds()[0]);
        } finally {
            db.db().execSQL("DELETE FROM sms_template WHERE _id=?", new Object[]{id});
        }
    }
}
