package tw.tib.financisto.activity;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import tw.tib.financisto.R;
import tw.tib.financisto.adapter.BlotterListAdapter;
import tw.tib.financisto.backup.DatabaseExport;
import tw.tib.financisto.filter.WhereFilter;
import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.Project;
import tw.tib.financisto.utils.EnumUtils;
import tw.tib.financisto.utils.LocalizableEnum;

import java.util.Arrays;

public class MassOpFragment extends BlotterFragment {

    public MassOpFragment() {
        super(R.layout.blotter_mass_op);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

        ((AppCompatActivity) getActivity()).setSupportActionBar((Toolbar) view.findViewById(R.id.toolbar));

        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.mass_op_base), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.statusBars()
                    | WindowInsetsCompat.Type.captionBar());
            var lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.topMargin = insets.top;
            lp.bottomMargin = insets.bottom;
            v.setLayoutParams(lp);
            return WindowInsetsCompat.CONSUMED;
        });

        bFilter = view.findViewById(R.id.bFilter);
        bFilter.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), BlotterFilterActivity.class);
            blotterFilter.toIntent(intent);
            startActivityForResult(intent, FILTER_REQUEST);
        });

        ImageButton bCheckAll = view.findViewById(R.id.bCheckAll);
        bCheckAll.setOnClickListener(arg0 -> {
            var adapter = (BlotterListAdapter) getListAdapter();
            if (adapter != null) adapter.checkAll();
        });

        ImageButton bUncheckAll = view.findViewById(R.id.bUncheckAll);
        bUncheckAll.setOnClickListener(arg0 -> {
            var adapter = (BlotterListAdapter)getListAdapter();
            if (adapter != null) adapter.uncheckAll();
        });

        final MassOp[] operations = MassOp.values();
        final Spinner spOperation = view.findViewById(R.id.spOperation);
        Button proceed = view.findViewById(R.id.proceed);
        proceed.setOnClickListener(v -> {
            MassOp op = operations[spOperation.getSelectedItemPosition()];
            applyMassOp(op);
        });
        spOperation.setPrompt(getString(R.string.mass_operations));
        spOperation.setAdapter(new SpinnerAdapter() {
            private LayoutInflater inflater;

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return getView(position, convertView, parent);
            }

            @Override
            public void registerDataSetObserver(DataSetObserver observer) {

            }

            @Override
            public void unregisterDataSetObserver(DataSetObserver observer) {

            }

            @Override
            public int getCount() {
                return operations.length;
            }

            @Override
            public Object getItem(int position) {
                return operations[position];
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public boolean hasStableIds() {
                return false;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Context context = getContext();
                if (inflater == null) inflater = LayoutInflater.from(context);
                View view = inflater.inflate(R.layout.mass_op_action_item, parent, false);
                TextView indicator = view.findViewById(R.id.indicator);
                TextView title = view.findViewById(R.id.center);
                title.setText(context.getString(operations[position].getTitleId()));
                indicator.setBackgroundColor(ContextCompat.getColor(context, operations[position].getColor()));
                return view;
            }

            @Override
            public int getItemViewType(int position) {
                return 0;
            }

            @Override
            public int getViewTypeCount() {
                return 1;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }
        });
        prepareTransactionActionGrid();

        emptyText = view.findViewById(android.R.id.empty);
        progressBar = view.findViewById(android.R.id.progress);

        // 帶篩選進來（從交易列表的批次異動鈕）就直接載入清單。
        //
        // ⚠️ 這段**必須**排在 emptyText / progressBar 指派之後：createAdapter 會用到那兩個，
        // 先叫 recreateCursor 會 NPE。
        //
        // 而且非叫不可——這個畫面原本的設計是「開起來是空的、請按篩選鈕」（空清單文字就是
        // 那句 mass_operations_use_filter），初次載入是靠篩選畫面回來時的 onActivityResult
        // 觸發的。從外面把篩選塞進 args 不會經過那條路，不主動載就永遠是空的。
        Bundle args = getArguments();
        if (args != null && !args.isEmpty()) {
            blotterFilter = WhereFilter.fromBundle(args);
            // 帶著篩選進來＝意圖就是「對這批做」，所以預設全勾。
            // 從主選單進來（沒有 args）維持原樣：那條路是先自己篩、再自己挑，預設不勾才對。
            checkAllOnFirstLoad = true;
            applyFilter();
            recreateCursor();
        }
    }

    /** 從交易列表帶篩選進來時，第一次載入完要把全部勾起來（見 onViewCreated）。 */
    private boolean checkAllOnFirstLoad;

    /**
     * 選了操作按「執行」的入口。需要參數的操作（改分類／改專案／附加備註）先問參數，
     * 拿到之後才走 {@link #confirmAndApply}。
     */
    protected void applyMassOp(final MassOp op) {
        BlotterListAdapter adapter = (BlotterListAdapter) getListAdapter();
        // ⚠️ **當場把 id 快照下來**，不要留著 adapter 等一下再問它。
        //
        // 需要選參數的操作會跳出另一個 Activity（分類選擇器），回來時 Loader 會送一份新 cursor
        // 進來、setListAdapter 換成新的 adapter，舊的那個 cursor 已經關掉——此時
        // getAllCheckedIds() 會安靜地回空陣列，於是備份照存、確認框照跳、什麼都沒改。
        // （實測踩到：log 是 "Will apply SET_CATEGORY on []"。原本的批次畫面沒這問題，
        //  因為它中間不會跳出別的 Activity。）
        pendingIds = adapter == null ? new long[0] : adapter.getAllCheckedIds();
        int count = pendingIds.length;
        if (count == 0) {
            // 用對話框而不是短 toast：這是這個畫面最容易撞到的死路（選了操作、按了執行、
            // 卻一筆都沒勾），而一閃而過的 toast 看起來就跟「按了沒反應」一樣。
            new AlertDialog.Builder(getContext())
                    .setMessage(R.string.apply_mass_op_zero_count)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        switch (op.pick()) {
            case CATEGORY:
                pendingOp = op;
                // ⚠️ 不用 CategorySelectorActivity.pickCategory()：它是用 **Activity** 呼叫
                // startActivityForResult，結果會回到 MassOpActivity.onActivityResult 而不是
                // 這個 fragment 的——挑完分類會安靜地什麼都沒發生（實測踩到）。自己發 intent。
                //
                // 一律用樹狀選擇器（不看使用者偏好）：批次改分類挑錯一個的代價是一整批，
                // 值得多一層層級確認。
                Intent pick = new Intent(getContext(), CategorySelectorActivity.class);
                pick.putExtra(CategorySelectorActivity.SELECTED_CATEGORY_ID, Category.NO_CATEGORY_ID);
                pick.putExtra(CategorySelectorActivity.SELECTED_ACCOUNT_ID,
                        CategorySelectorActivity.NO_SELECTED_ACCOUNT);
                pick.putExtra(CategorySelectorActivity.EXCLUDED_SUB_TREE_ID, -1L);
                pick.putExtra(CategorySelectorActivity.INCLUDE_SPLIT_CATEGORY, false);
                startActivityForResult(pick, R.id.category_pick);
                return;
            case PROJECT:
                pickProject(op);
                return;
            case NOTE:
                pickNoteText(op);
                return;
            default:
                confirmAndApply(op, new Param());
        }
    }

    /** 專案只有二十幾個且是平的，用單選清單就夠，不必為它開一個畫面。 */
    private void pickProject(final MassOp op) {
        final var projects = db.getActiveProjectsList(true);
        String[] titles = new String[projects.size()];
        for (int i = 0; i < projects.size(); i++) {
            titles[i] = projects.get(i).title;
        }
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.mass_operations_set_project)
                .setItems(titles, (d, which) -> {
                    Param p = new Param();
                    p.id = projects.get(which).id;
                    p.label = projects.get(which).title;
                    confirmAndApply(op, p);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickNoteText(final MassOp op) {
        final EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setHint(R.string.mass_operations_append_note_hint);
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.mass_operations_append_note)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) {
                        Toast.makeText(getContext(), R.string.mass_operations_append_note_empty,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Param p = new Param();
                    p.text = text;
                    p.label = text;
                    confirmAndApply(op, p);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 最後一關：講清楚要對幾筆做什麼，並告知**會先存一份備份**。
     *
     * 備份不分操作種類一律做（Gary 2026-08-13 拍板）。理由是「哪些操作算破壞性」很難劃線
     * ——把一批交易的分類改錯，事後也不知道原本各自是什麼。而每日備份只到昨天，
     * 今天已記的那些一起回不來。備份的成本是一次幾百 KB 的寫檔，換掉的是「不可回復」。
     */
    private void confirmAndApply(final MassOp op, final Param param) {
        final long[] ids = pendingIds;
        if (ids == null || ids.length == 0) return;
        String what = getString(op.getTitleId());
        if (param.label != null) {
            what = what + "「" + param.label + "」";
        }
        new AlertDialog.Builder(getContext())
                .setMessage(getString(R.string.apply_mass_op, what, ids.length)
                        + "\n\n" + getString(R.string.mass_operations_backup_first))
                .setPositiveButton(R.string.yes, (d, w) -> runMassOp(op, param, ids))
                .setNegativeButton(R.string.no, null)
                .show();
    }

    /** 備份 → 套用 → 重讀清單。都在背景執行緒，備份是寫檔、不能卡 UI。 */
    private void runMassOp(final MassOp op, final Param param, final long[] ids) {
        Log.d("Financisto", "Will apply " + op + " on " + Arrays.toString(ids));
        final ProgressDialog progress = ProgressDialog.show(getContext(), null,
                getString(R.string.mass_operations_working), true, false);
        // context 先抓下來：進了背景執行緒之後 getContext() 可能已經是 null（fragment 被收掉），
        // 那會在備份那行 NPE，而使用者看到的是「按了沒反應」
        final Context appContext = requireContext().getApplicationContext();
        tw.tib.financisto.Application.getExecutor().execute(() -> {
            String backupError = null;
            try {
                new DatabaseExport(appContext, db.db(), true).export();
            } catch (Exception e) {
                Log.e("Financisto", "批次操作前的備份失敗", e);
                backupError = e.getMessage();
            }
            final String err = backupError;
            if (err != null) {
                // 備份失敗就**不做**：這個操作的安全性完全建立在「出錯還原得回來」上面，
                // 沒備份還做下去等於偷偷取消使用者剛才同意的那個條件。
                runOnUi(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(getContext())
                            .setMessage(getString(R.string.mass_operations_backup_failed, err))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
                return;
            }
            int affected;
            try {
                affected = op.apply(db, ids, param);
            } catch (Exception e) {
                Log.e("Financisto", "批次操作失敗", e);
                runOnUi(() -> {
                    progress.dismiss();
                    Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                });
                return;
            }
            final int skipped = affected < 0 ? 0 : ids.length - affected;
            runOnUi(() -> {
                progress.dismiss();
                if (skipped > 0) {
                    // 少做了要講。分割交易被跳過是刻意的，但使用者不會自己看出「怎麼有幾筆沒變」
                    Toast.makeText(getContext(),
                            getString(R.string.mass_operations_skipped_splits, skipped),
                            Toast.LENGTH_LONG).show();
                }
                pendingIds = null;
                // 取當下的 adapter，不是操作開始時那個——中間可能已經被 Loader 換掉
                BlotterListAdapter current = (BlotterListAdapter) getListAdapter();
                if (current != null) current.uncheckAll();
                recreateCursor();
            });
        });
    }

    private void runOnUi(Runnable r) {
        if (getActivity() != null) getActivity().runOnUiThread(r);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == R.id.category_pick && resultCode == AppCompatActivity.RESULT_OK
                && data != null && pendingOp != null) {
            MassOp op = pendingOp;
            pendingOp = null;
            Param p = new Param();
            p.id = data.getLongExtra(CategorySelectorActivity.SELECTED_CATEGORY_ID,
                    Category.NO_CATEGORY_ID);
            // ⚠️ 擋掉分割佔位符。INCLUDE_SPLIT_CATEGORY=false 擋不住它——選擇器上方那條
            // 「最近使用」快選列不看這個旗標，[Split…] 照樣點得到（實測）。挑到它會把一批
            // 正常交易變成「沒有子交易的分割父交易」＝壞掉的分割結構，所以在用的這一端擋。
            if (p.id == Category.SPLIT_CATEGORY_ID) {
                Toast.makeText(getContext(), R.string.mass_operations_split_not_a_target,
                        Toast.LENGTH_LONG).show();
                return;
            }
            Category c = db.getCategory(p.id);
            p.label = c != null ? c.title : String.valueOf(p.id);
            confirmAndApply(op, p);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 需要參數的操作暫存在這裡，等 CategorySelectorActivity 回來。 */
    private MassOp pendingOp;
    /** 使用者按下「執行」當下勾選的那批 id（見 applyMassOp 的註解：不能事後再問 adapter）。 */
    private long[] pendingIds;

    /** 操作的參數：id 用於分類／專案，text 用於備註，label 只給確認對話框顯示。 */
    private static class Param {
        long id;
        String text;
        String label;
    }

    private enum Pick { NONE, CATEGORY, PROJECT, NOTE }

    @Override
    protected void applyFilter() {
        updateFilterImage();
    }

    @Override
    protected void calculateTotals(WhereFilter filter) {
        // do nothing
    }

    @Override
    protected ListAdapter createAdapter(Context context, Cursor cursor) {
        if (cursor.getCount() == 0) {
            emptyText.setVisibility(View.VISIBLE);
        }
        progressBar.setVisibility(View.GONE);
        BlotterListAdapter adapter =
                new BlotterListAdapter(context, db, R.layout.blotter_mass_op_list_item, cursor, true);
        if (checkAllOnFirstLoad) {
            checkAllOnFirstLoad = false;   // 只在第一次載入，之後（做完一次操作重讀）不要又全勾回來
            adapter.checkAll();
        }
        return adapter;
    }

    /**
     * apply 回傳「實際改到的列數」，-1＝沒在算。
     *
     * 為什麼要這個回傳值：改分類會刻意跳過分割交易，於是選了 10 筆可能只改到 8 筆。
     * 少做而不講就是靜默失敗——使用者只會覺得「怎麼有幾筆沒變」。改狀態與刪除那幾條走的是
     * 上游既有的 SQL 輔助函式（不回傳列數），維持 -1、不硬去改它們。
     */
    private enum MassOp implements LocalizableEnum{
        PENDING(R.string.mass_operations_mark_pending_all) {
            @Override
            public int apply(DatabaseAdapter db, long[] ids, Param p) {
                db.markPendingSelectedTransactions(ids);
                return -1;
            }
            @Override
            public int getColor() {
                return R.color.pending_transaction_color;
            }
        },
        RESTORED(R.string.mass_operations_mark_restored_all) {
            @Override
            public int apply(DatabaseAdapter db, long[] ids, Param p) {
                db.markRestoredSelectedTransactions(ids);
                return -1;
            }
            @Override
            public int getColor() {
                return R.color.restored_transaction_color;
            }
        },
        UNRECONCILED(R.string.mass_operations_mark_unreconciled_all) {
            @Override
            public int apply(DatabaseAdapter db, long[] ids, Param p) {
                db.markUnreconciledSelectedTransactions(ids);
                return -1;
            }
            @Override
            public int getColor() {
                return R.color.unreconciled_transaction_color;
            }
        },
        CLEAR(R.string.mass_operations_clear_all){
            @Override
            public int apply(DatabaseAdapter db, long[] ids, Param p) {
                db.clearSelectedTransactions(ids);
                return -1;
            }
            @Override
            public int getColor() {
                return R.color.cleared_transaction_color;
            }
        },
        RECONCILE(R.string.mass_operations_reconcile){
            @Override
            public int apply(DatabaseAdapter db, long[] ids, Param p) {
                db.reconcileSelectedTransactions(ids);
                return -1;
            }
            @Override
            public int getColor() {
                return R.color.reconciled_transaction_color;
            }
        },
        SET_CATEGORY(R.string.mass_operations_set_category){
            @Override
            public int apply(DatabaseAdapter db, long[] ids, Param p) {
                return db.updateCategoryForSelectedTransactions(ids, p.id);
            }
            @Override
            public Pick pick() {
                return Pick.CATEGORY;
            }
            @Override
            public int getColor() {
                return R.color.material_teal;
            }
        },
        CLEAR_CATEGORY(R.string.mass_operations_clear_category){
            @Override
            public int apply(DatabaseAdapter db, long[] ids, Param p) {
                return db.updateCategoryForSelectedTransactions(ids, Category.NO_CATEGORY_ID);
            }
            @Override
            public int getColor() {
                return R.color.material_teal;
            }
        },
        SET_PROJECT(R.string.mass_operations_set_project){
            @Override
            public int apply(DatabaseAdapter db, long[] ids, Param p) {
                return db.updateProjectForSelectedTransactions(ids, p.id);
            }
            @Override
            public Pick pick() {
                return Pick.PROJECT;
            }
            @Override
            public int getColor() {
                return R.color.material_teal;
            }
        },
        CLEAR_PROJECT(R.string.mass_operations_clear_project){
            @Override
            public int apply(DatabaseAdapter db, long[] ids, Param p) {
                return db.updateProjectForSelectedTransactions(ids, Project.NO_PROJECT_ID);
            }
            @Override
            public int getColor() {
                return R.color.material_teal;
            }
        },
        APPEND_NOTE(R.string.mass_operations_append_note){
            @Override
            public int apply(DatabaseAdapter db, long[] ids, Param p) {
                return db.appendNoteToSelectedTransactions(ids, p.text);
            }
            @Override
            public Pick pick() {
                return Pick.NOTE;
            }
            @Override
            public int getColor() {
                return R.color.material_teal;
            }
        },
        // 刪除擺最後：它是唯一不可回復的（備份還原不算「這一步的復原」），
        // 而 Spinner 預設選第一項，所以危險的那個不能在上面
        DELETE(R.string.mass_operations_delete){
            @Override
            public int apply(DatabaseAdapter db, long[] ids, Param p) {
                db.deleteSelectedTransactions(ids);
                db.rebuildRunningBalances();
                return -1;
            }
            @Override
            public int getColor() {
                return R.color.holo_red_dark;
            }
        };

        private final int titleId;

        MassOp(int titleId) {
            this.titleId = titleId;
        }

        public abstract int getColor();
        public abstract int apply(DatabaseAdapter db, long[] ids, Param p);

        /** 這個操作要先問什麼參數。預設不必問。 */
        public Pick pick() {
            return Pick.NONE;
        }

        @Override
        public int getTitleId() {
            return titleId;
        }
    }

}
