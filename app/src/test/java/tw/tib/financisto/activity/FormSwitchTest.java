package tw.tib.financisto.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import tw.tib.financisto.activity.FormSwitch.Block;
import tw.tib.financisto.activity.FormSwitch.Kind;
import tw.tib.financisto.activity.FormSwitch.Origin;
import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.Transaction;
import tw.tib.financisto.model.TransactionStatus;

/**
 * 三種表單之間的切換：什麼都不改只切換，一定要能回到原點；中途改了某一欄，改的照改、沒動的照舊。
 *
 * 表單的行為用 {@link Form} 模擬：載入 {@link FormSwitch#project} 的結果、讀回「載入完那一刻」的值。
 * 調整餘額表單讀回的是差額（顯示的新餘額 − 目前餘額），轉帳表單顯示不了分割與原幣——跟實際表單一樣。
 */
public class FormSwitchTest {

	private static final long BALANCE = 100_000;   // 主帳戶目前餘額（調整餘額表單用）

	// ---------------------------------------------------------------- 起始狀態

	private static Transaction common(Transaction t) {
		t.categoryId = 5;
		t.projectId = 6;
		t.payeeId = 7;
		t.locationId = 8;
		t.note = "午餐";
		t.tags = "\n出差\n";
		t.dateTime = 1_790_000_000_000L;
		t.status = TransactionStatus.PN;
		t.attachedPicture = "pic.jpg";
		t.isCCardPayment = 1;
		HashMap<Long, String> attrs = new HashMap<>();
		attrs.put(3L, "x");
		t.categoryAttributes = attrs;
		return t;
	}

	private static Transaction expense() {
		Transaction t = common(new Transaction());
		t.fromAccountId = 1;
		t.fromAmount = -15_000;
		return t;
	}

	private static Transaction income() {
		Transaction t = common(new Transaction());
		t.fromAccountId = 1;
		t.fromAmount = 20_000;
		return t;
	}

	private static Transaction foreignCurrency() {
		Transaction t = common(new Transaction());
		t.fromAccountId = 1;
		t.fromAmount = -3_200;
		t.originalCurrencyId = 9;
		t.originalFromAmount = -1_000;
		return t;
	}

	private static Transaction split(long id, long from, long to, long amount, long category, String note) {
		Transaction s = new Transaction();
		s.id = id;
		s.fromAccountId = from;
		s.toAccountId = to;
		s.fromAmount = amount;
		s.toAmount = to > 0 ? -amount : 0;
		s.categoryId = category;
		s.note = note;
		return s;
	}

	private static Transaction withSplits() {
		Transaction t = expense();
		t.categoryId = Category.SPLIT_CATEGORY_ID;
		t.splits = new ArrayList<>(Arrays.asList(
				split(-1, 1, 0, -10_000, 2, "食材"),
				split(-2, 1, 4, -5_000, 0, "代墊")));
		return t;
	}

	private static Transaction transfer(long from, long to, long fromAmount, long toAmount) {
		Transaction t = common(new Transaction());
		t.fromAccountId = from;
		t.toAccountId = to;
		t.fromAmount = fromAmount;
		t.toAmount = toAmount;
		return t;
	}

	private static Transaction balanceWithSplits() {
		Transaction t = withSplits();      // 差額 −15000，拆成兩份
		return t;
	}

	// ---------------------------------------------------------------- 模擬表單

	/** 一張開著的表單：載入時的值（base）與目前的值（now）。 */
	private static final class Form {
		final Kind kind;
		final Origin origin;       // null＝不是切換過來的表單
		final Transaction base;
		Transaction now;
		boolean incomeSelected;
		final Boolean transferInflow;

		Form(Kind kind, Transaction loaded, boolean incomeSelected, Origin origin) {
			this.kind = kind;
			this.origin = origin;
			this.base = loaded;
			this.now = copy(loaded);
			this.incomeSelected = incomeSelected;
			this.transferInflow = origin != null ? Boolean.TRUE.equals(origin.inflow) : null;
		}

		/** 使用者第一次打開的表單（不是切換來的）。 */
		static Form open(Kind kind, Transaction t, boolean incomeSelected) {
			return new Form(kind, copy(t), incomeSelected, null);
		}

		Origin currentOrigin() {
			Origin now = FormSwitch.canonical(kind, this.now, incomeSelected, transferInflow);
			if (origin == null) return now;
			Origin base = FormSwitch.canonical(kind, this.base, baseIncome, transferInflow);
			return FormSwitch.merge(origin, base, now);
		}

		boolean baseIncome;

		Form switchTo(Kind target) {
			return load(currentOrigin(), target);
		}

		/** 帶著原件開一張目標表單（標題列切換與 AI 改型別最後都走這裡）。 */
		static Form load(Origin o, Kind target) {
			Transaction p = FormSwitch.project(o, target, BALANCE);
			Transaction loaded = copy(p);
			if (target == Kind.BALANCE) {
				loaded.fromAmount = p.fromAmount - BALANCE;   // 表單讀回的是差額
			}
			// 交易表單：金額 0 時收支方向由原件帶（實際表單在載入後補設收支鈕）
			boolean income = target == Kind.TRANSACTION
					? (loaded.fromAmount != 0 ? loaded.fromAmount > 0 : Boolean.TRUE.equals(o.inflow))
					: false;
			Form f = new Form(target, loaded, income, o);
			f.baseIncome = income;
			return f;
		}
	}

	private static Transaction copy(Transaction t) {
		Transaction c = t.clone();
		c.categoryAttributes = t.categoryAttributes != null ? new HashMap<>(t.categoryAttributes) : null;
		c.splits = FormSwitch.copySplits(t.splits);
		return c;
	}

	private static Form walk(Form start, Kind... path) {
		Form f = start;
		for (Kind k : path) f = f.switchTo(k);
		return f;
	}

	private static void assertSameAsStart(Form start, Form end) {
		assertEquals(start.kind, end.kind);
		Transaction a = start.now;
		Transaction b = end.now;
		assertEquals("from", a.fromAccountId, b.fromAccountId);
		assertEquals("to", a.toAccountId, b.toAccountId);
		assertEquals("fromAmount", a.fromAmount, b.fromAmount);
		if (start.kind == Kind.TRANSFER) assertEquals("toAmount", a.toAmount, b.toAmount);
		assertEquals("originalCurrency", a.originalCurrencyId, b.originalCurrencyId);
		assertEquals("originalAmount", a.originalFromAmount, b.originalFromAmount);
		assertEquals("category", a.categoryId, b.categoryId);
		assertEquals("project", a.projectId, b.projectId);
		assertEquals("payee", a.payeeId, b.payeeId);
		assertEquals("location", a.locationId, b.locationId);
		assertEquals("note", a.note, b.note);
		assertEquals("tags", a.tags, b.tags);
		assertEquals("dateTime", a.dateTime, b.dateTime);
		assertEquals("status", a.status, b.status);
		assertEquals("picture", a.attachedPicture, b.attachedPicture);
		assertEquals("ccard", a.isCCardPayment, b.isCCardPayment);
		assertEquals("attributes", a.categoryAttributes, b.categoryAttributes);
		assertTrue("splits", FormSwitch.splitsEqual(a.splits, b.splits));
		if (start.kind == Kind.TRANSACTION) assertEquals("income toggle", start.incomeSelected, end.incomeSelected);
	}

	/** 從 start 出發，走過每一條「只切換不改」的路回到起點，都要一模一樣。 */
	private static void assertAllRoundTrips(Form start, boolean hasSplits) {
		List<Kind> others = new ArrayList<>(Arrays.asList(Kind.values()));
		others.remove(start.kind);
		List<List<Kind>> paths = new ArrayList<>();
		for (Kind a : others) {
			paths.add(Arrays.asList(a, start.kind));
			for (Kind b : Kind.values()) {
				if (b == a) continue;
				paths.add(b == start.kind ? Arrays.asList(a, b) : Arrays.asList(a, b, start.kind));
			}
		}
		paths.add(Arrays.asList(others.get(0), others.get(1), others.get(0), start.kind));
		for (List<Kind> path : paths) {
			if (hasSplits && path.contains(Kind.TRANSFER)) continue;     // 有分割不能切轉帳
			Form end = walk(start, path.toArray(new Kind[0]));
			try {
				assertSameAsStart(start, end);
			} catch (AssertionError e) {
				throw new AssertionError(start.kind + " → " + path + ": " + e.getMessage(), e);
			}
		}
	}

	// ---------------------------------------------------------------- 只切換不改：回到原點

	@Test
	public void expenseRoundTrips() {
		assertAllRoundTrips(Form.open(Kind.TRANSACTION, expense(), false), false);
	}

	@Test
	public void incomeRoundTrips() {
		assertAllRoundTrips(Form.open(Kind.TRANSACTION, income(), true), false);
	}

	@Test
	public void zeroAmountIncomeKeepsItsDirection() {
		Transaction t = income();
		t.fromAmount = 0;
		assertAllRoundTrips(Form.open(Kind.TRANSACTION, t, true), false);
	}

	@Test
	public void foreignCurrencyRoundTrips() {
		assertAllRoundTrips(Form.open(Kind.TRANSACTION, foreignCurrency(), false), false);
	}

	@Test
	public void splitTransactionRoundTripsThroughBalance() {
		assertAllRoundTrips(Form.open(Kind.TRANSACTION, withSplits(), false), true);
	}

	@Test
	public void sameCurrencyTransferRoundTrips() {
		assertAllRoundTrips(Form.open(Kind.TRANSFER, transfer(1, 2, -5_000, 5_000), false), false);
	}

	@Test
	public void crossCurrencyTransferRoundTrips() {
		assertAllRoundTrips(Form.open(Kind.TRANSFER, transfer(1, 3, -3_000, 100), false), false);
	}

	@Test
	public void transferWithoutDestinationRoundTrips() {
		assertAllRoundTrips(Form.open(Kind.TRANSFER, transfer(1, 0, -700, 700), false), false);
	}

	@Test
	public void transferWithOnlyDestinationRoundTrips() {
		assertAllRoundTrips(Form.open(Kind.TRANSFER, transfer(0, 2, -800, 800), false), false);
	}

	@Test
	public void balanceWithSplitsRoundTrips() {
		assertAllRoundTrips(Form.open(Kind.BALANCE, balanceWithSplits(), false), true);
	}

	@Test
	public void zeroDifferenceBalanceRoundTrips() {
		Transaction t = expense();
		t.fromAmount = 0;
		assertAllRoundTrips(Form.open(Kind.BALANCE, t, false), false);
	}

	@Test
	public void noAccountRoundTrips() {
		Transaction t = expense();
		t.fromAccountId = 0;
		assertAllRoundTrips(Form.open(Kind.TRANSACTION, t, false), false);
	}

	// ---------------------------------------------------------------- 各方向怎麼對應

	@Test
	public void expenseBecomesTransferOutOfTheAccount() {
		Form x = Form.open(Kind.TRANSACTION, expense(), false).switchTo(Kind.TRANSFER);
		assertEquals(1, x.now.fromAccountId);
		assertEquals(0, x.now.toAccountId);
		assertEquals(-15_000, x.now.fromAmount);
		assertEquals(15_000, x.now.toAmount);
	}

	@Test
	public void incomeBecomesTransferIntoTheAccount() {
		Form x = Form.open(Kind.TRANSACTION, income(), true).switchTo(Kind.TRANSFER);
		assertEquals(0, x.now.fromAccountId);
		assertEquals(1, x.now.toAccountId);
		assertEquals(-20_000, x.now.fromAmount);
		assertEquals(20_000, x.now.toAmount);
	}

	@Test
	public void transactionAmountBecomesBalanceDifference() {
		Transaction p = FormSwitch.project(
				FormSwitch.canonical(Kind.TRANSACTION, expense(), false, null), Kind.BALANCE, BALANCE);
		assertEquals(BALANCE - 15_000, p.fromAmount);   // 表單顯示的新餘額
	}

	@Test
	public void balanceDifferenceBecomesTransactionAmount() {
		Transaction b = expense();
		b.fromAmount = 2_500;                            // 差額 +2500
		Transaction p = FormSwitch.project(
				FormSwitch.canonical(Kind.BALANCE, b, false, null), Kind.TRANSACTION, 0);
		assertEquals(2_500, p.fromAmount);
	}

	@Test
	public void transferIntoAccountBecomesIncome() {
		Form t = Form.open(Kind.TRANSFER, transfer(0, 2, -800, 800), false).switchTo(Kind.TRANSACTION);
		assertEquals(2, t.now.fromAccountId);
		assertEquals(800, t.now.fromAmount);
	}

	@Test
	public void splitCategoryWithoutSplitsShowsAsNoCategoryOnTransfer() {
		Transaction t = expense();
		t.categoryId = Category.SPLIT_CATEGORY_ID;
		Form x = Form.open(Kind.TRANSACTION, t, false).switchTo(Kind.TRANSFER);
		assertEquals(Category.NO_CATEGORY_ID, x.now.categoryId);
		assertEquals(Category.SPLIT_CATEGORY_ID, x.switchTo(Kind.TRANSACTION).now.categoryId);
	}

	// ---------------------------------------------------------------- 中途改了：改的照改、沒動的照舊

	@Test
	public void editedAmountOnTransactionKeepsTransferDestination() {
		Form t = Form.open(Kind.TRANSFER, transfer(1, 2, -5_000, 5_000), false).switchTo(Kind.TRANSACTION);
		t.now.fromAmount = -6_000;
		Form x = t.switchTo(Kind.TRANSFER);
		assertEquals(1, x.now.fromAccountId);
		assertEquals(2, x.now.toAccountId);
		assertEquals(-6_000, x.now.fromAmount);
		assertEquals(6_000, x.now.toAmount);
	}

	@Test
	public void editedNoteKeepsCrossCurrencyAmounts() {
		Form t = Form.open(Kind.TRANSFER, transfer(1, 3, -3_000, 100), false).switchTo(Kind.TRANSACTION);
		t.now.note = "改過";
		Form x = t.switchTo(Kind.TRANSFER);
		assertEquals(-3_000, x.now.fromAmount);
		assertEquals(100, x.now.toAmount);
		assertEquals("改過", x.now.note);
	}

	@Test
	public void editedNewBalanceChangesTheAmount() {
		Form b = Form.open(Kind.TRANSACTION, expense(), false).switchTo(Kind.BALANCE);
		b.now.fromAmount = -20_000;                     // 新餘額改成 80000 → 差額 −20000
		Form t = b.switchTo(Kind.TRANSACTION);
		assertEquals(-20_000, t.now.fromAmount);
		assertEquals("午餐", t.now.note);
	}

	@Test
	public void formAutoAdjustingDirectionIsNotAnEdit() {
		// 金額 0 的收入：交易表單載入後依分類把收支鈕翻成支出（表單自己做的，不是使用者）
		Transaction t = income();
		t.fromAmount = 0;
		Form x = Form.open(Kind.TRANSACTION, t, true).switchTo(Kind.TRANSFER);
		Form back = x.switchTo(Kind.TRANSACTION);
		back.baseIncome = false;
		back.incomeSelected = false;
		Form again = back.switchTo(Kind.TRANSFER).switchTo(Kind.TRANSACTION);
		assertTrue(again.incomeSelected);
	}

	@Test
	public void userFlippingDirectionIsAnEdit() {
		Form t = Form.open(Kind.TRANSACTION, expense(), false).switchTo(Kind.BALANCE).switchTo(Kind.TRANSACTION);
		t.now.fromAmount = 15_000;
		t.incomeSelected = true;
		Form x = t.switchTo(Kind.TRANSFER);
		assertEquals(1, x.now.toAccountId);
	}

	@Test
	public void changingOnlyASplitStatusIsAnEdit() {
		// 子項有自己的狀態：只改了某一份的狀態，切換後也要留著
		Form b = Form.open(Kind.TRANSACTION, withSplits(), false).switchTo(Kind.BALANCE);
		b.now.splits.get(0).status = TransactionStatus.CL;
		Form t = b.switchTo(Kind.TRANSACTION);
		assertEquals(TransactionStatus.CL, t.now.splits.get(0).status);
	}

	@Test
	public void editedSplitsReplaceTheCarriedOnes() {
		Form b = Form.open(Kind.TRANSACTION, withSplits(), false).switchTo(Kind.BALANCE);
		b.now.splits = new ArrayList<>(Collections.singletonList(split(-9, 1, 0, -15_000, 2, "全部食材")));
		Form t = b.switchTo(Kind.TRANSACTION);
		assertEquals(1, t.now.splits.size());
		assertEquals("全部食材", t.now.splits.get(0).note);
	}

	// ---------------------------------------------------------------- AI 補充模式改型別

	/** 模擬 AbstractTransactionActivity.switchTransactionType：原件 ＋ 這句話講的 → 目標表單。 */
	private static Form aiChange(Form f, boolean toTransfer, long account, long to, boolean income, Long amount) {
		Origin o = f.currentOrigin();
		assertNull(FormSwitch.applySpokenTypeChange(o, toTransfer, account, to, income));
		if (amount != null) {
			o.amount = amount;
			o.otherAmount = amount;
		}
		return Form.load(o, toTransfer ? Kind.TRANSFER : Kind.TRANSACTION);
	}

	@Test
	public void aiExpenseToTransferUsesSpokenDestination() {
		Form x = aiChange(Form.open(Kind.TRANSACTION, expense(), false), true, 0, 2, false, null);
		assertEquals(1, x.now.fromAccountId);
		assertEquals(2, x.now.toAccountId);
		assertEquals(-15_000, x.now.fromAmount);
		assertEquals(15_000, x.now.toAmount);
		assertEquals("午餐", x.now.note);
		assertEquals(TransactionStatus.PN, x.now.status);
		assertEquals("pic.jpg", x.now.attachedPicture);
	}

	@Test
	public void aiTransferWithoutSpokenDestinationReusesTheCarriedOne() {
		// 轉帳 → 標題列切到交易 → AI 講「改成轉帳」沒講轉入：用原件裡保管的那個
		Form t = Form.open(Kind.TRANSFER, transfer(1, 2, -5_000, 5_000), false).switchTo(Kind.TRANSACTION);
		Form x = aiChange(t, true, 0, 0, false, null);
		assertEquals(2, x.now.toAccountId);
	}

	@Test
	public void aiTransferNeedsADestination() {
		Origin o = Form.open(Kind.TRANSACTION, expense(), false).currentOrigin();
		Origin before = o.copy();
		assertEquals(Block.NO_TO_ACCOUNT, FormSwitch.applySpokenTypeChange(o, true, 0, 0, false));
		assertEquals(before.account, o.account);
		assertEquals(before.inflow, o.inflow);
		assertEquals(Block.SAME_ACCOUNT, FormSwitch.applySpokenTypeChange(o, true, 0, 1, false));
	}

	@Test
	public void aiSpokenAccountAndAmountOverride() {
		Form x = aiChange(Form.open(Kind.TRANSACTION, expense(), false), true, 3, 2, false, 9_900L);
		assertEquals(3, x.now.fromAccountId);
		assertEquals(-9_900, x.now.fromAmount);
		assertEquals(9_900, x.now.toAmount);
	}

	@Test
	public void aiTransferToIncomeKeepsTheMainAccount() {
		Form t = aiChange(Form.open(Kind.TRANSFER, transfer(1, 2, -5_000, 5_000), false), false, 0, 0, true, null);
		assertEquals(1, t.now.fromAccountId);
		assertEquals(5_000, t.now.fromAmount);
		assertEquals(0, t.now.toAccountId);
		// 另一方仍在原件裡：再用標題列切回轉帳，它還在；方向已是收入，主帳戶在轉入側
		Form x = t.switchTo(Kind.TRANSFER);
		assertEquals(2, x.now.fromAccountId);
		assertEquals(1, x.now.toAccountId);
	}

	@Test
	public void aiOutOfBalanceWithoutAmountCarriesTheDifference() {
		Transaction b = expense();
		b.fromAmount = -300;                             // 差額 −300（新餘額比目前少 300）
		Form t = aiChange(Form.open(Kind.BALANCE, b, false), false, 0, 0, false, null);
		assertEquals(-300, t.now.fromAmount);
	}

	@Test
	public void aiOnASwitchedFormKeepsEditsMadeThere() {
		// 交易 → 標題列切到轉帳、改了轉入帳戶 → AI 講「改成支出」：回到交易，轉入帳戶留在原件
		Form x = Form.open(Kind.TRANSACTION, expense(), false).switchTo(Kind.TRANSFER);
		x.now.toAccountId = 4;
		x.now.note = "改過";
		Form t = aiChange(x, false, 0, 0, false, null);
		assertEquals(-15_000, t.now.fromAmount);
		assertEquals("改過", t.now.note);
		assertEquals(4, t.switchTo(Kind.TRANSFER).now.toAccountId);
	}

	// ---------------------------------------------------------------- 擋下的情況

	@Test
	public void blocks() {
		assertEquals(Block.SPLIT_TO_TRANSFER, FormSwitch.check(Kind.TRANSFER, true));
		assertNull(FormSwitch.check(Kind.BALANCE, true));
		assertNull(FormSwitch.check(Kind.TRANSFER, false));
		assertNull(FormSwitch.check(Kind.TRANSACTION, true));
	}

	// ---------------------------------------------------------------- 既有交易切調整餘額：目前餘額扣掉這筆自己

	@Test
	public void newTransactionUsesTheAccountTotal() {
		assertEquals(700, FormSwitch.balanceExcluding(700, 1, 0, 0, 0, 0));
	}

	@Test
	public void existingExpenseIsTakenOutOfTheBalance() {
		// 錢包總額 700 含午餐 −150：目前餘額 850；切過去顯示新餘額 850 − 150 ＝ 700（就是現況）
		long base = FormSwitch.balanceExcluding(700, 1, 1, 0, -150, 0);
		assertEquals(850, base);
		Origin o = FormSwitch.canonical(Kind.TRANSACTION, lunch(-150), false, null);
		assertEquals(700, FormSwitch.project(o, Kind.BALANCE, base).fromAmount);
		// 數完報 680 → 這筆＝680 − 850 ＝ −170（拿總額算會錯成 +20）
		assertEquals(-170, 680 - base);
	}

	@Test
	public void existingTransferUsesTheSideOfThatAccount() {
		// 轉帳 甲→乙 500：在乙（轉入側）切調整餘額，扣的是轉入金額；跟這筆無關的帳戶就是總額
		assertEquals(1_000 - 500, FormSwitch.balanceExcluding(1_000, 2, 1, 2, -500, 500));
		assertEquals(300 + 500, FormSwitch.balanceExcluding(300, 1, 1, 2, -500, 500));
		assertEquals(42, FormSwitch.balanceExcluding(42, 3, 1, 2, -500, 500));
	}

	private static Transaction lunch(long amount) {
		Transaction t = new Transaction();
		t.fromAccountId = 1;
		t.fromAmount = amount;
		return t;
	}
}
