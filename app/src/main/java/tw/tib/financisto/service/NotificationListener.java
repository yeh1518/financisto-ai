package tw.tib.financisto.service;

import static tw.tib.financisto.service.FinancistoService.ACTION_NEW_TRANSACTION_SMS;
import static tw.tib.financisto.service.FinancistoService.ACTION_NEW_TRANSACTION_WALLET;
import static tw.tib.financisto.service.FinancistoService.SMS_TRANSACTION_BODY;
import static tw.tib.financisto.service.FinancistoService.SMS_TRANSACTION_IS_GROUP_SUMMARY;
import static tw.tib.financisto.service.FinancistoService.SMS_TRANSACTION_NUMBER;
import static tw.tib.financisto.service.FinancistoService.SMS_TRANSACTION_PACKAGE;
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

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import tw.tib.financisto.ai.NotificationJournal;
import tw.tib.financisto.ai.ProcessedNotificationLog;
import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.model.SmsTemplate;
import tw.tib.financisto.utils.MyPreferences;
import tw.tib.financisto.worker.AutoBackupWorker;

public class NotificationListener extends NotificationListenerService {
    private static final String TAG = "NotificationListener";

    /** 遠端觸發備份的通知指令標記，與記帳訊息同一家族（🧾 開頭、全形｜分隔）。 */
    public static final String BACKUP_TRIGGER = "🧾備份";

    private static final Set<String> GOOGLE_WALLET_PACKAGES = new HashSet<>(Arrays.asList(
            // Google Wallet
            "com.google.android.apps.walletnfcrel",
            // Google Pay (India)
            "com.google.android.apps.nbu.paisa.user"));

    private String packageName;
    private NotificationCache notificationCache;

    /** 這則通知是不是 Google 錢包發的——決定它走 Wallet 解析還是使用者的樣板。 */
    public static boolean isGoogleWalletPackage(String pkg) {
        return pkg != null && GOOGLE_WALLET_PACKAGES.contains(pkg);
    }

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
     * ——實機一天內更新四版 APK，每次都得手動關開權限才活。改成先把元件
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

            // 疊起來的前幾則裡，上一次這則通知發出時就已經在的，當時要嘛是內文（已處理）、
            // 要嘛更早——都不重來。這層靠時間、不靠字面，Telegram 的內文欄與訊息清單
            // 寫法若有出入，指紋擋不住，這裡擋得住。cache 被清空（listener 重綁）時沒有
            // existing，就只剩指紋那一層。
            if (existing != null && !notification.earlierMessages.isEmpty()) {
                for (Iterator<EarlierMessage> it = notification.earlierMessages.iterator(); it.hasNext(); ) {
                    if (it.next().time <= existing.postTime) it.remove();
                }
            }

            Context context = getApplicationContext();
            String pkg = notification.pkg;
            String title = notification.title;
            String body = notification.body;
            boolean isGroupSummary = notification.isGroupSummary;

            Log.d(TAG, "title=\"" + title + "\", body=\"" + body + "\", isGroupSummary=" + isGroupSummary);
            Log.d(TAG, sbn.getNotification().extras.toString());

            // 存進滾動日誌給「AI 產樣板」的通知列表用（cache 滑掉就沒了，日誌留 7 天）。
            // body 存與樣板引擎吃到的同一格式（含 title 前綴），生成樣板回測才一致。
            // 時間一定要傳 sbn 的 postTime、不能讓日誌自己取當下：onListenerConnected 會把
            // 通知欄裡還掛著的舊通知整批重掃一遍，用當下時間會把它們全壓成「app 啟動那一刻」。
            // 群組摘要不進日誌：它多半是同一則通知的複本（上游 #157 的 monobank），
            // 進了日誌列表就是兩則一模一樣的，產樣板也只會挑到預設不比對摘要的那種。
            if (!isGroupSummary) {
                for (EarlierMessage m : notification.earlierMessages) {
                    NotificationJournal.record(context, packageName, title, m.body, m.time);
                }
                NotificationJournal.record(context, packageName, title, body, notification.postTime);
            }

            if (processTemplate && (existing == null || !body.equals(existing.body))) {
                // 遠端觸發備份：任何通知內文含這個標記（慣例是電腦端要對帳前發來的
                // 訊息通知）→ 立刻跑一次完整備份（含 AI 解析紀錄），寫進備份資料夾，
                // 讓資料夾同步工具把檔帶回電腦。標記後面慣例帶時間戳，讓每次內文互異、
                // 不被上面的去重擋掉。只做字串比對所以放程式不放樣板；任何 app 發這串
                // 都會觸發，最壞就是多備份一次，無害。
                // 疊在同一則通知裡的前幾則也要看（見 earlierMessages）：備份指令與記帳訊息
                // 一起到時，誰都不能把對方吃掉。
                boolean backupRequested = body.contains(BACKUP_TRIGGER);
                for (EarlierMessage m : notification.earlierMessages) {
                    if (m.body.contains(BACKUP_TRIGGER)) backupRequested = true;
                }
                if (backupRequested) {
                    Log.i(TAG, "backup trigger notification received");
                    AutoBackupWorker.requestImmediateBackup(context);
                    if (notification.earlierMessages.isEmpty()) return;
                }

                // 摘要不走 Wallet 解析：上游 v265 以前摘要在 extractNotification 就被丟掉，
                // Wallet 那條路從來沒看過摘要；保留時不擋，一則消費就可能記兩筆。
                if (!isGroupSummary
                        && isGoogleWalletPackage(packageName)
                        && MyPreferences.isGoogleWalletTransactionEnabled())
                {
                    Intent serviceIntent = new Intent(ACTION_NEW_TRANSACTION_WALLET, null, context, FinancistoService.class);
                    serviceIntent.putExtra(SMS_TRANSACTION_PACKAGE, packageName);
                    serviceIntent.putExtra(WALLET_TRANSACTION_TITLE, title);
                    serviceIntent.putExtra(WALLET_TRANSACTION_TEXT, notification.text);
                    FinancistoService.enqueueWork(context, serviceIntent);
                    return;
                }

                final DatabaseAdapter db = new DatabaseAdapter(context);
                List<SmsTemplate> templates = db.getSmsTemplatesByPkgTitle(pkg, title);
                // 只留「摘要與否」對得上的樣板（同 SmsTransactionProcessor 的篩法）。一定要在
                // 下面寫指紋之前篩：摘要與本體內文相同，摘要若先到、沒有樣板會收它卻照樣寫下指紋，
                // 隨後到的本體就被當成「已處理」擋掉，這筆帳整個漏記。
                // （不用 removeIf：minSdk 23，那是 API 24 的。）
                for (Iterator<SmsTemplate> it = templates.iterator(); it.hasNext(); ) {
                    if (it.next().matchGroupSummary != isGroupSummary) it.remove();
                }

                if (!templates.isEmpty()) {
                    // 疊在同一則通知裡、比最新那則早的訊息先送（見 earlierMessages），
                    // 最後才是這次的內文——依發出順序入帳。
                    List<String> bodies = new ArrayList<>();
                    for (EarlierMessage m : notification.earlierMessages) {
                        bodies.add(m.body);
                    }
                    bodies.add(body);
                    for (String b : bodies) {
                        if (b.contains(BACKUP_TRIGGER)) continue;
                        // 防重複記帳：上面那個 cache 比對只擋「同一個通知 key 的內文沒變」，而
                        // listener 一斷線 cache 就整個清空（APK 更新後會重綁），同一則通知再被
                        // 投遞一次就又記一筆。2026-08-20 實地記成兩筆，改用持久化的內文指紋擋
                        // （取捨說明見 ProcessedNotificationLog）。疊起來的訊息也靠它：前一則
                        // 若早已單獨跳過通知、記過了，這裡組出的 body 跟當時一字不差，會被擋下。
                        if (!ProcessedNotificationLog.markIfNew(context, b)) {
                            Log.i(TAG, "notification already processed, skip");
                            continue;
                        }
                        Intent serviceIntent = new Intent(ACTION_NEW_TRANSACTION_SMS, null, context, FinancistoService.class);
                        serviceIntent.putExtra(SMS_TRANSACTION_PACKAGE, pkg);
                        serviceIntent.putExtra(SMS_TRANSACTION_NUMBER, title);
                        serviceIntent.putExtra(SMS_TRANSACTION_BODY, b);
                        serviceIntent.putExtra(SMS_TRANSACTION_IS_GROUP_SUMMARY, isGroupSummary);
                        FinancistoService.enqueueWork(context, serviceIntent);
                    }
                }
            }
        }
    }

    public static ParsedNotification extractNotification(StatusBarNotification sbn) {
        ParsedNotification result = null;
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        // skip group summary notifications
//        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
//            return null;
//        }
        if (extras != null) {
            StringBuilder sb = new StringBuilder();
            result = new ParsedNotification();
            result.key = sbn.getKey();
            result.pkg = sbn.getPackageName();
            result.postTime = sbn.getPostTime();
            result.isGroupSummary = ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0);
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
            result.earlierMessages = earlierMessages(notification, result.title, result.postTime);
        }
        return result;
    }

    /** 疊起來的訊息只回溯這麼久：協助「同時到達被疊在一起」的情況，不重掃整串聊天。 */
    static final long EARLIER_MESSAGE_WINDOW_MS = 30L * 60 * 1000;

    /**
     * 同一個對話的通知裡，比最新那則更早、但還沒被當成內文送過的訊息。
     *
     * 聊天 app（Telegram 等）一個對話只掛一則通知，新訊息到了是「更新」那則通知：
     * EXTRA_TEXT 只放最新一則，之前的訊息只留在 MessagingStyle 的訊息清單裡。平常一則
     * 一則到，每則都輪過一次 EXTRA_TEXT，各自被記到；但手機睡著時幾則訊息會一起到，
     * app 只跳一次通知，前面幾則就從沒當過 EXTRA_TEXT——**靜默漏記**。2026-10-08 實地：
     * Finn 隔 4 秒發的兩筆記帳只記到第 2 筆，第 1 筆連 0 元殘骸都沒有（從沒進樣板比對）。
     *
     * 回傳的 body 用與 {@link #extractNotification} 完全相同的組法（「標題 空格 內文 空格」——
     * 沒有 big text 時 {@link #getString} 給空字串，結尾就多一個空格），這樣一則訊息不論是
     * 自己跳過通知、還是後來疊在別則裡被翻出來，指紋都一樣，{@code ProcessedNotificationLog}
     * 才擋得住重複。
     *
     * 刻意保守的三點：
     * <ul>
     *   <li>清單最後一則一律跳過——它就是 EXTRA_TEXT 那則，已經由主內文處理；就算兩邊
     *       字面有出入，也不能讓同一則訊息從兩條路各記一筆。</li>
     *   <li>只回溯 {@link #EARLIER_MESSAGE_WINDOW_MS}：通知沒被滑掉時，對話裡未讀訊息會
     *       一直累積，過了指紋的保存期就會被當成新的再記一次。</li>
     *   <li>讀不出 MessagingStyle 就回空清單，行為與以前相同。</li>
     * </ul>
     */
    static List<EarlierMessage> earlierMessages(Notification notification, String title, long postTime) {
        List<EarlierMessage> out = new ArrayList<>();
        NotificationCompat.MessagingStyle style;
        try {
            style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read messaging style", e);
            return out;
        }
        if (style == null) return out;
        List<NotificationCompat.MessagingStyle.Message> messages = style.getMessages();
        for (int i = 0; i < messages.size() - 1; i++) {
            NotificationCompat.MessagingStyle.Message m = messages.get(i);
            CharSequence text = m.getText();
            if (text == null || text.length() == 0) continue;
            long time = m.getTimestamp();
            if (time > 0 && postTime - time > EARLIER_MESSAGE_WINDOW_MS) continue;
            out.add(new EarlierMessage(title + " " + text + " ", time > 0 ? time : postTime));
        }
        return out;
    }

    public static class EarlierMessage {
        public final String body;
        public final long time;

        EarlierMessage(String body, long time) {
            this.body = body;
            this.time = time;
        }
    }

    public static class ParsedNotification {
        public String key;
        public String title;
        public String text;
        public String body;
        /** 來源套件名。列表顯示 app 名稱、以及排除整個 app 都靠它。 */
        public String pkg;
        public boolean isGroupSummary;
        /** When the notification was posted; used to order the notification list. */
        public long postTime;
        /** 疊在這則通知裡、比內文那則更早的訊息（見 {@link #earlierMessages}）。 */
        public List<EarlierMessage> earlierMessages = new ArrayList<>();
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
