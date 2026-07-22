---
date: 2026-07-22
status: 已完結（結論：短期維持雲端）
---

# 本地/端側 LLM 記帳解析評測（2026-07-22）

> 接手 session 一律以繁體中文回應與產出。

上游作者在 [financisto1-holo#121](https://github.com/tiberiusteng/financisto1-holo/issues/121)
問「Gemma-3n E4B 這種端側模型夠不夠力把交易解析成 JSON」。本實驗直接量測。

## 方法

- **Prompt / schema**：harness 從 `BookkeepingParser.java` 原始碼**現場抽**
  SYSTEM_PROMPT 與 strict JSON schema（字串串接還原），保證與 app 一字不差。
- **候選清單**：`extract_entities.py` 從真實 `.backup` 抽出（35 帳戶 / 83 分類 /
  22 專案，含 hint），照 `EntityContextBuilder` 格式組 context。
- **約束解碼**：schema 攤平 `$defs/$ref` 後餵 Ollama `format` 參數（grammar
  constrained decoding，與 LiteRT-LM constrained decoding 同機制類）。
- **語料**：24 題，涵蓋 expense / income / transfer / balance / splits /
  相對日期（昨天、上週三）/ 時間（早上八點半）/ 專案比對 / 同名消歧
  （富邦 vs 富邦-老婆）/ 異形字（「臺灣银行」）。時間釘死 2026-07-22 12:00（週三）。
- **Runtime**：GARY_Z13 的 Ollama，經 mesh-SSH port-forward（`localhost:11435`）。

## 結果

| 模型 | 級距 | 全題正確 | 欄位級 | 速度(warm) |
|---|---|---|---|---|
| gemma3n:e4b | 手機端側 | **33%** (8/24) | 78.9% | ~5s |
| qwen3-vl:8b | 端側上限 | ~77%（13 題有效的部分跑分¹） | — | 慢² |
| qwen3.6 (23GB) | 桌機消費級 | **96%** (23/24) | 98.9% | ~5s |

¹ 只計未 timeout 的 13 題（10 對 3 錯）。
² 與常駐的 qwen3.6 搶記憶體被擠到 CPU，速度數據無參考價值。

qwen3.6 唯一「錯」題：「全家60」帳戶猜「身上現金」而非留 null——語義上可辯護。

## 結論

1. **E4B 直上不可用**（33%＝三筆兩筆要人工修）。錯誤集中：候選挑選錯亂
   （轉帳方向對調、挑到不相干帳戶）＋過度保守（「加油」不敢對「燃油」填 null）
   ＋細部推理（「兩張580」回 1160、「上週三」差一天）。
2. **失敗有規律 → 預處理層（日期時間規則＋候選縮小＋別名表）估可救到 70-80%**，
   仍低於雲端體驗，投資報酬差。
3. **約束解碼零故障**：三模型全部 100% schema 合法 JSON——本地端的瓶頸是
   「挑選智力」，不是結構合規。
4. **20B+ 消費級本地模型已能追平雲端**（96%）——但那是「一人一伺服器」，
   無 app 級分享價值；定位僅適合設定頁「自訂端點」進階選項。
5. **產品路線維持雲端**；端側等手機級模型把「挑選」能力補上再議。

## 檔案

| 檔 | 說明 | git |
|---|---|---|
| `harness.py` | 評測主程式（`python harness.py <model> [case_id...]`；`NOTHINK=1` 關 qwen thinking） | ✅ |
| `extract_entities.py` | 從 .backup 抽清單（路徑寫死在檔頭，換備份要改） | ✅ |
| `entities.json` / `testset.json` | 真實清單 / 語料＋gold（**含個人財務與家人資訊，gitignore，不進 git**） | ❌ |
| `result_*.json` / `gemma_failures.json` | 跑分結果 / 失敗分析（同上，含真實帳戶名） | ❌ |

重跑：`extract_entities.py` 對任一備份重產 `entities.json`；harness 內路徑指向
scratchpad，搬家後改 `SCRATCH`/`JAVA`/`OLLAMA` 三個常數即可。
