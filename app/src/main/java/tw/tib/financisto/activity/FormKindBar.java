package tw.tib.financisto.activity;

import android.app.ActionBar;
import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import tw.tib.financisto.R;

/**
 * 交易表單標題列的種類切換：交易｜移轉｜調整餘額。樣子照主畫面那排頁籤（帳戶列表／交易明細…）：
 * 深灰底、平均分滿整排、選中白字＋底下一條青綠線、其他灰字。
 * 只管畫面與點擊；能不能切、切過去帶什麼由 {@link AbstractTransactionActivity} 決定。
 */
final class FormKindBar {

	interface Listener {
		void onKindTapped(FormSwitch.Kind kind);
	}

	// 與主畫面 TabLayout 同色（AppCompat 暗色主題的 colorPrimary／colorAccent）
	private static final int BAR = 0xFF212121;
	private static final int ACCENT = 0xFF80CBC4;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFF808080;
	private static final int TEXT_DISABLED = 0xFF4A4A4A;
	private static final int[] TITLES = {R.string.transaction, R.string.transfer, R.string.update_balance};

	private final Activity activity;
	private final FormSwitch.Kind current;
	private final LinearLayout row;
	private final TextView[] tabs = new TextView[FormSwitch.Kind.values().length];

	FormKindBar(Activity activity, FormSwitch.Kind current, Listener listener) {
		this.activity = activity;
		this.current = current;
		row = new LinearLayout(activity);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		for (FormSwitch.Kind kind : FormSwitch.Kind.values()) {
			TextView v = new TextView(activity);
			v.setText(TITLES[kind.ordinal()]);
			v.setSingleLine(true);
			v.setGravity(Gravity.CENTER);
			v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
			v.setPadding(dp(14), dp(12), dp(14), dp(12));
			boolean selected = kind == current;
			v.setTextColor(selected ? TEXT : TEXT_DIM);
			if (selected) {
				v.setBackground(underline());
			} else {
				v.setBackground(selectableBackground());
				v.setOnClickListener(x -> listener.onKindTapped(kind));
			}
			row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
			tabs[kind.ordinal()] = v;
		}
	}

	/** 選中標記：底部 2dp 青綠線，與整格同寬（同主畫面頁籤）。 */
	private Drawable underline() {
		LayerDrawable d = new LayerDrawable(new Drawable[]{new ColorDrawable(ACCENT)});
		d.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
		d.setLayerHeight(0, dp(2));
		return d;
	}

	private Drawable selectableBackground() {
		TypedValue tv = new TypedValue();
		activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
		return activity.getDrawable(tv.resourceId);
	}

	/** 切不過去的種類灰掉、不能按（灰掉就說明了「現在不能切」，不另跳訊息）。 */
	void setAvailable(FormSwitch.Kind kind, boolean available) {
		if (kind == current) return;
		TextView v = tabs[kind.ordinal()];
		v.setTextColor(available ? TEXT_DIM : TEXT_DISABLED);
		v.setEnabled(available);
		v.setClickable(available);
	}

	/**
	 * 放進標題列、取代標題與左側圖示，整排換成主畫面頁籤的深灰底。Holo 標題列自帶的橫貫藍色底線
	 * 跟著背景一起換掉（不然會跟選中頁籤的底線疊在一起）。標題列不存在時什麼都不做。
	 */
	boolean install() {
		ActionBar actionBar = activity.getActionBar();
		if (actionBar == null) return false;
		actionBar.setBackgroundDrawable(new ColorDrawable(BAR));
		actionBar.setDisplayShowHomeEnabled(false);
		actionBar.setDisplayShowTitleEnabled(false);
		actionBar.setDisplayShowCustomEnabled(true);
		actionBar.setCustomView(row, new ActionBar.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		paintStatusBar();
		return true;
	}

	/**
	 * 狀態列也換成頁籤的深灰，跟主畫面一樣從螢幕頂端一路灰到頁籤底線。交易表單用的 Holo 主題
	 * 沒讓 app 畫系統列背景，狀態列一律是黑的；要先開 FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS 才設得了顏色。
	 */
	private void paintStatusBar() {
		android.view.Window w = activity.getWindow();
		w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
		w.setStatusBarColor(BAR);
	}

	private int dp(int v) {
		return Math.round(v * activity.getResources().getDisplayMetrics().density);
	}
}
