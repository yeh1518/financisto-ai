#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿語料庫裡的真實句子重跑解析，比較不同「臂」（prompt 版本／模型／模式）。

要回答的是三個沒辦法用眼睛看出來的問題：

1. **飄移有多大**：同一句話、同一個 prompt、同一顆模型、temperature=0，跑兩次會不會不一樣。
   這是所有比較的地板——如果 A 跟 B 的差異沒有超過飄移，那個差異就不是真的。
2. **語音直解比文字解析笨嗎**：音檔直解會把它聽出來的逐字稿記在 transcript 欄。拿那段
   逐字稿走純文字路徑再跑一次，輸入內容完全相同、只差在「一邊要邊聽邊解析」。
   兩者的差異若明顯大於飄移，就是直解真的比較笨，而不是運氣。
3. **改 prompt 有沒有變好**：新舊兩版規則跑同一批句子。

計分不靠人工標註（沒有那個工時），改用兩個都可機器判定的指標：
  - 對照組一致率：與 baseline 臂在關鍵欄位上的一致比率
  - 可驗證正確率：句子裡明確講到的東西有沒有被抓對（金額字面值、原句只出現一個帳戶名
    且該名字不是別的帳戶名的子字串時的帳戶）。這個子集小但答案沒有爭議。

用法：
    python tools/ai_replay.py --ledger <放 .backup 的資料夾> [--limit N] [--old-rev <commit>]

金鑰讀環境變數 GEMINI_API_KEY（沒有就從 myinfra secrets.json 撈，路徑用 --secrets 指定）。

⚠️ 這支工具會燒 API（臂數 × 句數 次呼叫），所以「跑完之後才會壞的東西」代價特別高——
底下那行 stdout 強制 UTF-8 就是為此：報表含 `↔`、`·` 等字元，Windows console 預設 cp950
編不出來，會在**四個臂都跑完、錢都花掉之後**才 UnicodeEncodeError 炸掉（2026-08-13 實際踩到）。
明細 JSON 那時已經寫出來了，重算報表不必重跑，但別再讓它發生。
"""

import argparse
import gzip
import io
import json
import os
import re
import sys
import time
import urllib.request
import urllib.error
from collections import defaultdict

# 見上方 docstring：報表印到 cp950 console 會炸，而那是在錢都花完之後
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "tw", "tib", "financisto", "ai",
                    "BookkeepingParser.java")
CORPUS = os.path.join(ROOT, "local-backups", "ai-corpus", "corpus.jsonl")
OUTDIR = os.path.join(ROOT, "local-backups", "ai-corpus")
BASE = "https://generativelanguage.googleapis.com/v1beta/openai"

# 比較哪些欄位。note 不比：那是自由文字，字面不同不代表錯
FIELDS = ["transaction_type", "amount", "account", "to_account", "category"]


# ------------------------------------------------------------------ prompt / context

def extract_java_string(src, const_name):
    m = re.search(const_name + r"\s*=\s*(.*?);", src, re.S)
    if not m:
        raise RuntimeError("找不到常數：" + const_name)
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
    return "".join(parts).replace("\\n", "\n").replace('\\"', '"')


def java_at(rev):
    """rev=None 取工作目錄現況；否則取那個 commit 的版本。"""
    if rev is None:
        return io.open(JAVA, encoding="utf-8").read()
    import subprocess
    rel = os.path.relpath(JAVA, ROOT).replace("\\", "/")
    return subprocess.check_output(["git", "show", "%s:%s" % (rev, rel)],
                                   cwd=ROOT).decode("utf-8")


def parse_backup(path):
    ents = []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        cur = None
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("$ENTITY:"):
                cur = {"_type": line[len("$ENTITY:"):]}
            elif line == "$$":
                if cur:
                    ents.append(cur)
                cur = None
            elif cur is not None and ":" in line:
                k, v = line.split(":", 1)
                cur[k] = v
    return ents


def build_entities(ledger_dir):
    """照 EntityContextBuilder 的格式從最新一份備份重建清單（分類要走 nested set 還原路徑）。"""
    import glob
    backups = sorted(glob.glob(os.path.join(ledger_dir, "*.backup")), key=os.path.getmtime)
    ents = parse_backup(backups[-1])
    cur_names = {e["_id"]: e.get("name", "") for e in ents if e["_type"] == "currency"}

    accounts = []
    for e in ents:
        if e["_type"] != "account" or e.get("is_active") != "1":
            continue
        o = {"id": int(e["_id"]), "name": e.get("title", ""), "type": e.get("type", "")}
        c = cur_names.get(e.get("currency_id", ""))
        if c:
            o["cur"] = c
        note = (e.get("note") or "").strip()
        if note:
            o["hint"] = note
        accounts.append(o)

    cats = [e for e in ents if e["_type"] == "category" and int(e["_id"]) > 0]
    for c in cats:
        c["left"], c["right"], c["id"] = int(c.get("left", 0)), int(c.get("right", 0)), int(c["_id"])
    cats.sort(key=lambda c: c["left"])
    categories, stack = [], []
    for c in cats:
        while stack and c["left"] > stack[-1][0]:
            stack.pop()
        path = [t for _, t in stack] + [c.get("title", "")]
        categories.append({"id": c["id"], "path": " > ".join(path),
                           "type": "income" if c.get("type") == "1" else "expense"})
        stack.append((c["right"], c.get("title", "")))

    projects = [{"id": int(e["_id"]), "name": e.get("title", "")}
                for e in ents if e["_type"] == "project"
                and e.get("is_active") == "1" and int(e["_id"]) > 0]
    return {"source": os.path.basename(backups[-1]),
            "accounts": accounts, "categories": categories, "projects": projects}


def build_system(prompt, ent, now="【現在時間】2026-08-10 12:00（星期一）\n"):
    j = lambda o: json.dumps(o, ensure_ascii=False, separators=(",", ":"))
    ctx = ("[帳戶清單]（hint＝該帳戶的口語別名/辨識提示，見下方規則）\n" + j(ent["accounts"])
           + "\n\n[分類清單]（path 只是層級背景；請直接對使用者說的名稱）\n" + j(ent["categories"])
           + "\n\n[專案清單]\n" + j(ent["projects"]))
    return prompt + "\n" + now + "\n" + ctx


# ------------------------------------------------------------------ 呼叫

def get_key(secrets):
    k = os.environ.get("GEMINI_API_KEY")
    if k:
        return k
    if secrets and os.path.exists(secrets):
        with io.open(secrets, encoding="utf-8-sig") as f:
            d = json.load(f)
        for name in ("GEMINI_API_KEY", "GEMINI_API_KEY_PAID", "GEMINI_API_KEY_FREE"):
            if d.get(name):
                return d[name]
    raise SystemExit("找不到金鑰：設環境變數 GEMINI_API_KEY 或用 --secrets 指到 secrets.json")


def call(system, schema, model, key, text, timeout=120):
    body = {"model": model, "temperature": 0,
            "messages": [{"role": "system", "content": system},
                         {"role": "user", "content": text}],
            "response_format": {"type": "json_schema", "json_schema": schema}}
    req = urllib.request.Request(BASE + "/chat/completions",
                                 json.dumps(body).encode("utf-8"),
                                 {"Content-Type": "application/json",
                                  "Authorization": "Bearer " + key})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                resp = json.load(r)
            return json.loads(resp["choices"][0]["message"]["content"])
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 503) and attempt < 3:
                time.sleep(4 * (attempt + 1))
                continue
            return {"_error": "HTTP %s %s" % (e.code, e.read()[:200])}
        except Exception as e:                                  # noqa: BLE001
            if attempt < 3:
                time.sleep(3)
                continue
            return {"_error": str(e)}
    return {"_error": "重試用盡"}


# ------------------------------------------------------------------ 比對與計分

def pick(d, f):
    v = (d or {}).get(f)
    if isinstance(v, dict):
        return v.get("id")
    return v


def same(a, b):
    ka = {f: pick(a, f) for f in FIELDS}
    kb = {f: pick(b, f) for f in FIELDS}
    return ka == kb, [f for f in FIELDS if ka[f] != kb[f]]


AMOUNT_RE = re.compile(r"(\d+(?:,\d{3})*(?:\.\d+)?)")


def verifiable(text, ent):
    """句子裡明確講到、答案沒有爭議的部分。抓不出來就回空 dict（那句不計分）。"""
    out = {}
    nums = [n.replace(",", "") for n in AMOUNT_RE.findall(text or "")]
    nums = [n for n in nums if len(n) <= 7]
    if len(set(nums)) == 1:
        out["amount"] = float(nums[0])
    names = [a["name"] for a in ent["accounts"] if a["name"] and a["name"] in (text or "")]
    # 名字是另一個帳戶名的子字串時（乙銀行信用卡 ⊂ 乙銀行信用卡-配偶）答案有歧義，不計分
    if len(names) == 1:
        n = names[0]
        if not any(o["name"] != n and n in o["name"] for o in ent["accounts"]):
            out["account"] = next(a["id"] for a in ent["accounts"] if a["name"] == n)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ledger", required=True)
    ap.add_argument("--secrets",
                    default=os.path.join(os.path.expanduser("~"), "syncthink", "myinfra",
                                         "dotfiles", "secrets", "secrets.json"))
    ap.add_argument("--limit", type=int, default=60)
    ap.add_argument("--old-rev", default="601a7e9", help="舊 prompt 取自哪個 commit")
    ap.add_argument("--model", default="gemini-3.1-flash-lite")
    ap.add_argument("--strong-model", default="gemini-3.1-flash")
    ap.add_argument("--out", default=os.path.join(OUTDIR, "replay.json"))
    args = ap.parse_args()

    key = get_key(args.secrets)
    ent = build_entities(args.ledger)
    print("實體清單：%s（帳戶 %d / 分類 %d / 專案 %d）"
          % (ent["source"], len(ent["accounts"]), len(ent["categories"]), len(ent["projects"])))

    cur_src = java_at(None)
    old_src = java_at(args.old_rev)
    schema = json.loads(extract_java_string(cur_src, "RESPONSE_SCHEMA"))
    sys_new = build_system(extract_java_string(cur_src, "SYSTEM_PROMPT"), ent)
    sys_old = build_system(extract_java_string(old_src, "SYSTEM_PROMPT"), ent)
    print("新規則 %d 字元、舊規則 %d 字元" % (len(sys_new), len(sys_old)))

    # 取音檔直解、且模型回報了逐字稿的那些——只有這種句子能做「直解 vs 文字」的對照
    cases = []
    for line in io.open(CORPUS, encoding="utf-8"):
        e = json.loads(line)
        if e.get("mode") or e.get("kind") == "template" or not e.get("got"):
            continue
        if not (e.get("stt") or "").startswith("direct:"):
            continue
        try:
            d = json.loads(e["got"])
        except ValueError:
            continue
        t = (d.get("transcript") or "").strip()
        if not t:
            continue
        cases.append({"at": e["at"], "text": t, "direct": d})
    cases = cases[-args.limit:]
    print("可用案例 %d 句（音檔直解且有逐字稿）\n" % len(cases))

    arms = [("新規則", sys_new, args.model), ("新規則·再跑一次", sys_new, args.model),
            ("舊規則", sys_old, args.model), ("新規則·強模型", sys_new, args.strong_model)]
    results = defaultdict(dict)
    for name, system, model in arms:
        t0 = time.time()
        for i, c in enumerate(cases):
            results[name][c["at"]] = call(system, schema, model, key, c["text"])
            if (i + 1) % 10 == 0:
                print("  %s %d/%d" % (name, i + 1, len(cases)))
        print("%s 完成，%.0f 秒" % (name, time.time() - t0))

    # 整臂全錯要當場喊，不要讓它安靜地變成報表裡的「0/0 = 0%」——那讀起來像「一致率 0%」
    # （很糟），實際是「這一臂沒有任何有效結果」（沒資訊），兩者的處置完全不同。
    # 2026-08-13 踩到：--strong-model 的預設 model 名已失效，整臂 60 次呼叫全 404。
    for name, _, model in arms:
        errs = [r for r in results[name].values() if "_error" in r]
        if len(errs) == len(cases):
            print("\n⚠️ 「%s」整臂失敗（model=%s），該臂不具參考價值：\n   %s"
                  % (name, model, errs[0]["_error"][:200]))

    rows = []
    for c in cases:
        row = {"at": c["at"], "text": c["text"], "expect": verifiable(c["text"], ent),
               "direct": {f: pick(c["direct"], f) for f in FIELDS}}
        for name, _, _ in arms:
            row[name] = {f: pick(results[name][c["at"]], f) for f in FIELDS}
            if "_error" in results[name][c["at"]]:
                row[name]["_error"] = results[name][c["at"]]["_error"]
        rows.append(row)

    with io.open(args.out, "w", encoding="utf-8") as f:
        json.dump({"entities": ent["source"], "cases": rows}, f, ensure_ascii=False, indent=1)

    def agree(a_key, b_key):
        n = ok = 0
        for r in rows:
            if r[a_key].get("_error") or r[b_key].get("_error"):
                continue
            n += 1
            ok += 1 if all(r[a_key][f] == r[b_key][f] for f in FIELDS) else 0
        return ok, n

    print("\n=== 一致率（關鍵欄位全同才算一致）===")
    for a, b, why in [("新規則", "新規則·再跑一次", "同 prompt 同模型跑兩次＝飄移地板"),
                      ("新規則", "direct", "文字解析 vs 音檔直解（同一段逐字稿）"),
                      ("新規則", "舊規則", "砍規則前後"),
                      ("新規則", "新規則·強模型", "換強模型")]:
        ok, n = agree(a, b)
        print("  %-22s %3d/%3d = %3d%%   %s" % (a + " ↔ " + b, ok, n, ok * 100 // max(n, 1), why))

    print("\n=== 可驗證正確率（句子裡明講的部分）===")
    for name in ["direct"] + [a[0] for a in arms]:
        tot = defaultdict(lambda: [0, 0])
        for r in rows:
            for f, want in r["expect"].items():
                if r[name].get("_error"):
                    continue
                tot[f][1] += 1
                got = r[name][f]
                if f == "amount":
                    tot[f][0] += 1 if (got is not None and abs(float(got) - want) < 0.01) else 0
                else:
                    tot[f][0] += 1 if got == want else 0
        s = "  %-16s" % name
        for f in ("amount", "account"):
            a, b = tot[f]
            s += "  %s %3d/%3d=%3d%%" % (f, a, b, a * 100 // max(b, 1))
        print(s)

    print("\n明細寫入 %s" % args.out)


if __name__ == "__main__":
    main()
