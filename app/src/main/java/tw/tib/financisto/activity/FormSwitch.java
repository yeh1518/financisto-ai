package tw.tib.financisto.activity;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.Transaction;
import tw.tib.financisto.model.TransactionStatus;

/**
 * 交易／轉帳／調整餘額三種表單之間的切換。
 *
 * 三種表單是同一筆資料的三種看法，但各自放得下的欄位不同：交易表單沒有轉入帳戶、轉帳表單沒有分割與
 * 原幣、調整餘額表單填的是「新餘額」而不是變動額。直接拿上一張表單的值去填下一張，放不下的就掉了，
 * 切過去再切回來回不到原樣。
 *
 * 所以切換時帶的是一份與表單無關的「原件」（{@link Origin}）：
 * <ul>
 *   <li>第一次切換時，把表單讀出來的值正規化成原件；</li>
 *   <li>每張表單只顯示原件裡它放得下的部分（{@link #project}），其餘留在原件裡；</li>
 *   <li>再切換時，只把「這張表單上真的被改過」的欄位寫回原件（{@link #merge}）。判斷改過的基準是
 *       表單剛載入完那一刻讀到的值，不是原件——表單載入時自己做的調整（金額為 0 時依分類翻收支、
 *       餘額模式依餘額正負定方向）就不會被當成使用者的修改。</li>
 * </ul>
 * 什麼都不改只切換，原件從頭到尾不被寫入，切回起點就是用原件重建，與中間繞過幾種表單無關。
 *
 * 金額一律存「主帳戶」那一側的絕對值加方向：交易與調整餘額的主帳戶就是那個帳戶；轉帳的主帳戶依方向
 * 落在轉出或轉入那一側。調整餘額表單的變動額＝新餘額 − 目前餘額，與交易金額互換，整數加減沒有誤差。
 */
public final class FormSwitch {

	public enum Kind { TRANSACTION, TRANSFER, BALANCE }

	/** 切不過去的原因；{@code null}＝可以切。 */
	public enum Block {
		/** 有分割：轉帳不能分割。 */
		SPLIT_TO_TRANSFER,
		/** AI 要求改成轉帳，但這句話沒講轉入帳戶、原件裡也沒有。 */
		NO_TO_ACCOUNT,
		/** AI 要求改成轉帳，轉入帳戶就是主帳戶自己。 */
		SAME_ACCOUNT
	}

	private FormSwitch() {
	}

	/** 與表單無關的正規形。只放欄位，不放行為；可序列化以便放進 Intent。 */
	public static final class Origin implements Serializable {
		private static final long serialVersionUID = 1L;

		/** 主帳戶（0＝未選）。 */
		public long account;
		/** 轉帳的另一方（0＝無）。 */
		public long other;
		/** 錢是否流進主帳戶；{@code null}＝表單表達不出來（調整餘額表單上差額為 0）。 */
		public Boolean inflow;
		/** 主帳戶側金額，絕對值，主帳戶幣別的 minor units。 */
		public long amount;
		/** 另一方的金額，絕對值；同幣別時與 amount 相同，跨幣別轉帳才不同。 */
		public long otherAmount;
		/** 交易的外幣原幣別（0＝與帳戶同幣別）與原幣金額（絕對值）。 */
		public long originalCurrencyId;
		public long originalAmount;

		public long categoryId;
		public long projectId;
		public long payeeId;
		public long locationId;
		public String note;
		public String tags;
		public long dateTime;
		public TransactionStatus status = TransactionStatus.UR;
		public String attachedPicture;
		public String blobKey;
		public int isCCardPayment;
		public HashMap<Long, String> attributes;
		/** 分割子項，表單內的形狀（帶號金額、相對主帳戶）；{@code null}＝不是分割。 */
		public ArrayList<Transaction> splits;

		public Origin copy() {
			Origin o = new Origin();
			o.account = account;
			o.other = other;
			o.inflow = inflow;
			o.amount = amount;
			o.otherAmount = otherAmount;
			o.originalCurrencyId = originalCurrencyId;
			o.originalAmount = originalAmount;
			o.categoryId = categoryId;
			o.projectId = projectId;
			o.payeeId = payeeId;
			o.locationId = locationId;
			o.note = note;
			o.tags = tags;
			o.dateTime = dateTime;
			o.status = status;
			o.attachedPicture = attachedPicture;
			o.blobKey = blobKey;
			o.isCCardPayment = isCCardPayment;
			o.attributes = attributes != null ? new HashMap<>(attributes) : null;
			o.splits = copySplits(splits);
			return o;
		}

		public boolean hasSplits() {
			return splits != null && !splits.isEmpty();
		}

		/** 帶號金額：流進主帳戶為正。 */
		public long signedAmount() {
			return Boolean.TRUE.equals(inflow) ? amount : -amount;
		}
	}

	/** 切到 {@code to} 能不能切。 */
	public static Block check(Kind to, boolean hasSplits) {
		if (to == Kind.TRANSFER && hasSplits) return Block.SPLIT_TO_TRANSFER;
		return null;
	}

	/**
	 * 調整餘額表單的「目前餘額」：帳戶總額扣掉這筆交易自己在這個帳戶上的金額。
	 *
	 * 調整餘額存檔時金額＝新餘額 − 目前餘額。新交易還沒算進帳戶總額，目前餘額就是總額；已存檔的交易
	 * 已經算在總額裡了，直接拿總額會把它扣兩次——錢包總額 700 含一筆 −150，數完報 680，正確是這筆改成
	 * −170，拿總額算會變成 +20。轉帳要看這個帳戶在哪一側：轉出側扣轉出金額、轉入側扣轉入金額，
	 * 跟這筆無關的帳戶就是總額。新交易傳 fromAccount＝toAccount＝0。
	 *
	 * @param fromAccount/toAccount/fromAmount/toAmount 這筆交易在資料庫裡的值（存檔前的原樣）
	 */
	public static long balanceExcluding(long accountTotal, long account,
										long fromAccount, long toAccount, long fromAmount, long toAmount) {
		if (account <= 0) return accountTotal;
		if (fromAccount == account) return accountTotal - fromAmount;
		if (toAccount == account) return accountTotal - toAmount;
		return accountTotal;
	}

	/**
	 * AI 補充模式要求改型別（「改成轉帳到 X」「其實是收入」）：把這句話講到的帳戶與方向套到原件上。
	 * 金額、分類、備註等其他欄位由呼叫端照講的覆蓋（金額要依帳戶幣別換算）。
	 * 擋下時原件不動。
	 *
	 * @param spokenAccount   這句話講的帳戶（≤0＝沒講，沿用原件的主帳戶）
	 * @param spokenToAccount 這句話講的轉入帳戶（≤0＝沒講，沿用原件保管的另一方）
	 * @param income          目標是交易時：這句話是不是講收入
	 */
	public static Block applySpokenTypeChange(Origin o, boolean toTransfer,
											  long spokenAccount, long spokenToAccount, boolean income) {
		long account = spokenAccount > 0 ? spokenAccount : o.account;
		if (toTransfer) {
			long to = spokenToAccount > 0 ? spokenToAccount : o.other;
			if (to <= 0) return Block.NO_TO_ACCOUNT;
			if (to == account) return Block.SAME_ACCOUNT;
			o.account = account;
			o.other = to;
			o.inflow = Boolean.FALSE;       // 「改成轉帳到 X」＝錢從主帳戶轉出去
		} else {
			o.account = account;
			o.inflow = income;
		}
		return null;
	}

	/**
	 * 表單讀出來的值 → 原件。
	 *
	 * @param t 表單現況，形狀同存檔：交易 from/fromAmount（外幣時另有原幣）；轉帳 from/to/fromAmount/toAmount；
	 *          調整餘額 from 與 fromAmount＝差額（新餘額 − 目前餘額）
	 * @param incomeSelected 交易表單的收支鈕狀態，金額為 0 時用來表達方向
	 * @param transferInflow 轉帳表單的主帳戶在哪一側：{@code true}＝轉入側。{@code null} 時依表單推斷
	 *                       （只填了轉入帳戶＝轉入側）。同一張表單前後兩次讀取必須用同一個值。
	 */
	public static Origin canonical(Kind kind, Transaction t, boolean incomeSelected, Boolean transferInflow) {
		Origin o = new Origin();
		o.categoryId = t.categoryId;
		o.projectId = t.projectId;
		o.payeeId = t.payeeId;
		o.locationId = t.locationId;
		o.note = t.note;
		o.tags = t.tags;
		o.dateTime = t.dateTime;
		o.status = t.status;
		o.attachedPicture = t.attachedPicture;
		o.blobKey = t.blobKey;
		o.isCCardPayment = t.isCCardPayment;
		o.attributes = t.categoryAttributes != null ? new HashMap<>(t.categoryAttributes) : null;
		switch (kind) {
			case TRANSFER: {
				boolean in = transferInflow != null ? transferInflow
						: (t.fromAccountId <= 0 && t.toAccountId > 0);
				if (in) {
					o.account = positive(t.toAccountId);
					o.other = positive(t.fromAccountId);
					o.amount = Math.abs(t.toAmount);
					o.otherAmount = Math.abs(t.fromAmount);
				} else {
					o.account = positive(t.fromAccountId);
					o.other = positive(t.toAccountId);
					o.amount = Math.abs(t.fromAmount);
					o.otherAmount = Math.abs(t.toAmount);
				}
				o.inflow = in;
				break;
			}
			case TRANSACTION:
			case BALANCE:
			default: {
				o.account = positive(t.fromAccountId);
				o.amount = Math.abs(t.fromAmount);
				if (t.fromAmount > 0) {
					o.inflow = Boolean.TRUE;
				} else if (t.fromAmount < 0) {
					o.inflow = Boolean.FALSE;
				} else {
					o.inflow = kind == Kind.TRANSACTION ? Boolean.valueOf(incomeSelected) : null;
				}
				if (kind == Kind.TRANSACTION && t.originalCurrencyId > 0) {
					o.originalCurrencyId = t.originalCurrencyId;
					o.originalAmount = Math.abs(t.originalFromAmount);
				}
				o.splits = t.splits != null && !t.splits.isEmpty() ? copySplits(t.splits) : null;
				break;
			}
		}
		return o;
	}

	/**
	 * 把這張表單上被改過的欄位寫回原件。{@code base}＝表單剛載入完時讀到的、{@code now}＝現在讀到的，
	 * 兩者都用 {@link #canonical} 以同一個方向正規化過。兩者相同的欄位一律保留原件的值。
	 */
	public static Origin merge(Origin origin, Origin base, Origin now) {
		Origin r = origin.copy();
		if (base.account != now.account) r.account = now.account;
		if (base.other != now.other) r.other = now.other;
		if (!Objects.equals(base.inflow, now.inflow) && now.inflow != null) r.inflow = now.inflow;
		if (base.amount != now.amount) {
			// 同幣別時另一方的金額就是同一個數字，主帳戶金額改了要跟著走；
			// 跨幣別時兩個數字各自獨立，沒被改就保留
			if (origin.otherAmount == origin.amount) r.otherAmount = now.amount;
			r.amount = now.amount;
		}
		if (base.otherAmount != now.otherAmount) r.otherAmount = now.otherAmount;
		if (base.originalCurrencyId != now.originalCurrencyId) r.originalCurrencyId = now.originalCurrencyId;
		if (base.originalAmount != now.originalAmount) r.originalAmount = now.originalAmount;
		if (base.categoryId != now.categoryId) r.categoryId = now.categoryId;
		if (base.projectId != now.projectId) r.projectId = now.projectId;
		if (base.payeeId != now.payeeId) r.payeeId = now.payeeId;
		if (base.locationId != now.locationId) r.locationId = now.locationId;
		if (!Objects.equals(base.note, now.note)) r.note = now.note;
		if (!Objects.equals(base.tags, now.tags)) r.tags = now.tags;
		if (base.dateTime != now.dateTime) r.dateTime = now.dateTime;
		if (base.status != now.status) r.status = now.status;
		if (!Objects.equals(base.attachedPicture, now.attachedPicture)) {
			r.attachedPicture = now.attachedPicture;
			r.blobKey = now.blobKey;
		}
		if (base.isCCardPayment != now.isCCardPayment) r.isCCardPayment = now.isCCardPayment;
		if (!Objects.equals(base.attributes, now.attributes)) {
			r.attributes = now.attributes != null ? new HashMap<>(now.attributes) : null;
		}
		if (!splitsEqual(base.splits, now.splits)) r.splits = copySplits(now.splits);
		return r;
	}

	/**
	 * 原件 → 目標表單要載入的交易（形狀同 {@link #canonical} 的輸入，id 一律 -1）。
	 *
	 * @param currentBalance 目標是調整餘額時，主帳戶的目前餘額；表單的金額欄＝目前餘額 ＋ 變動額
	 */
	public static Transaction project(Origin o, Kind kind, long currentBalance) {
		Transaction t = new Transaction();
		t.id = -1;
		t.categoryId = o.categoryId;
		t.projectId = o.projectId;
		t.payeeId = o.payeeId;
		t.locationId = o.locationId;
		t.note = o.note;
		t.tags = o.tags;
		t.dateTime = o.dateTime;
		t.status = o.status;
		t.attachedPicture = o.attachedPicture;
		t.blobKey = o.blobKey;
		t.isCCardPayment = o.isCCardPayment;
		t.categoryAttributes = o.attributes != null ? new HashMap<>(o.attributes) : null;
		switch (kind) {
			case TRANSFER: {
				boolean in = Boolean.TRUE.equals(o.inflow);
				long main = o.amount;
				long other = o.otherAmount > 0 ? o.otherAmount : o.amount;
				if (in) {
					t.fromAccountId = o.other;
					t.toAccountId = o.account;
					t.fromAmount = -other;
					t.toAmount = main;
				} else {
					t.fromAccountId = o.account;
					t.toAccountId = o.other;
					t.fromAmount = -main;
					t.toAmount = other;
				}
				// 選了「分割」但還沒有子項時可以切過來；轉帳的分類清單沒有分割這一項，先顯示成無分類
				// （沒動到的話原件裡還是分割，切回交易照原樣）
				if (t.categoryId == Category.SPLIT_CATEGORY_ID) t.categoryId = Category.NO_CATEGORY_ID;
				break;
			}
			case BALANCE:
				t.fromAccountId = o.account;
				t.fromAmount = currentBalance + o.signedAmount();
				t.splits = copySplits(o.splits);
				break;
			case TRANSACTION:
			default:
				t.fromAccountId = o.account;
				t.fromAmount = o.signedAmount();
				if (o.originalCurrencyId > 0) {
					t.originalCurrencyId = o.originalCurrencyId;
					t.originalFromAmount = Boolean.TRUE.equals(o.inflow) ? o.originalAmount : -o.originalAmount;
				}
				t.splits = copySplits(o.splits);
				break;
		}
		return t;
	}

	static ArrayList<Transaction> copySplits(List<Transaction> splits) {
		if (splits == null) return null;
		ArrayList<Transaction> copy = new ArrayList<>(splits.size());
		for (Transaction s : splits) {
			Transaction c = s.clone();
			c.categoryAttributes = s.categoryAttributes != null ? new HashMap<>(s.categoryAttributes) : null;
			copy.add(c);
		}
		return copy;
	}

	/** 分割內容相同（不看 id：表單每次載入都會重編暫用 id）。 */
	static boolean splitsEqual(List<Transaction> a, List<Transaction> b) {
		boolean aEmpty = a == null || a.isEmpty();
		boolean bEmpty = b == null || b.isEmpty();
		if (aEmpty || bEmpty) return aEmpty == bEmpty;
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			if (!splitSignature(a.get(i)).equals(splitSignature(b.get(i)))) return false;
		}
		return true;
	}

	private static String splitSignature(Transaction s) {
		Map<Long, String> attrs = s.categoryAttributes;
		return s.fromAccountId + "|" + s.toAccountId + "|" + s.fromAmount + "|" + s.toAmount
				+ "|" + s.categoryId + "|" + s.projectId + "|" + s.payeeId + "|" + s.locationId
				+ "|" + s.originalCurrencyId + "|" + s.originalFromAmount
				+ "|" + s.note + "|" + s.tags + "|" + s.status
				+ "|" + (attrs != null && !attrs.isEmpty() ? attrs : "");
	}

	private static long positive(long id) {
		return id > 0 ? id : 0;
	}
}
