package tw.tib.financisto.ai;

/**
 * 由「知道當前情境預設帳戶」的畫面實作（如帳戶明細 blotter）。
 * 全 App 浮動鈕（{@link AiFloatingButton}）啟動 {@link AiInputActivity} 前會問一下，
 * 有值就當預設帳戶帶進去——語音講到別的帳戶仍會覆蓋（見 AiInputActivity.launchPrefill）。
 */
public interface AiDefaultAccountProvider {
    /** 當前情境的預設帳戶 id；沒有（如全帳戶 blotter）回 -1。 */
    long getAiDefaultAccountId();
}
