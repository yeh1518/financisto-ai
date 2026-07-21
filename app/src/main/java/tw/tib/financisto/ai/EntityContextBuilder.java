package tw.tib.financisto.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.model.Account;
import tw.tib.financisto.model.Category;
import tw.tib.financisto.model.CategoryEntity;
import tw.tib.financisto.model.CategoryTree;
import tw.tib.financisto.model.Project;

/**
 * 把 DB 內的帳戶 / 分類（樹攤平成全路徑）/ 專案清單組成餵給模型的 context，
 * 同時保留合法 id 集合供 app 端驗證（定稿 3，2026-07-14）。
 *
 * 個人尺度清單全量注入即可，不做 RAG / 冷資料折疊（v1）。
 * 分類匹配哲學：「你說什麼分類就什麼分類」——模型直接對 name（葉或父皆可選），
 * 全路徑只當看懂層級 + 重名消歧的背景。
 */
public class EntityContextBuilder {

    public String promptContext = "";
    public final Set<Long> validAccountIds = new HashSet<>();
    public final Set<Long> validCategoryIds = new HashSet<>();
    public final Set<Long> validProjectIds = new HashSet<>();
    /** account id -> 幣別 scale（金額換算 minor units 用）。 */
    public final Map<Long, Integer> accountScale = new HashMap<>();

    public static EntityContextBuilder build(DatabaseAdapter db) {
        EntityContextBuilder ctx = new EntityContextBuilder();

        // --- 帳戶 ---
        JSONArray accounts = new JSONArray();
        for (Account a : db.getAllAccountsList()) {
            if (!a.isActive) continue;   // 停用（closed）帳戶不進 AI context：新交易不該對到、也省 token
            ctx.validAccountIds.add(a.id);
            int scale = (a.currency != null) ? a.currency.getScale() : 2;
            ctx.accountScale.put(a.id, scale);
            try {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("name", a.title);
                o.put("type", a.type);
                if (a.currency != null && a.currency.name != null) {
                    o.put("cur", a.currency.name);
                }
                // 帳戶備註（既有欄位，編輯帳戶頁就能打）當作辨識提示：語音引擎認不得
                // 人名這類專有名詞，但只要備註裡寫了常見的同音錯字，模型很擅長對回來。
                if (a.note != null && !a.note.trim().isEmpty()) {
                    o.put("hint", a.note.trim());
                }
                accounts.put(o);
            } catch (JSONException ignored) {}
        }

        // --- 分類（樹攤平成全路徑）---
        JSONArray categories = new JSONArray();
        CategoryTree<Category> tree = db.getCategoriesTree(false);
        flattenCategories(tree, new ArrayList<>(), categories, ctx.validCategoryIds);

        // --- 專案（active）---
        JSONArray projects = new JSONArray();
        for (Project p : db.getActiveProjectsList(false)) {
            if (p.id <= 0) continue;
            ctx.validProjectIds.add(p.id);
            try {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.title);
                projects.put(o);
            } catch (JSONException ignored) {}
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[帳戶清單]（hint＝該帳戶的口語別名/辨識提示，見下方規則）\n")
          .append(accounts.toString()).append("\n\n");
        sb.append("[分類清單]（path 只是層級背景；請直接對使用者說的名稱）\n")
          .append(categories.toString()).append("\n\n");
        sb.append("[專案清單]\n").append(projects.toString());
        ctx.promptContext = sb.toString();

        return ctx;
    }

    private static void flattenCategories(CategoryTree<Category> tree,
                                          List<String> pathParts,
                                          JSONArray out,
                                          Set<Long> validIds) {
        if (tree == null) return;
        for (Category c : tree) {
            if (c.id <= 0) continue; // 跳過 no-category / split 佔位
            List<String> path = new ArrayList<>(pathParts);
            path.add(c.title);
            validIds.add(c.id);
            try {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("path", join(path, " > "));
                o.put("type", c.type == CategoryEntity.TYPE_INCOME ? "income" : "expense");
                out.put(o);
            } catch (JSONException ignored) {}
            if (c.hasChildren()) {
                flattenCategories(c.children, path, out, validIds);
            }
        }
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
