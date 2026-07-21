package tw.tib.financisto;

import android.os.StrictMode;

import androidx.multidex.MultiDexApplication;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Application extends MultiDexApplication {
    private static Application instance;
    private static ExecutorService executor;

    public static Application getInstance() {
        return instance;
    }

    public static ExecutorService getExecutor() {
        return executor;
    }


    @Override
    public void onCreate()
    {
        super.onCreate();
        instance = this;
        executor = Executors.newCachedThreadPool();
        // 全 App AI 語音浮動鈕（取代分散的三顆麥克風鈕）
        tw.tib.financisto.ai.AiFloatingButton.register(this);
        // 背景預熱 Keystore/加密偏好——首次進 AI 設定/讀 API key 不卡主執行緒
        tw.tib.financisto.ai.AiPreferences.warmUpSecure(this);

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
