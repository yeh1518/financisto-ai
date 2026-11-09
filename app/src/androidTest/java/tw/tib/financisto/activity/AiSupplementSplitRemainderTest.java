package tw.tib.financisto.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import tw.tib.financisto.ai.ParsedTransaction;
import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.model.Transaction;

/**
 * 在已有金額的支出上用補充模式講「250 是拖鞋、剩下的是零食」：模型回兩份，第二份 amount=null
 * （殘額份）。表單要變成分割、總額維持原本的 875，殘額份補成 875 − 250 = 625。
 *
 * 餵給表單的是模型回應轉成的 {@link ParsedTransaction}（不呼叫模型），測的是 app 套用那一段。
 * 自己建帳戶、分類用 0（未分類）——這裡只驗金額怎麼分。
 */
@RunWith(AndroidJUnit4.class)
public class AiSupplementSplitRemainderTest {

    private static final String TAG = "AiSplitRemainder";
    private static final long ACCOUNT = 900300001L;

    private DatabaseAdapter db;
    private long tranId;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = new DatabaseAdapter(context);
        db.open();
        cleanUp();
        db.db().execSQL("INSERT INTO account (_id, title, type, currency_id, total_amount,"
                        + " is_active, is_include_into_totals, sort_order, creation_date, last_transaction_date)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                new Object[]{ACCOUNT, "測試信用卡", "CREDIT_CARD", currencyId(), 0L, 1L, 1L, 0L, 0L, 0L});
        Transaction t = new Transaction();
        t.fromAccountId = ACCOUNT;
        t.fromAmount = -87500;
        t.dateTime = 1762568100000L;
        t.note = "刷卡通知 875 元";
        tranId = db.insertOrUpdate(t);
    }

    @After
    public void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        db.db().execSQL("DELETE FROM running_balance WHERE account_id = " + ACCOUNT);
        db.db().execSQL("DELETE FROM transactions WHERE from_account_id = " + ACCOUNT);
        db.db().execSQL("DELETE FROM account WHERE _id = " + ACCOUNT);
    }

    private long currencyId() {
        try (Cursor c = db.db().rawQuery("SELECT _id FROM currency LIMIT 1", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    /** 模型那次的回應：splits=[{250, 拖鞋}, {null}]，其餘欄位全 null。 */
    private static ParsedTransaction knownPlusRemainder() {
        ParsedTransaction t = new ParsedTransaction();
        ParsedTransaction.Split known = new ParsedTransaction.Split();
        known.amount = 250.0;
        known.note = "拖鞋";
        ParsedTransaction.Split rest = new ParsedTransaction.Split();
        rest.amount = null;
        t.splits.add(known);
        t.splits.add(rest);
        return t;
    }

    @Test
    public void remainderShareTakesWhatIsLeftOfTheExistingAmount() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), TransactionActivity.class);
        intent.putExtra(AbstractTransactionActivity.TRAN_ID_EXTRA, tranId);
        try (ActivityScenario<TransactionActivity> scenario = ActivityScenario.launch(intent)) {
            AtomicLong before = new AtomicLong();
            AtomicLong after = new AtomicLong();
            AtomicReference<String> state = new AtomicReference<>();
            scenario.onActivity(a -> {
                before.set(a.rateView.getFromAmount());
                a.applyAiSupplement(knownPlusRemainder());
                after.set(a.rateView.getFromAmount());
                state.set(a.buildAiFormStateContext());
            });
            Log.i(TAG, "before=" + before.get() + " after=" + after.get() + "\n" + state.get());
            assertEquals(-87500, before.get());
            assertEquals("總額要維持原本的 875", -87500, after.get());
            assertTrue("要有 250 那份：\n" + state.get(), state.get().contains("250"));
            assertTrue("殘額份要補成 625：\n" + state.get(), state.get().contains("625"));

            // 紀錄：套用結果要寫進 AiLog，看得到走哪條路、套完總額多少
            String last = lastLogLine();
            assertTrue("要有 applied 紀錄：" + last, last.contains("\"kind\":\"applied\""));
            assertTrue("要記下路徑：" + last, last.contains("splits-remainder"));
            assertTrue("要記下套完的總額：" + last, last.contains("875"));
        }
    }

    /** 每份都有金額（沒有「剩下的」）：照舊，總額＝各份加總。 */
    @Test
    public void allSharesSpokenStillSumToTheTotal() {
        ParsedTransaction t = new ParsedTransaction();
        for (double amt : new double[]{300, 200}) {
            ParsedTransaction.Split s = new ParsedTransaction.Split();
            s.amount = amt;
            t.splits.add(s);
        }
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), TransactionActivity.class);
        intent.putExtra(AbstractTransactionActivity.TRAN_ID_EXTRA, tranId);
        try (ActivityScenario<TransactionActivity> scenario = ActivityScenario.launch(intent)) {
            AtomicLong after = new AtomicLong();
            scenario.onActivity(a -> {
                a.applyAiSupplement(t);
                after.set(a.rateView.getFromAmount());
            });
            assertEquals(-50000, after.get());
        }
    }

    private static String lastLogLine() {
        java.io.File f = tw.tib.financisto.ai.AiLog.file(ApplicationProvider.getApplicationContext());
        String last = "";
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) if (!line.trim().isEmpty()) last = line;
        } catch (java.io.IOException e) {
            return "（讀不到 AiLog：" + e + "）";
        }
        return last;
    }
}
