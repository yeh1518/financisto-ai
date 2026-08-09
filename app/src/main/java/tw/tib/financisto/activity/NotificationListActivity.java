package tw.tib.financisto.activity;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import tw.tib.financisto.R;
import tw.tib.financisto.ai.AiPreferences;
import tw.tib.financisto.ai.EntityContextBuilder;
import tw.tib.financisto.ai.NotificationFilter;
import tw.tib.financisto.ai.NotificationJournal;
import tw.tib.financisto.ai.TemplateGenerator;
import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.model.Account;
import tw.tib.financisto.model.Transaction;
import tw.tib.financisto.service.NotificationCache;
import tw.tib.financisto.service.NotificationListener;
import tw.tib.financisto.service.SmsTransactionProcessor;
import tw.tib.financisto.utils.MyPreferences;
import tw.tib.financisto.utils.Utils;

public class NotificationListActivity extends AppCompatActivity {

    /**
     * 挑選模式（AI 設定 →「通知樣板解析」進來）：列表加上持久化日誌的歷史通知，
     * 點一則＝丟 LLM 產樣板 → 預填原生樣板編輯器。原入口（實體選單）行為不變＝複製。
     */
    public static final String EXTRA_PICK_FOR_TEMPLATE = "PICK_FOR_TEMPLATE";

    private static final int REQ_TEMPLATE_EDIT = 4001;

    private ListView list;
    private boolean pickMode;
    /** 產樣板時觸發的那則通知；使用者把樣板存起來後要回頭把它記成一筆（見 onActivityResult）。 */
    private NotificationListener.ParsedNotification pendingReapply;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.notification_list);

        pickMode = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_PICK_FOR_TEMPLATE, false);

        setSupportActionBar(findViewById(R.id.toolbar));
        if (pickMode && getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.ai_notification_template_pick);
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.toolbar), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.statusBars()
                    | WindowInsetsCompat.Type.captionBar());
            if (v.getPaddingTop() == 0) {
                var lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                lp.height += insets.top;
                v.setLayoutParams(lp);
                v.setPadding(0, insets.top, 0, 0);
            }
            return WindowInsetsCompat.CONSUMED;
        });

        // 自癒：listener 可能被系統解綁（權限看似還在但收不到）——每次開列表都請求重綁
        NotificationListener.requestRebindIfGranted(this);
        // 沒授權就直接引導去系統設定（AI 設定入口進來時原本沒有任何提示，2026-07-23 補）
        if (!NotificationListener.isAccessGranted(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.notification_access_title)
                    .setMessage(R.string.notification_access_missing)
                    .setPositiveButton(R.string.notification_access_open_settings,
                            (d, w) -> openNotificationAccessSettings())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        list = findViewById(android.R.id.list);
        list.setAdapter(new NotificationListAdapter(this, pickMode));
        list.setOnItemClickListener((adapterView, view, i, l) -> {
            NotificationViewHolder holder = (NotificationViewHolder) view.getTag();

            if (pickMode) {
                showActionDialog(holder.notification);
                return;
            }

            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.notification_content),
                    holder.notification.title + "\n" + holder.notification.body);
            clipboard.setPrimaryClip(clip);

            Toast.makeText(this, R.string.notification_copied, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 樣板編輯器存檔回來 → 立刻拿新樣板把「觸發它的那則通知」記成一筆。
     *
     * 這補的是一個從一開始就在的漏洞：產樣板當下樣板還不存在，所以那則通知**永遠不會**
     * 被自動入帳，等於每次設樣板都會漏掉當下那一筆。順帶當成樣板的實地驗證——比不中就
     * 當場說，而不是等下個月刷卡才發現樣板是壞的。
     * 取消（RESULT_CANCELED）就什麼都不做。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TEMPLATE_EDIT) {
            NotificationListener.ParsedNotification n = pendingReapply;
            pendingReapply = null;
            if (resultCode == RESULT_OK && n != null) {
                reapplyTemplate(n, true);
            }
        }
    }

    private static final int MENU_ACCESS_SETTINGS = 1;
    private static final int MENU_TEMPLATE_LIST = 2;
    private static final int MENU_FILTER = 3;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // 權限快捷：更新後 listener 解綁時，關開一次通知存取權是唯一的手動解——給條近路
        menu.add(Menu.NONE, MENU_ACCESS_SETTINGS, 0, R.string.notification_access_open_settings);
        if (pickMode) {
            // AI 設定入口進來的順路：直通樣板列表，不必繞回實體選單
            menu.add(Menu.NONE, MENU_TEMPLATE_LIST, 1, R.string.sms_templates);
            menu.add(Menu.NONE, MENU_FILTER, 2, R.string.ai_notif_filter);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ACCESS_SETTINGS:
                openNotificationAccessSettings();
                return true;
            case MENU_TEMPLATE_LIST:
                startActivity(new Intent(this, SmsDragListActivity.class));
                return true;
            case MENU_FILTER:
                showFilterEditor(null);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 關鍵字排除清單的編輯器：一行一個，純文字整段編輯。
     *
     * 從選單進來＝單純看/改清單；從某則通知的「增加過濾」進來（{@code from} 非 null）
     * 則把那則通知附在上面當參考、並在清單末尾預先加一行建議關鍵字並選取起來——
     * 要的動作是「刪掉尾巴留下發送者」，選取狀態讓人直接開始改，不必自己先打字。
     */
    private void showFilterEditor(NotificationListener.ParsedNotification from) {
        View view = LayoutInflater.from(this).inflate(R.layout.notification_filter_edit, null);
        TextView sample = view.findViewById(R.id.filter_sample);
        EditText input = view.findViewById(R.id.filter_keywords);

        String raw = NotificationFilter.loadRaw(this);
        if (from == null) {
            input.setText(raw);
            input.setSelection(input.getText().length());
        } else {
            sample.setVisibility(View.VISIBLE);
            sample.setText(from.title + "\n" + from.body);
            String suggestion = NotificationFilter.suggestKeyword(from.title, from.body);
            String prefix = raw.isEmpty() || raw.endsWith("\n") ? raw : raw + "\n";
            input.setText(prefix + suggestion);
            input.setSelection(prefix.length(), prefix.length() + suggestion.length());
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.ai_notif_filter)
                .setView(view)
                .setPositiveButton(R.string.ai_notif_filter_save, (d, w) -> {
                    NotificationFilter.saveRaw(this, input.getText().toString());
                    reloadList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 關鍵字改完要當場看到結果，否則不知道剛剛那條到底有沒有擋到東西。 */
    private void reloadList() {
        list.setAdapter(new NotificationListAdapter(this, pickMode));
        int hidden = NotificationListAdapter.lastHiddenCount;
        Toast.makeText(this, getString(R.string.ai_notif_filter_applied, hidden),
                Toast.LENGTH_SHORT).show();
    }

    private void openNotificationAccessSettings() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (Exception e) {
            Toast.makeText(this, R.string.notification_access_settings_unavailable,
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 點一則通知的三條路：產樣板（長期）／直接記這筆（一次性）／套現有樣板（含驗證用途）。
     * 三者的差別是「要不要留下規則」與「誰來解析」，所以擺在同一個選單裡讓人當場選。
     */
    private void showActionDialog(NotificationListener.ParsedNotification n) {
        CharSequence[] items = {
                getString(R.string.ai_notif_action_generate),
                getString(R.string.ai_notif_action_parse),
                getString(R.string.ai_notif_action_reapply),
                getString(R.string.ai_notif_action_filter),
        };
        // 標題用「那則通知的標題」而非通用問句：對話框蓋住列表後，同一家銀行的好幾則
        // 長得很像，你會忘記剛剛點的是哪一則；三個動作本身已經夠自解釋
        CharSequence title = android.text.TextUtils.isEmpty(n.title)
                ? getString(R.string.ai_notif_action_title) : n.title;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: generateTemplate(n); break;
                        case 1: parseDirectly(n); break;
                        case 2: confirmReapply(n); break;
                        case 3: showFilterEditor(n); break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 一次性：把通知內文送進既有的一句話解析管線（AiInputActivity 帶 TEXT_EXTRA），
     * 解析完照樣預填原生交易頁讓使用者過目——與語音記帳共用同一條後半段，不另寫流程。
     */
    private void parseDirectly(NotificationListener.ParsedNotification n) {
        Intent i = new Intent(this, tw.tib.financisto.ai.AiInputActivity.class);
        i.putExtra(tw.tib.financisto.ai.AiInputActivity.TEXT_EXTRA, n.body);
        i.putExtra(tw.tib.financisto.ai.AiInputActivity.TEXT_SOURCE_EXTRA, "notification");
        startActivity(i);
    }

    /** 手動套樣板前先確認：這條路會**真的建立一筆交易**，連按兩次就是兩筆。 */
    private void confirmReapply(NotificationListener.ParsedNotification n) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.ai_notification_template)
                .setMessage(R.string.ai_notif_reapply_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> reapplyTemplate(n, false))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 拿現有樣板把這則通知記成一筆交易——直接呼叫**原生引擎**
     * {@link SmsTransactionProcessor#createTransactionBySms}，比對規則、金額解析、狀態
     * （預設「擱置」）都與背景自動入帳完全一致，不另寫一套。
     *
     * @param afterSave true＝剛存完樣板自動接著跑的（訊息不同：要講「樣板存了但比不中」）
     */
    private void reapplyTemplate(NotificationListener.ParsedNotification n, boolean afterSave) {
        final DatabaseAdapter db = new DatabaseAdapter(this);
        tw.tib.financisto.Application.getExecutor().execute(() -> {
            SmsTransactionProcessor.Result r = new SmsTransactionProcessor.Result();
            try {
                r = new SmsTransactionProcessor(db).process(
                        this, n.title, n.body,
                        MyPreferences.getSmsTransactionStatus(),
                        MyPreferences.shouldSaveSmsToTransactionNote());
            } catch (Exception e) {
                Log.e("NotificationList", "套用樣板失敗", e);
            }
            final SmsTransactionProcessor.Result result = r;
            runOnUiThread(() -> {
                if (result.transaction != null) {
                    AccountWidget.updateWidgets(this);
                    String desc = describe(db, result.transaction);
                    Toast.makeText(this, getString(afterSave
                                    ? R.string.ai_notif_template_saved_recorded
                                    : R.string.ai_notif_reapply_done, desc),
                            Toast.LENGTH_LONG).show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.ai_notification_template)
                            .setMessage(explainNoTransaction(result, afterSave))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            });
        });
    }

    /**
     * 沒記成一筆的時候要講對是哪一種：「比不中」跟「比中了但對不到帳戶」指向完全不同的
     * 修法（改樣板 vs 去帳戶填卡號末四碼），原本兩者共用「比不中」那句話，把人帶去錯的地方。
     */
    private String explainNoTransaction(SmsTransactionProcessor.Result r, boolean afterSave) {
        if (!r.matched) {
            return getString(afterSave
                    ? R.string.ai_notif_template_saved_no_match
                    : R.string.ai_notif_reapply_none);
        }
        return r.accountDigits == null
                ? getString(R.string.ai_notif_no_account_no_digits)
                : getString(R.string.ai_notif_no_account, r.accountDigits);
    }

    /** 「帳戶 金額」一行，給結果 toast 用（看得出記到哪、記了多少就夠）。 */
    private static String describe(DatabaseAdapter db, Transaction t) {
        try {
            Account a = db.getAccount(t.fromAccountId);
            if (a != null) {
                return a.title + " " + Utils.amountToString(a.currency, t.fromAmount);
            }
        } catch (Exception ignored) {}
        return String.valueOf(t.fromAmount);
    }

    /** 丟 LLM 產樣板（背景執行緒），成功就帶著預填值開原生樣板編輯器。 */
    private void generateTemplate(NotificationListener.ParsedNotification n) {
        AiPreferences prefs = AiPreferences.load(this);
        if (!prefs.isConfigured()) {
            Toast.makeText(this, R.string.ai_api_key_missing, Toast.LENGTH_LONG).show();
            return;
        }
        ProgressDialog progress = ProgressDialog.show(this, null,
                getString(R.string.ai_notification_template_generating), true, false);
        final DatabaseAdapter db = new DatabaseAdapter(this);
        tw.tib.financisto.Application.getExecutor().execute(() -> {
            try {
                EntityContextBuilder ctx = EntityContextBuilder.build(db);
                TemplateGenerator generator = new TemplateGenerator(this, prefs, ctx);
                TemplateGenerator.GeneratedTemplate t = generator.generate(n.title, n.body);
                runOnUiThread(() -> {
                    progress.dismiss();
                    Intent intent = new Intent(this, SmsTemplateActivity.class);
                    intent.putExtra(SmsTemplateActivity.EXTRA_PREFILL_TITLE, t.titleKey);
                    intent.putExtra(SmsTemplateActivity.EXTRA_PREFILL_TEMPLATE, t.template);
                    intent.putExtra(SmsTemplateActivity.EXTRA_PREFILL_EXAMPLE, n.body);
                    if (t.accountId != null) {
                        intent.putExtra(SmsTemplateActivity.EXTRA_PREFILL_ACCOUNT_ID, (long) t.accountId);
                    }
                    if (t.categoryId != null) {
                        intent.putExtra(SmsTemplateActivity.EXTRA_PREFILL_CATEGORY_ID, (long) t.categoryId);
                    }
                    intent.putExtra(SmsTemplateActivity.EXTRA_PREFILL_IS_INCOME, t.isIncome);
                    // 存完要回頭把「這一則」記起來（見 onActivityResult）：產樣板的當下，
                    // 觸發它的那則通知本身是不會被自動入帳的——樣板是存檔後才存在
                    pendingReapply = n;
                    startActivityForResult(intent, REQ_TEMPLATE_EDIT);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.ai_notification_template)
                            .setMessage(getString(R.string.ai_notification_template_failed,
                                    e.getMessage()))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        });
    }

    static class NotificationListAdapter extends BaseAdapter {
        /** 上一次建構時擋掉幾則——只為了改完關鍵字能當場回報「擋掉了幾則」。 */
        static int lastHiddenCount;

        private final ArrayList<NotificationListener.ParsedNotification> list;
        private final LayoutInflater inflater;

        public NotificationListAdapter(Context context, boolean includeJournal) {
            list = new ArrayList<>(NotificationCache.getInstance().cache.values());
            if (includeJournal) {
                // 挑選模式補上日誌裡的歷史通知（滑掉的還找得回來）；同標題+內文去重
                Set<String> seen = new HashSet<>();
                for (NotificationListener.ParsedNotification n : list) {
                    seen.add(n.title + "\n" + n.body);
                }
                for (NotificationJournal.Entry e : NotificationJournal.read(context)) {
                    if (seen.add(e.title + "\n" + e.body)) {
                        NotificationListener.ParsedNotification n =
                                new NotificationListener.ParsedNotification();
                        n.title = e.title;
                        n.body = e.body;
                        n.postTime = e.at;
                        list.add(n);
                    }
                }
            }
            // 關鍵字排除（只在挑選模式；實體選單那個入口是拿來複製通知內容的，不該少東西）。
            // 在顯示這一層過濾＝改掉關鍵字，被擋的東西就回來了；日誌那邊的過濾是為了不佔
            // 100 筆的額度，兩者目的不同、都要有。
            lastHiddenCount = 0;
            if (includeJournal) {
                List<String> keywords =
                        NotificationFilter.keywords(NotificationFilter.loadRaw(context));
                if (!keywords.isEmpty()) {
                    int before = list.size();
                    for (Iterator<NotificationListener.ParsedNotification> it = list.iterator();
                         it.hasNext(); ) {
                        NotificationListener.ParsedNotification n = it.next();
                        if (NotificationFilter.matches(keywords, n.title, n.body)) it.remove();
                    }
                    lastHiddenCount = before - list.size();
                }
            }
            // The cache is a HashMap, so its iteration order is bucket order: the list
            // comes out in no meaningful order and can reshuffle when a notification is
            // added. Show newest first instead. (This fork also merges the journal above,
            // so sorting covers both sources.)
            Collections.sort(list, (a, b) -> Long.compare(b.postTime, a.postTime));
            inflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() {
            return list.size();
        }

        @Override
        public Object getItem(int i) {
            return list.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup parent) {
            NotificationViewHolder notificationViewHolder;
            if (view == null) {
                view = inflater.inflate(R.layout.notification_list_item, parent, false);
                notificationViewHolder = new NotificationViewHolder(view);
                view.setTag(notificationViewHolder);
            }
            else {
                notificationViewHolder = (NotificationViewHolder) view.getTag();
            }
            notificationViewHolder.bindView(list.get(i));

            return view;
        }
    }

    static class NotificationViewHolder {
        public TextView title;
        public TextView body;
        public NotificationListener.ParsedNotification notification;

        public NotificationViewHolder(@NonNull View itemView) {
            title = itemView.findViewById(R.id.title);
            body = itemView.findViewById(R.id.body);
        }

        public void bindView(NotificationListener.ParsedNotification notification) {
            this.notification = notification;
            title.setText(notification.title);
            body.setText(notification.body);
        }
    }
}
