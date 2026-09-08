package tw.tib.financisto.ai;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.ComponentActivity;

/**
 * 桌面語音入口的中繼站（捷徑／widget 都走這裡），本身沒有畫面：onCreate 轉手把
 * {@link AiInputActivity} 拉起來就 finish。
 *
 * 為什麼要多這一層——**直接讓捷徑指向 AiInputActivity 會拿到「上次離開時的畫面」**：
 * 捷徑的 intent 與該 task 的 root intent 完全一樣時，系統判定為「回到既有 task」，
 * 不會重跑 onCreate；於是停在 AI 設定或錄音頁時按捷徑，只會把那個舊畫面叫回來，
 * 也不會自動開錄（2026-07-31 回報）。
 *
 * 這裡自己一個 task（taskAffinity=""）＋ noHistory＋不進 recents，所以**每次按都一定
 * 重跑 onCreate**；再用 CLEAR_TOP 把主 task 收回到語音頁，等於「桌面捷徑一律重新進入
 * 語音介面並開錄」。從 app 圖示或 recents 回去的路徑完全沒動，維持原本的續接行為。
 */
public class VoiceEntryActivity extends ComponentActivity {

    /** 釘在桌面的語音捷徑 id（AiSettingsActivity 建立、下面的修補用同一個）。 */
    public static final String PINNED_SHORTCUT_ID = "ai_voice_pinned";

    /**
     * 把**已經釘在桌面**的舊語音捷徑改指到本頁。
     *
     * 釘選捷徑的 intent 是 launcher 在釘的當下存起來的，之後改 app 的程式不會動到它——
     * 所以 2026-08-01 把入口換成本中繼站之後，早就釘在桌面的那顆仍走舊路（直接指語音頁），
     * 於是照樣拿到「上次離開時的畫面」。使用者只會看到「修了但沒用」，而且不會想到要重釘。
     * `updateShortcuts` 可以就地改寫自家已釘選的捷徑，開 app 時順手修掉。
     *
     * 沒釘過就什麼都不做（不要無中生有一顆）。API 25 以下沒有 ShortcutManager，直接跳過。
     */
    public static void repairPinnedShortcut(android.content.Context context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N_MR1) return;
        try {
            android.content.pm.ShortcutManager sm =
                    context.getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) return;
            boolean pinned = false;
            for (android.content.pm.ShortcutInfo s : sm.getPinnedShortcuts()) {
                if (PINNED_SHORTCUT_ID.equals(s.getId())) { pinned = true; break; }
            }
            if (!pinned) return;

            Intent launch = new Intent(context, VoiceEntryActivity.class)
                    .setAction(AiInputActivity.ACTION_VOICE_INPUT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            sm.updateShortcuts(java.util.Collections.singletonList(
                    new android.content.pm.ShortcutInfo.Builder(context, PINNED_SHORTCUT_ID)
                            .setShortLabel(context.getString(
                                    tw.tib.financisto.R.string.ai_voice_shortcut_short))
                            .setLongLabel(context.getString(
                                    tw.tib.financisto.R.string.ai_voice_shortcut_long))
                            .setIcon(android.graphics.drawable.Icon.createWithResource(context,
                                    tw.tib.financisto.R.mipmap.ai_mic_shortcut))
                            .setIntent(launch)
                            .build()));
        } catch (Exception ignored) {
            // launcher 不支援或系統擋下都不是致命問題：使用者仍可自己刪掉重釘
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent voice = new Intent(this, AiInputActivity.class)
                .setAction(AiInputActivity.ACTION_VOICE_INPUT)
                // NEW_TASK：回到 app 自己的 task（本頁在別的 task）
                // CLEAR_TOP：task 裡若已經有語音頁就收回到它（上面的設定頁/錄音頁一併關掉），
                //            沒有就直接疊上去——後者不會動到正在編輯的畫面
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // 帳戶預設等 extra 目前捷徑不帶；未來若要帶，從 getIntent() 轉手過去即可
        startActivity(voice);
        finish();
    }
}
