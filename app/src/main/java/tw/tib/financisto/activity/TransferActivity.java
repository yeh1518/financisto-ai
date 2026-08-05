/*******************************************************************************
 * Copyright (c) 2010 Denis Solonenko.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Contributors:
 *     Denis Solonenko - initial API and implementation
 ******************************************************************************/
package tw.tib.financisto.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import tw.tib.financisto.R;
import tw.tib.financisto.db.DatabaseHelper.AccountColumns;
import tw.tib.financisto.model.Account;
import tw.tib.financisto.model.Currency;
import tw.tib.financisto.model.Transaction;
import tw.tib.financisto.utils.MyPreferences;

import static tw.tib.financisto.activity.CategorySelector.SelectorType.TRANSFER;

public class TransferActivity extends AbstractTransactionActivity {

	public static final String AMOUNT_EXTRA = "amount";

	private TextView accountFromText;
	private TextView accountFromBalanceText;
	private TextView accountFromLimitText;

	private TextView accountToText;
	private TextView accountToBalanceText;
	private TextView accountToLimitText;

	private long selectedAccountFromId = -1;
	private long selectedAccountToId = -1;

	private boolean isShowCategoryInTransfer;

	/**
	 * 有金額、但轉出帳戶還沒定下來時暫存的金額（AI 只解析出轉入帳戶的情況）。
	 * 先用轉入帳戶的幣別把數字顯示出來，等使用者挑完轉出帳戶再依真正的幣別重填一次。
	 */
	private long pendingFromAmount;

	public TransferActivity() {
	}

	@Override
	protected void internalOnCreate() {
		super.internalOnCreate();
		if (transaction.isTemplateLike()) {
			setTitle(transaction.isTemplate() ? R.string.transfer_template : R.string.transfer_schedule);
			if (transaction.isTemplate()) {
				dateText.setEnabled(false);
				timeText.setEnabled(false);
			}
		}
	}

	@Override
	protected  void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		if (intent != null) {
			if (intent.hasExtra(AMOUNT_EXTRA)) {
				long amount = intent.getLongExtra(AMOUNT_EXTRA, 0);
				rateView.setFromAmount(amount);
			}
		}
	}

	protected void fetchCategories() {
		categorySelector.fetchCategories(false);
		categorySelector.doNotShowSplitCategory();
	}

	protected int getLayoutId() {
		return MyPreferences.isUseFixedLayout() ? R.layout.transfer_fixed : R.layout.transfer_free;
	}

	@Override
	protected void createListNodes(LinearLayout layout) {
		if (isShowAccountBalanceOnSelector) {
			accountFromText = x.addListNodeAccount(layout, R.id.account_from, R.string.account_from, R.string.select_account);
			View v = ((View) accountFromText.getTag());
			accountFromBalanceText = v.findViewById(R.id.balance);
			accountFromBalanceText.setVisibility(View.INVISIBLE);
			accountFromLimitText = v.findViewById(R.id.limit);
			accountFromLimitText.setVisibility(View.GONE);

			accountToText = x.addListNodeAccount(layout, R.id.account_to, R.string.account_to, R.string.select_account);
			v = ((View) accountToText.getTag());
			accountToBalanceText = v.findViewById(R.id.balance);
			accountToBalanceText.setVisibility(View.INVISIBLE);
			accountToLimitText = v.findViewById(R.id.limit);
			accountToLimitText.setVisibility(View.GONE);
		}
		else {
			accountFromText = x.addListNode(layout, R.id.account_from, R.string.account_from, R.string.select_account);
			accountToText = x.addListNode(layout, R.id.account_to, R.string.account_to, R.string.select_account);
		}
		// payee
		isShowPayee = MyPreferences.isShowPayeeInTransfers();
		if (isShowPayee) {
			createPayeeNode(layout);
		}
		// category
		isShowCategoryInTransfer = MyPreferences.isShowCategoryInTransferScreen();
		if (isShowCategoryInTransfer) {
			categorySelector.createNode(layout, TRANSFER);
		} else {
			categorySelector.createDummyNode();
		}
		// amounts
		rateView.createTransferUI();
	}

	@Override
	protected void updateUIforPreventEditing() {
		boolean enabled = !isPreventEditing();
		if (accountFromText.getTag() instanceof View v) v.setEnabled(enabled);
		if (accountToText.getTag() instanceof View v) v.setEnabled(enabled);
	}

	@Override
	protected void editTransaction(Transaction transaction) {
		if (transaction.fromAccountId > 0) {
			Account fromAccount = db.getAccount(transaction.fromAccountId);
			selectAccount(fromAccount, accountFromText, accountFromBalanceText, accountFromLimitText, false);
			rateView.selectCurrencyFrom(fromAccount.currency);
			rateView.setFromAmount(transaction.fromAmount);
			selectedAccountFromId = transaction.fromAccountId;
		}
		commonEditTransaction(transaction);
		if (transaction.toAccountId > 0) {
			Account toAccount = db.getAccount(transaction.toAccountId);
			selectAccount(toAccount, accountToText, accountToBalanceText, accountToLimitText, false);
			rateView.selectCurrencyTo(toAccount.currency);
			rateView.setToAmount(transaction.toAmount);
			selectedAccountToId = transaction.toAccountId;
		}
		// 轉出帳戶不明時，金額原本會跟著一起消失——setFromAmount 只寫在上面 fromAccountId>0
		// 的分支裡，而 currencyFrom 沒設又會讓「轉入金額」欄被 checkNeedRate 藏起來，
		// 兩個金額欄同時看不到值（AI 解析出金額卻沒對到轉出帳戶時必現）。
		// 先借轉入帳戶的幣別當顯示基準把數字放進去，使用者挑完轉出帳戶再重填（見 selectAccount）。
		if (transaction.fromAccountId <= 0 && transaction.fromAmount != 0) {
			Currency c = rateView.getCurrencyTo();
			if (c != null) {
				rateView.selectCurrencyFrom(c);
			}
			rateView.setFromAmount(transaction.fromAmount);
			pendingFromAmount = transaction.fromAmount;
		}
		selectPayee(transaction.payeeId);
	}

	@Override
	protected boolean onOKClicked() {
		if (selectedAccountFromId == -1) {
			Toast.makeText(this, R.string.select_from_account, Toast.LENGTH_SHORT).show();
			return false;
		}
		if (selectedAccountToId == -1) {
			Toast.makeText(this, R.string.select_to_account, Toast.LENGTH_SHORT).show();
			return false;
		}
		if (selectedAccountFromId == selectedAccountToId) {
			Toast.makeText(this, R.string.select_to_account_differ_from_to_account, Toast.LENGTH_SHORT).show();
			return false;
		}
		if (checkSelectedEntities()) {
			updateTransferFromUI();
			return true;
		}
		return false;
	}

	private void updateTransferFromUI() {
		updateTransactionFromUI(transaction);
		transaction.fromAccountId = selectedAccountFromId;
		transaction.toAccountId = selectedAccountToId;
		transaction.fromAmount = rateView.getFromAmount();
		transaction.toAmount = rateView.getToAmount();
	}

	@Override
	protected void onClick(View v, int id) {
		super.onClick(v, id);
		if (id == R.id.account_from) {
			x.select(this, R.id.account_from, R.string.account, accountCursor, accountAdapter,
					AccountColumns.ID, selectedAccountFromId);
		} else if (id == R.id.account_to) {
			x.select(this, R.id.account_to, R.string.account, accountCursor, accountAdapter,
					AccountColumns.ID, selectedAccountToId);
		}
	}

	@Override
	public void onSelectedPos(int id, int selectedPos) {
		super.onSelectedPos(id, selectedPos);
		if (id == R.id.payee) {
			if (isShowPayee && isRememberLastCategory) {
				selectLastCategoryForPayee(payeeSelector.getSelectedEntityId());
			}
		}
	}

	@Override
	public void onSelectedId(int id, long selectedId) {
		super.onSelectedId(id, selectedId);
		if (id == R.id.account_from) {
			selectFromAccount(selectedId);
		} else if (id == R.id.account_to) {
			selectToAccount(selectedId);
		} else if (id == R.id.payee) {
			if (isRememberLastCategory) {
				selectLastCategoryForPayee(selectedId);
			}
		}
	}

	private void selectFromAccount(long selectedId) {
		selectAccount(selectedId, true);
	}

	private void selectToAccount(long selectedId) {
		Account account = db.getAccount(selectedId);
		if (account != null) {
			selectAccount(account, accountToText, accountToBalanceText, accountToLimitText, false);
			selectedAccountToId = selectedId;
			rateView.selectCurrencyTo(account.currency);
		}
	}

	/**
	 * 轉帳特有：基底的 selectAccount() 在這裡只管「轉出」，轉入帳戶要自己接。
	 * 轉入金額也要一併設，否則只有轉出側被更新。
	 * override 就地套用層（applyAiSupplementFields）而非 applyAiSupplement——後者是型別切換
	 * 的 orchestrator（final），這裡只補轉帳特有欄位。
	 */
	@Override
	protected void applyAiSupplementFields(tw.tib.financisto.ai.ParsedTransaction t) {
		super.applyAiSupplementFields(t);
		if (t.toAccount.resolved()) {
			selectToAccount(t.toAccount.id);
		}
		if (t.amount != null) {
			Currency c = rateView.getCurrencyTo();
			int scale = c != null ? c.getScale() : 2;
			rateView.setToAmount(Math.round(Math.abs(t.amount) * Math.pow(10, scale)));
		}
	}

	@Override
	protected Account selectAccount(long accountId, boolean selectLast) {
		Account account = db.getAccount(accountId);
		if (account != null) {
			selectAccount(account, accountFromText, accountFromBalanceText, accountFromLimitText, selectLast);
			selectedAccountFromId = accountId;
			rateView.selectCurrencyFrom(account.currency);
			// 剛才是用轉入幣別暫顯的金額：現在有真正的轉出幣別了，依它重填一次（scale 可能不同）。
			// 使用者已經自己改過金額就不要蓋掉——值對不上就當作被改過。
			if (pendingFromAmount != 0) {
				if (rateView.getFromAmount() == pendingFromAmount) {
					rateView.setFromAmount(pendingFromAmount);
				}
				pendingFromAmount = 0;
			}
		}
		return account;
	}

	/**
	 * 轉帳頁的「當前帳戶」＝轉出帳戶。基底的 selectedAccount 在轉帳頁不設（改用 selectedAccountFromId），
	 * 補充模式切換型別/餘額時要靠這個抓當前帳戶，否則沒講帳戶就抓成 -1。
	 */
	@Override
	protected long getSelectedAccountId() {
		return selectedAccountFromId;
	}

	protected void selectAccount(Account account, TextView accountText, TextView accountBalanceText, TextView accountLimitText, boolean selectLast) {
		u.setAccountTitleBalance(account, accountText, accountBalanceText, accountLimitText);
		if (selectLast) {
			if (isRememberLastAccount) {
				selectToAccount(account.lastAccountId);
			}
			if (!isShowPayee && isShowCategoryInTransfer && isRememberLastCategory) {
				categorySelector.selectCategory(account.lastCategoryId);
			}
		}
	}

	/**
	 * 補充模式的表單狀態：轉帳頁用 selectedAccountFromId/ToId（基底 selectedAccount 不設），
	 * 故整段自建：型別＝轉帳、轉出/轉入帳戶、金額（轉出帳戶 scale）；分割不適用轉帳。
	 */
	@Override
	protected String buildAiFormStateContext() {
		StringBuilder sb = new StringBuilder(AI_FORM_STATE_HEADER);
		sb.append("型別：轉帳\n");
		Account from = selectedAccountFromId > 0 ? db.getAccount(selectedAccountFromId) : null;
		Account to = selectedAccountToId > 0 ? db.getAccount(selectedAccountToId) : null;
		if (from != null) sb.append("轉出帳戶：").append(from.title).append('\n');
		if (to != null) sb.append("轉入帳戶：").append(to.title).append('\n');
		long minor = rateView.getFromAmount();
		if (minor != 0) {
			sb.append("金額：").append(aiFormatMajor(Math.abs(minor), selectedAccountFromId)).append('\n');
		}
		String cat = aiCategoryName(categorySelector.getSelectedCategoryId());
		if (cat != null) sb.append("分類：").append(cat).append('\n');
		appendAiNoteProjectLines(sb);
		return sb.toString();
	}

}
