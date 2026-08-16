# financisto-ai

Financisto Holo 的自用強化版：AI 一句話記帳（語音／文字）、通知樣板解析、信用卡對帳。
接手本專案的 session **一律以繁體中文回應與產出**。

## 動手前先看的兩份

- **[`tools/README.md`](tools/README.md)** — repo 內的工具索引。**要寫新腳本之前先掃一眼**，
  想做的事多半已經有現成的（語料回歸、對照實驗、分類排序復原、報表等價驗證、模擬器）。
- **`docs/`** — 定稿的設計與流程文件。

檔案在磁碟上、但被 `.gitignore` 蓋住所以 git 裡看不到的：`DEV-BUILD.md`（建置與發版 SOP、
repo 拓撲、簽章金鑰位置）、`docs/對帳規則.md`。**翻 git 找不到不代表不存在。**

## 幾條會踩到的

- **做完一個可測的版本就出 APK 交付，不必等使用者開口**：`assembleRelease` → 覆蓋
  `syncthink\obsidian\financisto\apk\financisto-ai-release.apk`（Syncthing 同步到手機）。
  **一定是 release 版**——手機上是 release 簽章，debug APK 裝不上去且只會顯示「應用程式未安裝」。
  完整流程與金鑰位置見 `DEV-BUILD.md` 的「交付」節。
- 金額／統計／換匯相關的改動，出貨前用**全量真實備份**驗等價性
  （`tools/verify_report_equivalence.py`）。使用者無法自行驗算對錯，這一關不能省。
- 解析品質看語料、不看直覺：`tools/ai_corpus.py report`。改 prompt 或解析程式的前後都要跑，
  否則沒有依據判斷改動是好是壞。判準見 [`docs/AI解析-回歸檢測.md`](docs/AI解析-回歸檢測.md)。
- 規則放 prompt 還是放程式：**驗證只需要字串比對或查表 → 程式**；**需要語意判斷 → prompt**。
  程式端不寫死使用者的詞彙表，一律從 DB 現讀。
- 產出「要還原回手機」的備份檔一律寫成新檔、不覆蓋既有備份，並在寫檔前驗證。
  還原會整個蓋掉資料庫，出錯的代價是整本帳。
- **repo 裡不放真實的帳戶名／銀行名／卡號／消費紀錄**——測試 fixture、文件範例、prompt 裡的
  示範全部用中性等價物（甲銀行／乙銀行信用卡-配偶／`(旅遊基金)`／`NeoBank`／`Digi帳戶`）。
  換的時候**保留那個例子原本要示範的性質**：標點形狀（連字號、括號、ASCII、混寫）、
  母帳戶與後綴變體的對照、「帳戶名本身是一般語詞」。寫測試時隨手打真名是最自然的動作，
  所以這條要主動想起來。**推上去就拿不回來了。**
- `master` 是私有封存線，**絕不推 public**。日常開發線是 `public-master`。
- commit 訊息**不寫 `Machine:` 行**（全域 `G-env-fact` 在本 repo 整體豁免：開發只在一台，機器欄位沒有辨識價值，且公開線的訊息會被外人讀到）。理由與完整鐵則見 `DEV-BUILD.md`。
