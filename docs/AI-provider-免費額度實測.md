# AI provider 免費額度實測（2026-07-23）

> **接手本專案的 session 一律以繁體中文回應與產出。**
>
> 目的：回答「這個 app 能不能免費跑」。結論寫進 app 內說明（`ai_intro_message`）。

## 關鍵前提：本 app 的每次解析 ≈ 5,000 tokens

解析 prompt ＝ 系統規則（約 3.3k 字）＋**全量注入的帳戶／分類／專案清單**。
以實測帳本（35 帳戶／83 分類／22 專案）計：

| 服務商（各自 tokenizer） | prompt tokens | 回覆 tokens | 單次總計 |
|---|---|---|---|
| Gemini 3.5 Flash | 4,677 | 106 | ~4,800 |
| Groq gpt-oss-120b | 5,323 | 633（含 524 reasoning） | ~5,950 |
| Groq gpt-oss-20b | 5,3xx | 685 reasoning | ~6,100 |

清單越大 prompt 越大——這是判斷任何服務商免費層夠不夠用的**唯一關鍵數字**。

## 實測結果

### Gemini ✅ 免費且實用（推薦組合）

- 實打成功：4,677 prompt tokens、**3.2 秒**、解析結果正確。
- 免費層：250k TPM／10 RPM／每日約 250 次請求，**免綁卡**。
- 我們單次 ~4.8k tokens → TPM 可容納約 50 次/分，實際被 10 RPM 限住，個人記帳綽綽有餘。
- **「Gemini 免費 key ＋ 語音辨識選『內建』」＝ 完全不花錢**，已寫進 app 說明。

### Groq ⚠️ 免費層＝約「一分鐘一筆」

實測（gpt-oss-120b 與 20b 皆同）：

- rate limit header 實回：`x-ratelimit-limit-tokens: 8000`（TPM），`limit-requests: 1000`（RPD）。
- 第 1 發 OK（1.8 秒，很快）；**第 2、3 發立刻 429**：
  `Limit 8000, Used 5713, Requested 5328. Please try again in 22.8s`
- 原因＝單次請求就吃掉 ~6k/8000 TPM。**RPM/RPD 都很寬鬆，全被 TPM 卡死**。
- 綁卡升 Developer tier（本身仍免費）額度 ×10 可解除。
- 模型限制：Groq 的 **strict structured outputs 只支援 `openai/gpt-oss-20b` / `120b`**
  （其餘模型只有 JSON object mode，無 schema 強制）——本 app 預設 `openai/gpt-oss-120b`，正確。

### 踩雷紀錄

- Groq API 對 `Python-urllib` 這類預設 User-Agent 直接回 **Cloudflare 403 error 1010**（看起來像 key 失效，其實不是）。測試腳本要帶正常 UA；app 走 OkHttp 無此問題。

## 已據此做的改動

- app 說明（首次點麥克風彈出／設定頁「說明」）寫明免費組合與隱私聲明。
- `BookkeepingParser.httpErrorMessage`：429／401 翻成看得懂的話
  （429 → 「已達服務商的用量限制（每分鐘 token 上限），請稍候再試」），
  解析／STT／樣板生成三條鏈共用。

## 未來若要降低 token 用量（尚未做）

清單全量注入是 prompt 肥大的主因。可行方向＝端側實驗得出的「預處理層」：
先用字串比對／個人歷史把候選縮到少數幾個再注入。此舉同時降低成本、加快速度、
讓小模型可用——見 `docs/experiments/on-device-llm-eval/README.md` 的結論 2。
