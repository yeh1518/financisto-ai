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
import android.net.Uri;
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
import tw.tib.financisto.ai.BalanceSplitPlanner;
import tw.tib.financisto.ai.BookkeepingParser;
import tw.tib.financisto.ai.VoiceCaptureActivity;
import tw.tib.financisto.ai.EntityContextBuilder;
import tw.tib.financisto.ai.ParsedTransaction;
import tw.tib.financisto.datetime.DateUtils;
import tw.tib.financisto.db.DatabaseHelper.AccountColumns;
import tw.tib.financisto.db.DatabaseHelper.TransactionColumns;
import tw.tib.financisto.dialog.DatePickerTwinDialog;
import tw.tib.financisto.model.Account;
import tw.tib.financisto.model.Attribute;
import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.Currency;
import tw.tib.financisto.model.Payee;
import tw.tib.financisto.model.Project;
import tw.tib.financisto.model.SystemAttribute;
import tw.tib.financisto.model.Tag;
import tw.tib.financisto.model.Transaction;
import tw.tib.financisto.model.TransactionAttribute;
import tw.tib.financisto.model.TransactionStatus;
import tw.tib.financisto.recur.NotificationOptions;
import tw.tib.financisto.recur.Recurrence;
import tw.tib.financisto.service.IntentTransactionProcessor;
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
import java.util.HashMap;
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
import androidx.documentfile.provider.DocumentFile;

public abstract class AbstractTransactionActivity extends AbstractActivity implements CategorySelector.CategorySelectorListener {
	public static final String TAG = "AbstractTxActivity";

	public static final String TRAN_ID_EXTRA = "tranId";
	public static final String ACCOUNT_ID_EXTRA = "accountId";
	public static final String DUPLICATE_EXTRA = "isDuplicate";
	public static final String TEMPLATE_EXTRA = "isTemplate";
	public static final String DATETIME_EXTRA = "dateTimeExtra";
	public static final String NEW_FROM_TEMPLATE_EXTRA = "newFromTemplateExtra";
	/** AI 調整餘額 prefill：balance 型也可能講出用途（「電子錢包剩下895，飲食買炸春捲」）。
	 *  調整餘額不走 draft-shuttle（沒有 template 可帶欄位），分類/備註/專案改用這組 extra 帶進表單。 */
	public static final String AI_PREFILL_CATEGORY_ID_EXTRA = "aiPrefillCategoryId";
	public static final String AI_PREFILL_NOTE_EXTRA = "aiPrefillNote";
	public static final String AI_PREFILL_PROJECT_ID_EXTRA = "aiPrefillProjectId";
	/** 調整餘額 + 分割：三個等長陣列，一份一格（分類 id / 帶號 minor 金額 / 備註可 null）。
	 *  由 {@link #putAiPrefillSplits} 寫、{@link #applyAiPrefillSplits} 讀；有這組時分類固定是分割，
	 *  AI_PREFILL_CATEGORY_ID_EXTRA 不看。 */
	public static final String AI_PREFILL_SPLIT_CATEGORY_IDS_EXTRA = "aiPrefillSplitCategoryIds";
	public static final String AI_PREFILL_SPLIT_AMOUNTS_EXTRA = "aiPrefillSplitAmounts";
	public static final String AI_PREFILL_SPLIT_NOTES_EXTRA = "aiPrefillSplitNotes";
	public static final String TAGS_EXTRA = "tags";
	/** 表單切換（交易／轉帳／調整餘額）帶過來的原件，見 {@link FormSwitch}。 */
	public static final String FORM_SWITCH_ORIGIN_EXTRA = "formSwitchOrigin";
	/** 表單切換時正在改的既有交易 id：存檔時直接更新這筆（id 不變），不另開新筆。 */
	public static final String FORM_SWITCH_REPLACES_EXTRA = "formSwitchReplaces";

	private static final int RECURRENCE_REQUEST = 4003;
	private static final int NOTIFICATION_REQUEST = 4004;
	private static final int PICTURE_REQUEST = 4005;
	private static final int AI_VOICE_REQUEST = 4006;
	private static final int AI_MIC_PERMISSION_REQUEST = 4008;
	private static final int AI_VOICE_CAPTURE_REQUEST = 4009;

	/** 切換過來的表單帶著的原件；{@code null}＝不是切換來的。 */
	private FormSwitch.Origin switchOrigin;
	/** 表單載入完、分類套上後讀到的值（正規化過）：判斷哪些欄位被使用者改過的基準。 */
	private FormSwitch.Origin switchBaseline;
	/** 切換中的既有交易 id（{@link #FORM_SWITCH_REPLACES_EXTRA}）。 */
	private long switchReplacesId = -1;
	private FormKindBar kindBar;

	private static final TransactionStatus[] statuses = TransactionStatus.values();

	protected RateLayoutView rateView;

	protected EditText templateName;
	protected TextView accountText;
	protected Cursor accountCursor;
	protected ListAdapter accountAdapter;

	protected Calendar dateTime;
	protected ImageButton status;
	/** 這次編輯中使用者有沒有自己挑過狀態；有的話 save() 不做擱置→未對帳的自動升級。 */
	private boolean statusPickedByUser;
	protected Button dateText;
	protected Button timeText;

	protected EditText noteText;
	protected TagSelector<AbstractTransactionActivity> tagSelector;
	protected TextView recurText;
	protected TextView notificationText;

	private View pictureTopView;
	private View takePhotoButton;
	private View selectFromAlbumButton;
	private View removePictureButton;
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
	protected boolean isShowTags;
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

	protected ActivityResultLauncher<PickVisualMediaRequest> pickMedia;
	protected ActivityResultLauncher<Uri> takePicture;

	protected static final String NEW_PICTURE_URI = "newPictureUri";
	protected Uri newPictureUri;

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
		isShowTags = MyPreferences.isShowTags();
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
			if (intent.hasExtra(FORM_SWITCH_ORIGIN_EXTRA)) {
				// 從另一種表單切過來：這張表單要顯示的就是原件在這種表單上的樣子
				switchOrigin = (FormSwitch.Origin) intent.getSerializableExtra(FORM_SWITCH_ORIGIN_EXTRA);
				switchReplacesId = intent.getLongExtra(FORM_SWITCH_REPLACES_EXTRA, -1);
				transaction = FormSwitch.project(switchOrigin, kindForIntent(intent),
						intent.getLongExtra(TransactionActivity.CURRENT_BALANCE_EXTRA, 0));
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

		// 既有交易切換型別過來的表單也算編輯：這顆是「取消」，按了原交易不動
		final boolean isEdit = transaction.id > 0 || switchReplacesId > 0;
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
					prepareIntentForNext(intent);
					startActivityForResult(intent, -1);
				}
			}
		});

		if (transactionId != -1 || switchOrigin != null) {
			isOpenCalculatorForTemplates &= isNewFromTemplate;
			editTransaction(transaction);
			if (switchOrigin != null) onFormSwitchLoaded(switchOrigin);
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
			// AI 調整餘額 prefill：放在 selectAccount 之後才不會被「帳戶的上次分類」蓋掉
			applyAiPrefillExtras(intent);
		}

		if (intent != null) {
			List<String> intentTags = IntentTransactionProcessor.extractTagsFromIntent(intent);
			if (!intentTags.isEmpty()) {
				for (String tag : intentTags) {
					db.findOrInsertEntityByTitle(Tag.class, tag);
				}
				String intentTagsStr = "\n" + String.join("\n", intentTags) + "\n";
				transaction.tags = intentTagsStr;
				if (isShowTags && tagSelector != null) {
					tagSelector.setSelectedTags(intentTagsStr);
					tagSelector.fetchEntities();
				}
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

			takePicture = registerForActivityResult(new ActivityResultContracts.TakePicture(), ret -> {
				if (ret == false) return;
				PicturesUtil.backupPictureFile(this, newPictureUri);
				selectPicture(DocumentFile.fromSingleUri(this, newPictureUri).getName());
			});
		}

		installFormKindBar();
		if (switchOrigin != null) captureSwitchBaselineWhenSettled(0);

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

	protected void createTagsNode(LinearLayout layout) {
		tagSelector = new TagSelector<>(this, db, x);
		tagSelector.createNode(layout);
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
			// 既有交易換了型別：同一張表裡交易與轉帳只差 to_account_id，原地更新那一筆即可，
			// id 不變（附件、關聯都還在）；帳戶餘額與逐筆餘額由 updateTransaction 依新舊兩版重算
			if (switchReplacesId > 0) {
				transaction.id = switchReplacesId;
			}
			boolean isNew = transaction.id == -1;
			// 擱置＝「還沒過目」（自動記帳寫進來的預設狀態）。人打開它按了儲存，
			// 就是過目了——不管內容有沒有改——所以升成未對帳，不必再手動改狀態。
			// 但使用者自己在狀態選單挑過（含刻意挑「擱置」）就尊重他，不覆蓋。
			if (!isNew && !statusPickedByUser && transaction.status == TransactionStatus.PN) {
				transaction.status = TransactionStatus.UR;
				// 子項有自己的狀態、存檔不會沿用母筆：擱置的子項跟著母筆一起過目，一起升
				// （同列表上改母筆狀態會連帶改子項）。不升的話會一直卡在「只看擱置」——
				// 明細總表套了篩選時顯示的是子項
				if (transaction.splits != null) {
					for (Transaction split : transaction.splits) {
						if (split.status == TransactionStatus.PN) split.status = TransactionStatus.UR;
					}
				}
			}
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
		int tagsOrder = MyPreferences.getTagsOrder();
		for (int i = 0; i < 7; i++) {
			if (i == locationOrder) {
				locationSelector.createNode(layout);
			}
			if (i == noteOrder) {
				if (isShowNote) {
					//note
					noteText = new EditText(this);
					noteText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
					noteText.setTextColor(getResources().getColorStateList(android.R.color.primary_text_dark));
					noteText.setId(R.id.note);
					x.addEditNode(layout, R.string.note, noteText);
				}
			}
			if (i == projectOrder) {
				projectSelector.createNode(layout);
			}
			if (i == tagsOrder) {
				if (isShowTags) {
					createTagsNode(layout);
				}
			}
		}
		if (isShowTakePicture && transaction.isNotTemplateLike()) {
			pictureTopView = x.addPictureNodeMinus(this, layout, R.id.attach_picture, R.id.take_photo, R.id.select_from_album, R.id.delete_picture, R.string.attach_picture, R.string.new_picture);
			takePhotoButton = pictureTopView.findViewById(R.id.take_photo);
			selectFromAlbumButton = pictureTopView.findViewById(R.id.select_from_album);
			removePictureButton = pictureTopView.findViewById(R.id.delete_picture);
			pictureView = pictureTopView.findViewById(R.id.picture);
			pictureDescView = pictureTopView.findViewById(R.id.data);
			takePhotoButton.setVisibility(View.VISIBLE);
			selectFromAlbumButton.setVisibility(View.VISIBLE);
			removePictureButton.setVisibility(View.GONE);
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
		if (tagSelector != null) tagSelector.onClick(id);

		if (id == R.id.account) {
			x.select(this, R.id.account, R.string.account, accountCursor, accountAdapter,
					AccountColumns.ID, getSelectedAccountId());
		}
		else if (id == R.id.recurrence_pattern) {
			Intent intent = new Intent(this, RecurrenceActivity.class);
			intent.putExtra(RecurrenceActivity.RECURRENCE_PATTERN, recurrence);
			startActivityForResult(intent, RECURRENCE_REQUEST);
		}
		else if (id == R.id.notification) {
			if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ||
				(!isRequestingPermission(this, POST_NOTIFICATIONS)))
			{
				Intent intent = new Intent(this, NotificationOptionsActivity.class);
				intent.putExtra(NotificationOptionsActivity.NOTIFICATION_OPTIONS, notificationOptions);
				startActivityForResult(intent, NOTIFICATION_REQUEST);
			}
		}
		else if (id == R.id.select_from_album) {
			if (PicturesUtil.getPictureFolderUri(this) != null) {
				transaction.blobKey = null;
				pickMedia.launch(new PickVisualMediaRequest.Builder()
						.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
						.build());
			}
		}
		else if (id ==R.id.take_photo) {
			if (PicturesUtil.getPictureFolderUri(this) != null) {
				transaction.blobKey = null;
				newPictureUri = PicturesUtil.createPictureFile(this);
				takePicture.launch(newPictureUri);
			}
		}
		else if (id == R.id.delete_picture) {
			removePicture();
		}
		else if (id == R.id.is_ccard_payment) {
			ccardPayment.setChecked(!ccardPayment.isChecked());
			transaction.isCCardPayment = ccardPayment.isChecked() ? 1 : 0;
		}
	}

	@Override
	public void onSelectedPos(int id, int selectedPos) {
		if (isShowPayee) payeeSelector.onSelectedPos(id, selectedPos);
		projectSelector.onSelectedPos(id, selectedPos);
		locationSelector.onSelectedPos(id, selectedPos);
		switch (id) {
			case R.id.status:
				// 記下「狀態是人挑的」——save() 的擱置→未對帳自動升級要避開這種情況
				statusPickedByUser = true;
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

	private void runAiSupplement(final String text, final String source) {
		Toast.makeText(this, R.string.ai_parsing, Toast.LENGTH_SHORT).show();
		final AiPreferences prefs = AiPreferences.load(this);
		// 目前表單狀態要在 UI 執行緒讀（讀 selector/rateView），先算好再丟進背景解析
		final String formState = buildAiFormStateContext();
		new Thread(() -> {
			try {
				EntityContextBuilder ctx = EntityContextBuilder.build(db);
				final ParsedTransaction t = new BookkeepingParser(prefs, ctx, getApplicationContext())
						.inputSource(source)
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

		// 換整張表單是破壞性操作（拉起另一個 Activity／進出餘額模式），要求模型明確表態
		// 「使用者這句話是在要求改型別」才動（見 ParsedTransaction.typeChange）。
		// 擋的是「只補講一個帳戶名，模型順手回 expense 把轉帳表單切掉」那種自作主張；
		// 純粹的收入↔支出方向不走這裡（同一張表單、就地翻符號，見 applyAiSupplementFields）。
		if (!t.typeChange) return false;

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
		// 已存在的交易也能改型別：與介面上的切換鈕走同一條路（FormSwitch），存檔時原地更新那一筆
		switchTransactionType(t, wantTransfer);
		return true;
	}

	/** 目前是否在「調整餘額」模式（只有 TransactionActivity 帶 CURRENT_BALANCE 進來時為 true）。 */
	protected boolean isBalanceAdjustMode() {
		return false;
	}

	/** 套 AI_PREFILL_* extras（目前只有調整餘額的兩個啟動點會帶）。 */
	private void applyAiPrefillExtras(Intent intent) {
		if (intent == null) return;
		long[] splitAmounts = intent.getLongArrayExtra(AI_PREFILL_SPLIT_AMOUNTS_EXTRA);
		if (splitAmounts != null && splitAmounts.length > 0) {
			applyAiPrefillSplits(intent.getLongArrayExtra(AI_PREFILL_SPLIT_CATEGORY_IDS_EXTRA), splitAmounts,
					intent.getStringArrayExtra(AI_PREFILL_SPLIT_NOTES_EXTRA));
		} else {
			long catId = intent.getLongExtra(AI_PREFILL_CATEGORY_ID_EXTRA, -1);
			if (catId >= 0) categorySelector.selectCategory(catId, false);
		}
		String note = intent.getStringExtra(AI_PREFILL_NOTE_EXTRA);
		if (!TextUtils.isEmpty(note)) noteText.setText(note);
		long projId = intent.getLongExtra(AI_PREFILL_PROJECT_ID_EXTRA, -1);
		if (projId > 0) projectSelector.selectEntity(projId);
	}

	/** 把 planner 算好的各份寫成 AI_PREFILL_SPLIT_* extras；空清單什麼都不寫、回 false（呼叫端改帶單一分類）。 */
	public static boolean putAiPrefillSplits(Intent intent, List<BalanceSplitPlanner.Share> shares) {
		if (shares == null || shares.isEmpty()) return false;
		long[] cats = new long[shares.size()];
		long[] amounts = new long[shares.size()];
		String[] notes = new String[shares.size()];
		for (int i = 0; i < shares.size(); i++) {
			cats[i] = shares.get(i).categoryId;
			amounts[i] = shares.get(i).amountMinor;
			notes[i] = shares.get(i).note;
		}
		intent.putExtra(AI_PREFILL_SPLIT_CATEGORY_IDS_EXTRA, cats);
		intent.putExtra(AI_PREFILL_SPLIT_AMOUNTS_EXTRA, amounts);
		intent.putExtra(AI_PREFILL_SPLIT_NOTES_EXTRA, notes);
		return true;
	}

	/** 分割是 TransactionActivity 才有的能力，這裡預設不做；它 override 後把各份建成分割子項。 */
	protected void applyAiPrefillSplits(long[] categoryIds, long[] amounts, String[] notes) {
	}

	/**
	 * 既有交易上講餘額（「剩下X」）：預設不支援、跳提示（轉帳分不清是轉出還是轉入方的餘額）；
	 * TransactionActivity 覆寫成「修這筆的金額」（見 {@link BalanceSplitPlanner#restatedAmount}）。
	 * 回 true＝已接手（有動表單或已跳提示）。
	 */
	protected boolean restateExistingBalance(ParsedTransaction t) {
		Toast.makeText(this, R.string.ai_balance_existing_unsupported, Toast.LENGTH_LONG).show();
		return true;
	}

	/**
	 * 補充模式講到餘額（「剩下X」）→ 新交易切到「調整餘額」模式：帶入帳戶目前餘額 + 講出來的新餘額，
	 * 差額由 TransactionActivity 自己算。走 CURRENT_BALANCE / NEW_BALANCE extra（同 AiInputActivity
	 * .launchBalanceAdjust，不需 draft）；回來的結果轉發沿用型別切換那條 onActivityResult 路徑。
	 * 回 true＝已接手（切走或跳錯誤）。
	 */
	private boolean switchToBalanceAdjust(ParsedTransaction t) {
		// 已存在的交易不切模式、不另開新筆：這筆已算在帳戶餘額裡，講餘額＝修這筆的金額
		// （TransactionActivity 覆寫；轉帳分不清講的是哪一方的餘額，預設跳提示）
		if (isExistingForSwitch()) {
			// 既有交易已在調整餘額表單上：講的就是新餘額，就地填進去（目前餘額已扣掉這筆自己）
			if (isBalanceAdjustMode()) {
				applyAiSupplementFields(t);
				Toast.makeText(this, R.string.ai_recognized, Toast.LENGTH_SHORT).show();
				return true;
			}
			return restateExistingBalance(t);
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
		// 「剩下752其中80是早餐剩下的是食材」：各份分的是差額（新餘額 − 目前餘額），殘額份由 planner 補。
		// 沒報新餘額就算不出差額，分割放棄、退回單一分類。
		boolean splitApplied = false;
		if (t.amount != null && t.hasSplits()) {
			long minor = toMinor(t.amount, accountId);
			long newBalance = t.amount < 0 ? -minor : minor;
			splitApplied = putAiPrefillSplits(intent, BalanceSplitPlanner.plan(
					t.splits, newBalance - account.totalAmount, accountScale(accountId)));
		}
		// 分類/備註/專案帶去調整餘額表單：這句話講到的優先，沒講到的沿用當前表單已填的（切模式不丟資料）
		long catId = t.category.resolved() ? t.category.id : categorySelector.getSelectedCategoryId();
		if (!splitApplied && catId > 0) intent.putExtra(TransactionActivity.AI_PREFILL_CATEGORY_ID_EXTRA, catId);
		String note = !TextUtils.isEmpty(t.note) ? t.note
				: (noteText != null ? noteText.getText().toString().trim() : "");
		if (!TextUtils.isEmpty(note)) intent.putExtra(TransactionActivity.AI_PREFILL_NOTE_EXTRA, note);
		long projId = t.project.resolved() ? t.project.id : projectSelector.getSelectedEntityId();
		if (projId > 0) intent.putExtra(TransactionActivity.AI_PREFILL_PROJECT_ID_EXTRA, projId);
		startReplacingThisForm(intent);
		return true;
	}

	/**
	 * 補充模式要求改型別（交易↔轉帳）：拿本頁的原件（含本頁顯示不了、由原件保管的欄位），
	 * 套上這句話講到的內容，切到對方表單。與標題列切換鈕走同一條路（{@link #launchFormSwitch}）。
	 */
	private void switchTransactionType(ParsedTransaction t, boolean toTransfer) {
		FormSwitch.Origin o = currentOrigin();
		// 轉帳一定要有轉入帳戶：這句話講的優先，沒講就用原件裡保管的（從轉帳切過來又切回去時有）
		FormSwitch.Block block = FormSwitch.applySpokenTypeChange(o, toTransfer,
				t.account.resolved() ? t.account.id : 0,
				t.toAccount.resolved() ? t.toAccount.id : 0,
				t.isIncome());
		if (block == FormSwitch.Block.NO_TO_ACCOUNT) {
			Toast.makeText(this, R.string.ai_transfer_need_to_account, Toast.LENGTH_LONG).show();
			return;
		}
		if (block == FormSwitch.Block.SAME_ACCOUNT) {
			Toast.makeText(this, R.string.select_to_account_differ_from_to_account, Toast.LENGTH_LONG).show();
			return;
		}
		if (t.amount != null) {
			o.amount = toMinor(t.amount, o.account);
			o.otherAmount = o.other > 0 ? toMinor(t.amount, o.other) : o.amount;
		}
		// 分類兩個方向都保留：轉帳表單本來就有分類欄（依偏好 isShowCategoryInTransferScreen）
		if (t.category.resolved()) o.categoryId = t.category.id;
		if (!TextUtils.isEmpty(t.note)) o.note = t.note;
		if (t.project.resolved()) o.projectId = t.project.id;
		Long spoken = t.resolveDateTimeMillis();
		if (spoken != null) o.dateTime = spoken;
		launchFormSwitch(toTransfer ? FormSwitch.Kind.TRANSFER : FormSwitch.Kind.TRANSACTION, o);
	}

	// ---------------- 表單切換（交易／轉帳／調整餘額） ----------------

	/** 這張表單是哪一種。 */
	protected FormSwitch.Kind formKind() {
		return FormSwitch.Kind.TRANSACTION;
	}

	/** 開啟參數對應的表單種類（onCreate 早期用，那時子類的狀態還沒建好）。 */
	private FormSwitch.Kind kindForIntent(Intent intent) {
		if (this instanceof TransferActivity) return FormSwitch.Kind.TRANSFER;
		return intent.hasExtra(TransactionActivity.CURRENT_BALANCE_EXTRA)
				? FormSwitch.Kind.BALANCE : FormSwitch.Kind.TRANSACTION;
	}

	/**
	 * 表單現況（不驗證、不存檔），形狀同存檔：從 {@link #transaction} 複製一份再讀畫面，
	 * 畫面上沒有的欄位（依偏好隱藏的專案、付款人等）就保留原值。子類補帳戶與金額。
	 */
	protected Transaction readFormForSwitch() {
		Transaction t = transaction.clone();
		t.splits = null;
		updateTransactionFromUI(t);
		HashMap<Long, String> attrs = new HashMap<>();
		for (TransactionAttribute a : categorySelector.getAttributes()) {
			attrs.put(a.attributeId, a.value);
		}
		t.categoryAttributes = attrs;
		return t;
	}

	/** 有分割子項（切轉帳要擋）。TransactionActivity 覆寫。 */
	protected boolean hasSplitsForSwitch() {
		return false;
	}

	/** 切換過來的表單載入完之後的補強（如金額為 0 時的收支方向）。 */
	protected void onFormSwitchLoaded(FormSwitch.Origin origin) {
	}

	private Boolean transferInflowHint() {
		return switchOrigin != null ? Boolean.valueOf(Boolean.TRUE.equals(switchOrigin.inflow)) : null;
	}

	private FormSwitch.Origin readCanonical() {
		return FormSwitch.canonical(formKind(), readFormForSwitch(), rateView.isIncomeSelected(), transferInflowHint());
	}

	/** 本頁的原件：切換過來的表單＝原件併入本頁改過的欄位；一般表單＝本頁現況。 */
	private FormSwitch.Origin currentOrigin() {
		if (switchOrigin == null) return readCanonical();
		// 基準還沒抓到＝表單還在載入、使用者不可能改過任何東西
		if (switchBaseline == null) return switchOrigin.copy();
		return FormSwitch.merge(switchOrigin, switchBaseline, readCanonical());
	}

	/** 等分類套上（表單會在那一刻調整屬性欄、分割區、收支鈕）再讀基準；最多等約兩秒。 */
	private void captureSwitchBaselineWhenSettled(int attempt) {
		getWindow().getDecorView().postDelayed(() -> {
			if (isFinishing()) return;
			if (!categorySelector.isSettled() && attempt < 40) {
				captureSwitchBaselineWhenSettled(attempt + 1);
				return;
			}
			switchBaseline = readCanonical();
		}, attempt == 0 ? 0 : 50);
	}

	protected boolean isExistingForSwitch() {
		return transaction.id > 0 || switchReplacesId > 0;
	}

	/** 正在編輯的既有交易在資料庫裡的原樣（存檔前）；新交易為 null。 */
	private Transaction existingInDb;

	/**
	 * 調整餘額的「目前餘額」：帳戶總額扣掉正在編輯的這筆在該帳戶上的金額（新交易就是總額）。
	 * 見 {@link FormSwitch#balanceExcluding}。
	 */
	protected long balanceExcludingThis(Account a) {
		long id = transaction.id > 0 ? transaction.id : switchReplacesId;
		if (id > 0 && existingInDb == null) {
			existingInDb = db.getTransaction(id);
		}
		Transaction e = id > 0 ? existingInDb : null;
		if (e == null) return a.totalAmount;
		return FormSwitch.balanceExcluding(a.totalAmount, a.id,
				e.fromAccountId, e.toAccountId, e.fromAmount, e.toAmount);
	}

	/** 標題列的三格分段鈕。樣板、排程、分割子項的表單標題另有意義，不放。 */
	private void installFormKindBar() {
		if (transaction.isTemplateLike() || transaction.parentId > 0) return;
		FormKindBar bar = new FormKindBar(this, formKind(), this::onFormKindTapped);
		if (bar.install()) {
			kindBar = bar;
			refreshFormKindBar();
		}
	}

	/** 依目前狀態灰掉切不過去的格子。分類變動（選了／取消分割）時重算。 */
	protected void refreshFormKindBar() {
		if (kindBar == null) return;
		boolean splits = hasSplitsForSwitch();
		boolean locked = isPreventEditing();
		for (FormSwitch.Kind k : FormSwitch.Kind.values()) {
			kindBar.setAvailable(k, !locked && FormSwitch.check(k, splits) == null);
		}
	}

	private void onFormKindTapped(FormSwitch.Kind target) {
		// 切不過去的格子已灰掉、不能按；這裡再擋一次只是保險，不跳訊息
		if (target == formKind() || isPreventEditing()
				|| FormSwitch.check(target, hasSplitsForSwitch()) != null) {
			return;
		}
		launchFormSwitch(target, currentOrigin());
	}

	/**
	 * 開目標表單、帶原件過去，並把本頁換掉（存檔結果直接回給最初開表單的那一頁）。
	 * 有分割切轉帳的訊息是給 AI 補充那條路的：講話不像按鈕看得到灰掉，要說出來。
	 */
	private void launchFormSwitch(FormSwitch.Kind target, FormSwitch.Origin origin) {
		if (target == FormSwitch.Kind.TRANSFER && origin.hasSplits()) {
			Toast.makeText(this, R.string.form_switch_split_no_transfer, Toast.LENGTH_SHORT).show();
			return;
		}
		Intent intent = new Intent(this,
				target == FormSwitch.Kind.TRANSFER ? TransferActivity.class : TransactionActivity.class);
		intent.putExtra(FORM_SWITCH_ORIGIN_EXTRA, origin);
		long replaces = transaction.id > 0 ? transaction.id : switchReplacesId;
		if (replaces > 0) intent.putExtra(FORM_SWITCH_REPLACES_EXTRA, replaces);
		if (target == FormSwitch.Kind.BALANCE) {
			// 既有交易：目前餘額要扣掉這筆自己，否則存檔時會被扣兩次
			Account a = origin.account > 0 ? db.getAccount(origin.account) : null;
			intent.putExtra(TransactionActivity.CURRENT_BALANCE_EXTRA, a != null ? balanceExcludingThis(a) : 0L);
			if (a != null) intent.putExtra(ACCOUNT_ID_EXTRA, a.id);
		}
		startReplacingThisForm(intent);
	}

	/**
	 * 以 intent 開的新表單取代本頁：本頁關掉，新表單存檔的結果直接回給最初開表單的那一頁。
	 * 來回切幾次都不會疊出一串表單；切換的動畫關掉，看起來像原地換了畫面。
	 */
	private void startReplacingThisForm(Intent intent) {
		intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
		startActivity(intent);
		overridePendingTransition(0, 0);
		finish();
		overridePendingTransition(0, 0);
	}

	/**
	 * 「儲存並新增」重開本頁前整理開啟參數：拿掉切換帶來的原件（新的一筆是空白表單），
	 * 調整餘額模式的目前餘額換成剛存完的最新值（沿用舊值的話差額會整個錯開）。
	 */
	private void prepareIntentForNext(Intent intent) {
		intent.removeExtra(FORM_SWITCH_ORIGIN_EXTRA);
		intent.removeExtra(FORM_SWITCH_REPLACES_EXTRA);
		intent.setFlags(intent.getFlags() & ~Intent.FLAG_ACTIVITY_FORWARD_RESULT);
		if (intent.hasExtra(TransactionActivity.CURRENT_BALANCE_EXTRA)) {
			Account a = db.getAccount(transaction.fromAccountId);
			if (a != null) {
				intent.putExtra(ACCOUNT_ID_EXTRA, a.id);
				intent.putExtra(TransactionActivity.CURRENT_BALANCE_EXTRA, a.totalAmount);
			}
			intent.removeExtra(TransactionActivity.NEW_BALANCE_EXTRA);
		}
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
			Currency c = rateView.getCurrencyFrom();
			int scale = c != null ? c.getScale() : 2;
			long minor = Math.round(Math.abs(t.amount) * Math.pow(10, scale));
			if (isBalanceAdjustMode()) {
				// 餘額模式的金額欄＝「新餘額」不是變動額，符號另有規則
				// （見 TransactionActivity 的 NEW_BALANCE 預填），這裡維持原樣不動
				rateView.setFromAmount(minor);
			} else {
				// AmountInput.setAmount **是用參數的正負號決定收入/支出的**（內部只把數字部分
				// 取絕對值），所以傳絕對值會把整張表單翻成收入——補充模式只要句子帶金額就中招
				// （2026-08-01 實測：支出表單上補「559元午餐吃拿坡裡披薩」→ 變成 +559 收入；
				// 不帶金額的補充則不會，正是「有時有有時沒有」的來源）。
				// 明講收入才用正號；沒講型別就沿用畫面現在的方向。
				boolean income = t.isIncome()
						|| (t.transactionType == null && rateView.isIncomeSelected());
				rateView.setFromAmount(income ? minor : -minor);
			}
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
		refreshFormKindBar();
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
						// 這條路一定是內建 Google 語音彈窗（不看 prefs：勾雲端時走不到這裡）
						runAiSupplement(heard.get(0), tw.tib.financisto.ai.AiPreferences.PROVIDER_SYSTEM);
					}
					break;
				case AI_VOICE_CAPTURE_REQUEST: {
					// 錄音頁回來：轉寫文字（純轉寫）或 WAV 路徑（一次到位）
					String text = data.getStringExtra(VoiceCaptureActivity.EXTRA_TEXT);
					String wavPath = data.getStringExtra(VoiceCaptureActivity.EXTRA_WAV_PATH);
					if (text != null) {
						runAiSupplement(text, AiPreferences.load(this).getSttLabel());
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

		takePhotoButton.setVisibility(View.GONE);
		selectFromAlbumButton.setVisibility(View.GONE);
		removePictureButton.setVisibility(View.VISIBLE);
	}

	private void removePicture() {
		if (pictureView == null) {
			return;
		}
		// TODO: delete (potentially multiple) removed pictures or not-saved new pictures
		//  when saving and cancel creating
		// can't just delete here, since it should be only committed when explicitly pressed "Save"
		transaction.attachedPicture = null;
		transaction.blobKey = null;
		pictureView.setImageBitmap(null);
		pictureView.setTag(R.id.attached_picture, null);
		pictureDescView.setText(R.string.new_picture);
		takePhotoButton.setVisibility(View.VISIBLE);
		selectFromAlbumButton.setVisibility(View.VISIBLE);
		removePictureButton.setVisibility(View.GONE);
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
		if (tagSelector != null) tagSelector.setEnabled(enabled);
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
		refreshFormKindBar();
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
		if (isShowTags && tagSelector != null) {
			tagSelector.setSelectedTags(transaction.tags);
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
		if (isShowProject) {
			transaction.projectId = projectSelector.getSelectedEntityId();
		}
		if (isShowLocation) {
			transaction.locationId = locationSelector.getSelectedEntityId();
		}
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
		if (isShowTags && tagSelector != null) {
			transaction.tags = tagSelector.getSelectedTags();
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
		if (payeeSelector != null) payeeSelector.onDestroy();
		if (projectSelector != null) projectSelector.onDestroy();
		if (locationSelector != null) locationSelector.onDestroy();
		if (categorySelector != null) categorySelector.onDestroy();
		if (tagSelector != null) tagSelector.onDestroy();
		super.onDestroy();
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if (newPictureUri != null) {
			outState.putString(NEW_PICTURE_URI, newPictureUri.toString());
		}
	}

	@Override
	protected void onRestoreInstanceState(Bundle saved) {
		super.onRestoreInstanceState(saved);
		String savedUri = saved.getString(NEW_PICTURE_URI);
		if (savedUri != null) newPictureUri = Uri.parse(savedUri);
	}
}
