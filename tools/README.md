# tools/

repo 內的工具索引。**要寫新腳本之前先掃一眼這張表**——這裡的東西多半已經涵蓋了。

多數工具吃的是 Financisto 的 `.backup`（gzip 文字、`$ENTITY:` 分段），因為那是唯一能拿到
全量真實資料的格式。凡是會產出「要還原回手機」的檔案，一律寫成新檔、不覆蓋既有備份。

| 工具 | 什麼時候伸手拿它 |
|---|---|
| `ai_corpus.py` | 想知道 AI 解析的品質、想比較不同 prompt 版本 |
| `ai_replay.py` | 兩種設定／模型／prompt 哪個好，而且需要對照組才說得準 |
| `restore_category_order.py` | 分類排序被弄亂（分類頁的 A-Z 排序按下去回不來） |
| `verify_report_equivalence.py` | 動到報表 view SQL、或合併上游之後 |
| `restructure-categories.py` ᴸ | 重構整棵分類樹並把交易搬過去 |
| `recon/` ᴸ | 信用卡帳單對帳 |
| `start-emu.cmd` / `start-emu-window.cmd` | 跑 Android 模擬器 |

ᴸ = 本地限定（含個人資料，`.gitignore` 蓋住，檔案在磁碟上但不在 git 裡）

---

## ai_corpus.py — 解析語料庫與回歸檢測

手機端的解析紀錄只留 1000 筆會被捲掉；這支把每次匯出併進電腦端一份只增不減的語料庫，
並依 prompt 版本分組跑不變式檢查。**改 prompt 或解析程式的前後都要跑**，否則沒有依據
判斷改動是好是壞。完整說明見 [`docs/AI解析-回歸檢測.md`](../docs/AI解析-回歸檢測.md)。

```bash
python tools/ai_corpus.py ingest      # 併進新匯出（去重、只增不減）
python tools/ai_corpus.py report      # 依 pv / sha / month 分組的報表
python tools/ai_corpus.py checks      # 不變式清單與每條的理由
```

第一次要給 `--source <備份資料夾>` 與 `--ledger <放 .backup 的資料夾>`，之後記在
`local-backups/ai-corpus/config.json`。

## ai_replay.py — 語料重放（對照實驗）

拿語料庫裡的真實句子重跑，同一批句子跑多個臂：現行 prompt、同一臂再跑一次（**飄移地板**）、
舊版 prompt、換模型。先量飄移是重點——沒有地板，任何兩臂的差異都分不出是真的還是運氣。

音檔直解的比法：直解會把自己聽出來的逐字稿記在 `transcript` 欄，拿那段字走純文字路徑再跑
一次，輸入完全相同、只差在要不要一邊聽一邊解析。

```bash
python tools/ai_replay.py --ledger <放 .backup 的資料夾> [--limit 45]
```

金鑰讀環境變數 `GEMINI_API_KEY`，其次讀 `--secrets` 指到的 secrets.json。

## restore_category_order.py — 恢復分類排序

Financisto 的分類順序不存 `sort_order`，存在 nested set 的 `left`/`right` 裡，所以
「恢復排序」等於把每個分類的 left/right 重算。誤觸 A-Z 排序之後唯一的救法就是拿排序前的
備份當順序來源，套回現在的資料上。

```bash
python tools/restore_category_order.py --data <新備份> --order <排序前的備份> \
    [--hoist 收入] [--dry-run]
```

`--data` 提供資料（交易、帳戶），`--order` 提供分類順序，可以是不同時間的備份。兩份的
分類集合不同也能修（只在新備份裡的分類會排在其父層最後並印出來）；父子關係不同則中止，
那代表有人搬過層級，機械式移植會搬錯位置。輸出前跑八項檢查，驗不過就不寫檔。

## verify_report_equivalence.py — 報表金額等價性

拿真實備份在記憶體 SQLite 重建，分別套用兩個 git ref 的 view SQL，diff 各報表的數字。
資料相同、只有 view SQL 不同 → 差異純由邏輯造成。

```bash
python tools/verify_report_equivalence.py <backup> [ref_before] [ref_after]
```

## start-emu.cmd / start-emu-window.cmd

`start-emu.cmd` 是 headless，給非互動 session（mesh-ssh、session 0）用。
`start-emu-window.cmd` 會開視窗，**必須從互動桌面的 PowerShell 跑**——ssh 起的視窗會畫在
看不見的桌面上。寫成檔案是刻意的：這串命令列經 bash → ssh → PowerShell 會被引號吃掉。
