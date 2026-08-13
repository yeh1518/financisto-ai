# Financisto AI

[Financisto Holo](https://github.com/tiberiusteng/financisto1-holo) 的 AI 加強 fork：
對著 app 講一句「身上現金早餐120」，LLM 解析成完整交易帶入表單，確認後儲存。

本 fork 新增：

* **AI 一句話記帳**——全 App 浮動麥克風鈕；語音或打字一句話 → 帳戶/分類/金額/
  日期/專案/備註自動解析帶入表單。支援分割交易、轉帳、調整餘額（「中信剩下300」）、
  補充模式（對既有表單追加講一句）。長按麥克風／浮動鈕直接進 AI 設定
* **AI 通知樣板解析**——把上游「通知樣板自動記帳」的樣板改由 AI 產：在 AI 設定選一則
  銀行/支付通知，LLM 產出對應的解析樣板（含帳戶/分類綁定建議），程式端用原生引擎
  雙重回測驗證（金額比對＋變異測試）後預填進樣板編輯器，過目即存。之後同類通知由
  **原生機制**全自動記帳——AI 只做一次性的「產樣板」，執行期零 API 費、離線、隱私
* 語音辨識：標準 Google 語音輸入，或雲端 STT 自錄音（Groq / OpenAI / Gemini）；
  Gemini / OpenAI 另有「語音直達」一次到位模式（音檔直接解析成表單，跳過兩段串接）
* LLM 解析：OpenAI-相容 structured output（OpenAI / Gemini / Groq），注入帳戶與
  分類樹 context、嚴格 id 驗證防幻覺；模型只能從 /models 清單選、不手打；
  API key 加密存本機（Android Keystore），不進備份
* **可完全免費**：Gemini 免費 key ＋ 語音辨識選「內建」＝ 零成本（個人記帳用量下
  免費額度充足）
* 帳戶「計入統計報表」開關：虛擬額度/信封袋帳戶的額度操作不再汙染收支統計
  （此功能已上游 merge，PR #124）
* debug build 帶 `.ai` applicationId 尾綴，與 Play 商店版同機並存互不干擾

金額相關改動以全量真實備份驗證等價性（`tools/verify_report_equivalence.py`）。

上游（Play 商店版）：https://github.com/tiberiusteng/financisto1-holo
授權：GPL v2（見 license.txt），與上游相同。

---

以下為上游原始 README：

# Financisto Holo

Get it on Google Play: https://play.google.com/store/apps/details?id=tw.tib.financisto

If you find this app helpful, please consider support the maintainer
with PayPal -- Non-recurring, amount as you wish, a cup of tea latte is great: 
[![](https://www.paypalobjects.com/en_US/i/btn/btn_donate_LG.gif)](https://paypal.me/tibteng/10)

Please see https://github.com/dsolonenko/financisto for latest development by 
orginal author.

This codebase is started from an imported copy of an old version of source code 
at launchpad (https://code.launchpad.net/~financisto-dev/financisto/trunk), as 
an working interim version until proper version 2 comes out.

Old-school, no cloud, no online service. Everything is on your device, unless you explicitly enables
Google Drive and/or Dropbox online backup. I used it for 12+ years but it stopped updated a while ago,
tweaked some quirks to fit my own needs. Hope it helps you too!

BE SURE TO BACKUP YOUR DATA!

* Holo/Material theme (only partial update to Material due to class hierarchy difficult to upgrade ...)
* Date/time picker provided by new Android versions
* Tweaked text layout, support device text scaling
* Search memo text, amount value (even with range)
* Location removed due to huge change in google maps API
* Backup file compatible with Play store version 1.7.1
* SMS template has been changed to Notification template, supporting other apps' push notification

<p>
<img alt="Account list" src="docs/screenshots/accounts.png" width="24%" />
<img alt="Blotter" src="docs/screenshots/blotter.png" width="24%" />
<img alt="Transaction" src="docs/screenshots/transaction.png" width="24%" />
<img alt="Entity Autocomplete" src="docs/screenshots/autocomplete.png" width="24%" />
</p>

I have some example scripts that can:

* Exporting Financisto backup files to hledger text format (for easy human read, searching in editor)
* Creating transactions from Taiwan EasyCard
* Importing transaction logs from Taiwan Government Unified Invoice

Find them at: https://github.com/tiberiusteng/financisto-backup-to-hledger
