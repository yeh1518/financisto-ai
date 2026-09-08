package tw.tib.financisto.activity;

import android.content.Context;
import android.widget.ImageButton;

import tw.tib.financisto.R;
import tw.tib.financisto.filter.WhereFilter;

class FilterState {

    static void updateFilterColor(Context context, WhereFilter filter, ImageButton button) {
        updateFilterColor(context, filter, button, false);
    }

    /**
     * @param treatAsUnfiltered 篩選物件不是空的，但內容純粹是導覽帶進來的，畫面上該當成「沒篩選」。
     *                          帳戶明細就是這種：它一定帶著那個帳戶的條件，但使用者什麼都還沒挑，
     *                          圖示不該一進來就亮著（亮起來的意思是「你現在看到的不是全部」）。
     */
    static void updateFilterColor(Context context, WhereFilter filter, ImageButton button,
                                  boolean treatAsUnfiltered) {
        boolean unfiltered = treatAsUnfiltered || filter.isEmpty();
        int color = unfiltered ? context.getResources().getColor(R.color.bottom_bar_tint)
                : context.getResources().getColor(R.color.holo_blue_bright);
        if (button != null) {
            button.setColorFilter(color);
        }
    }

}
