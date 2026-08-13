package tw.tib.financisto.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 通知排除清單的比對規則。擋錯了會讓真的銀行通知消失，所以每種形狀都釘住。 */
public class NotificationFilterTest {

    private static final String BANK_TITLE = "信用卡消費通知";
    private static final String BANK_BODY =
            "信用卡消費通知 消費金額：1179元\n卡　　號：末四碼1706\n商店名稱：Nintendo CC1685606546";
    private static final String IG = "com.instagram.android";

    @Test
    public void emptyListHidesNothing() {
        assertFalse(NotificationFilter.matches(Collections.<String>emptyList(), BANK_TITLE, BANK_BODY));
        assertFalse(NotificationFilter.matches(NotificationFilter.keywords(""), BANK_TITLE, BANK_BODY));
        assertFalse(NotificationFilter.matches(NotificationFilter.keywords(null), BANK_TITLE, BANK_BODY));
    }

    @Test
    public void matchesOnTitle() {
        List<String> k = NotificationFilter.keywords("pt._cat\n限時動態");
        assertTrue(NotificationFilter.matches(k, "pt._cat 和另外 2 人", "新限時動態："));
    }

    @Test
    public void matchesOnBody() {
        List<String> k = NotificationFilter.keywords("Syncing:");
        assertTrue(NotificationFilter.matches(k, "Syncthing", "Syncing: 0% complete, 0 devices online"));
    }

    @Test
    public void bankNotificationSurvivesTypicalList() {
        List<String> k = NotificationFilter.keywords(
                "限時動態\n優惠最後倒數\nSyncing:\n# 以下是社群 app\npt._cat");
        assertFalse(NotificationFilter.matches(k, BANK_TITLE, BANK_BODY));
    }

    @Test
    public void caseInsensitive() {
        assertTrue(NotificationFilter.matches(Arrays.asList("SYNCING"), "x", "Syncing: 0%"));
        assertTrue(NotificationFilter.matches(Arrays.asList("syncing"), "x", "SYNCING NOW"));
    }

    @Test
    public void blankLinesAndCommentsAreNotKeywords() {
        // 空行若被當成關鍵字，「什麼都含空字串」會把整份清單擋光——這是最危險的一種寫錯
        List<String> k = NotificationFilter.keywords("\n\n  \n# 註解不是關鍵字\n限時動態\n");
        assertEquals(Arrays.asList("限時動態"), k);
        assertFalse(NotificationFilter.matches(k, BANK_TITLE, BANK_BODY));
    }

    @Test
    public void keywordsAreTrimmed() {
        assertEquals(Arrays.asList("限時動態"), NotificationFilter.keywords("  限時動態  "));
    }

    @Test
    public void nullTitleOrBodyIsSafe() {
        List<String> k = NotificationFilter.keywords("限時動態");
        assertFalse(NotificationFilter.matches(k, null, null));
        assertTrue(NotificationFilter.matches(k, null, "新限時動態"));
        assertTrue(NotificationFilter.matches(k, "限時動態", null));
    }

    @Test
    public void suggestKeywordPrefersTitle() {
        assertEquals("pt._cat 和另外 2 人",
                NotificationFilter.suggestKeyword("pt._cat 和另外 2 人", "新限時動態：\n第二行"));
    }

    @Test
    public void suggestKeywordFallsBackToFirstBodyLine() {
        assertEquals("消費金額：1179元", NotificationFilter.suggestKeyword("  ", "消費金額：1179元\n卡號"));
        assertEquals("", NotificationFilter.suggestKeyword(null, null));
    }

    // --- app: 規則 ---

    @Test
    public void appRuleIsNotAlsoAKeyword() {
        // 套件名若同時被當關鍵字，擋到的東西就不是使用者以為的那些了
        assertEquals(Collections.<String>emptyList(),
                NotificationFilter.keywords("app:" + IG));
        assertEquals(Collections.singleton(IG), NotificationFilter.packages("app:" + IG));
    }

    @Test
    public void appRuleIgnoresTrailingNoteAndCase() {
        Set<String> p = NotificationFilter.packages("APP: Com.Instagram.Android  # Instagram");
        assertEquals(Collections.singleton(IG), p);
        assertTrue(NotificationFilter.matchesPackage(p, IG));
        assertTrue(NotificationFilter.matchesPackage(p, "COM.INSTAGRAM.ANDROID"));
    }

    @Test
    public void unknownPackageIsNeverHidden() {
        // 拿不到來源（舊日誌紀錄、抓不到套件名）不該被無聲吃掉
        Set<String> p = NotificationFilter.packages("app:" + IG);
        assertFalse(NotificationFilter.matchesPackage(p, null));
        assertFalse(NotificationFilter.matchesPackage(p, ""));
        assertFalse(NotificationFilter.matchesPackage(p, "tw.tib.financisto"));
        assertFalse(NotificationFilter.matchesPackage(Collections.<String>emptySet(), IG));
    }

    @Test
    public void bareAppPrefixIsNotARule() {
        // 「app:」自己一行不能變成「排除空字串」＝什麼都擋
        assertTrue(NotificationFilter.packages("app:").isEmpty());
        assertTrue(NotificationFilter.packages("app:   # 想打但沒打完").isEmpty());
        assertEquals(Collections.<String>emptyList(), NotificationFilter.keywords("app:"));
    }

    @Test
    public void mixedListKeepsBothKindsApart() {
        String raw = "app:" + IG + "  # Instagram\n限時動態\n# 註解\napp:com.spotify.music";
        assertEquals(Arrays.asList("限時動態"), NotificationFilter.keywords(raw));
        assertEquals(new java.util.HashSet<>(Arrays.asList(IG, "com.spotify.music")),
                NotificationFilter.packages(raw));
    }

    @Test
    public void addPackageWritesLabelAsNote() {
        String raw = NotificationFilter.addPackage("限時動態", IG, "Instagram");
        assertEquals("限時動態\napp:" + IG + "  # Instagram", raw);
        assertEquals(Arrays.asList("限時動態"), NotificationFilter.keywords(raw));
        assertTrue(NotificationFilter.packages(raw).contains(IG));
    }

    @Test
    public void addPackageDoesNotAccumulateBlankLines() {
        // SharedPreferences 讀回來的值尾端會多出縮排空白（值以換行結尾時 XML writer 寫進去的）。
        // 沒削掉的話每加一個 app 就多一個只有空白的行。
        String fromPrefs = "app:a  # A\n    ";
        assertEquals("app:a  # A\napp:b  # B",
                NotificationFilter.addPackage(fromPrefs, "b", "B"));
        assertEquals("app:b  # B", NotificationFilter.addPackage("   \n\n  ", "b", "B"));
    }

    @Test
    public void addPackageIsIdempotent() {
        String once = NotificationFilter.addPackage("", IG, "Instagram");
        assertEquals(once, NotificationFilter.addPackage(once, IG, "Instagram"));
        // 大小寫不同也算同一個，不該重複加一行
        assertEquals(once, NotificationFilter.addPackage(once, "COM.Instagram.Android", "IG"));
    }

    @Test
    public void addPackageHandlesEmptyAndUnlabeled() {
        assertEquals("app:" + IG, NotificationFilter.addPackage(null, IG, null));
        assertEquals("app:" + IG, NotificationFilter.addPackage("", IG, ""));
        // 查不到名稱時 AppLabels 會退回套件名——別寫成「app:x  # x」這種廢註記
        assertEquals("app:" + IG, NotificationFilter.addPackage("", IG, IG));
        assertEquals("限時動態", NotificationFilter.addPackage("限時動態", "  ", "x"));
    }
}
