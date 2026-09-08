# financisto-ai

[Financisto Holo](https://github.com/tiberiusteng/financisto1-holo) 的 fork，加上 AI 記帳：
一句話記帳（語音／文字）、通知樣板解析，以及若干修正。功能總覽與畫面見 [`README.md`](README.md)。

本檔是給在這個 fork 上動手的人（含 AI 助理）看的：**怎麼建置、怎麼測、以及幾個踩過會很痛的地方。**

## 建置

```bash
./gradlew assembleDebug      # 或 assembleRelease
```

需要 JDK 21 與 Android SDK（`compileSdk 36`）；`JAVA_HOME` / `ANDROID_HOME` 指好即可，
gradle wrapper 已進版控，乾淨 clone 就跑得動。

- `applicationId` 是 `tw.tib.financisto.ai`（debug 與 release 都帶 `.ai` 尾綴）——
  **與 Play 商店版同機並存、互不干擾**，資料可以用備份檔搬過來。
- `versionName` ＝ 建置時間，顯示在關於頁；沒有 `keystore.properties` 時 release 會**退回 debug 簽章**
  仍然 build 得出來，只是不是可散佈的穩定簽章。

## 測試：兩套，不能只跑一套

```bash
./gradlew testDebugUnitTest            # JVM 單元測試，不需要裝置
./gradlew connectedDebugAndroidTest    # 需要裝置／模擬器（DB 與 regex 行為的測試在這裡）
```

**動 `SmsTransactionProcessor.Placeholder` 的 regex，兩套都要跑。** 那些 regex 不只給執行期
比對用，`TemplateGenerator` 的 `validate()` 與 `repairWhitespace()` 也依賴它們的行為：
2026-09-04 放寬 `{{e}}` 時只跑了 androidTest 的 `PlaceholderCaptureTest`，JVM 那套紅了
將近一天沒被發現。

**佔位符捕獲行為的測試刻意放在 androidTest**：Android 的 regex 是 ICU 實作，同一個 pattern
在桌面 JVM 上的答案可能不同。不要為了跑得快把它們搬去 JVM 那套。

## 幾個踩過的地方

- **`minSdk 23`，新 API 一律要 gate。** 例如 `NotificationListenerService.requestRebind()`
  是 API 24，直接呼叫會在 23 上 `NoSuchMethodError`。比照 `NotificationChannelService`
  既有的 SDK 判斷寫法，不要自己發明一套。
- **金額／統計／換匯的改動，出貨前要驗等價性。** 做法：拿一份全量真實備份在記憶體 SQLite
  重建，分別套用改動前後兩版的報表 view SQL，diff 各報表的數字——資料相同、只有 SQL 不同，
  差異就純由邏輯造成。**使用者自己驗算不出對錯，這一關不能省。**
- **解析品質看語料，不看直覺。** app 內的「AI 記錄」頁可以把解析紀錄匯出成 jsonl；
  改 prompt 或解析程式的前後各跑一次比較，否則沒有依據判斷改動是好是壞。
  手機端只保留最近 1000 筆，會被捲掉——要長期比較就得先匯出留存。
- **測試 fixture 與文件範例不要用真實的帳戶名／銀行名／卡號／消費紀錄**，用中性等價物
  （`甲銀行`、`乙銀行信用卡-配偶`、`NeoBank`、`(旅遊基金)`）。換的時候**保留那個例子原本
  要示範的性質**：標點形狀（連字號、括號、ASCII 與中文混寫）、母帳戶與後綴變體的對照、
  「帳戶名本身就是一般語詞」。寫測試時隨手打真名是最自然的動作，所以這條要主動想起來。
- **Google Drive 備份在本 fork 停用。** Drive 登入是靠「套件名 ＋ 簽章金鑰」對認上游的
  Google Cloud 專案，這個 fork 換了兩者就對不上，登入不會成功——改用本機備份資料夾
  （搭配任何檔案同步工具）。設定頁與字串資源都已改成這個說明，不要「修好」它。
- **合併上游時，自動合併掉的檔也要逐行看。** 不進衝突清單不代表對：實際踩過上游新增的欄位
  被複製成兩份（本 fork 早有同名欄位）、layout 殘留上游的 `@id` 參照——兩者都只有編譯才抓到。
- **上游的測試有時會領先或落後其程式碼。** 合併後若上游自帶的測試是紅的，先確認它在
  `upstream/master` 上本來就紅，再決定是改預期還是真的合壞了。
