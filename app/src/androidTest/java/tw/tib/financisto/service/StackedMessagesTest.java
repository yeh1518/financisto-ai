package tw.tib.financisto.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.content.Context;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import tw.tib.financisto.service.NotificationListener.EarlierMessage;
import tw.tib.financisto.service.NotificationListener.ParsedNotification;

/**
 * 聊天 app 把同一個對話的幾則訊息疊進一則通知時，前面幾則要翻得出來
 * （{@link NotificationListener#earlierMessages}）。
 *
 * 要用裝置跑：Notification 與 MessagingStyle 的 extras 在桌面 JVM 上只是 stub。
 */
@RunWith(AndroidJUnit4.class)
public class StackedMessagesTest {

    private static final String TITLE = "Finn";
    private static final String MSG1 = "🧾記帳｜支出｜338｜某帳戶｜82｜調理油｜1791419700000｜1/2";
    private static final String MSG2 = "🧾記帳｜支出｜140｜某帳戶｜7｜午餐｜1791421260000｜2/2";
    private static final String TEMPLATE = "🧾記帳｜支出｜{{p}}｜{{c}}｜{{k}}｜{{t}}｜{{g}}｜";
    private static final long NOW = 1791421800000L;

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    /** 照 Telegram 的長相：一個對話一則通知，內文是最新一則，前面的只在 MessagingStyle 裡。 */
    private Notification chat(String latestText, Object... messages) {
        Person me = new Person.Builder().setName("me").build();
        Person bot = new Person.Builder().setName(TITLE).build();
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(me);
        for (int i = 0; i < messages.length; i += 2) {
            style.addMessage((String) messages[i], (Long) messages[i + 1], bot);
        }
        return new NotificationCompat.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(TITLE)
                .setContentText(latestText)
                .setStyle(style)
                .build();
    }

    private ParsedNotification parse(Notification n) {
        @SuppressWarnings("deprecation")
        StatusBarNotification sbn = new StatusBarNotification(
                "org.telegram.messenger", "org.telegram.messenger", 1, null,
                Process.myUid(), 0, 0, n, Process.myUserHandle(), NOW);
        return NotificationListener.extractNotification(sbn);
    }

    @Test
    public void stackedNotificationExposesTheEarlierMessage() {
        ParsedNotification p = parse(chat(MSG2, MSG1, NOW - 15_000L, MSG2, NOW - 11_000L));
        assertEquals(1, p.earlierMessages.size());
        assertEquals(NOW - 15_000L, p.earlierMessages.get(0).time);
        // 前一則與最新一則各自比得中記帳樣板
        assertNotNull(SmsTransactionProcessor.findTemplateMatches(TEMPLATE, p.earlierMessages.get(0).body));
        assertNotNull(SmsTransactionProcessor.findTemplateMatches(TEMPLATE, p.body));
    }

    /**
     * 防重複記帳的前提：同一則訊息「自己跳通知時」與「後來疊在別則裡被翻出來時」
     * 組出的 body 必須一字不差，指紋才擋得住。
     */
    @Test
    public void earlierMessageBodyMatchesTheBodyItHadWhenPostedAlone() {
        ParsedNotification alone = parse(chat(MSG1, MSG1, NOW - 15_000L));
        ParsedNotification stacked = parse(chat(MSG2, MSG1, NOW - 15_000L, MSG2, NOW - 11_000L));
        assertEquals(alone.body, stacked.earlierMessages.get(0).body);
    }

    @Test
    public void singleMessageHasNothingEarlier() {
        assertTrue(parse(chat(MSG1, MSG1, NOW - 15_000L)).earlierMessages.isEmpty());
    }

    @Test
    public void plainNotificationHasNothingEarlier() {
        Notification n = new NotificationCompat.Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("某銀行")
                .setContentText("您的信用卡消費 100 元")
                .build();
        assertTrue(parse(n).earlierMessages.isEmpty());
    }

    /** 對話裡累積很久的未讀訊息不回頭重記：超過回溯窗的不算。 */
    @Test
    public void oldUnreadMessagesAreLeftAlone() {
        long old = NOW - NotificationListener.EARLIER_MESSAGE_WINDOW_MS - 60_000L;
        List<EarlierMessage> earlier = parse(chat(MSG2,
                "🧾記帳｜支出｜50｜某帳戶｜0｜昨天的｜1791300000000｜1/1", old,
                MSG1, NOW - 15_000L,
                MSG2, NOW - 11_000L)).earlierMessages;
        assertEquals(1, earlier.size());
        assertTrue(earlier.get(0).body.contains("1/2"));
    }
}
