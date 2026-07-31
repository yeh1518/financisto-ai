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
