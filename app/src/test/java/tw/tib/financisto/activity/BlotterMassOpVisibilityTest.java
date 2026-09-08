package tw.tib.financisto.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import tw.tib.financisto.blotter.BlotterFilter;
import tw.tib.financisto.filter.WhereFilter;

/**
 * 批次異動鈕的顯示判準：這個篩選是使用者點出來的，還是導覽帶進來的。
 *
 * 從帳戶列表點某個帳戶的「明細」進來時，帳戶條件是導覽的一部分（畫面本來就只給那個帳戶看），
 * 不該被當成「使用者篩選了東西」而冒出批次異動。從交易畫面用篩選介面挑帳戶則是有意的。
 */
public class BlotterMassOpVisibilityTest {

    private static WhereFilter accountOnly() {
        return WhereFilter.empty().eq(BlotterFilter.FROM_ACCOUNT_ID, "5");
    }

    @Test
    public void accountBlotterWithOnlyTheAccountIsNavigationOnly() {
        assertTrue(BlotterFragment.isNavigationOnlyFilter(true, accountOnly()));
    }

    @Test
    public void accountBlotterWithAnExtraCriterionIsUserIntent() {
        WhereFilter f = accountOnly().eq(BlotterFilter.CATEGORY_ID, "7");
        assertFalse("使用者在帳戶明細再加了分類條件，就是有意的篩選",
                BlotterFragment.isNavigationOnlyFilter(true, f));
    }

    /** 「只看擱置」把 STATUS 塞進同一個篩選物件，所以它也算使用者的意圖。 */
    @Test
    public void accountBlotterWithPendingOnlyIsUserIntent() {
        WhereFilter f = accountOnly().eq(BlotterFilter.STATUS, "PN");
        assertFalse(BlotterFragment.isNavigationOnlyFilter(true, f));
    }

    /** 從交易畫面用篩選介面挑帳戶：同樣只有一個條件，但不是帳戶明細畫面，照常顯示。 */
    @Test
    public void mainBlotterFilteredByAccountIsUserIntent() {
        assertFalse(BlotterFragment.isNavigationOnlyFilter(false, accountOnly()));
    }

    @Test
    public void emptyFilterIsNotNavigationOnly() {
        assertFalse(BlotterFragment.isNavigationOnlyFilter(true, WhereFilter.empty()));
    }
}
