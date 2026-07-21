# Financisto AI

[Financisto Holo](https://github.com/tiberiusteng/financisto1-holo) 的 AI 加強 fork：
對著 app 講一句「身上現金早餐120」，LLM 解析成完整交易帶入表單，確認後儲存。

本 fork 新增：

* **AI 一句話記帳**——全 App 浮動麥克風鈕；語音或打字一句話 → 帳戶/分類/金額/
  日期/專案/備註自動解析帶入表單。支援分割交易、轉帳、調整餘額（「中信剩下300」）、
  補充模式（對既有表單追加講一句）
* 語音辨識：標準 Google 語音輸入，或雲端 STT 自錄音（Groq / OpenAI / Gemini）
* LLM 解析：OpenAI / Gemini structured output，注入帳戶與分類樹 context、
  嚴格 id 驗證防幻覺；API key 加密存本機，不進備份
* 帳戶「計入統計報表」開關：虛擬額度/信封袋帳戶的額度操作不再汙染收支統計
* 修正：交易列表 fast scroll 誤觸跳位、開樣板/排程列表閃退（v235）
* 效能：總額計算改 SQL 端聚合（兩萬筆以上資料集有感）
* debug build 帶 `.ai` applicationId 尾綴，與 Play 商店版同機並存互不干擾

金額相關改動以全量真實備份驗證等價性（`tools/verify_report_equivalence.py`）。

上游（Play 商店版）：https://github.com/tiberiusteng/financisto1-holo
授權：GPL v2（見 license.txt），與上游相同。

---

以下為上游原始 README：

# Financisto Holo

Get it on Google Play: https://play.google.com/store/apps/details?id=tw.tib.financisto

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
* Photo removed due to backup and content linking/updating difficulties
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
