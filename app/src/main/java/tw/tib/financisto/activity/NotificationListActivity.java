package tw.tib.financisto.activity;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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
import java.util.HashSet;
import java.util.Set;

import tw.tib.financisto.R;
import tw.tib.financisto.ai.AiPreferences;
import tw.tib.financisto.ai.EntityContextBuilder;
import tw.tib.financisto.ai.NotificationJournal;
import tw.tib.financisto.ai.TemplateGenerator;
import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.service.NotificationCache;
import tw.tib.financisto.service.NotificationListener;

public class NotificationListActivity extends AppCompatActivity {

    /**
     * 挑選模式（AI 設定 →「通知樣板解析」進來）：列表加上持久化日誌的歷史通知，
     * 點一則＝丟 LLM 產樣板 → 預填原生樣板編輯器。原入口（實體選單）行為不變＝複製。
     */
    public static final String EXTRA_PICK_FOR_TEMPLATE = "PICK_FOR_TEMPLATE";

    private ListView list;
    private boolean pickMode;

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
                generateTemplate(holder.notification);
                return;
            }

            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.notification_content),
                    holder.notification.title + "\n" + holder.notification.body);
            clipboard.setPrimaryClip(clip);

            Toast.makeText(this, R.string.notification_copied, Toast.LENGTH_SHORT).show();
        });
    }

    private static final int MENU_ACCESS_SETTINGS = 1;
    private static final int MENU_TEMPLATE_LIST = 2;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // 權限快捷：更新後 listener 解綁時，關開一次通知存取權是唯一的手動解——給條近路
        menu.add(Menu.NONE, MENU_ACCESS_SETTINGS, 0, R.string.notification_access_open_settings);
        if (pickMode) {
            // AI 設定入口進來的順路：直通樣板列表，不必繞回實體選單
            menu.add(Menu.NONE, MENU_TEMPLATE_LIST, 1, R.string.sms_templates);
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
        }
        return super.onOptionsItemSelected(item);
    }

    private void openNotificationAccessSettings() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (Exception e) {
            Toast.makeText(this, R.string.notification_access_settings_unavailable,
                    Toast.LENGTH_LONG).show();
        }
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
                TemplateGenerator generator = new TemplateGenerator(prefs, ctx);
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
                    startActivity(intent);
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
                        list.add(n);
                    }
                }
            }
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
