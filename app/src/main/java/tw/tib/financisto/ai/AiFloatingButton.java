package tw.tib.financisto.ai;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import tw.tib.financisto.R;
import tw.tib.financisto.activity.AbstractTransactionActivity;

/**
 * 全 App 的 AI 語音浮動鈕（2026-07-17 定案）：一處註冊、每個 Activity 都有，取代原本
 * 分散在帳戶頁 / Blotter / 交易表單的三顆麥克風鈕。
 *
 * 靠 {@link Application.ActivityLifecycleCallbacks} 在每個 Activity resume 時把浮動鈕
 * {@code addContentView} 疊到內容之上——**不改任何 Activity 或 layout**。
 *
 * 行為依當前頁面而定（有界判斷，不逐頁盤點）：
 * <ul>
 *   <li>{@link AiInputActivity}：啟動麥克風。</li>
 *   <li>{@link AbstractTransactionActivity}（交易/轉帳編輯）：補充模式（就地套用，不離開）。</li>
 *   <li>其他一律：把辨識介面**疊在當前頁上面**開起來，當前頁原封不動留在下面——所以就算在
 *       某個編輯頁按下去，編輯內容不會丟失（記完那筆按返回就回到原頁）。</li>
 * </ul>
 * 位置預設右下角、在下排工具列上方；半透明、可拖曳（拖曳位置跨頁沿用）。
 */
public class AiFloatingButton implements Application.ActivityLifecycleCallbacks {

    private static final String VIEW_TAG = "ai_floating_button";

    /**
     * 不顯示浮動鈕的頁面（用 simple name 比對）。
     * - PinActivity：解鎖前不該出現。
     * - AiSettingsActivity：這裡點浮動鈕會再開一個 AiInputActivity（dispatch 的 else 分支）→
     *   設定→鈕→錄音→設定… 無限套娃。設定頁改用自己的「示意用預覽鈕」（見該頁 setupFabSizeSeek）。
     */
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList(
            "PinActivity", "AiSettingsActivity"));

    // 拖曳位置存在 prefs（見 AiPreferences.getFabTransX/Y）＝跨 Activity 也跨重開沿用。
    // 不留 static 快取：prefs 本身就有記憶體快取，單一真相來源比較不會有人忘了同步。

    public static void register(Application app) {
        app.registerActivityLifecycleCallbacks(new AiFloatingButton());
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (EXCLUDED.contains(activity.getClass().getSimpleName())) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        // 已經有了就別重複加——但**一定要重新套用當前設定**而非直接跳過：stack 裡還活著的
        // 畫面沿用的是它建立當時那顆鈕，不套的話尺寸與拖曳位置都會停在舊值（返回時看到舊的、
        // 要再切一次才對）。尺寸與位置一律走 applyStyle，避免又漏掉其中一項。
        View existing = content.findViewWithTag(VIEW_TAG);
        if (existing != null) {
            applyStyle((ImageView) existing, activity);
            return;
        }

        float d = activity.getResources().getDisplayMetrics().density;

        ImageView fab = new ImageView(activity);
        fab.setTag(VIEW_TAG);
        fab.setImageResource(R.drawable.ic_ai_mic);
        fab.setBackgroundResource(R.drawable.btn_ai_mic_circle);
        fab.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fab.setAlpha(0.6f);                                       // 半透明
        fab.setContentDescription(activity.getString(R.string.ai_voice_button_desc));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, 0);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = (int) (16 * d);
        lp.bottomMargin = (int) (88 * d);                         // 在下排工具列上方
        fab.setLayoutParams(lp);
        applyStyle(fab, activity);                                // 尺寸/padding/位置一律走這裡

        attachDragAndClick(fab, activity);
        content.addView(fab);
    }

    /**
     * 套用當前的鈕大小、圖示邊距與拖曳位置。建立時與 resume 時（對既有的鈕）都走這裡——
     * 這是「改了設定或拖了位置，返回其他畫面就生效」的唯一入口。
     */
    private void applyStyle(ImageView fab, Activity activity) {
        fab.setTranslationX(AiPreferences.getFabTransX(activity));
        fab.setTranslationY(AiPreferences.getFabTransY(activity));
        // 存下來的位置在別的畫面尺寸下可能失效（例如轉向）——位置是持久化的，失效就永遠卡在
        // 畫面外。等 layout 完成（此時才量得到父容器與 insets）再夾一次當保險。
        fab.post(() -> {
            fab.setTranslationX(clampX(fab, fab.getTranslationX()));
            fab.setTranslationY(clampY(fab, fab.getTranslationY()));
        });

        float d = activity.getResources().getDisplayMetrics().density;
        int sizeDp = AiPreferences.getFabSizeDp(activity);
        int size = (int) (sizeDp * d);
        int pad = (int) (sizeDp * 0.25f * d);   // 圖示留 1/4 邊距，隨鈕等比縮放

        ViewGroup.LayoutParams lp = fab.getLayoutParams();
        if (lp != null && (lp.width != size || lp.height != size)) {
            lp.width = size;
            lp.height = size;
            fab.setLayoutParams(lp);            // 觸發重新 layout
        }
        if (fab.getPaddingLeft() != pad) {
            fab.setPadding(pad, pad, pad, pad);
        }
    }

    /**
     * 拖曳 vs 點擊 vs 長按：移動超過 touchSlop 算拖曳（記位置）；按住不動超過長按時間
     * ＝直接進 AI 設定（省得為了改設定點來點去）；否則算點擊（分派動作）。
     */
    private void attachDragAndClick(ImageView fab, Activity activity) {
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        fab.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY, startX, startY;
            boolean dragged;
            boolean longPressed;
            Runnable longPress;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = v.getTranslationX();
                        startY = v.getTranslationY();
                        dragged = false;
                        longPressed = false;
                        longPress = () -> {
                            longPressed = true;
                            v.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.LONG_PRESS);
                            openSettings(activity);
                        };
                        handler.postDelayed(longPress,
                                ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                            dragged = true;
                            handler.removeCallbacks(longPress);   // 開始拖就不是長按
                        }
                        if (longPressed) return true;             // 已進設定就不再跟著手指跑
                        // 夾在畫面內：位置會存進 prefs，拖出畫面外就再也點不到、也拖不回來了
                        v.setTranslationX(clampX(v, startX + dx));
                        v.setTranslationY(clampY(v, startY + dy));
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(longPress);
                        if (longPressed) {
                            // 長按已經開了設定；放開不要再觸發點擊
                        } else if (dragged) {
                            saveDragPosition(activity, v);
                        } else {
                            v.performClick();
                            dispatch(activity);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        // 拖到一半被攔截（例如父層接手觸控）：鈕已經移動了，位置也要存，
                        // 否則切到別的畫面又會跳回舊位置。取消不算點擊。
                        handler.removeCallbacks(longPress);
                        if (dragged) saveDragPosition(activity, v);
                        return true;
                }
                return false;
            }
        });
    }

    /** 長按浮動鈕的去處：AI 設定。 */
    private static void openSettings(Activity activity) {
        activity.startActivity(new android.content.Intent(activity, AiSettingsActivity.class));
    }

    private static void saveDragPosition(Activity activity, View v) {
        AiPreferences.saveFabTrans(activity, v.getTranslationX(), v.getTranslationY());
    }

    /**
     * 把位移夾在**系統列以內**（鈕預設錨在右下＋margin，故 translation 往左/上為負）。
     *
     * 位置會存進 prefs、重開也留著，沒夾的話一旦拖出畫面就永久失聯。夾的範圍不能只用父容器：
     * targetSdk 35 的 edge-to-edge 下 `android.R.id.content` 涵蓋整個視窗（含狀態列/手勢列後方），
     * 只夾父容器會讓鈕停在狀態列底下——難按，甚至觸控被系統拿去拉通知欄。
     */
    private static Insets systemBars(View v) {
        WindowInsetsCompat wi = ViewCompat.getRootWindowInsets(v);
        return wi != null ? wi.getInsets(WindowInsetsCompat.Type.systemBars()) : Insets.NONE;
    }

    private static float clampX(View v, float x) {
        View parent = (View) v.getParent();
        if (parent == null) return x;
        Insets bars = systemBars(v);
        int right = marginRight(v);
        float min = -(parent.getWidth() - v.getWidth() - right - bars.left);  // 貼齊左緣（不進左 inset）
        float max = right - bars.right;                                       // 貼齊右緣（不進右 inset）
        return Math.max(min, Math.min(max, x));
    }

    private static float clampY(View v, float y) {
        View parent = (View) v.getParent();
        if (parent == null) return y;
        Insets bars = systemBars(v);
        int bottom = marginBottom(v);
        float min = -(parent.getHeight() - v.getHeight() - bottom - bars.top); // 不進狀態列
        float max = bottom - bars.bottom;                                      // 不進手勢列
        return Math.max(min, Math.min(max, y));
    }

    private static int marginRight(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        return (lp instanceof ViewGroup.MarginLayoutParams)
                ? ((ViewGroup.MarginLayoutParams) lp).rightMargin : 0;
    }

    private static int marginBottom(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        return (lp instanceof ViewGroup.MarginLayoutParams)
                ? ((ViewGroup.MarginLayoutParams) lp).bottomMargin : 0;
    }

    private void dispatch(Activity activity) {
        if (activity instanceof AiInputActivity) {
            ((AiInputActivity) activity).startVoiceFromFab();
        } else if (activity instanceof AbstractTransactionActivity) {
            ((AbstractTransactionActivity) activity).launchAiSupplement();
        } else {
            // 疊在當前頁上面開辨識介面，不 finish 當前頁＝不丟失正在編輯的內容
            android.content.Intent i = new android.content.Intent(activity, AiInputActivity.class);
            i.putExtra(AiInputActivity.START_VOICE_EXTRA, true);
            // 帳戶明細頁（AiDefaultAccountProvider）進來的：帶該帳戶當預設，語音講到別的仍會覆蓋
            if (activity instanceof AiDefaultAccountProvider) {
                long acc = ((AiDefaultAccountProvider) activity).getAiDefaultAccountId();
                if (acc > 0) i.putExtra(AiInputActivity.ACCOUNT_ID_EXTRA, acc);
            }
            activity.startActivity(i);
        }
    }

    @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
    @Override public void onActivityStarted(@NonNull Activity a) {}
    @Override public void onActivityPaused(@NonNull Activity a) {}
    @Override public void onActivityStopped(@NonNull Activity a) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
    @Override public void onActivityDestroyed(@NonNull Activity a) {}
}
