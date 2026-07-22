# -*- coding: utf-8 -*-
"""記帳解析評測 harness：同一套 prompt+schema 打 Ollama 模型，對 gold labels 計分。

- SYSTEM_PROMPT 直接從 BookkeepingParser.java 抽（字串串接還原），保證與 app 一致
- context 從 entities.json（真實備份抽出）組，格式照 EntityContextBuilder
- schema 攤平 $defs/$ref 後餵 Ollama 的 format 參數（= 約束解碼）
- now 固定 2026-07-22 12:00（星期三）確保相對日期可評
用法：python harness.py <model> [case_id ...]
"""
import io, json, os, re, sys, time, urllib.request

SCRATCH = r"C:\Users\yeh15\AppData\Local\Temp\claude\C--Users-yeh15-dev-financisto-ai\16622c8c-fe3c-4d25-98b9-7d67faab8703\scratchpad"
JAVA = r"C:\Users\yeh15\dev\financisto-ai\app\src\main\java\tw\tib\financisto\ai\BookkeepingParser.java"
OLLAMA = "http://localhost:11435/api/chat"

# ---------- 從 Java 原始碼抽字串常數 ----------
def extract_java_string(src, const_name):
    m = re.search(const_name + r"\s*=\s*(.*?);", src, re.S)
    if not m: raise RuntimeError("const not found: " + const_name)
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
    s = "".join(parts)
    return s.replace("\\n", "\n").replace('\\"', '"')

with io.open(JAVA, encoding="utf-8") as f:
    java_src = f.read()
SYSTEM_PROMPT = extract_java_string(java_src, "SYSTEM_PROMPT")
RESPONSE_SCHEMA = json.loads(extract_java_string(java_src, "RESPONSE_SCHEMA"))

# ---------- schema 攤平（$ref → inline，Ollama format 不吃 $defs） ----------
def flatten_schema(schema):
    defs = schema.pop("$defs", {})
    def walk(node):
        if isinstance(node, dict):
            if "$ref" in node:
                ref = node["$ref"].split("/")[-1]
                return walk(json.loads(json.dumps(defs[ref])))
            return {k: walk(v) for k, v in node.items()}
        if isinstance(node, list):
            return [walk(x) for x in node]
        return node
    return walk(schema)

FLAT_SCHEMA = flatten_schema(json.loads(json.dumps(RESPONSE_SCHEMA["schema"])))

# ---------- context（照 EntityContextBuilder 格式） ----------
with io.open(SCRATCH + r"\entities.json", encoding="utf-8") as f:
    ENT = json.load(f)

def build_context():
    acc = json.dumps(ENT["accounts"], ensure_ascii=False, separators=(",", ":"))
    cat = json.dumps(ENT["categories"], ensure_ascii=False, separators=(",", ":"))
    prj = json.dumps(ENT["projects"], ensure_ascii=False, separators=(",", ":"))
    return ("[帳戶清單]（hint＝該帳戶的口語別名/辨識提示，見下方規則）\n" + acc
            + "\n\n[分類清單]（path 只是層級背景；請直接對使用者說的名稱）\n" + cat
            + "\n\n[專案清單]\n" + prj)

NOW = "【現在時間】2026-07-22 12:00（星期三）\n"
SYSTEM = SYSTEM_PROMPT + "\n" + NOW + "\n" + build_context()

# ---------- 呼叫模型 ----------
def call_model(model, user_text, timeout=300):
    body = {
        "model": model,
        "stream": False,
        "options": {"temperature": 0, "num_ctx": 16384},
        "format": FLAT_SCHEMA,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": user_text},
        ],
    }
    if os.environ.get("NOTHINK") == "1":
        body["think"] = False   # qwen3 系 thinking 模型：關掉思考直接出 JSON
    req = urllib.request.Request(OLLAMA, json.dumps(body).encode("utf-8"),
                                 {"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        resp = json.load(r)
    dt = time.time() - t0
    content = resp["message"]["content"]
    return json.loads(content), dt

# ---------- 計分 ----------
def pick_id(v):
    """模型欄位是 {id,confidence,alternatives} 或純值；統一取 id。"""
    if isinstance(v, dict): return v.get("id")
    return v

def match(gold, got):
    if isinstance(gold, list):
        if len(gold) > 0 and isinstance(gold[0], dict):  # splits
            if not isinstance(got, list) or len(got) != len(gold): return False
            used = [False] * len(got)
            for g in gold:
                ok = False
                for i, s in enumerate(got):
                    if used[i]: continue
                    if match(g.get("amount"), s.get("amount")) and \
                       match(g.get("category"), pick_id(s.get("category"))):
                        used[i] = True; ok = True; break
                if not ok: return False
            return True
        return got in gold
    return gold == got

def score_case(gold, parsed):
    fields = {}
    for k, gv in gold.items():
        if k in ("account", "to_account", "category", "project"):
            got = pick_id(parsed.get(k))
        elif k == "splits":
            got = parsed.get(k)
        else:
            got = parsed.get(k)
        fields[k] = (match(gv, got), got)
    return fields

def main():
    model = sys.argv[1]
    only = set(int(x) for x in sys.argv[2:]) if len(sys.argv) > 2 else None
    with io.open(SCRATCH + r"\testset.json", encoding="utf-8") as f:
        cases = json.load(f)["cases"]
    results, n_field_ok, n_field = [], 0, 0
    n_case_ok = 0
    for c in cases:
        if only and c["id"] not in only: continue
        try:
            parsed, dt = call_model(model, c["text"])
            fields = score_case(c["gold"], parsed)
            ok_all = all(v[0] for v in fields.values())
        except Exception as e:
            parsed, dt, fields, ok_all = {"_error": str(e)}, -1, {}, False
        n_case_ok += 1 if ok_all else 0
        for k, (ok, got) in fields.items():
            n_field += 1; n_field_ok += 1 if ok else 0
        results.append({"id": c["id"], "text": c["text"], "ok": ok_all, "sec": round(dt, 1),
                        "fields": {k: {"ok": ok, "gold": c["gold"][k], "got": got}
                                   for k, (ok, got) in fields.items()},
                        "raw": parsed})
        sys.stderr.write("case %d %s %.1fs\n" % (c["id"], "OK " if ok_all else "FAIL", dt))
    total = len(results)
    summary = {"model": model, "cases": total, "case_pass": n_case_ok,
               "case_acc": round(n_case_ok / total, 3) if total else 0,
               "field_acc": round(n_field_ok / n_field, 3) if n_field else 0}
    out = {"summary": summary, "results": results}
    fn = SCRATCH + r"\result_" + re.sub(r"[^\w.]", "_", model) + ".json"
    with io.open(fn, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False))

if __name__ == "__main__":
    main()
