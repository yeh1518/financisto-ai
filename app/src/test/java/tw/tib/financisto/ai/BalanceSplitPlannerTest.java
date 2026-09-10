package tw.tib.financisto.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 調整餘額 + 分割：各份分的是差額，殘額份由程式補。
 *
 * 案例形狀來自 2026-09-10 早上的真實語句（金額照抄、帳戶分類換成中性代號）：
 * 「現金剩下752其中80是早餐剩下的是食材」在帳上 915 時，差額 −163 → 早餐 −80、食材 −83。
 */
public class BalanceSplitPlannerTest {

    private static final long BREAKFAST = 7;
    private static final long GROCERIES = 82;

    private static ParsedTransaction.Split share(Long category, Double amount, String note) {
        ParsedTransaction.Split s = new ParsedTransaction.Split();
        if (category != null) {
            s.category.id = category;
            s.category.confidence = 1;
        }
        s.amount = amount;
        s.note = note;
        return s;
    }

    private static List<ParsedTransaction.Split> shares(ParsedTransaction.Split... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    @Test
    public void remainderShareTakesDeltaMinusKnown() {
        // 915 → 752：差額 −16300（分）
        List<BalanceSplitPlanner.Share> out = BalanceSplitPlanner.plan(
                shares(share(BREAKFAST, 80.0, "早餐"), share(GROCERIES, null, null)), -16300, 2);
        assertEquals(2, out.size());
        assertEquals(BREAKFAST, out.get(0).categoryId);
        assertEquals(-8000, out.get(0).amountMinor);
        assertEquals(GROCERIES, out.get(1).categoryId);
        assertEquals(-8300, out.get(1).amountMinor);
        assertNull(out.get(1).note);
    }

    @Test
    public void allAmountsGivenAreKeptEvenWhenSumDiffersFromDelta() {
        // 「早餐59、青菜45、雞蛋38，剩下752」但帳上差額其實是 −163：不自動平，三份照回
        List<BalanceSplitPlanner.Share> out = BalanceSplitPlanner.plan(
                shares(share(BREAKFAST, 59.0, null), share(GROCERIES, 45.0, "青菜"), share(GROCERIES, 38.0, "雞蛋")),
                -16300, 2);
        assertEquals(3, out.size());
        long sum = 0;
        for (BalanceSplitPlanner.Share s : out) sum += s.amountMinor;
        assertEquals(-14200, sum);            // 未分配 = −16300 − (−14200) = −2100，留給表單顯示
        assertEquals("雞蛋", out.get(2).note);
    }

    @Test
    public void positiveDeltaMakesSharesPositive() {
        // 數出來比帳上多：差額 +500，各份正號
        List<BalanceSplitPlanner.Share> out = BalanceSplitPlanner.plan(
                shares(share(BREAKFAST, 2.0, null), share(GROCERIES, null, null)), 500, 2);
        assertEquals(200, out.get(0).amountMinor);
        assertEquals(300, out.get(1).amountMinor);
    }

    @Test
    public void remainderShareDroppedWhenNothingLeft() {
        List<BalanceSplitPlanner.Share> out = BalanceSplitPlanner.plan(
                shares(share(BREAKFAST, 80.0, null), share(GROCERIES, null, null)), -8000, 2);
        assertEquals(1, out.size());
    }

    @Test
    public void remainderShareKeepsOppositeSignWhenKnownSharesExceedDelta() {
        // 講的份 120 超過差額 100：殘額 +20 帶號照建（口誤或真有收入，程式不替人判斷），未分配歸 0
        List<BalanceSplitPlanner.Share> out = BalanceSplitPlanner.plan(
                shares(share(BREAKFAST, 120.0, null), share(GROCERIES, null, null)), -10000, 2);
        assertEquals(2, out.size());
        assertEquals(-12000, out.get(0).amountMinor);
        assertEquals(GROCERIES, out.get(1).categoryId);
        assertEquals(2000, out.get(1).amountMinor);
    }

    @Test
    public void mixedSignSharesFromRealInflow() {
        // 2026-09-10 實測句：「剩下300其中的500是食材剩下的是幫忙我收錢的」，帳上 500 → 300：
        // 差額 −200 ＝ 食材 −500 ＋ 其他收入 +300
        final long OTHER_INCOME = 9;
        List<BalanceSplitPlanner.Share> out = BalanceSplitPlanner.plan(
                shares(share(GROCERIES, 500.0, null), share(OTHER_INCOME, null, "幫忙收錢")), -20000, 2);
        assertEquals(2, out.size());
        assertEquals(-50000, out.get(0).amountMinor);
        assertEquals(OTHER_INCOME, out.get(1).categoryId);
        assertEquals(30000, out.get(1).amountMinor);
        assertEquals("幫忙收錢", out.get(1).note);
    }

    @Test
    public void onlyFirstNullShareIsRemainder() {
        List<BalanceSplitPlanner.Share> out = BalanceSplitPlanner.plan(
                shares(share(BREAKFAST, 80.0, null), share(GROCERIES, null, "食材"), share(null, null, "其他")),
                -16300, 2);
        assertEquals(2, out.size());
        assertEquals("食材", out.get(1).note);
    }

    @Test
    public void unresolvedCategoryBecomesNoCategory() {
        List<BalanceSplitPlanner.Share> out = BalanceSplitPlanner.plan(
                shares(share(null, 80.0, "不知道算什麼")), -16300, 2);
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).categoryId);
    }

    @Test
    public void emptyOrNullInputGivesEmptyPlan() {
        assertTrue(BalanceSplitPlanner.plan(null, -100, 2).isEmpty());
        assertTrue(BalanceSplitPlanner.plan(shares(), -100, 2).isEmpty());
        // 只有一個殘額份、差額 0：沒東西可分
        assertTrue(BalanceSplitPlanner.plan(shares(share(GROCERIES, null, null)), 0, 2).isEmpty());
    }

    @Test
    public void scaleIsHonoured() {
        List<BalanceSplitPlanner.Share> out = BalanceSplitPlanner.plan(
                shares(share(BREAKFAST, 80.0, null)), -163, 0);   // 零小數位幣別
        assertEquals(-80, out.get(0).amountMinor);
    }
}
