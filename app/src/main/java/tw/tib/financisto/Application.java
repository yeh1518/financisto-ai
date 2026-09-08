package tw.tib.financisto;

import android.os.StrictMode;

import androidx.multidex.MultiDexApplication;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

public class Application extends MultiDexApplication {
    private static Application instance;
    private static ExecutorService executor;
    // transaction ID -> copied timestamp millis
    private static Long2LongOpenHashMap copiedUneditedTransactions;

    public static Application getInstance() {
        return instance;
    }

    public static ExecutorService getExecutor() {
        return executor;
    }

    public static Long2LongOpenHashMap getCopiedUneditedTransactions() {
        return copiedUneditedTransactions;
    }


    @Override
    public void onCreate()
    {
        super.onCreate();
        instance = this;
        executor = Executors.newCachedThreadPool();
        copiedUneditedTransactions = new Long2LongOpenHashMap();
        // 全 App AI 語音浮動鈕（取代分散的三顆麥克風鈕）
        tw.tib.financisto.ai.AiFloatingButton.register(this);
        // 背景預熱 Keystore/加密偏好——首次進 AI 設定/讀 API key 不卡主執行緒
        tw.tib.financisto.ai.AiPreferences.warmUpSecure(this);
        // 舊版釘在桌面的語音捷徑仍指著舊入口（launcher 快取 intent），開 app 時就地修掉
        tw.tib.financisto.ai.VoiceEntryActivity.repairPinnedShortcut(this);
        // 通知 listener 被系統解綁的自癒（APK 更新後常見，權限看似還在但收不到）。
        // 掛在啟動而不只在更新廣播：更新廣播那次不一定救得回來，而「開一次 app」
        // 是使用者發現沒記到帳時本來就會做的事——讓那個動作順便把它修好。
        // 已綁好時是 no-op。
        tw.tib.financisto.service.NotificationListener.requestRebindIfGranted(this);

        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
            //        .detectLeakedSqlLiteObjects()
            //        .detectLeakedClosableObjects()
                    .detectAll()
                    .penaltyLog()
                    .build());
        }
    }
}
