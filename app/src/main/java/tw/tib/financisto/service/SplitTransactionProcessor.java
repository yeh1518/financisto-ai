package tw.tib.financisto.service;

import static tw.tib.financisto.service.SmsTransactionProcessor.HUNDRED;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import tw.tib.financisto.ai.AiLog;
import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.model.Account;
import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.Transaction;
import tw.tib.financisto.model.TransactionStatus;

/**
 * 把 {@link SplitMessage} 記成一筆分割交易（父＝總額、分類為「分割」；子＝各份）。
 *
 * 與樣板路線（{@link SmsTransactionProcessor}）對齊的幾個決定：
 * <ul>
 *   <li>帳戶用名稱原文對；對不到就不記（樣板路線也是整筆不記）。帳戶錯了金額就記在錯的
 *       地方，比不記更糟。</li>
 *   <li>分類 id 必須真的存在，否則那一份分類留空、交易照記——與 {@code {{k}}} 同一條政策：
 *       分類錯不影響金額與帳戶，事後在 blotter 補得回來。</li>
 *   <li>金額一律以 2 位小數換成 minor units（{@code HUNDRED}），與樣板路線相同。</li>
 *   <li>狀態由呼叫端給（同樣板路線用的偏好設定），使用者篩選的習慣不用變。</li>
 *   <li>每一則的下場都記進解析紀錄（kind=tmatch、outcome=split_*），靜默失敗事後查得到。</li>
 * </ul>
 */
public class SplitTransactionProcessor {
    private static final String TAG = "SplitTxProcessor";

    private final DatabaseAdapter db;

    public SplitTransactionProcessor(DatabaseAdapter db) {
        this.db = db;
    }

    /**
     * @param titleKey 通知標題（只為了解析紀錄）
     * @param body     引擎吃到的內文（含標題前綴）
     * @return 建好的父交易；格式不對或帳戶對不到回 null
     */
    public Transaction process(Context context, String titleKey, String body, TransactionStatus status) {
        SplitMessage msg;
        try {
            msg = SplitMessage.parse(body);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "split message rejected: " + e.getMessage());
            trace(context, titleKey, body, "格式無效：" + e.getMessage(), "split_invalid");
            return null;
        }
        return create(context, titleKey, body, msg, status);
    }

    public Transaction create(Context context, String titleKey, String body, SplitMessage msg, TransactionStatus status) {
        long accountId = db.getEntityIdByTitle(Account.class, msg.accountName);
        if (accountId <= 0) {
            Log.w(TAG, "split message: account not found: " + msg.accountName);
            trace(context, titleKey, body, "帳戶對不到：" + msg.accountName, "split_no_account");
            return null;
        }
        int sign = msg.income ? 1 : -1;

        Transaction parent = new Transaction();
        parent.isTemplate = 0;
        parent.fromAccountId = accountId;
        parent.dateTime = msg.timestampMillis != null ? msg.timestampMillis : System.currentTimeMillis();
        parent.note = msg.note;
        parent.status = status;
        parent.categoryId = Category.SPLIT_CATEGORY_ID;
        parent.fromAmount = sign * toMinor(msg.total);

        List<Transaction> children = new ArrayList<>();
        int unknownCategories = 0;
        for (SplitMessage.Part p : msg.parts) {
            Transaction c = new Transaction();
            c.fromAccountId = accountId;
            c.fromAmount = sign * toMinor(p.amount);
            // 子項有自己的狀態、存檔不會沿用母筆：建立時就跟母筆一樣（擱置），
            // 明細總表一套篩選就改顯示子項，子項不是擱置的話整筆會從「只看擱置」裡消失
            c.status = status;
            if (p.categoryId > 0) {
                if (db.getCategory(p.categoryId) != null) c.categoryId = p.categoryId;
                else unknownCategories++;
            }
            if (p.note != null && !p.note.isEmpty()) c.note = p.note;
            children.add(c);
        }
        parent.splits = children;

        long id = db.insertOrUpdate(parent);
        parent.id = id;
        Log.i(TAG, "split transaction added id=" + id + " parts=" + children.size());
        trace(context, titleKey, body,
                "記成 _id=" + id + "，" + children.size() + " 份"
                        + (unknownCategories > 0 ? "，" + unknownCategories + " 份分類 id 不存在、留空" : ""),
                "split_created");
        return parent;
    }

    private static long toMinor(java.math.BigDecimal major) {
        return Math.abs(major.multiply(HUNDRED).longValue());
    }

    private static void trace(Context context, String titleKey, String body, String result, String outcome) {
        if (context == null) return;
        try {
            JSONArray tpls = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("id", 0);
            o.put("title", titleKey == null ? "" : titleKey);
            o.put("tpl", SplitMessage.TRIGGER);
            o.put("res", result);
            tpls.put(o);
            AiLog.recordTemplateMatch(context, titleKey, body, tpls, outcome);
        } catch (JSONException e) {
            Log.w(TAG, "trace failed", e);
        }
    }
}
