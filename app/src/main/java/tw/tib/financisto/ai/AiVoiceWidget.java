package tw.tib.financisto.ai;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import tw.tib.financisto.R;

/**
 * 1x1 語音記帳 widget：桌面上一顆麥克風，點下去直接開錄。
 *
 * 走與桌面捷徑相同的 {@link AiInputActivity#ACTION_VOICE_INPUT}——同一條路代表行為一致：
 * 一進去就開錄，記完依 AI 設定的「完成後進 App」決定回桌面還是留在 App
 * （見 {@code AiPreferences.isShortcutEntersApp}）。所以這顆鈕「開錄」與「進 App」兩用，
 * 不需要 widget 自己的設定頁。
 *
 * 沒有資料要刷新（updatePeriodMillis=0），onUpdate 只負責把 PendingIntent 掛上去。
 */
public class AiVoiceWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, buildViews(context));
        }
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_1x1_voice);
        // 指中繼站而非語音頁：直接指語音頁會拿到「上次離開時的畫面」，理由見 VoiceEntryActivity
        Intent launch = new Intent(context, VoiceEntryActivity.class)
                .setAction(AiInputActivity.ACTION_VOICE_INPUT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // FLAG_IMMUTABLE：API 31 起 PendingIntent 一定要指定可變性，這裡沒有要讓別人改 intent
        PendingIntent pending = PendingIntent.getActivity(context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.ai_widget_root, pending);
        return views;
    }
}
