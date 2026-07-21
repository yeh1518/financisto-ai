package tw.tib.financisto.utils;

import android.os.Handler;
import android.os.Looper;
import android.widget.AbsListView;

/**
 * 修 ListView 原生 fast scroll 的誤觸問題（Gary 回報 2026-07-20）：
 * 框架的 FastScroller 在縮圖「淡出隱形後」右緣觸控照樣被攔截、直接跳位——
 * 靜止狀態下點到列表右側就整個列表亂跳，非常容易誤觸。
 *
 * 解法＝動態開關：平常整個停用 fast scroll（右緣觸控完全不攔截）；開始捲動才啟用
 * （拉桿現身、可抓取快速捲動）；停止捲動一段時間後再停用。等同 androidx RecyclerView
 * fast scroll 的「可見才抓得到」行為，是通用的現代 UX。
 */
public class SafeFastScroll {

    /** 捲動停止後多久收掉 fast scroll（毫秒）。拉桿本身的淡出動畫比這短，視覺上自然。 */
    private static final long DISABLE_DELAY_MS = 1500;

    public static void attach(final AbsListView list) {
        list.setFastScrollEnabled(false);   // 初始停用：隱形攔截自此不存在
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable disable = () -> list.setFastScrollEnabled(false);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_IDLE) {
                    handler.postDelayed(disable, DISABLE_DELAY_MS);
                } else {
                    handler.removeCallbacks(disable);
                    if (!view.isFastScrollEnabled()) {
                        view.setFastScrollEnabled(true);
                    }
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {
                // 拖著拉桿快速捲動時 state 可能停在 IDLE，但 onScroll 會持續觸發；
                // 視為活動中，延後停用，免得拉桿在使用者手下消失。
                if (view.isFastScrollEnabled()) {
                    handler.removeCallbacks(disable);
                    handler.postDelayed(disable, DISABLE_DELAY_MS);
                }
            }
        });
    }
}
