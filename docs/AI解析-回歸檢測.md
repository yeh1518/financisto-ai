# AI 解析：語料累積與回歸檢測

解析品質靠一句話一句話的真實使用來驗，不靠讀 prompt 想像。這份文件說明語料怎麼累積、
每筆紀錄怎麼對應到當時的規則與程式、以及改動 prompt 或解析程式時要跑什麼。

## 為什麼需要這一層

解析出錯時唯一能做的補救，長期都是「往 prompt 再加一條規則」。規則只會長不會縮，
而且沒有任何機制回答「加了之後有沒有變好」。這一層要解決的就是後半句：
**讓每一筆紀錄帶著版本，讓品質變成可以逐版並排比較的數字。**

## 資料流

```
手機 app（AiLog）
  └─ 每筆解析／產樣板寫一行 JSONL，帶 pv / av / sha
  └─ 每日自動備份時，連同 .backup 一起把 ai-log.jsonl 寫進備份資料夾
       （AiLogActivity 也有「存到備份夾」鈕，不想等當天備份時用）
            │  Syncthing
            ▼
電腦：備份資料夾
  └─ tools/ai_corpus.py ingest   → local-backups/ai-corpus/corpus.jsonl（只增不減、去重）
  └─ tools/ai_corpus.py report   → 依版本分組的不變式報表
```

手機端的紀錄有 1000 筆上限、會被捲掉，所以電腦端這份才是正本。

## 每筆紀錄的版本欄位

| 欄位 | 內容 | 什麼時候會變 |
|---|---|---|
| `pv` | 規則文字的 SHA-1 前 8 碼（`SYSTEM_PROMPT` + `AUDIO_RULE` + `SUPPLEMENT_RULE`） | 改到 prompt 任何一個字 |
| `av` | 建置時間（＝`versionName`） | 每次 build |
| `sha` | 建置當下的 commit 短 hash | 每次 build |

`pv` 取雜湊而不是流水號：流水號要靠人記得改，忘了改就整批語料標錯，比沒有版本更糟。
指紋刻意**不含**帳戶／分類清單與【現在時間】——那些是資料、天天在變，混進來版本就沒有意義。

`sha` 涵蓋的是解析程式那一半（後處理、重送機制、驗證關卡），`pv` 只涵蓋規則文字。
兩者要一起看：同一版 prompt 配不同版程式，結果可以差很多。

### 2026-08-09 之前的舊語料

那時還沒有版本戳，全部標成 `pv=legacy`。`report` 會列出改過 prompt 的 commit 與各自
之後的筆數，當作粗略定位——**只能當「不早於」看待**：手機跑的是上次安裝的那包 APK，
不是 commit 當下的碼，中間可能隔了好幾天。精確對應從帶版本戳的建置開始才成立。

## 不變式檢查

「機器驗得出對錯」的規則。跑 `python tools/ai_corpus.py checks` 看完整清單與每條的理由。

每條標了 **owner**：

- `prompt`：目前只寫在 prompt 裡、靠模型自覺遵守
- `code`：已經在程式裡強制
- `-`：不是對錯，是品質指標（例如帳戶解不出來的比率）

**owner=prompt 而違規率下不去的，就是下一條該搬進程式的規則。** 判準是：
驗證只需要字串比對或查表 → 程式做得比模型穩；需要語意判斷 → 只能留在 prompt。

**看 owner=code 的數字要小心**：紀錄存的是模型的原始輸出、不是後處理過的結果，
所以那些數字量的是「程式攔下來幾次」＝模型在這件事上有多不可靠，不是使用者受害幾次。
搬進程式不會讓數字歸零，也不該期待它歸零。

## 改 prompt 或解析程式的流程

1. **動手前先跑報表**，把當下數字存成快照：
   ```
   python tools/ai_corpus.py report --json local-backups/ai-corpus/baseline-<日期>.json
   ```
2. 改。**同一次改動要整體檢查，不是只看新加的那條**：規則之間會互相稀釋，
   加一條的代價是其他每一條被遵守的機率都下降一點。順手看看有沒有可以砍掉或搬進程式的。
3. build → 裝上手機 → 正常用幾天。
4. 再 `ingest` + `report`。新的 `pv` 會自成一列，跟舊列並排比。
5. 變壞的項目就是回歸；變好的把數字記進 commit 訊息。

只改解析程式（沒動 prompt）時 `pv` 不變、`sha` 會變，用 `report --by sha` 分組。

## 需要對照組的問題：語料重放

報表只看得到「違反了哪些不變式」，回答不了「音檔直解是不是比較笨」「換 prompt 有沒有變好」
這種需要並排比較的問題。那些走 `tools/ai_replay.py`：同一批真實句子跑多個臂
（現行 prompt／同一臂再跑一次／舊版 prompt／換模型），輸出一致率與可驗證正確率。

**先量飄移**是這支工具的重點——同一句、同 prompt、同模型、temperature=0 跑兩次的一致率
就是地板。沒有地板，任何兩臂之間的差異都分不出是真的還是運氣。

音檔直解的比法：直解會把自己聽出來的逐字稿記在 `transcript` 欄，拿那段字走純文字路徑再跑
一次，輸入內容完全相同、只差在要不要一邊聽一邊解析。

```
python tools/ai_replay.py --ledger <放 .backup 的資料夾> [--limit N]
```

## 指令

```
python tools/ai_corpus.py ingest --source <備份資料夾> --ledger <放 .backup 的資料夾>
python tools/ai_corpus.py report [--by pv|sha|month] [--limit N] [--json OUT]
python tools/ai_corpus.py checks
```

第一次給過 `--source` / `--ledger` 之後會記在 `local-backups/ai-corpus/config.json`，
往後直接 `ingest` 即可。`--ledger` 是拿帳本備份當「當時的帳戶／分類名單」——
好幾條檢查要靠它，而且**每份備份都會讀**，每筆紀錄對照的是它發生當下的名單
（拿今天的名單去驗三週前的紀錄，那時還不存在的分類會被誤判成模型亂編 id）。

語料與快照都在 `local-backups/`（gitignored，內含個人財務內容，不進公開版控）。
