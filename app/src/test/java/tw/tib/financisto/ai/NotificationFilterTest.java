package tw.tib.financisto.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 通知排除清單的比對規則。擋錯了會讓真的銀行通知消失，所以每種形狀都釘住。 */
public class NotificationFilterTest {

    private static final String BANK_TITLE = "信用卡消費通知";
    private static final String BANK_BODY =
            "信用卡消費通知 消費金額：1179元\n卡　　號：末四碼1706\n商店名稱：Nintendo CC1685606546";

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
}
