package tw.tib.financisto.ai;

import android.media.AudioManager;
import android.media.ToneGenerator;

/**
 * 開始錄音的提示音（單次短嗶）。用 {@link ToneGenerator} 合成，不必帶音檔資源；
 * 走 rington 音量流，靜音/震動模式下自然不出聲（記帳常在安靜場合，這行為剛好）。
 *
 * 每次 play 開一個一次性 generator、120ms 後 release（ToneGenerator 不宜長期持有）。
 */
public final class RecordCue {

    private RecordCue() {}

    public static void playStart() {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_RING, 70);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            // 放完再釋放；beep 本身約 120ms
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(tg::release, 220);
        } catch (Exception ignored) {
            // 某些裝置 ToneGenerator 會擲 RuntimeException（資源忙），沒有提示音不影響功能
        }
    }
}
