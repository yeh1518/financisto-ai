/*
 * Copyright (c) 2012 Denis Solonenko.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 */

package tw.tib.financisto.activity;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import androidx.core.util.Pair;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.*;

import tw.tib.financisto.Application;
import tw.tib.financisto.R;
import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.db.DatabaseHelper;
import tw.tib.financisto.model.Account;
import tw.tib.financisto.model.Attribute;
import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.MultiChoiceItem;
import tw.tib.financisto.model.MyEntity;
import tw.tib.financisto.model.Transaction;
import tw.tib.financisto.model.TransactionAttribute;
import tw.tib.financisto.utils.ArrUtils;
import tw.tib.financisto.utils.TransactionUtils;
import tw.tib.financisto.utils.Utils;
import tw.tib.financisto.view.AttributeView;
import tw.tib.financisto.view.AttributeViewFactory;

import java.util.*;

import static java.util.Objects.requireNonNull;
import static tw.tib.financisto.model.Category.NO_CATEGORY_ID;
import static tw.tib.financisto.model.Category.SPLIT_CATEGORY_ID;

public class CategorySelector<A extends AbstractActivity> {
    private final String TAG = "CategorySelector";

    private final A activity;
    private final DatabaseAdapter db;
    private final ActivityLayout x;

    private View node = null;
    private boolean loaded = false;
    private long toSelectCategoryId = NO_CATEGORY_ID;
    private boolean toSelectLast = true;

    private TextView categoryText;
    private boolean categoryTextIsEmpty = true;
    private AutoCompleteTextView filterAutoCompleteTxt;
    private SimpleCursorAdapter autoCompleteAdapter;
    private Cursor categoryCursor;
    private ListAdapter categoryAdapter;
    private LinearLayout attributesLayout;

    // ===== 篩選專用：包含子分類 =====
    // 分類篩選走 nested set，btw(category_left, left, right) 本來就把整個子樹框進去。
    // 取消勾選時改送 btw(left, left)——只框住這個分類自己那一格，子分類不算。
    // 這一行只有 FILTER 型別建得出來，而且只在「選到的分類裡有人有子分類」時才顯示：
    // 選的全是葉節點時「含不含子分類」沒有意義，多出一個永遠沒作用的勾選框只會讓人猶豫。
    private View includeSubCategoriesRow;
    private CheckBox includeSubCategoriesCheckBox;
    private boolean includeSubCategories = true;
    /** 只是把 UI 同步成目前狀態時用，避免 setChecked 反過來又去重建一次 criterion。 */
    private boolean syncingIncludeSubCategories = false;

    private long selectedCategoryId = NO_CATEGORY_ID;
    private Account selectedAccount;
    private CategorySelectorListener listener;
    private boolean showSplitCategory = true;
    private boolean multiSelect;
    private boolean useMultiChoicePlainSelector;
    private final long excludingSubTreeId;
    private List<Category> categories = Collections.emptyList();
    private int emptyResId;

    private String toCheckCommaIds = null;
    private String[] toCheckStringIds = null;
    private List<Long> toCheckIds = null;

    private boolean enabled = true;

    public CategorySelector(A activity, DatabaseAdapter db, ActivityLayout x) {
        this(activity, db, x, -1);
    }

    public CategorySelector(A activity, DatabaseAdapter db, ActivityLayout x, long exclSubTreeId) {
        this.activity = activity;
        this.db = db;
        this.x = x;
        this.excludingSubTreeId = exclSubTreeId;
    }
    
    
    public void setListener(CategorySelectorListener listener) {
        this.listener = listener;
    }

    public void doNotShowSplitCategory() {
        this.showSplitCategory = false;
    }
    
    public void initMultiSelect() {
        this.multiSelect = true;
        synchronized (this) {
            loaded = false;
        }
        Application.getExecutor().execute(() -> {
            this.categories = db.getCategoriesList(true);
            synchronized (this) {
                loaded = true;
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                if (node != null){
                    if (categoryTextIsEmpty) {
                        categoryText.setText(emptyResId);
                    }
                    node.setEnabled(true);
                }
                if (toCheckStringIds != null) {
                    updateCheckedEntities(toCheckStringIds);
                    fillCategoryInUI();
                    toCheckStringIds = null;
                }
                if (toCheckCommaIds != null) {
                    updateCheckedEntities(toCheckCommaIds);
                    fillCategoryInUI();
                    toCheckCommaIds = null;
                }
                if (toCheckIds != null) {
                    updateCheckedEntities(toCheckIds);
                    fillCategoryInUI();
                    toCheckIds = null;
                }
            });
        });
        this.doNotShowSplitCategory();
    }

    public void setUseMultiChoicePlainSelector() {
        this.useMultiChoicePlainSelector = true;
    }

    public void setEmptyResId(int emptyResId) {
        this.emptyResId = emptyResId;
    }

    public int getEmptyResId() {
        return emptyResId;
    }

    public List<Category> getCategories() {
        return categories;
    }

    public String getCheckedTitles() {
        return MyEntitySelector.getCheckedTitles(categories);
    }

    public String getCheckedIdsAsStr() {
        return MyEntitySelector.getCheckedIdsAsStr(categories);
    }

    public String[] getCheckedCategoryIds() {
        return MyEntitySelector.getCheckedIds(categories);
    }

    public String[] getCheckedCategoryLeafs() {
        LinkedList<String> res = new LinkedList<>();
        for (Category c : categories) {
            if (c.checked) {
                if (c.id == NO_CATEGORY_ID) { // special case as it must include only itself
                    res.add("0");
                    res.add("0");
                } else if (!includeSubCategories && hasSubCategories(c)) {
                    res.add(String.valueOf(c.left));
                    res.add(String.valueOf(c.left));
                } else {
                    res.add(String.valueOf(c.left));
                    res.add(String.valueOf(c.right));
                }
            }
        }
        return ArrUtils.strListToArr(res);
    }

    public void fetchCategories(boolean fetchAll) {
        if (multiSelect) {
            return;
        }
        synchronized (this) {
            loaded = false;
        }
        Application.getExecutor().execute(() -> {
            if (fetchAll) {
                categoryCursor = db.getAllCategories();
            } else {
                if (excludingSubTreeId > 0) {
                    categoryCursor = db.getCategoriesWithoutSubtree(excludingSubTreeId, true);
                } else {
                    categoryCursor = db.getCategories(true);
                }
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                categoryAdapter = TransactionUtils.createCategoryAdapter(db, activity, categoryCursor);
                synchronized (this) {
                    Log.d(TAG, "fetchCategories loaded");
                    loaded = true;
                }
                activity.startManagingCursor(categoryCursor);
                if (node != null){
                    if (categoryTextIsEmpty) {
                        categoryText.setText(emptyResId);
                    }
                    if (enabled) node.setEnabled(true);
                    selectCategory(toSelectCategoryId, toSelectLast);
                }
            });
        });
    }

    public void setNode(TextView textNode) {
        categoryText = textNode;
    }

    public TextView createNode(LinearLayout layout, SelectorType type) {
        final Pair<TextView, AutoCompleteTextView> nodes;
        switch (type) {
            case TRANSACTION:
                setEmptyResId(R.string.select_category);
                nodes = x.addListNodeCategory(layout, R.id.category_filter_toggle);
                break;
            case SPLIT:
            case TRANSFER:
                if (emptyResId <=0) setEmptyResId(R.string.select_category);
                nodes = x.addListNodeWithButtonsAndFilter(layout, R.id.category, R.id.category_add, R.id.category_clear, R.string.category, R.string.loading, R.id.category_filter_toggle);
                break;
            case FILTER:
                if (emptyResId <=0) setEmptyResId(R.string.no_filter);
                nodes = x.addListNodeWithClearButtonAndFilter(layout, R.id.category, R.id.category_clear, R.string.category, R.string.loading, R.id.category_filter_toggle);
                break;
            case PARENT:
                if (emptyResId <=0) setEmptyResId(R.string.select_category);
                nodes = Pair.create(x.addListNode(layout, R.id.category, R.string.parent, R.string.loading), null);
                break;
            default:
                throw new IllegalArgumentException("unknown type: " + type);
        }
        categoryText = nodes.first;
        categoryTextIsEmpty = true;
        filterAutoCompleteTxt = nodes.second;
        node = (View) categoryText.getTag();
        node.setEnabled(false);
        if (type == SelectorType.FILTER) {
            addIncludeSubCategoriesRow(layout);
        }
        return categoryText;
    }

    /** 接在分類欄位下方的那一行小字，靠右。 */
    private void addIncludeSubCategoriesRow(LinearLayout layout) {
        includeSubCategoriesRow = LayoutInflater.from(activity)
                .inflate(R.layout.select_entry_include_sub, layout, false);
        includeSubCategoriesCheckBox = includeSubCategoriesRow.findViewById(R.id.category_include_sub);
        includeSubCategoriesCheckBox.setChecked(includeSubCategories);
        includeSubCategoriesCheckBox.setOnCheckedChangeListener((btn, checked) -> {
            includeSubCategories = checked;
            // 條件本身變了，要讓 activity 用新的範圍重建 criterion
            if (!syncingIncludeSubCategories && listener != null) {
                listener.onCategorySelected(null, false);
            }
        });
        includeSubCategoriesRow.setVisibility(View.GONE);
        // 每個節點後面都跟著一條分隔線（NodeInflater.Builder.create 加的）。插在分隔線之前，
        // 這一行才屬於分類那一格；append 到最後會落在分隔線下面，看起來像下一個欄位的東西。
        layout.addView(includeSubCategoriesRow, layout.indexOfChild(node) + 1);
    }

    /** nested set：葉節點的 right 一定是 left + 1，有子分類的才會更大。 */
    private static boolean hasSubCategories(Category c) {
        // id 0 是整棵樹的根（「未分類」借用它），它的 left/right 框住全部分類，不能當成「有子分類」
        return c.id != NO_CATEGORY_ID && c.right - c.left > 1;
    }

    private void updateIncludeSubCategoriesRow() {
        if (includeSubCategoriesRow == null) return;
        boolean anyParentChecked = false;
        for (Category c : categories) {
            if (c.checked && hasSubCategories(c)) {
                anyParentChecked = true;
                break;
            }
        }
        includeSubCategoriesRow.setVisibility(anyParentChecked ? View.VISIBLE : View.GONE);
    }

    /**
     * 從篩選條件回推勾選狀態。條件是 (left, right) 成對，收起子分類時送的是 (left, left)
     * ——兩值相等就代表當初是收起來的。("0","0") 是「未分類」的特例，不算。
     *
     * 狀態存在 criterion 裡、不另外存一個旗標：篩選會經 Intent / SharedPreferences 來回搬，
     * 多一個旗標就多一個會跟條件對不起來的地方。
     */
    public void applyIncludeSubCategoriesFromFilter(String[] values) {
        boolean collapsed = false;
        if (values != null) {
            for (int i = 0; i + 1 < values.length; i += 2) {
                if (values[i].equals(values[i + 1]) && !"0".equals(values[i])) {
                    collapsed = true;
                    break;
                }
            }
        }
        setIncludeSubCategories(!collapsed);
    }

    private void setIncludeSubCategories(boolean include) {
        includeSubCategories = include;
        if (includeSubCategoriesCheckBox != null && includeSubCategoriesCheckBox.isChecked() != include) {
            syncingIncludeSubCategories = true;
            includeSubCategoriesCheckBox.setChecked(include);
            syncingIncludeSubCategories = false;
        }
    }

    private void initAutoCompleteFilter(final AutoCompleteTextView filterTxt) { // init only after it's toggled
        autoCompleteAdapter = TransactionUtils.createCategoryFilterAdapter(activity, db);
        filterTxt.setInputType(InputType.TYPE_CLASS_TEXT 
                        | InputType.TYPE_TEXT_FLAG_CAP_WORDS 
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        | InputType.TYPE_TEXT_VARIATION_FILTER);
        filterTxt.setThreshold(1);
        filterTxt.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                filterTxt.setAdapter(requireNonNull(autoCompleteAdapter));
                filterTxt.selectAll();
            }
        });
        filterTxt.setOnItemClickListener((parent, view, position, id) -> {
            if (enabled) activity.onSelectedId(R.id.category, id);
            ToggleButton toggleBtn = (ToggleButton) filterTxt.getTag();
            toggleBtn.performClick();
        });
    }

    public void createDummyNode() {
        categoryText = new EditText(activity);
    }

    public void onClick(int id) {
        if (!enabled) return;

        switch (id) {
            case R.id.category: {
                if (useMultiChoicePlainSelector) {
                    x.selectMultiChoice(activity, R.id.category, R.string.categories, categories);
                } else if (!CategorySelectorActivity.pickCategory(activity, multiSelect, selectedCategoryId, selectedAccount, excludingSubTreeId, showSplitCategory)) {
                    x.select(activity, R.id.category, R.string.category, categoryCursor, categoryAdapter,
                        DatabaseHelper.CategoryViewColumns._id.name(), selectedCategoryId);
                    
                }
                break;
            }
            case R.id.category_add: {
                Intent intent = new Intent(activity, CategoryActivity.class);
                activity.startActivityForResult(intent, R.id.category_add);
                break;
            }
            case R.id.category_split:
                selectCategory(Category.SPLIT_CATEGORY_ID);
                break;
            case R.id.category_filter_toggle:
                if (autoCompleteAdapter == null) initAutoCompleteFilter(filterAutoCompleteTxt);
                break;
            case R.id.category_clear:
                clearCategory();
                break;
        }
    }

    private void clearCategory() {
        categoryText.setText(emptyResId);
        selectedCategoryId = NO_CATEGORY_ID;
        for (MyEntity e : categories) e.setChecked(false);
        showHideMinusBtn(false);
        updateIncludeSubCategoriesRow();
        if (listener != null) {
            listener.onCategorySelected(Category.noCategory(), false);
        }
    }

    public void onSelectedId(int id, long selectedId) {
        onSelectedId(id, selectedId, true);
    }

    public void onSelectedId(int id, long selectedId, boolean selectLast) {
        if (id == R.id.category) {
            selectCategory(selectedId, selectLast);
        }
    }

    public void onSelected(int id, List<? extends MultiChoiceItem> ignore) {
        if (id == R.id.category) fillCategoryInUI();
    }

    public void fillCategoryInUI() {
        if (!loaded) {
            return;
        }
        String selected = getCheckedTitles();
        if (Utils.isEmpty(selected)) {
            clearCategory();
        } else {
            synchronized (this) {
                categoryText.setText(selected);
                categoryTextIsEmpty = false;
                showHideMinusBtn(true);
            }
            updateIncludeSubCategoriesRow();
        }
    }
    
    public long getSelectedCategoryId() {
        return selectedCategoryId;
    }

    public void selectCategory(long categoryId) {
        selectCategory(categoryId, true);
    }

    public void selectCategory(long categoryId, boolean selectLast) {
        synchronized (this) {
            if (!loaded) {
                toSelectCategoryId = categoryId;
                this.toSelectLast = selectLast;
                return;
            }
        }
        if (multiSelect) {
            updateCheckedEntities("" + categoryId);
            selectedCategoryId = categoryId;
            fillCategoryInUI();
            if (listener != null) listener.onCategorySelected(null, false);
        } else {
            if (selectedCategoryId != categoryId) {
                Application.getExecutor().execute(() -> {
                    Category category = db.getCategoryWithParent(categoryId);
                    List<String> tree = (category == null ? null : db.getFullCategoryPath(category));
                    selectedCategoryId = categoryId;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        showHideMinusBtn(true);
                        Log.d(TAG, "selectCategory categoryId=" + categoryId);
                        categoryTextIsEmpty = false;
                        if (categoryId == NO_CATEGORY_ID) {
                            clearCategory();
                            return;
                        }
                        else if (categoryId == SPLIT_CATEGORY_ID) {
                            categoryText.setText(R.string.split);
                        }
                        else if (tree != null) {
                            categoryText.setText(String.join(" / ", tree));
                        }
                        if (listener != null) listener.onCategorySelected(category, selectLast);
                    });
                });
            }
        }
    }

    public void updateCheckedEntities(String checkedCommaIds) {
        synchronized (this) {
            if (!loaded) {
                toCheckCommaIds = checkedCommaIds;
                return;
            }
        }
        MyEntitySelector.updateCheckedEntities(this.categories, checkedCommaIds);
    }

    public void updateCheckedEntities(String[] checkedIds) {
        synchronized (this) {
            if (!loaded) {
                toCheckStringIds = checkedIds;
                return;
            }
        }
        MyEntitySelector.updateCheckedEntities(this.categories, checkedIds);
    }

    public void updateCheckedEntities(List<Long> checkedIds) {
        synchronized (this) {
            if (!loaded) {
                toCheckIds = checkedIds;
                return;
            }
        }
        Log.d(TAG, "updateCheckedEntities loaded");
        for (Long id : checkedIds) {
            for (MyEntity e : categories) {
                if (e.id == id) {
                    e.checked = true;
                    break;
                }
            }
        }
    }
    
    private void showHideMinusBtn(boolean show) {
        ImageView minusBtn = (ImageView) categoryText.getTag(R.id.bMinus);
        if (minusBtn != null) minusBtn.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public void createAttributesLayout(LinearLayout layout) {
        attributesLayout = new LinearLayout(activity);
        attributesLayout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(attributesLayout, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    protected List<TransactionAttribute> getAttributes() {
        List<TransactionAttribute> list = new LinkedList<TransactionAttribute>();
        long count = attributesLayout.getChildCount();
        for (int i=0; i<count; i++) {
            View v = attributesLayout.getChildAt(i);
            Object o = v.getTag();
            if (o instanceof AttributeView) {
                AttributeView av = (AttributeView)o;
                TransactionAttribute ta = av.newTransactionAttribute();
                list.add(ta);
            }
        }
        return list;
    }

    public void addAttributes(Transaction transaction) {
        attributesLayout.removeAllViews();
        ArrayList<Attribute> attributes = db.getAllAttributesForCategory(selectedCategoryId);
        Map<Long, String> values = transaction.categoryAttributes;
        for (Attribute a : attributes) {
            AttributeView av = inflateAttribute(a);
            String value = values != null ? values.get(a.id) : null;
            if (value == null) {
                value = a.defaultValue;
            }
            View v = av.inflateView(attributesLayout, value);
            v.setTag(av);
        }
    }

    private AttributeView inflateAttribute(Attribute attribute) {
        return AttributeViewFactory.createViewForAttribute(activity, attribute);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            switch (requestCode) {
                case R.id.category_add: {
                    categoryCursor.requery();
                    long categoryId = data.getLongExtra(DatabaseHelper.CategoryColumns._id.name(), -1);
                    if (categoryId != -1) {
                        selectCategory(categoryId);
                    }
                    break;
                }
                case R.id.category_pick: {
                    long categoryId = data.getLongExtra(CategorySelectorActivity.SELECTED_CATEGORY_ID, 0);
                    selectCategory(categoryId);
                    break;
                }
            }
        }
    }

    public boolean isSplitCategorySelected() {
        return Category.isSplit(selectedCategoryId);
    }

    public void setSelectedAccount(Account account) {
        selectedAccount = account;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (node != null) node.setEnabled(enabled);
    }

    @Deprecated // todo.mb: it seems not much sense in it, better do it in single place - activity.onSelectedId
    public interface CategorySelectorListener {
        @Deprecated
        void onCategorySelected(Category category, boolean selectLast);
    }
    public boolean isMultiSelect() {
        return multiSelect;
    }


    public void onDestroy() {
        if (autoCompleteAdapter != null) {
            autoCompleteAdapter.changeCursor(null);
            autoCompleteAdapter = null;
        }
    }

    public enum SelectorType {
        TRANSACTION, SPLIT, TRANSFER, FILTER, PARENT;
    }
}
