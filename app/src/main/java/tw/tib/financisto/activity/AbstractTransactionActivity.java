/*******************************************************************************
 * Copyright (c) 2010 Denis Solonenko.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * <p/>
 * Contributors:
 * Denis Solonenko - initial API and implementation
 ******************************************************************************/
package tw.tib.financisto.activity;

import static android.Manifest.permission.POST_NOTIFICATIONS;

import android.content.Intent;
import android.database.Cursor;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;

import greendroid.widget.QuickActionWidget;
import tw.tib.financisto.Application;
import tw.tib.financisto.R;
import tw.tib.financisto.ai.AiPreferences;
import tw.tib.financisto.ai.BookkeepingParser;
import tw.tib.financisto.ai.VoiceCaptureActivity;
import tw.tib.financisto.ai.EntityContextBuilder;
import tw.tib.financisto.ai.ParsedTransaction;
import tw.tib.financisto.datetime.DateUtils;
import tw.tib.financisto.db.DatabaseHelper.AccountColumns;
import tw.tib.financisto.db.DatabaseHelper.TransactionColumns;
import tw.tib.financisto.dialog.DatePickerTwinDialog;
import tw.tib.financisto.model.*;
import tw.tib.financisto.model.Account;
import tw.tib.financisto.model.Attribute;
import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.SystemAttribute;
import tw.tib.financisto.model.Transaction;
import tw.tib.financisto.model.TransactionAttribute;
import tw.tib.financisto.model.TransactionStatus;
import tw.tib.financisto.recur.NotificationOptions;
import tw.tib.financisto.recur.Recurrence;
import tw.tib.financisto.utils.EnumUtils;
import tw.tib.financisto.utils.MyPreferences;
import tw.tib.financisto.utils.PicturesUtil;
import tw.tib.financisto.utils.TransactionUtils;
import tw.tib.financisto.utils.Utils;
import tw.tib.financisto.view.AttributeView;
import tw.tib.financisto.view.AttributeViewFactory;
import tw.tib.financisto.widget.RateLayoutView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static tw.tib.financisto.activity.RequestPermission.isRequestingPermission;
import static tw.tib.financisto.model.Category.NO_CATEGORY_ID;
import static tw.tib.financisto.model.Category.SPLIT_CATEGORY_ID;
import static tw.tib.financisto.model.MyLocation.CURRENT_LOCATION_ID;
import static tw.tib.financisto.model.Project.NO_PROJECT_ID;
import static tw.tib.financisto.utils.Utils.text;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public abstract class AbstractTransactionActivity extends AbstractActivity implements CategorySelector.CategorySelectorListener {

	public static final String TRAN_ID_EXTRA = "tranId";
	public static final String ACCOUNT_ID_EXTRA = "accountId";
	public static final String DUPLICATE_EXTRA = "isDuplicate";
	public static final String TEMPLATE_EXTRA = "isTemplate";
	public static final String DATETIME_EXTRA = "dateTimeExtra";
	public static final String NEW_FROM_TEMPLATE_EXTRA = "newFromTemplateExtra";

	private static final int RECURRENCE_REQUEST = 4003;
	private static final int NOTIFICATION_REQUEST = 4004;
	private static final int PICTURE_REQUEST = 4005;
	private static final int AI_VOICE_REQUEST = 4006;
	private static final int AI_TYPE_SWITCH_REQUEST = 4007;
	private static final int AI_MIC_PERMISSION_REQUEST = 4008;
	private static final int AI_VOICE_CAPTURE_REQUEST = 4009;

	/** 補充模式切換交易型別時，帶去對方表單的 draft template；回來要刪，見 cleanupTypeSwitchDraft。 */
	private long pendingTypeSwitchDraftId = -1;

	private static final TransactionStatus[] statuses = TransactionStatus.values();

	protected RateLayoutView rateView;

	protected EditText templateName;
	protected TextView accountText;
	protected Cursor accountCursor;
	protected ListAdapter accountAdapter;

	protected Calendar dateTime;
	protected ImageButton status;
	protected Button dateText;
	protected Button timeText;

	protected EditText noteText;
	protected TextView recurText;
	protected TextView notificationText;

	private View pictureTopView;
	private ImageView pictureView;
	private TextView pictureDescView;

	private TextView editDisabled;

	private CheckBox ccardPayment;

	protected Account selectedAccount;

	protected String recurrence;
	protected String notificationOptions;

	protected boolean isDuplicate = false;

	protected PayeeSelector<AbstractTransactionActivity> payeeSelector;
	protected ProjectSelector<AbstractTransactionActivity> projectSelector;
	protected LocationSelector<AbstractTransactionActivity> locationSelector;
	protected CategorySelector<AbstractTransactionActivity> categorySelector;

	protected boolean isRememberLastAccount;
	protected boolean isRememberLastCategory;
	protected boolean isRememberLastLocation;
	protected boolean isRememberLastProject;
	protected boolean isShowLocation;
	protected boolean isShowProject;
	protected boolean isShowNote;
	protected boolean isShowTakePicture;
	protected boolean isShowIsCCardPayment;
	protected boolean isOpenCalculatorForTemplates;
	protected boolean isShowAccountBalanceOnSelector;
	protected boolean isTrackSplitEntityInChild;

	protected boolean isShowPayee = true;
//    protected AutoCompleteTextView payeeText;
//    protected SimpleCursorAdapter payeeAdapter;

	protected AttributeView deleteAfterExpired;

	protected DateFormat df;
	protected DateFormat tf;

	protected Utils u;

	private QuickActionWidget pickImageActionGrid;

	ActivityResultLauncher<PickVisualMediaRequest> pickMedia;

	protected Transaction transaction = new Transaction();

	public AbstractTransactionActivity() {
	}

	protected abstract int getLayoutId();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		u = new Utils(this);

		if (MyPreferences.isSecureWindow()) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		}

		df = DateUtils.getLongDateFormat(this);
		tf = DateUtils.getTimeFormat(this);

		long t0 = System.currentTimeMillis();

		setContentView(getLayoutId());

		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.transaction_base), (v, windowInsets) -> {
			Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
					| WindowInsetsCompat.Type.statusBars()
					| WindowInsetsCompat.Type.captionBar());
			var lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
			lp.topMargin = insets.top;
			lp.bottomMargin = insets.bottom;
			v.setLayoutParams(lp);
			return WindowInsetsCompat.CONSUMED;
		});

		isRememberLastAccount = MyPreferences.isRememberAccount();
		isRememberLastCategory = isRememberLastAccount && MyPreferences.isRememberCategory();
		isRememberLastLocation = isRememberLastCategory && MyPreferences.isRememberLocation();
		isRememberLastProject = isRememberLastCategory && MyPreferences.isRememberProject();
		isShowLocation = MyPreferences.isShowLocation();
		isShowProject = MyPreferences.isShowProject();
		isShowNote = MyPreferences.isShowNote();
		isShowTakePicture = MyPreferences.isShowTakePicture();
		isShowIsCCardPayment = MyPreferences.isShowIsCCardPayment();
		isOpenCalculatorForTemplates = MyPreferences.isOpenCalculatorForTemplates();
		isShowAccountBalanceOnSelector = MyPreferences.isShowAccountBalanceOnSelector();
		isTrackSplitEntityInChild = MyPreferences.isTrackSplitEntityInChild();

		categorySelector = new CategorySelector<>(this, db, x);
		categorySelector.setListener(this);

		long accountId = -1;
		long transactionId = -1;
		boolean isNewFromTemplate = false;
		final Intent intent = getIntent();
		if (intent != null) {
			accountId = intent.getLongExtra(ACCOUNT_ID_EXTRA, -1);
			transactionId = intent.getLongExtra(TRAN_ID_EXTRA, -1);
			transaction.dateTime = intent.getLongExtra(DATETIME_EXTRA, System.currentTimeMillis());
			if (transactionId != -1) {
				transaction = db.getTransaction(transactionId);
				transaction.categoryAttributes = db.getAllAttributesForTransaction(transactionId);
				isDuplicate = intent.getBooleanExtra(DUPLICATE_EXTRA, false);
				isNewFromTemplate = intent.getBooleanExtra(NEW_FROM_TEMPLATE_EXTRA, false);
				if (isDuplicate) {
					transaction.id = -1;
					// 複製一筆預設用「現在」，但呼叫端明確指定 DATETIME_EXTRA 時要照它走
					// （AI 記帳講出日期時間時就是走這條；沒指定仍是現在，行為不變）。
					transaction.dateTime = intent.getLongExtra(DATETIME_EXTRA, System.currentTimeMillis());
				}
			}
			transaction.isTemplate = intent.getIntExtra(TEMPLATE_EXTRA, transaction.isTemplate);
		}

		if (transaction.id == -1) {
			accountCursor = db.getAllActiveAccounts();
		} else {
			accountCursor = db.getAccountsForTransaction(transaction);
		}
		startManagingCursor(accountCursor);
		if (isShowAccountBalanceOnSelector) {
			accountAdapter = TransactionUtils.createAccountBalanceAdapter(this, accountCursor);
		}
		else {
			accountAdapter = TransactionUtils.createAccountAdapter(this, accountCursor);
		}

		dateTime = Calendar.getInstance();
		Date date = dateTime.getTime();

		status = findViewById(R.id.status);
		status.setOnClickListener(v -> {
			ArrayAdapter<String> adapter = EnumUtils.createDropDownAdapter(AbstractTransactionActivity.this, statuses);
			x.selectPosition(AbstractTransactionActivity.this, R.id.status, R.string.transaction_status, adapter, transaction.status.ordinal());
		});

		dateText = findViewById(R.id.date);
		dateText.setText(df.format(date));
		dateText.setOnClickListener(arg0 -> {
			if (MyPreferences.isUseTwinDatePicker() && Build.VERSION.SDK_INT >= 22) {
				DatePickerTwinDialog dpd = DatePickerTwinDialog.newInstance(
						dateTime.get(Calendar.YEAR),
						dateTime.get(Calendar.MONTH),
						dateTime.get(Calendar.DAY_OF_MONTH),
						(view, year, monthOfYear, dayOfMonth) -> {
							dateTime.set(year, monthOfYear, dayOfMonth);
							dateText.setText(df.format(dateTime.getTime()));
						});
				dpd.show(getFragmentManager(), DatePickerTwinDialog.TAG);
			}
			else {
				DatePickerDialog dialog = new DatePickerDialog(AbstractTransactionActivity.this,
						(view, year, monthOfYear, dayOfMonth) -> {
							dateTime.set(year, monthOfYear, dayOfMonth);
							dateText.setText(df.format(dateTime.getTime()));
						},
						dateTime.get(Calendar.YEAR),
						dateTime.get(Calendar.MONTH),
						dateTime.get(Calendar.DAY_OF_MONTH)
				);
				dialog.show();
			}
		});

		timeText = findViewById(R.id.time);
		timeText.setText(tf.format(date));
		timeText.setOnClickListener(arg0 -> {
			boolean is24Format = DateUtils.is24HourFormat(AbstractTransactionActivity.this);
			TimePickerDialog dialog = new TimePickerDialog(AbstractTransactionActivity.this,
					(picker, hourOfDay, minute) -> {
						dateTime.set(Calendar.HOUR_OF_DAY, hourOfDay);
						dateTime.set(Calendar.MINUTE, minute);
						timeText.setText(tf.format(dateTime.getTime()));
					},
					dateTime.get(Calendar.HOUR_OF_DAY), dateTime.get(Calendar.MINUTE), is24Format
			);
			dialog.show();
		});

		internalOnCreate();

		LinearLayout layout = findViewById(R.id.list);

		editDisabled = findViewById(R.id.edit_disabled);
		this.templateName = new EditText(this);
		if (transaction.isTemplate()) {
			x.addEditNode(layout, R.string.template_name, templateName);
		}

		rateView = new RateLayoutView(this, x, layout);

		locationSelector = new LocationSelector<>(this, db, x);
		if (transaction.locationId != CURRENT_LOCATION_ID) {
			locationSelector.setIncludeEntityIds(transaction.locationId);
		}

		projectSelector = new ProjectSelector<>(this, db, x);
		if (transaction.projectId != NO_PROJECT_ID) {
			projectSelector.setIncludeEntityIds(transaction.projectId);
		}

		createListNodes(layout);
		categorySelector.createAttributesLayout(layout);
		createCommonNodes(layout);

		// ensure views are created before start loading
		// such that loading would never finish before view available
		fetchCategories();
		locationSelector.fetchEntities();
		projectSelector.fetchEntities();

		if (transaction.isScheduled()) {
			recurText = x.addListNode(layout, R.id.recurrence_pattern, R.string.recur, R.string.recur_interval_no_recur);
			notificationText = x.addListNode(layout, R.id.notification, R.string.notification, R.string.notification_options_default);
			Attribute sa = db.getSystemAttribute(SystemAttribute.DELETE_AFTER_EXPIRED);
			deleteAfterExpired = AttributeViewFactory.createViewForAttribute(this, sa);
			String value = transaction.getSystemAttribute(SystemAttribute.DELETE_AFTER_EXPIRED);
			deleteAfterExpired.inflateView(layout, value != null ? value : sa.defaultValue);
		}

		// 這排維持原版的文字鈕（麥克風移到全 App 浮動鈕後就不再擠，可以回歸原介面）：
		// 兩顆 Button，編輯既有交易時 bSaveAndNew 變「取消」——上游本來的設計。
		Button bSave = findViewById(R.id.bSave);
		bSave.setOnClickListener(arg0 -> saveAndFinish());

		final boolean isEdit = transaction.id > 0;
		Button bSaveAndNew = findViewById(R.id.bSaveAndNew);
		if (isEdit) {
			bSaveAndNew.setText(R.string.cancel);
		}
		bSaveAndNew.setOnClickListener(arg0 -> {
			if (isEdit) {
				setResult(RESULT_CANCELED);
				finish();
			} else {
				if (saveAndFinish()) {
					intent.putExtra(DATETIME_EXTRA, transaction.dateTime);
					startActivityForResult(intent, -1);
				}
			}
		});

		if (transactionId != -1) {
			isOpenCalculatorForTemplates &= isNewFromTemplate;
			editTransaction(transaction);
		} else {
			setDateTime(transaction.dateTime);
			categorySelector.selectCategory(NO_CATEGORY_ID);
			if (accountId != -1) {
				selectAccount(accountId);
			} else {
				long lastAccountId = MyPreferences.getLastAccount();
				if (isRememberLastAccount && lastAccountId != -1) {
					selectAccount(lastAccountId);
				}
			}
			if (!isRememberLastProject) {
				projectSelector.selectEntity(NO_PROJECT_ID);
			}
			if (!isRememberLastLocation) {
				locationSelector.selectEntity(CURRENT_LOCATION_ID);
			}
			if (transaction.isScheduled()) {
				selectStatus(TransactionStatus.PN);
			}
		}

		if (isShowTakePicture) {
			pickMedia =
					registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
						// Callback is invoked after the user selects a media item or closes the
						// photo picker.
						if (uri != null) {
							Log.d("PhotoPicker", "Selected URI: " + uri);
							Application.getExecutor().execute(() -> {
								String fileName = PicturesUtil.saveSelectedPicture(this, uri);
								if (fileName != null) {
									new Handler(Looper.getMainLooper()).post(() -> selectPicture(fileName));
								}
							});
						} else {
							Log.d("PhotoPicker", "No media selected");
						}
					});
		}

		long t1 = System.currentTimeMillis();
		Log.i("TransactionActivity", "onCreate " + (t1 - t0) + "ms");
	}

	protected void createPayeeNode(LinearLayout layout) {
		payeeSelector = new PayeeSelector<>(this, db, x);
		if (transaction.payeeId != Payee.EMPTY.id) {
			payeeSelector.setIncludeEntityIds(transaction.payeeId);
		}
		payeeSelector.createNode(layout);
		payeeSelector.fetchEntities();
	}

	protected abstract void fetchCategories();

	private boolean saveAndFinish() {
		long id = save();
		if (id > 0) {
			Intent data = new Intent();
			data.putExtra(TransactionColumns._id.name(), id);
			setResult(RESULT_OK, data);
			finish();
			return true;
		}
		return false;
	}

	private long save() {
		if (onOKClicked()) {
			boolean isNew = transaction.id == -1;
			long id = db.insertOrUpdate(transaction, getAttributes());
			if (isNew) {
				MyPreferences.setLastAccount(transaction.fromAccountId);
			}
			AccountWidget.updateWidgets(this);
			return id;
		}
		return -1;
	}

	private List<TransactionAttribute> getAttributes() {
		List<TransactionAttribute> attributes = categorySelector.getAttributes();
		if (deleteAfterExpired != null) {
			TransactionAttribute ta = deleteAfterExpired.newTransactionAttribute();
			attributes.add(ta);
		}
		return attributes;
	}

	protected void internalOnCreate() {
	}

	@Override
	protected boolean shouldLock() {
		return MyPreferences.isPinProtectedNewTransaction();
	}

	protected void createCommonNodes(LinearLayout layout) {
		int locationOrder = MyPreferences.getLocationOrder();
		int noteOrder = MyPreferences.getNoteOrder();
		int projectOrder = MyPreferences.getProjectOrder();
		for (int i = 0; i < 6; i++) {
			if (i == locationOrder) {
				locationSelector.createNode(layout);
			}
			if (i == noteOrder) {
				if (isShowNote) {
					//note
					noteText = new EditText(this);
					noteText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
					noteText.setTextColor(getResources().getColorStateList(android.R.color.primary_text_dark));
					x.addEditNode(layout, R.string.note, noteText);
				}
			}
			if (i == projectOrder) {
				projectSelector.createNode(layout);
			}
		}
		if (isShowTakePicture && transaction.isNotTemplateLike()) {
			pictureTopView = x.addPictureNodeMinus(this, layout, R.id.attach_picture, R.id.delete_picture, R.string.attach_picture, R.string.new_picture);
			pictureView = pictureTopView.findViewById(R.id.picture);
			pictureDescView = pictureTopView.findViewById(R.id.data);
		}
		if (isShowIsCCardPayment) {
			// checkbox to register if the transaction is a credit card payment.
			// this will be used to exclude from totals in bill preview
			ccardPayment = x.addCheckboxNode(layout, R.id.is_ccard_payment,
					R.string.is_ccard_payment, R.string.is_ccard_payment_summary, false);
		}
	}

	protected abstract void createListNodes(LinearLayout layout);

	protected abstract boolean onOKClicked();

	@Override
	protected void onClick(View v, int id) {
		if (isPreventEditing()) return;
		if (isShowPayee) payeeSelector.onClick(id);
		projectSelector.onClick(id);
		categorySelector.onClick(id);
		locationSelector.onClick(id);
		switch (id) {
			case R.id.account:
				x.select(this, R.id.account, R.string.account, accountCursor, accountAdapter,
						AccountColumns.ID, getSelectedAccountId());
				break;
			case R.id.recurrence_pattern: {
				Intent intent = new Intent(this, RecurrenceActivity.class);
				intent.putExtra(RecurrenceActivity.RECURRENCE_PATTERN, recurrence);
				startActivityForResult(intent, RECURRENCE_REQUEST);
				break;
			}
			case R.id.notification: {
				if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ||
					(!isRequestingPermission(this, POST_NOTIFICATIONS)))
				{
					Intent intent = new Intent(this, NotificationOptionsActivity.class);
					intent.putExtra(NotificationOptionsActivity.NOTIFICATION_OPTIONS, notificationOptions);
					startActivityForResult(intent, NOTIFICATION_REQUEST);
				}
				break;
			}
			case R.id.attach_picture: {
				if (PicturesUtil.getPictureFolderUri(this) != null) {
					transaction.blobKey = null;
					pickMedia.launch(new PickVisualMediaRequest.Builder()
							.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
							.build());
				}
				break;
			}
			case R.id.delete_picture: {
				removePicture();
				break;
			}
			case R.id.is_ccard_payment: {
				ccardPayment.setChecked(!ccardPayment.isChecked());
				transaction.isCCardPayment = ccardPayment.isChecked() ? 1 : 0;
			}
		}
	}

	@Override
	public void onSelectedPos(int id, int selectedPos) {
		if (isShowPayee) payeeSelector.onSelectedPos(id, selectedPos);
		projectSelector.onSelectedPos(id, selectedPos);
		locationSelector.onSelectedPos(id, selectedPos);
		switch (id) {
			case R.id.status:
				selectStatus(statuses[selectedPos]);
				break;
		}
	}

	@Override
	public void onSelectedId(int id, long selectedId) {
		if (isShowPayee) payeeSelector.onSelectedId(id, selectedId);
		categorySelector.onSelectedId(id, selectedId);
		projectSelector.onSelectedId(id, selectedId);
		locationSelector.onSelectedId(id, selectedId);
		switch (id) {
			case R.id.account:
				selectAccount(selectedId);
				break;
		}
	}

	private void selectStatus(TransactionStatus transactionStatus) {
		transaction.status = transactionStatus;
		status.setImageResource(transactionStatus.iconId);
		updateCommonUIforPreventEditing();
	}

	// ---------------- AI 語音補充/修正 ----------------

	/**
	 * 對「已開啟的表單」用一句話補充或修正欄位。
	 * 只套用模型有回值的槽；沒講到的欄位維持原狀（見 BookkeepingParser 的補充模式）。
	 * 按鈕在 save_and_new_buttons.xml，四種交易 layout 共用同一個 include。
	 */
	/**
	 * 交易/轉帳表單上的 AI 補充。原本綁 bAiVoice 按鈕，改由全 App 浮動鈕
	 * （AiFloatingButton）在這類頁面直接呼叫——鈕撤了，行為留著。
	 */
	public void launchAiSupplement() {
		AiPreferences prefs = AiPreferences.load(this);
		if (!prefs.isConfigured()) {
			Toast.makeText(this, R.string.ai_not_configured, Toast.LENGTH_LONG).show();
			return;
		}
		// 雲端語音：跳同款大麥克風錄音頁（進頁即錄、點麥克風完成，無靜音截斷）。
		// 純轉寫＝回文字進原本的補充解析；一次到位＝回 WAV、在本頁直接進音檔補充解析。
		if (prefs.isCloudStt()) {
			if (!prefs.isCloudSttConfigured()) {
				Toast.makeText(this, R.string.ai_stt_not_configured, Toast.LENGTH_LONG).show();
				return;
			}
			if (!VoiceCaptureActivity.hasMicPermission(this)) {
				VoiceCaptureActivity.requestMicPermission(this, AI_MIC_PERMISSION_REQUEST);
				return;
			}
			Intent capture = new Intent(this, VoiceCaptureActivity.class);
			capture.putExtra(VoiceCaptureActivity.EXTRA_MODE, prefs.isDirectVoiceParse()
					? VoiceCaptureActivity.MODE_RECORD : VoiceCaptureActivity.MODE_TRANSCRIBE);
			capture.putExtra(VoiceCaptureActivity.EXTRA_PROMPT,
					getString(R.string.ai_voice_supplement_prompt));
			startActivityForResult(capture, AI_VOICE_CAPTURE_REQUEST);
			return;
		}
		Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
		intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
		intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW");
		intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ai_voice_supplement_prompt));
		intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6000);
		intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000);
		intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000);
		if (intent.resolveActivity(getPackageManager()) == null) {
			Toast.makeText(this, R.string.ai_voice_unavailable, Toast.LENGTH_LONG).show();
			return;
		}
		try {
			startActivityForResult(intent, AI_VOICE_REQUEST);
		} catch (Exception e) {
			Toast.makeText(this, R.string.ai_voice_unavailable, Toast.LENGTH_LONG).show();
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == AI_MIC_PERMISSION_REQUEST) {
			if (grantResults.length > 0
					&& grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
				launchAiSupplement();
			} else {
				Toast.makeText(this, R.string.ai_stt_need_mic, Toast.LENGTH_LONG).show();
			}
		}
	}

	/** 一次到位：錄音頁回來的 WAV 直接進補充解析（含表單狀態），結果就地套用。 */
	private void runAiSupplementAudio(final java.io.File wav) {
		Toast.makeText(this, R.string.ai_parsing, Toast.LENGTH_SHORT).show();
		final AiPreferences prefs = AiPreferences.load(this);
		final String formState = buildAiFormStateContext();
		new Thread(() -> {
			try {
				EntityContextBuilder ctx = EntityContextBuilder.build(db);
				final BookkeepingParser.AudioParseResult r =
						new BookkeepingParser(prefs, ctx, getApplicationContext())
								.parseAudio(wav, true, formState);
				runOnUiThread(() -> applyAiSupplement(r.transaction));
			} catch (final Exception e) {
				runOnUiThread(() -> Toast.makeText(this,
						getString(R.string.ai_failed, e.getMessage()), Toast.LENGTH_LONG).show());
			} finally {
				wav.delete();
			}
		}).start();
	}

	private void runAiSupplement(final String text) {
		Toast.makeText(this, R.string.ai_parsing, Toast.LENGTH_SHORT).show();
		final AiPreferences prefs = AiPreferences.load(this);
		// 目前表單狀態要在 UI 執行緒讀（讀 selector/rateView），先算好再丟進背景解析
		final String formState = buildAiFormStateContext();
		new Thread(() -> {
			try {
				EntityContextBuilder ctx = EntityContextBuilder.build(db);
				final ParsedTransaction t = new BookkeepingParser(prefs, ctx, getApplicationContext())
						.parse(text, true, formState);
				runOnUiThread(() -> applyAiSupplement(t));
			} catch (final Exception e) {
				runOnUiThread(() -> Toast.makeText(this,
						getString(R.string.ai_failed, e.getMessage()), Toast.LENGTH_LONG).show());
			}
		}).start();
	}

	/** 所有型別共用的表單狀態表頭。 */
	protected static final String AI_FORM_STATE_HEADER =
			"【目前表單已填內容】使用者正在編輯這筆交易，「使用者這句話」是要在它之上做補充或修正：\n";

	/**
	 * 補充模式：把目前表單「已填內容」序列化成給模型的 context，讓它知道這筆現在長什麼樣，
	 * 才能分辨這句話是「再補一份分割」還是「改某個欄位」（見 BookkeepingParser 的補充模式規則）。
	 * 子類覆寫以補自己特有的內容：TransactionActivity 的分割/調整餘額、TransferActivity 的轉入帳戶。
	 * 這裡處理「單一分類」的一般交易；回傳的字串直接接在補充 prompt 後面。
	 */
	protected String buildAiFormStateContext() {
		StringBuilder sb = new StringBuilder(AI_FORM_STATE_HEADER);
		sb.append("型別：").append(aiFormStateTypeLabel()).append('\n');
		appendAiAccountLine(sb);
		long minor = rateView.getFromAmount();
		if (minor != 0) {
			sb.append("金額：").append(aiFormatMajor(Math.abs(minor), getSelectedAccountId())).append('\n');
		}
		String cat = aiCategoryName(categorySelector.getSelectedCategoryId());
		if (cat != null) sb.append("分類：").append(cat).append('\n');
		appendAiNoteProjectLines(sb);
		return sb.toString();
	}

	/** 型別標籤：依畫面金額正負判斷收入/支出（轉帳/餘額由子類覆寫）。 */
	protected String aiFormStateTypeLabel() {
		return rateView.getFromAmount() > 0 ? "收入" : "支出";
	}

	protected void appendAiAccountLine(StringBuilder sb) {
		if (selectedAccount != null) {
			sb.append("帳戶：").append(selectedAccount.title).append('\n');
		}
	}

	protected void appendAiNoteProjectLines(StringBuilder sb) {
		if (noteText != null) {
			String note = noteText.getText().toString().trim();
			if (!TextUtils.isEmpty(note)) sb.append("備註：").append(note).append('\n');
		}
		long pid = projectSelector.getSelectedEntityId();
		if (pid > 0) {
			Project p = db.getProject(pid);
			if (p != null && !TextUtils.isEmpty(p.title)) sb.append("專案：").append(p.title).append('\n');
		}
	}

	/** 分類 id → 名稱；0(無分類)/-1(分割父) 回 null（不輸出分類行）。 */
	protected String aiCategoryName(long categoryId) {
		if (categoryId <= 0) return null;
		Category c = db.getCategory(categoryId);
		return c != null ? c.title : null;
	}

	/** minor units → 主單位口語數字字串（依帳戶幣別 scale，去掉多餘小數）。 */
	protected String aiFormatMajor(long minorAbs, long accountId) {
		int scale = accountScale(accountId);
		double major = minorAbs / Math.pow(10, scale);
		if (major == Math.floor(major)) return String.valueOf((long) major);
		return new java.math.BigDecimal(major)
				.setScale(scale, java.math.RoundingMode.HALF_UP)
				.stripTrailingZeros().toPlainString();
	}

	/**
	 * 補充模式進入點。先看這句話有沒有要求切換交易型別（交易↔轉帳）：要切就交給
	 * {@link #switchTransactionType}（拉起對方表單、共用欄位帶過去），本頁不再就地套；
	 * 不切才走 {@link #applyAiSupplementFields} 就地套用欄位。
	 */
	protected final void applyAiSupplement(ParsedTransaction t) {
		if (maybeSwitchType(t)) return;
		applyAiSupplementFields(t);
		Toast.makeText(this, R.string.ai_recognized, Toast.LENGTH_SHORT).show();
	}

	/**
	 * 這句話要不要把當前表單切換成另一種型別（交易↔轉帳↔調整餘額）。回 true＝已接手（切走或已跳錯誤 toast）。
	 * 只在使用者明確要改型別（模型回了與當前頁不符的 transaction_type）時才切。
	 */
	private boolean maybeSwitchType(ParsedTransaction t) {
		String type = t.transactionType;
		if (type == null) return false;                    // 沒指定型別 → 不切

		// 餘額：補充模式講「剩下X／餘額X」→ 切到調整餘額模式。不能就地套——amount 是「新餘額」不是變動金額，
		// 直接當交易金額灌進去會錯亂（使用者回報的舊問題）。
		if (t.isBalance()) {
			return switchToBalanceAdjust(t);
		}

		boolean wantTransfer = t.isTransfer();
		boolean isTransfer = (this instanceof TransferActivity);
		// 需要切的情況：(a) 目前在調整餘額模式、卻改口要一般型別（就地套會因 isUpdateBalanceMode 拿新餘額當
		// 金額去減 currentBalance → 算錯差額，必須切出餘額模式）；(b) 一般↔轉帳型別不符。
		boolean needSwitch = isBalanceAdjustMode() || (wantTransfer != isTransfer);
		if (!needSwitch) return false;                     // 型別一致且非餘額模式 → 就地套
		// 已存在的交易改型別＝會另開一筆新的、原筆不動，語義混亂，擋掉請使用者新增
		if (transaction != null && transaction.id > 0) {
			Toast.makeText(this, R.string.ai_type_switch_cancelled, Toast.LENGTH_SHORT).show();
			return true;
		}
		switchTransactionType(t, wantTransfer);
		return true;
	}

	/** 目前是否在「調整餘額」模式（只有 TransactionActivity 帶 CURRENT_BALANCE 進來時為 true）。 */
	protected boolean isBalanceAdjustMode() {
		return false;
	}

	/**
	 * 補充模式講到餘額（「剩下X」）→ 切到「調整餘額」模式：帶入帳戶目前餘額 + 講出來的新餘額，
	 * 差額由 TransactionActivity 自己算。走 CURRENT_BALANCE / NEW_BALANCE extra（同 AiInputActivity
	 * .launchBalanceAdjust，不需 draft）；回來的結果轉發沿用型別切換那條 onActivityResult 路徑。
	 * 回 true＝已接手（切走或跳錯誤）。
	 */
	private boolean switchToBalanceAdjust(ParsedTransaction t) {
		// 已存在的交易不切（比照型別切換：改型別會另開新筆、原筆不動，語義混亂）
		if (transaction != null && transaction.id > 0) {
			Toast.makeText(this, R.string.ai_type_switch_cancelled, Toast.LENGTH_SHORT).show();
			return true;
		}
		long accountId = t.account.resolved() ? t.account.id : getSelectedAccountId();
		Account account = accountId > 0 ? db.getAccount(accountId) : null;
		if (account == null) {
			Toast.makeText(this, R.string.ai_balance_need_account, Toast.LENGTH_LONG).show();
			return true;
		}
		Intent intent = new Intent(this, TransactionActivity.class);
		intent.putExtra(ACCOUNT_ID_EXTRA, accountId);
		intent.putExtra(TransactionActivity.CURRENT_BALANCE_EXTRA, account.totalAmount);
		if (t.amount != null) {
			// 餘額可能為負（如信用卡）：依新餘額定號，不硬給正號
			long newBalance = toMinor(t.amount, accountId);
			intent.putExtra(TransactionActivity.NEW_BALANCE_EXTRA, t.amount < 0 ? -newBalance : newBalance);
		}
		Long spoken = t.resolveDateTimeMillis();
		if (spoken != null) intent.putExtra(DATETIME_EXTRA, (long) spoken);
		startActivityForResult(intent, AI_TYPE_SWITCH_REQUEST);
		return true;
	}

	/**
	 * 把當前表單切成另一種型別：收集當前 UI 的共用欄位（帳戶/金額/日期/備註/分類/專案）＋併入
	 * 這句話的 delta，寫一筆 draft template，用 duplicate prefill 拉起對方 Activity，回來刪 draft。
	 * 共用欄位這樣才帶得過去（兩種型別是不同 Activity/layout，沒法就地變）。
	 */
	private void switchTransactionType(ParsedTransaction t, boolean toTransfer) {
		long originalFromId = getSelectedAccountId();
		long fromAccountId = t.account.resolved() ? t.account.id : originalFromId;

		// 目標若是轉帳：一定要有轉入帳戶（這句話講出來的），湊不齊就跳錯誤、不切
		long toAccountId = t.toAccount.resolved() ? t.toAccount.id : -1;
		if (toTransfer) {
			if (toAccountId <= 0) {
				Toast.makeText(this, R.string.ai_transfer_need_to_account, Toast.LENGTH_LONG).show();
				return;
			}
			if (toAccountId == fromAccountId) {
				Toast.makeText(this, R.string.select_to_account_differ_from_to_account, Toast.LENGTH_LONG).show();
				return;
			}
		}

		// 主單位金額：這句話有講就用講的；否則用畫面現值——但若是從餘額模式切出，畫面金額其實是「新餘額」
		// 不是交易金額，不能沿用，留 0 讓使用者填（有講金額才會有值）。
		double curMajor;
		if (t.amount != null) {
			curMajor = t.amount;
		} else if (isBalanceAdjustMode()) {
			curMajor = 0;
		} else {
			long uiMinor = Math.abs(rateView.getFromAmount());
			curMajor = uiMinor / Math.pow(10, accountScale(originalFromId));
		}

		Transaction draft = new Transaction();
		updateTransactionFromUI(draft);        // dateTime / note / category / project / location（當前 UI）
		draft.isTemplate = 1;
		draft.fromAccountId = fromAccountId;
		if (toTransfer) {
			draft.toAccountId = toAccountId;
			draft.categoryId = NO_CATEGORY_ID; // 轉帳無分類
			// 慣例：轉出為負、轉入為正（各依自己帳戶 scale 換算）
			draft.fromAmount = -toMinor(curMajor, fromAccountId);
			draft.toAmount = toMinor(curMajor, toAccountId);
		} else {
			draft.toAccountId = 0;
			if (t.category.resolved()) draft.categoryId = t.category.id;
			long minor = toMinor(curMajor, fromAccountId);
			draft.fromAmount = t.isIncome() ? minor : -minor;
		}
		// 這句話有講的話覆蓋 UI 收集值
		if (!TextUtils.isEmpty(t.note)) draft.note = t.note;
		if (t.project.resolved()) draft.projectId = t.project.id;
		Long spoken = t.resolveDateTimeMillis();
		if (spoken != null) draft.dateTime = spoken;

		try {
			pendingTypeSwitchDraftId = db.insertOrUpdate(draft);
		} catch (Exception e) {
			Toast.makeText(this, getString(R.string.ai_failed, e.getMessage()), Toast.LENGTH_LONG).show();
			return;
		}

		Intent intent = new Intent(this, toTransfer ? TransferActivity.class : TransactionActivity.class);
		intent.putExtra(TRAN_ID_EXTRA, pendingTypeSwitchDraftId);
		intent.putExtra(DUPLICATE_EXTRA, true);
		intent.putExtra(TEMPLATE_EXTRA, 0);              // draft 本身是 template，強制存成正式交易
		intent.putExtra(DATETIME_EXTRA, draft.dateTime); // 帶著收集到的日期時間，別被重設成現在
		startActivityForResult(intent, AI_TYPE_SWITCH_REQUEST);
	}

	/** 主單位金額 → 該帳戶幣別的 minor units（取絕對值，正負由呼叫端定）。 */
	private long toMinor(double major, long accountId) {
		return Math.round(Math.abs(major) * Math.pow(10, accountScale(accountId)));
	}

	private int accountScale(long accountId) {
		if (accountId > 0) {
			Account a = db.getAccount(accountId);
			if (a != null && a.currency != null) return a.currency.getScale();
		}
		return 2;
	}

	private void cleanupTypeSwitchDraft() {
		if (pendingTypeSwitchDraftId > 0) {
			try {
				db.deleteTransaction(pendingTypeSwitchDraftId);
			} catch (Exception ignored) {}
			pendingTypeSwitchDraftId = -1;
		}
	}

	/** 只套用非 null 的槽。子類別可 override 以處理自己特有的欄位（如轉帳的轉入帳戶）。 */
	protected void applyAiSupplementFields(ParsedTransaction t) {
		if (t.account.resolved()) {
			selectAccount(t.account.id, false);
		}
		if (t.category.resolved()) {
			categorySelector.selectCategory(t.category.id, false);
		}
		if (t.project.resolved()) {
			projectSelector.selectEntity(t.project.id);
		}
		if (!TextUtils.isEmpty(t.note)) {
			noteText.setText(t.note);
		}
		if (t.amount != null) {
			// 正負號沿用畫面上現有的收入/支出狀態（AmountInput.setAmount 內部取絕對值）
			Currency c = rateView.getCurrencyFrom();
			int scale = c != null ? c.getScale() : 2;
			rateView.setFromAmount(Math.round(Math.abs(t.amount) * Math.pow(10, scale)));
		}
		Long spoken = t.resolveDateTimeMillis();
		if (spoken != null) {
			setDateTime(spoken);
		}
		// 「已辨識」toast 由 applyAiSupplement orchestrator 統一跳，這裡不重複
	}

	protected Account selectAccount(long accountId) {
		return selectAccount(accountId, true);
	}

	abstract protected Account selectAccount(long accountId, boolean selectLast); /* {
		Account a = db.getAccount(accountId);
		if (a != null) {
			accountText.setText(a.title);
			rateView.selectCurrencyFrom(a.currency);
			selectedAccount = a;
		}
		categorySelector.setSelectedAccount(a);
		return a;
	} */

	protected long getSelectedAccountId() {
		return selectedAccount != null ? selectedAccount.id : -1;
	}

	@Override
	public void onCategorySelected(Category category, boolean selectLast) {
		addOrRemoveSplits();
		categorySelector.addAttributes(transaction);
		switchIncomeExpenseButton(category);
		if (selectLast && isRememberLastLocation) {
			locationSelector.selectEntity(category.lastLocationId);
		}
		if (selectLast && isRememberLastProject) {
			projectSelector.selectEntity(category.lastProjectId);
		}
		if (isTrackSplitEntityInChild) {
			if (transaction.payeeId == Payee.EMPTY.id && payeeSelector != null) {
				payeeSelector.setNodeVisible(!category.isSplit());
			}
			if (transaction.locationId == CURRENT_LOCATION_ID) {
				locationSelector.setNodeVisible(!category.isSplit());
			}
		}
		projectSelector.setNodeVisible(!category.isSplit());
	}

	protected void addOrRemoveSplits() {
	}

	protected void switchIncomeExpenseButton(Category category) {

	}

	private void setRecurrence(String recurrence) {
		this.recurrence = recurrence;
		if (recurrence == null) {
			recurText.setText(R.string.recur_interval_no_recur);
			dateText.setEnabled(true);
			timeText.setEnabled(true);
		} else {
			dateText.setEnabled(false);
			timeText.setEnabled(false);
			Recurrence r = Recurrence.parse(recurrence);
			recurText.setText(r.toInfoString(this));
		}
	}

	private void setNotification(String notificationOptions) {
		this.notificationOptions = notificationOptions;
		if (notificationOptions == null) {
			notificationText.setText(R.string.notification_options_default);
		} else {
			NotificationOptions o = NotificationOptions.parse(notificationOptions);
			notificationText.setText(o.toInfoString(this));
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		projectSelector.onActivityResult(requestCode, resultCode, data);
		categorySelector.onActivityResult(requestCode, resultCode, data);
		locationSelector.onActivityResult(requestCode, resultCode, data);
		if (isShowPayee) {
			payeeSelector.onActivityResult(requestCode, resultCode, data);
		}
		// 補充模式切換型別回來：不論成敗都要刪掉帶過去的 draft template。
		// 成功＝對方表單已存成正式交易 → 把結果原封轉發給本頁的呼叫端並收掉本頁；
		// 取消＝留在原頁（原本輸入的內容還在），只提示一下。
		if (requestCode == AI_TYPE_SWITCH_REQUEST) {
			cleanupTypeSwitchDraft();
			if (resultCode == RESULT_OK) {
				setResult(RESULT_OK, data);
				finish();
			} else {
				Toast.makeText(this, R.string.ai_type_switch_cancelled, Toast.LENGTH_SHORT).show();
			}
			return;
		}
		if (resultCode == RESULT_OK) {
			switch (requestCode) {
				case RECURRENCE_REQUEST:
					String recurrence = data.getStringExtra(RecurrenceActivity.RECURRENCE_PATTERN);
					setRecurrence(recurrence);
					break;
				case NOTIFICATION_REQUEST:
					String notificationOptions = data.getStringExtra(NotificationOptionsActivity.NOTIFICATION_OPTIONS);
					setNotification(notificationOptions);
					break;
				case AI_VOICE_REQUEST:
					ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
					if (heard != null && !heard.isEmpty()) {
						runAiSupplement(heard.get(0));
					}
					break;
				case AI_VOICE_CAPTURE_REQUEST: {
					// 錄音頁回來：轉寫文字（純轉寫）或 WAV 路徑（一次到位）
					String text = data.getStringExtra(VoiceCaptureActivity.EXTRA_TEXT);
					String wavPath = data.getStringExtra(VoiceCaptureActivity.EXTRA_WAV_PATH);
					if (text != null) {
						runAiSupplement(text);
					} else if (wavPath != null) {
						runAiSupplementAudio(new java.io.File(wavPath));
					}
					break;
				}
				default:
					break;
			}
		} else {
			if (requestCode == PICTURE_REQUEST) {
				removePicture();
			}
		}
	}

	private void selectPicture(String pictureFileName) {
		if (pictureView == null) {
			return;
		}
		if (pictureFileName == null) {
			return;
		}
		PicturesUtil.showImage(this, pictureView, pictureDescView, pictureFileName);
		pictureView.setTag(R.id.attached_picture, pictureFileName);
		transaction.attachedPicture = pictureFileName;
	}

	private void removePicture() {
		if (pictureView == null) {
			return;
		}
		transaction.attachedPicture = null;
		transaction.blobKey = null;
		pictureView.setImageBitmap(null);
		pictureView.setTag(R.id.attached_picture, null);
		pictureDescView.setText(R.string.new_picture);
	}

	protected void setDateTime(long date) {
		Date d = new Date(date);
		dateTime.setTime(d);
		dateText.setText(df.format(d));
		timeText.setText(tf.format(d));
	}

	protected abstract void editTransaction(Transaction transaction);
	protected void updateUIforPreventEditing() {}

	protected boolean isPreventEditing() {
		return MyPreferences.isPreventEditClearedReconciledTransactions() &&
				(transaction.status == TransactionStatus.CL ||
				 transaction.status == TransactionStatus.RC);
	}

	protected void updateCommonUIforPreventEditing() {
		boolean enabled = !isPreventEditing();

		dateText.setEnabled(enabled);
		timeText.setEnabled(enabled);
		categorySelector.setEnabled(enabled);
		if (projectSelector != null) projectSelector.setEnabled(enabled);
		if (locationSelector != null) locationSelector.setEnabled(enabled);
		if (payeeSelector != null) payeeSelector.setEnabled(enabled);
		if (noteText != null) noteText.setEnabled(enabled);
		if (pictureTopView != null) pictureTopView.setEnabled(enabled);
		if (ccardPayment != null) ((View) ccardPayment.getTag()).setEnabled(enabled);
		rateView.setEnabled(enabled);

		if (enabled) {
			editDisabled.setVisibility(View.GONE);
		}
		else {
			editDisabled.setVisibility(View.VISIBLE);
		}

		updateUIforPreventEditing();
	}

	protected void commonEditTransaction(Transaction transaction) {
		selectStatus(transaction.status);
		categorySelector.selectCategory(transaction.categoryId, false);
		projectSelector.selectEntity(transaction.projectId);
		locationSelector.selectEntity(transaction.locationId);
		setDateTime(transaction.dateTime);
		if (isShowNote) {
			noteText.setText(transaction.note);
		}
		if (transaction.isTemplate()) {
			templateName.setText(transaction.templateName);
		}
		if (transaction.isScheduled()) {
			setRecurrence(transaction.recurrence);
			setNotification(transaction.notificationOptions);
		}
		if (isShowTakePicture) {
			selectPicture(transaction.attachedPicture);
		}
		if (isShowIsCCardPayment) {
			setIsCCardPayment(transaction.isCCardPayment);
		}

		if (transaction.isCreatedFromTemlate() && isOpenCalculatorForTemplates && !isPreventEditing()) {
			rateView.openFromAmountCalculator();
		}

		updateCommonUIforPreventEditing();
	}

	private void setIsCCardPayment(int isCCardPaymentValue) {
		transaction.isCCardPayment = isCCardPaymentValue;
		ccardPayment.setChecked(isCCardPaymentValue == 1);
	}

	protected boolean checkSelectedEntities() {
		if (isShowPayee) {
			payeeSelector.autoCreateNewEntityFromSearch();
		}
		if (isShowLocation) {
			locationSelector.autoCreateNewEntityFromSearch();
		}
		if (isShowProject) {
			projectSelector.autoCreateNewEntityFromSearch();
		}
		return true;
	}

	protected void updateTransactionFromUI(Transaction transaction) {
		transaction.categoryId = categorySelector.getSelectedCategoryId();
		transaction.projectId = projectSelector.getSelectedEntityId();
		transaction.locationId = locationSelector.getSelectedEntityId();
		if (transaction.isScheduled()) {
			DateUtils.zeroSeconds(dateTime);
		}
		transaction.dateTime = dateTime.getTime().getTime();
		if (isShowPayee) {
			transaction.payeeId = payeeSelector.getSelectedEntityId();
		}
		if (isShowNote) {
			transaction.note = text(noteText);
		}
		if (transaction.isTemplate()) {
			transaction.templateName = text(templateName);
		}
		if (transaction.isScheduled()) {
			transaction.recurrence = recurrence;
			transaction.notificationOptions = notificationOptions;
		}
	}

	protected void selectPayee(long payeeId) {
		if (isShowPayee) {
			payeeSelector.selectEntity(payeeId);
		}
	}

	protected void selectLastCategoryForPayee(long id) {
		Payee p = db.get(Payee.class, id);
		if (p != null) {
			if (categorySelector.getSelectedCategoryId() != SPLIT_CATEGORY_ID) {
				categorySelector.selectCategory(p.lastCategoryId);
			}
		}
	}

	@Override
	protected void onDestroy() {
		cleanupTypeSwitchDraft();   // 被系統回收而沒走 onActivityResult 時的保險，避免 draft template 殘留
		if (payeeSelector != null) payeeSelector.onDestroy();
		if (projectSelector != null) projectSelector.onDestroy();
		if (locationSelector != null) locationSelector.onDestroy();
		if (categorySelector != null) categorySelector.onDestroy();
		super.onDestroy();
	}
}
