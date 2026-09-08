package tw.tib.financisto.ai;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;

import tw.tib.financisto.R;

/**
 * AI 記帳的說明／引導（2026-07-23）。
 *
 * 第一次點麥克風時自動出現一次（看過就記住），之後從 AI 設定頁的「說明」叫出。
 * 內容三段：怎麼用、要準備什麼（含「免費也能跑」的組合指引）、隱私聲明——
 * 語音與文字會送到使用者自己選的雲端服務商，本 App 不經手、不代管，風險自負。
 */
public class AiIntroDialog {

    private AiIntroDialog() {}

    /** 第一次使用才彈；彈過就記住。@return true＝這次有彈（呼叫端應等使用者關掉再繼續）。 */
    public static boolean showIfFirstTime(Context context, Runnable onContinue) {
        if (AiPreferences.isIntroShown(context)) return false;
        AiPreferences.saveIntroShown(context, true);
        show(context, onContinue);
        return true;
    }

    /** 主動叫出（設定頁的「說明」）。 */
    public static void show(Context context) {
        show(context, null);
    }

    // 按「開始使用」才接著做原本的事（返回鍵關掉＝先不錄，使用者自己再點麥克風）
    private static void show(Context context, Runnable onContinue) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.ai_intro_title)
                .setMessage(R.string.ai_intro_message)
                .setPositiveButton(R.string.ai_intro_got_it,
                        (d, w) -> { if (onContinue != null) onContinue.run(); })
                .setNeutralButton(R.string.ai_intro_open_settings, (d, w) ->
                        context.startActivity(new Intent(context, AiSettingsActivity.class)))
                .show();
    }
}
