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
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TextView;
import android.widget.Toast;
import greendroid.widget.QuickActionGrid;
import greendroid.widget.QuickActionWidget;
import tw.tib.financisto.R;
import tw.tib.financisto.model.Account;
import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.Currency;
import tw.tib.financisto.ai.ParsedTransaction;
import tw.tib.financisto.model.MyEntity;
import tw.tib.financisto.model.Payee;
import tw.tib.financisto.model.Transaction;
import tw.tib.financisto.model.TransactionStatus;
import tw.tib.financisto.utils.CurrencyCache;
import tw.tib.financisto.utils.MyPreferences;
import tw.tib.financisto.utils.SplitAdjuster;
import tw.tib.financisto.utils.TransactionTitleUtils;
import tw.tib.financisto.utils.TransactionUtils;
import tw.tib.financisto.utils.Utils;

import java.io.*;
import java.util.*;

import static tw.tib.financisto.utils.Utils.isNotEmpty;

import com.google.api.client.util.Lists;

public class TransactionActivity extends AbstractTransactionActivity {
    private static final String TAG = "TransactionActivity";

    public static final String CURRENT_BALANCE_EXTRA = "accountCurrentBalance";
    /**
     * 調整餘額模式下，預先填入的「新餘額」（minor units）。
     * 給 AI 一句話記帳用（「甲銀行剩下300」）：CURRENT_BALANCE_EXTRA 帶入目前餘額，
     * 這個帶入目標餘額，差額由既有的 listener 自動算出。
     */
    public static final String NEW_BALANCE_EXTRA = "accountNewBalance";
    public static final String AMOUNT_EXTRA = "accountAmount";
    public static final String ACTIVITY_STATE = "ACTIVITY_STATE";
    public static final String SPLIT_PARENT_ACCOUNT = "splitParentAccount";

    private static final int SPLIT_REQUEST = 5001;

    private final Currency currencyAsAccount = new Currency();

    private long idSequence = 0;
    private final IdentityHashMap<View, Transaction> viewToSplitMap = new IdentityHashMap<>();
    /** store splits in added/transaction ID order to keep display order stable */
    private final TreeMap<Long, Transaction> splits = new TreeMap<>();

    private TextView accountBalanceText;
    private TextView accountLimitText;

    private TextView differenceText;
    private boolean isUpdateBalanceMode = false;
    private long currentBalance;

    private LinearLayout splitsLayout;
    private LinearLayout unsplitContainer;
    private TextView unsplitAmountText;
    private TextView currencyText;
    private TransactionTitleUtils transactionTitleUtils;
    private int colors[];

    private QuickActionWidget unsplitActionGrid;
    private long selectedOriginCurrencyId = -1;

    private boolean isQuickMenuEnabledForSplit;
    private QuickActionWidget splitActionGrid;
    private Transaction selectedSplit;

    public TransactionActivity() {
    }

    protected int getLayoutId() {
        return MyPreferences.isUseFixedLayout() ? R.layout.transaction_fixed : R.layout.transaction_free;
    }

    @Override
    protected void internalOnCreate() {
        Intent intent = getIntent();
        if (intent != null) {
            if (intent.hasExtra(CURRENT_BALANCE_EXTRA)) {
                currentBalance = intent.getLongExtra(CURRENT_BALANCE_EXTRA, 0);
                isUpdateBalanceMode = true;
            } else if (intent.hasExtra(AMOUNT_EXTRA)) {
                currentBalance = intent.getLongExtra(AMOUNT_EXTRA, 0);
            }
        }
        if (transaction.isTemplateLike()) {
            setTitle(transaction.isTemplate() ? R.string.transaction_template : R.string.transaction_schedule);
            if (transaction.isTemplate()) {
                dateText.setEnabled(false);
                timeText.setEnabled(false);
            }
        }
        prepareUnsplitActionGrid();
        prepareSplitActionGrid();
        currencyAsAccount.name = getString(R.string.original_currency_as_account);
        transactionTitleUtils = new TransactionTitleUtils(this, MyPreferences.isColorizeBlotterItem());
        colors = Utils.getTransactionStatusColors(this);
        isQuickMenuEnabledForSplit = MyPreferences.isQuickMenuEnabledForSplit();
    }

    private void prepareUnsplitActionGrid() {
        unsplitActionGrid = new QuickActionGrid(this);
        unsplitActionGrid.addQuickAction(new MyQuickAction(this, R.drawable.ic_action_add, R.string.transaction));
        unsplitActionGrid.addQuickAction(new MyQuickAction(this, R.drawable.ic_action_transfer, R.string.transfer));
        unsplitActionGrid.addQuickAction(new MyQuickAction(this, R.drawable.ic_action_tick, R.string.unsplit_adjust_amount));
        unsplitActionGrid.addQuickAction(new MyQuickAction(this, R.drawable.ic_action_tick, R.string.unsplit_adjust_evenly));
        unsplitActionGrid.addQuickAction(new MyQuickAction(this, R.drawable.ic_action_tick, R.string.unsplit_adjust_last));
        unsplitActionGrid.setOnQuickActionClickListener(unsplitActionListener);
    }

    private QuickActionWidget.OnQuickActionClickListener unsplitActionListener = (widget, position, action) -> {
        switch (position) {
            case 0:
                createSplit(false);
                break;
            case 1:
                createSplit(true);
                break;
            case 2:
                unsplitAdjustAmount();
                break;
            case 3:
                unsplitAdjustEvenly();
                break;
            case 4:
                unsplitAdjustLast();
                break;
        }
    };

    private void prepareSplitActionGrid() {
        splitActionGrid = new QuickActionGrid(this);
        splitActionGrid.addQuickAction(new MyQuickAction(this, R.drawable.ic_action_status_cleared, MyQuickAction.NO_FILTER, R.string.clear));
        splitActionGrid.addQuickAction(new MyQuickAction(this, R.drawable.ic_action_edit, R.string.edit));
        splitActionGrid.addQuickAction(new MyQuickAction(this, R.drawable.ic_action_status_reconciled, MyQuickAction.NO_FILTER, R.string.reconcile));
        splitActionGrid.setOnQuickActionClickListener(splitActionListener);
    }

    private QuickActionWidget.OnQuickActionClickListener splitActionListener = (widget, position, action) -> {
        int titleId = ((MyQuickAction) action).titleId;
        if (titleId == R.string.edit) {
            editExistingSplit(selectedSplit);
        }
        else if (titleId == R.string.clear) {
            selectedSplit.status = TransactionStatus.CL;
            addOrEditSplit(selectedSplit);
        }
        else if (titleId == R.string.reconcile) {
            selectedSplit.status = TransactionStatus.RC;
            addOrEditSplit(selectedSplit);
        }
    };

    private void unsplitAdjustAmount() {
        long splitAmount = calculateSplitAmount();
        rateView.setFromAmount(splitAmount);
        updateUnsplitAmount();
    }

    private void unsplitAdjustEvenly() {
        long unsplitAmount = calculateUnsplitAmount();
        if (unsplitAmount != 0) {
            List<Transaction> splits = new ArrayList<>(viewToSplitMap.values());
            SplitAdjuster.adjustEvenly(splits, unsplitAmount);
            updateSplits();
        }
    }

    private void unsplitAdjustLast() {
        long unsplitAmount = calculateUnsplitAmount();
        if (unsplitAmount != 0) {
            Transaction latestTransaction = null;
            for (Transaction t : splits.values()) {
                if (latestTransaction == null || latestTransaction.id > t.id) {
                    latestTransaction = t;
                }
            }
            if (latestTransaction != null) {
                SplitAdjuster.adjustSplit(latestTransaction, unsplitAmount);
                updateSplits();
            }
        }
    }

    private void updateSplits() {
        for (Map.Entry<View, Transaction> entry : viewToSplitMap.entrySet()) {
            View v = entry.getKey();
            Transaction split = entry.getValue();
            setSplitData(v, split);
        }
        updateUnsplitAmount();
    }

    @Override
    protected void fetchCategories() {
        categorySelector.fetchCategories(!isUpdateBalanceMode);
    }

    /** 補充模式切換型別時要知道是否在調整餘額模式（切出時金額不沿用「新餘額」）。 */
    @Override
    protected boolean isBalanceAdjustMode() {
        return isUpdateBalanceMode;
    }

    @Override
    protected void createListNodes(LinearLayout layout) {
        //account
        if (isShowAccountBalanceOnSelector) {
            accountText = x.addListNodeAccount(layout, R.id.account, R.string.account, R.string.select_account);
            View v = ((View) accountText.getTag());
            accountBalanceText = v.findViewById(R.id.balance);
            accountBalanceText.setVisibility(View.INVISIBLE);
            accountLimitText = v.findViewById(R.id.limit);
            accountLimitText.setVisibility(View.GONE);
        }
        else {
            accountText = x.addListNode(layout, R.id.account, R.string.account, R.string.select_account);
        }
        //payee
        isShowPayee = MyPreferences.isShowPayee();
        if (isShowPayee) {
            createPayeeNode(layout);
        }
        //category
        categorySelector.createNode(layout, CategorySelector.SelectorType.TRANSACTION);
        //amount
        if (!isUpdateBalanceMode && MyPreferences.isShowCurrency()) {
            currencyText = x.addListNode(layout, R.id.original_currency, R.string.currency, R.string.original_currency_as_account);
        } else {
            currencyText = new TextView(this);
        }
        rateView.createTransactionUI();
        // difference
        if (isUpdateBalanceMode) {
            differenceText = x.addInfoNode(layout, -1, R.string.difference, "0");
            rateView.setFromAmount(currentBalance);
            rateView.setAmountFromChangeListener((oldAmount, newAmount) -> {
                long balanceDifference = newAmount - currentBalance;
                u.setAmountText(differenceText, rateView.getCurrencyFrom(), balanceDifference, true);
            });
            if (currentBalance > 0) {
                rateView.setIncome();
            } else {
                rateView.setExpense();
            }
            // AI 一句話記帳（「甲銀行剩下300」）：預填講出來的新餘額。
            // 必須放在上面的 setIncome/setExpense 之後才不會被蓋掉，且正負號要依「新」
            // 餘額決定（AmountInput.setAmount 內部取絕對值，符號由 income/expense 決定）——
            // 依舊餘額判斷的話，負餘額帳戶(如信用卡)會把差額算反。
            // 差額不用自己算：setFromAmount 會觸發上面註冊的 listener。
            if (getIntent() != null && getIntent().hasExtra(NEW_BALANCE_EXTRA)) {
                long newBalance = getIntent().getLongExtra(NEW_BALANCE_EXTRA, currentBalance);
                if (newBalance >= 0) {
                    rateView.setIncome();
                } else {
                    rateView.setExpense();
                }
                rateView.setFromAmount(newBalance);
            }
        } else {
            if (currentBalance > 0) {
                rateView.setIncome();
            } else {
                rateView.setExpense();
            }
            createSplitsLayout(layout);
            rateView.setAmountFromChangeListener((oldAmount, newAmount) -> updateUnsplitAmount());
        }
    }

    private void createSplitsLayout(LinearLayout layout) {
        splitsLayout = new LinearLayout(this);
        splitsLayout.setOrientation(LinearLayout.VERTICAL);
        unsplitContainer = new LinearLayout(this);
        splitsLayout.addView(unsplitContainer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(splitsLayout, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void addOrRemoveSplits() {
        if (splitsLayout == null) {
            return;
        }
        if (categorySelector.isSplitCategorySelected()) {
            View v = x.addNodeUnsplit(unsplitContainer);
            unsplitAmountText = v.findViewById(R.id.data);
            unsplitAmountText.setTag(v);
            updateUnsplitAmount();
        } else {
            resetSplitsLayout();
        }
    }

    protected void resetSplitsLayout() {
        splitsLayout.removeAllViews();
        unsplitContainer = new LinearLayout(this);
        splitsLayout.addView(unsplitContainer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void updateUnsplitAmount() {
        if (unsplitAmountText != null) {
            long amountDifference = calculateUnsplitAmount();
            u.setAmountText(unsplitAmountText, rateView.getCurrencyFrom(), amountDifference, false);
        }
    }

    private long calculateUnsplitAmount() {
        long splitAmount = calculateSplitAmount();
        return rateView.getFromAmount() - splitAmount;
    }

    private long calculateSplitAmount() {
        long amount = 0;
        for (Transaction split : splits.values()) {
            if (split.fromAccountId == getSelectedAccountId()) {
                amount += split.fromAmount;
            }
            else {
                amount += split.toAmount;
            }
        }
        return amount;
    }

    protected void switchIncomeExpenseButton(Category category) {
        if (!isUpdateBalanceMode) {
            if (rateView.getFromAmount() == 0) {
                if (category.isIncome()) {
                    rateView.setIncome();
                } else {
                    rateView.setExpense();
                }
            }
        }
    }

    @Override
    protected boolean onOKClicked() {
        if (checkSelectedAccount() && checkUnsplitAmount() && checkSelectedEntities()) {
            updateTransactionFromUI();
            return true;
        }
        return false;
    }

    private boolean checkSelectedAccount() {
        return checkSelectedId(getSelectedAccountId(), R.string.select_account);
    }

    private boolean checkUnsplitAmount() {
        if (categorySelector.isSplitCategorySelected()) {
            long unsplitAmount = calculateUnsplitAmount();
            if (unsplitAmount != 0) {
                Toast.makeText(this, R.string.unsplit_amount_greater_than_zero, Toast.LENGTH_LONG).show();
                return false;
            }
        }
        return true;
    }

    @Override
    protected void updateUIforPreventEditing() {
        boolean enabled = !isPreventEditing();
        if (accountText.getTag() instanceof View v) v.setEnabled(enabled);
        if (currencyText.getTag() instanceof View v) v.setEnabled(enabled);
        if (unsplitAmountText != null && unsplitAmountText.getTag() instanceof View v) v.setEnabled(enabled);
        for (View v : viewToSplitMap.keySet()) {
            v.setEnabled(enabled);
        }
    }

    @Override
    protected void editTransaction(Transaction transaction) {
        selectAccount(transaction.fromAccountId, false);
        commonEditTransaction(transaction);
        selectCurrency(transaction);
        fetchSplits();
        selectPayee(transaction.payeeId);
    }

    private void selectCurrency(Transaction transaction) {
        if (transaction.originalCurrencyId > 0) {
            selectOriginalCurrency(transaction.originalCurrencyId);
            rateView.setFromAmount(transaction.originalFromAmount);
            rateView.setToAmount(transaction.fromAmount);
        } else {
            selectAccountCurrency();
            if (transaction.fromAmount != 0) {
                rateView.setFromAmount(transaction.fromAmount);
            }
        }
    }

    private void fetchSplits() {
        // 複製一筆時 transaction.id 已被重設成 -1，用它重查會抓不到子項；此時改用
        // getTransaction 當初載入好的 transaction.splits（複製分割交易、AI 分割 draft 都靠這條）。
        List<Transaction> splits = (transaction.id > 0)
                ? db.getSplitsForTransaction(transaction.id)
                : (transaction.splits != null ? transaction.splits : new ArrayList<>());
        for (Transaction split : splits) {
            split.id = --idSequence;
            split.categoryAttributes = db.getAllAttributesForTransaction(split.id);
            if (split.originalCurrencyId > 0) {
                split.fromAmount = split.originalFromAmount;
            }
            addOrEditSplit(split);
        }
    }

    private void updateTransactionFromUI() {
        updateTransactionFromUI(transaction);
        transaction.fromAccountId = selectedAccount.id;
        long amount = rateView.getFromAmount();
        if (isUpdateBalanceMode) {
            amount -= currentBalance;
        }
        transaction.fromAmount = amount;
        updateTransactionOriginalAmount();
        if (categorySelector.isSplitCategorySelected()) {
            transaction.splits = Lists.newArrayList(splits.values());
        } else {
            transaction.splits = null;
        }
    }

    private void updateTransactionOriginalAmount() {
        if (isDifferentCurrency()) {
            transaction.originalCurrencyId = selectedOriginCurrencyId;
            transaction.originalFromAmount = rateView.getFromAmount();
            transaction.fromAmount = rateView.getToAmount();
        } else {
            transaction.originalCurrencyId = 0;
            transaction.originalFromAmount = 0;
        }
    }

    private boolean isDifferentCurrency() {
        return selectedOriginCurrencyId > 0 && selectedOriginCurrencyId != selectedAccount.currency.id;
    }

    @Override
    protected Account selectAccount(long accountId, boolean selectLast) {
        Account a = db.getAccount(accountId);
        categorySelector.setSelectedAccount(a);

        if (a != null) {
            // update account used in split transactions
            // ⚠️ selectedAccount 可為 null（AI 分割 prefill 沒帶帳戶時：fetchSplits 先建好清單、
            // 使用者才第一次選帳戶）——原寫法直接 selectedAccount.id 會 NPE。null 視為 oldId=0，
            // 順便讓「帳戶未定（fromAccountId=0）」的分割子項認養第一次選到的帳戶，unsplit 才算得對。
            long oldId = selectedAccount != null ? selectedAccount.id : 0;
            for (var p : viewToSplitMap.entrySet()) {
                var t = p.getValue();
                var v = p.getKey();

                if (oldId > 0 &&
                    ((t.fromAccountId == oldId && t.toAccountId == a.id) ||
                     (t.fromAccountId == a.id && t.toAccountId == oldId)))
                {
                    // will become self transfer, just delete it
                    deleteSplit(v);
                }
                else {
                    if (t.fromAccountId == oldId) {
                        t.fromAccountId = a.id;
                    }
                    // toAccountId 只有轉帳分割才有值；oldId=0 時不能碰（一般分割的 to 本來就是 0）
                    if (oldId > 0 && t.toAccountId == oldId) {
                        t.toAccountId = a.id;
                    }
                    setSplitData(v, t);
                }
            }
            if (!viewToSplitMap.isEmpty()) {
                updateUnsplitAmount();   // 子項帳戶剛被 remap，未分配金額要跟著重算
            }

            u.setAccountTitleBalance(a, accountText, accountBalanceText, accountLimitText);

            // 調整餘額模式下，差額＝新餘額 − 該帳戶目前餘額。currentBalance 原本只在 onCreate
            // 從 intent 讀一次，換帳戶時沒跟著換，差額就會拿舊帳戶的餘額去減＝算錯。
            // （原生就有的問題，不是 AI 那條路帶進來的。）
            // selectedAccount == null＝第一次帶入，intent 給的 currentBalance 本來就是對的
            if (isUpdateBalanceMode && selectedAccount != null && a.id != selectedAccount.id) {
                currentBalance = a.totalAmount;
                if (differenceText != null) {
                    u.setAmountText(differenceText, rateView.getCurrencyFrom(),
                            rateView.getFromAmount() - currentBalance, true);
                }
            }

            selectedAccount = a;

            if (selectLast && !isShowPayee && isRememberLastCategory) {
                categorySelector.selectCategory(a.lastCategoryId);
            }
        }
        if (selectedOriginCurrencyId > 0) {
            selectOriginalCurrency(selectedOriginCurrencyId);
        }
        else {
            selectAccountCurrency();
        }
        return a;
    }

    @Override
    protected void onClick(View v, int id) {
        if (isPreventEditing()) return;
        super.onClick(v, id);
        switch (id) {
            case R.id.unsplit_action:
                unsplitActionGrid.show(v);
                break;
            case R.id.add_split:
                createSplit(false);
                break;
            case R.id.add_split_transfer:
                if (selectedOriginCurrencyId > 0) {
                    Toast.makeText(this, R.string.split_transfer_not_supported_yet, Toast.LENGTH_LONG).show();
                    break;
                }
                createSplit(true);
                break;
            case R.id.delete_split:
                Log.d(TAG, "deleteSplit");
                View parentView = (View) v.getParent().getParent();
                deleteSplit(parentView);
                break;
            case R.id.original_currency:
                List<Currency> currencies = db.getAllCurrenciesList();
                currencies.add(0, currencyAsAccount);
                ListAdapter adapter = TransactionUtils.createCurrencyAdapter(this, currencies);
                int selectedPos = MyEntity.indexOf(currencies, selectedOriginCurrencyId);
                x.selectItemId(this, R.id.currency, R.string.currency, adapter, selectedPos);
                break;
        }
        Transaction split = viewToSplitMap.get(v);
        if (split != null) {
            if (isQuickMenuEnabledForSplit) {
                selectedSplit = split;
                splitActionGrid.show(v);
            }
            else {
                editExistingSplit(split);
            }
        }
    }

    private void editExistingSplit(Transaction split) {
        if (split.fromAccountId == getSelectedAccountId()) {
            split.unsplitAmount = split.fromAmount + calculateUnsplitAmount();
        }
        else {
            split.unsplitAmount = split.toAmount + calculateUnsplitAmount();
        }
        editSplit(split, split.toAccountId > 0 ? SplitTransferActivity.class : SplitTransactionActivity.class);
    }

    @Override
    public void onSelectedPos(int id, int selectedPos) {
        super.onSelectedPos(id, selectedPos);
        if (id == R.id.payee) {
            if (isRememberLastCategory && !categorySelector.isSplitCategorySelected()) {
                selectLastCategoryForPayee(payeeSelector.getSelectedEntityId());
            }
        }
    }

    @Override
    public void onSelectedId(int id, long selectedId) {
        super.onSelectedId(id, selectedId);
        if (id == R.id.currency) {
            selectOriginalCurrency(selectedId);
        } else if (id == R.id.payee) {
            if (isRememberLastCategory) {
                selectLastCategoryForPayee(selectedId);
            }
        }
    }

    private void selectOriginalCurrency(long selectedId) {
        long prevCurrencyFromId = rateView.getCurrencyFromId();
        selectedOriginCurrencyId = selectedId;
        if (selectedId == -1) {
            if (selectedAccount != null) {
                if (selectedAccount.currency.id == rateView.getCurrencyToId()) {
                    rateView.setFromAmount(rateView.getToAmount());
                }
            }
            selectAccountCurrency();
        } else {
            long fromAmount = rateView.getFromAmount();
            long toAmount = rateView.getToAmount();
            Currency currency = CurrencyCache.getCurrency(selectedId);
            rateView.selectCurrencyFrom(currency);
            if (selectedAccount != null) {
                if (selectedId == selectedAccount.currency.id) {
                    if (selectedId == rateView.getCurrencyToId()) {
                        rateView.setFromAmount(toAmount);
                    }
                    selectAccountCurrency();
                    return;
                }
                else {
                    if (prevCurrencyFromId == selectedAccount.currency.id || prevCurrencyFromId == 0) {
                        rateView.clearFromAmount();
                        rateView.setToAmount(fromAmount);
                    }
                }
                rateView.selectCurrencyTo(selectedAccount.currency);
            }
            currencyText.setText(currency.name);
        }
    }

    private void selectAccountCurrency() {
        rateView.selectSameCurrency(selectedAccount != null ? selectedAccount.currency : Currency.EMPTY);
        currencyText.setText(R.string.original_currency_as_account);
    }

    private void createSplit(boolean asTransfer) {
        Transaction split = new Transaction();
        split.id = --idSequence;
        split.fromAccountId = getSelectedAccountId();
        split.fromAmount = split.unsplitAmount = calculateUnsplitAmount();
        split.originalCurrencyId = selectedOriginCurrencyId;
        editSplit(split, asTransfer ? SplitTransferActivity.class : SplitTransactionActivity.class);
    }

    private void editSplit(Transaction split, Class splitActivityClass) {
        Intent intent = new Intent(this, splitActivityClass);
        Log.d(TAG, "editSplit id=" + split.id);
        split.toIntentAsSplit(intent);
        intent.putExtra(SPLIT_PARENT_ACCOUNT, getSelectedAccountId());
        startActivityForResult(intent, SPLIT_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SPLIT_REQUEST) {
            if (resultCode == RESULT_OK) {
                Transaction split = Transaction.fromIntentAsSplit(data);
                addOrEditSplit(split);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.d("Financisto", "onSaveInstanceState");
        try {
            if (categorySelector.isSplitCategorySelected()) {
                Log.d("Financisto", "Saving splits...");
                ActivityState state = new ActivityState();
                state.categoryId = categorySelector.getSelectedCategoryId();
                state.idSequence = idSequence;
                state.splits = Lists.newArrayList(splits.values());
                try (ByteArrayOutputStream s = new ByteArrayOutputStream()) {
                    ObjectOutputStream out = new ObjectOutputStream(s);
                    out.writeObject(state);
                    outState.putByteArray(ACTIVITY_STATE, s.toByteArray());
                }
            }
        } catch (IOException e) {
            Log.e("Financisto", "Unable to save state", e);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        Log.d("Financisto", "onRestoreInstanceState");
        byte[] bytes = savedInstanceState.getByteArray(ACTIVITY_STATE);
        if (bytes != null) {
            try {
                try (ByteArrayInputStream s = new ByteArrayInputStream(bytes)) {
                    ObjectInputStream in = new ObjectInputStream(s);
                    ActivityState state = (ActivityState) in.readObject();
                    if (state.categoryId == Category.SPLIT_CATEGORY_ID) {
                        Log.d("Financisto", "Restoring splits...");
                        viewToSplitMap.clear();
                        splits.clear();
                        resetSplitsLayout();
                        idSequence = state.idSequence;
                        categorySelector.selectCategory(state.categoryId);
                        for (Transaction split : state.splits) {
                            addOrEditSplit(split);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("Financisto", "Unable to restore state", e);
            }
        }
    }

    private void addOrEditSplit(Transaction split) {
        View v = findView(split);
        if (v == null) {
            v = x.addSplitNodeMinus(splitsLayout, R.id.edit_split, R.id.delete_split, R.string.split, "");
        }
        setSplitData(v, split);
        viewToSplitMap.put(v, split);
        splits.put(Math.abs(split.id), split);
        updateUnsplitAmount();
    }

    private View findView(Transaction split) {
        for (Map.Entry<View, Transaction> entry : viewToSplitMap.entrySet()) {
            Transaction s = entry.getValue();
            if (s.id == split.id) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 補充模式：這句話有帶分割就把本筆改成分割交易、套上各份；否則走一般欄位補充。
     * 分割是 TransactionActivity 才有的能力（TransferActivity 無），故在此 override。
     */
    @Override
    protected void applyAiSupplementFields(ParsedTransaction t) {
        if (t.hasSplits() && applyAiSplits(t)) {
            return;
        }
        super.applyAiSupplementFields(t);
    }

    /**
     * 把本筆改成分割交易並套上 AI 各份：共用欄位（帳戶/日期/專案）照套，分類改走 SPLIT、
     * 父金額＝各份加總（unsplit 歸零）。回 true＝至少套進一份；沒有任何有效份（金額都不明）回 false
     * 交回一般流程、不動現有表單。順序比照 onRestoreInstanceState 重建分割。
     */
    private boolean applyAiSplits(ParsedTransaction t) {
        int valid = 0;
        for (ParsedTransaction.Split s : t.splits) if (s.amount != null) valid++;
        if (valid == 0) return false;

        // 共用欄位先套（分類/金額改由分割決定，不走這裡）
        if (t.account.resolved()) selectAccount(t.account.id, false);
        if (t.project.resolved()) projectSelector.selectEntity(t.project.id);
        Long spoken = t.resolveDateTimeMillis();
        if (spoken != null) setDateTime(spoken);

        // 重建分割清單
        boolean wasSplit = categorySelector.isSplitCategorySelected();
        viewToSplitMap.clear();
        splits.clear();
        resetSplitsLayout();
        categorySelector.selectCategory(Category.SPLIT_CATEGORY_ID, false);
        // selectCategory 對「同一個分類」是 no-op、不會觸發 listener → resetSplitsLayout 清掉的
        // 「未分配」node 不會被 addOrRemoveSplits 補回。已是分割時要自己補（重講分割的情境）。
        if (wasSplit) {
            addOrRemoveSplits();
        }

        long accountId = getSelectedAccountId();
        Currency cur = rateView.getCurrencyFrom();
        int scale = cur != null ? cur.getScale() : 2;
        // 正負號：明講 income 就 income；補充模式 type 通常 null → 繼承表單現有符號
        // （在收入交易上講分割，子項也要正號，否則與父金額打架、未分配永不為 0）
        boolean income = t.isIncome()
                || (t.transactionType == null && rateView.getFromAmount() > 0);
        long total = 0;
        for (ParsedTransaction.Split s : t.splits) {
            if (s.amount == null) continue;
            Transaction split = new Transaction();
            split.id = --idSequence;
            split.fromAccountId = accountId;
            split.originalCurrencyId = selectedOriginCurrencyId;
            long minor = Math.round(Math.abs(s.amount) * Math.pow(10, scale));
            split.fromAmount = income ? minor : -minor;
            if (s.category.resolved()) split.categoryId = s.category.id;
            if (s.note != null && !s.note.trim().isEmpty()) split.note = s.note;
            addOrEditSplit(split);
            total += split.fromAmount;
        }
        rateView.setFromAmount(total);   // 父金額＝加總，unsplit 歸零
        return true;
    }

    /**
     * 補充模式的表單狀態：交易頁多兩種型態要單獨描述——
     * (1) 調整餘額模式：金額是「新餘額」不是變動，只給型別＋帳戶，避免模型把它當交易金額拆分。
     * (2) 分割交易：逐份列出「分類 金額（備註）」，模型才能在再補一份時回報「含現有各份」的完整 splits。
     * 其餘（單一分類）走基底。
     */
    @Override
    protected String buildAiFormStateContext() {
        if (isUpdateBalanceMode) {
            StringBuilder sb = new StringBuilder(AI_FORM_STATE_HEADER);
            sb.append("型別：調整餘額\n");
            appendAiAccountLine(sb);
            return sb.toString();
        }
        if (categorySelector.isSplitCategorySelected() && !splits.isEmpty()) {
            StringBuilder sb = new StringBuilder(AI_FORM_STATE_HEADER);
            sb.append("型別：").append(aiFormStateTypeLabel()).append('\n');
            appendAiAccountLine(sb);
            sb.append("這是分割交易，目前各份（分類 金額）：\n");
            long accountId = getSelectedAccountId();
            for (Transaction s : splits.values()) {
                String cat = aiCategoryName(s.categoryId);
                sb.append("- ").append(cat != null ? cat : "未分類")
                        .append(' ').append(aiFormatMajor(Math.abs(s.fromAmount), accountId));
                if (s.note != null && !s.note.trim().isEmpty()) {
                    sb.append("（").append(s.note.trim()).append("）");
                }
                sb.append('\n');
            }
            appendAiNoteProjectLines(sb);
            return sb.toString();
        }
        return super.buildAiFormStateContext();
    }

    private void setSplitData(View v, Transaction split) {
        TextView label = v.findViewById(R.id.label);
        TextView data = v.findViewById(R.id.data);
        TextView indicator = v.findViewById(R.id.indicator);

        indicator.setBackgroundColor(colors[split.status.ordinal()]);

        setSplitData(split, label, data);
    }

    private void setSplitData(Transaction split, TextView label, TextView data) {
        if (split.isTransfer()) {
            setSplitDataTransfer(split, label, data);
        } else {
            setSplitDataTransaction(split, label, data);
        }
    }

    private void setSplitDataTransaction(Transaction split, TextView label, TextView data) {
        Category category = db.getCategory(split.categoryId);
        String payee = split.payeeId < 1 ? null : db.get(Payee.class, split.payeeId).title;
        label.setText(transactionTitleUtils.generateTransactionTitle(
                false, payee, null, split.note, null,
                split.categoryId, category.title));
        Currency currency = getCurrency();
        u.setAmountText(data, currency, split.fromAmount, false);
    }

    private String createSplitTransactionTitle(Transaction split) {
        StringBuilder sb = new StringBuilder();
        Category category = db.getCategoryWithParent(split.categoryId);
        sb.append(category.title);
        if (isNotEmpty(split.note)) {
            sb.append(" (").append(split.note).append(")");
        }
        return sb.toString();
    }

    private void setSplitDataTransfer(Transaction split, TextView label, TextView data) {
        Account fromAccount = db.getAccount(split.fromAccountId);
        Account toAccount = db.getAccount(split.toAccountId);
        Category category = db.getCategory(split.categoryId);
        label.setText(transactionTitleUtils.generateTransactionTitle(
                true, null, u.getTransferTitleText(fromAccount, toAccount),
                split.note, null, split.categoryId, split.categoryId == 0 ? "" : category.title));
        //u.setTransferTitleText(label, fromAccount, toAccount);
        u.setTransferAmountText(data, fromAccount.currency, split.fromAmount, toAccount.currency, split.toAmount);
    }

    private void deleteSplit(View v) {
        Transaction split = viewToSplitMap.remove(v);
        if (split != null) {
            splits.remove(Math.abs(split.id));
            removeSplitView(v);
            updateUnsplitAmount();
        }
    }

    private void removeSplitView(View v) {
        splitsLayout.removeView(v);
        View dividerView = (View) v.getTag();
        if (dividerView != null) {
            splitsLayout.removeView(dividerView);
        }
    }

    private Currency getCurrency() {
        if (selectedOriginCurrencyId > 0) {
            return CurrencyCache.getCurrency(selectedOriginCurrencyId);
        }
        if (selectedAccount != null) {
            return selectedAccount.currency;
        }
        return Currency.EMPTY;
    }

    private static class ActivityState implements Serializable {
        public long categoryId;
        public long idSequence;
        public List<Transaction> splits;
    }


}
