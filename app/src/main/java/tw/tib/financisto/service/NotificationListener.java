package tw.tib.financisto.service;

import static tw.tib.financisto.service.FinancistoService.ACTION_NEW_TRANSACTION_SMS;
import static tw.tib.financisto.service.FinancistoService.ACTION_NEW_TRANSACTION_WALLET;
import static tw.tib.financisto.service.FinancistoService.SMS_TRANSACTION_BODY;
import static tw.tib.financisto.service.FinancistoService.SMS_TRANSACTION_NUMBER;
import static tw.tib.financisto.service.FinancistoService.WALLET_TRANSACTION_TEXT;
import static tw.tib.financisto.service.FinancistoService.WALLET_TRANSACTION_TITLE;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.SpannableString;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tw.tib.financisto.ai.NotificationJournal;
import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.model.SmsTemplate;
import tw.tib.financisto.utils.MyPreferences;

public class NotificationListener extends NotificationListenerService {
    private static final String TAG = "NotificationListener";

    private static final Set<String> GOOGLE_WALLET_PACKAGES = new HashSet<>(Arrays.asList(
            // Google Wallet
            "com.google.android.apps.walletnfcrel",
            // Google Pay (India)
            "com.google.android.apps.nbu.paisa.user"));

    private String packageName;
    private NotificationCache notificationCache;

    /** 使用者是否已授予通知存取權（授了不代表 listener 有被系統綁上，見下）。 */
    public static boolean isAccessGranted(Context context) {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.getPackageName());
    }

    /**
     * 請系統重新綁定 listener。Android 有個長年怪癖：APK 更新（或某些系統狀況）後
     * listener 會被解綁、且**權限還顯示已授予**，但通知完全收不到，得手動關開一次
     * 通知存取權才復活。
     *
     * <p>2026-08-04 加強：原本只叫 {@code requestRebind}，但那個 API 眾所周知不可靠
     * ——Gary 實機一天內更新四版 APK，每次都得手動關開權限才活。改成先把元件
     * <b>停用再啟用</b>（等同「關開一次」但不需要人去設定頁），再 requestRebind。
     * 元件的 enabled 狀態與授權是兩回事：授權存在 {@code Settings.Secure}
     * 的 enabled_notification_listeners（以元件名為鍵），toggle enabled 不會動到它，
     * 所以權限不會掉。
     *
     * <p>沒授權時直接跳過（沒授權時做什麼都沒用，反而可能把元件留在停用狀態）；
     * 已綁好時整串是 no-op，多叫無害——所以掛在 Application 啟動、APK 更新、
     * 開啟通知列表三處，開一次 app 就自動修好。
     */
    public static void requestRebindIfGranted(Context context) {
        if (!isAccessGranted(context)) return;
        ComponentName cn = new ComponentName(context, NotificationListener.class);
        try {
            PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            Log.d(TAG, "listener component toggled off/on");
        } catch (Exception e) {
            // 失敗也要把元件掰回啟用，不能讓它卡在停用狀態
            Log.e(TAG, "toggle component failed", e);
            try {
                context.getPackageManager().setComponentEnabledSetting(cn,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            } catch (Exception ignored) {}
        }
        // requestRebind 是 API 24 才有的；minSdk 23，Android 6 上直接呼叫會 NoSuchMethodError
        // ——而那是 Error 不是 Exception，catch 不到，掛在 Application.onCreate 等於開不了 app。
        // API 23 上就只靠上面的元件 toggle（實測有效的本來也是那半）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(cn);
                Log.d(TAG, "requestRebind sent");
            } catch (Exception e) {
                Log.e(TAG, "requestRebind failed", e);
            }
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "onListenerConnected");

        packageName = getApplicationContext().getPackageName();
        notificationCache = NotificationCache.getInstance();

        for (StatusBarNotification sbn : getActiveNotifications()) {
            processNotification(sbn, false);
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "onListenerDisconnected");
        notificationCache.cache.clear();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        Log.d(TAG, "onNotificationPosted");
        processNotification(sbn, true);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        String key = sbn.getKey();
        Log.d(TAG, "onNotificationRemoved key=" + key);
        notificationCache.cache.remove(key);
    }

    private void processNotification(StatusBarNotification sbn, boolean processTemplate) {
        String packageName = sbn.getPackageName();
        // don't try to process our own notification to enter an infinite recursion
        if (packageName.equals(this.packageName)) {
            return;
        }
        Log.d(TAG, "package=" + packageName);
        Log.d(TAG, "key=" + sbn.getKey());

        ParsedNotification notification = extractNotification(sbn);

        if (notification != null) {
            ParsedNotification existing = null;
            if (notification.title != null && notification.body != null) {
                existing = notificationCache.cache.put(notification.key, notification);
            }

            Context context = getApplicationContext();
            String title = notification.title;
            String body = notification.body;

            Log.d(TAG, "title=\"" + title + "\", body=\"" + body + "\"");
            Log.d(TAG, sbn.getNotification().extras.toString());

            // 存進滾動日誌給「AI 產樣板」的通知列表用（cache 滑掉就沒了，日誌留 7 天）。
            // body 存與樣板引擎吃到的同一格式（含 title 前綴），生成樣板回測才一致。
            NotificationJournal.record(context, packageName, title, body);

            if (processTemplate && (existing == null || !body.equals(existing.body))) {
                if (GOOGLE_WALLET_PACKAGES.contains(packageName)
                        && MyPreferences.isGoogleWalletTransactionEnabled())
                {
                    Intent serviceIntent = new Intent(ACTION_NEW_TRANSACTION_WALLET, null, context, FinancistoService.class);
                    serviceIntent.putExtra(WALLET_TRANSACTION_TITLE, title);
                    serviceIntent.putExtra(WALLET_TRANSACTION_TEXT, notification.text);
                    FinancistoService.enqueueWork(context, serviceIntent);
                    return;
                }

                final DatabaseAdapter db = new DatabaseAdapter(context);
                List<SmsTemplate> templates = db.getSmsTemplatesByNumber(title);

                if (!templates.isEmpty()) {
                    Intent serviceIntent = new Intent(ACTION_NEW_TRANSACTION_SMS, null, context, FinancistoService.class);
                    serviceIntent.putExtra(SMS_TRANSACTION_NUMBER, title);
                    serviceIntent.putExtra(SMS_TRANSACTION_BODY, body);
                    FinancistoService.enqueueWork(context, serviceIntent);
                }
            }
        }
    }

    public static ParsedNotification extractNotification(StatusBarNotification sbn) {
        ParsedNotification result = null;
        Bundle extras = sbn.getNotification().extras;
        if (extras != null) {
            StringBuilder sb = new StringBuilder();
            result = new ParsedNotification();
            result.key = sbn.getKey();
            result.postTime = sbn.getPostTime();
            result.title = getString(extras.getCharSequence(Notification.EXTRA_TITLE));
            String text = getString(extras.getCharSequence(Notification.EXTRA_TEXT));
            if (text != null) {
                sb.append(text);
            }
            String bigText = getString(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (bigText != null && !bigText.equals(text)) {
                if (text != null) {
                    sb.append(" ");
                }
                sb.append(bigText);
            }
            result.text = sb.toString();
            result.body = result.title + " " + sb;
        }
        return result;
    }

    public static class ParsedNotification {
        public String key;
        public String title;
        public String text;
        public String body;
        /** 通知發出的時刻（毫秒）——列表要靠它排序，見 NotificationListActivity 的適配器。 */
        public long postTime;
    }

    private static String getString(Object s) {
        if (s instanceof SpannableString) {
            return ((SpannableString) s).subSequence(0, ((SpannableString) s).length()).toString();
        }
        if (s == null) {
            return "";
        }
        return (String) s;
    }
}
