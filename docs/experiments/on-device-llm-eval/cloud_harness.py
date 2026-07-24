# -*- coding: utf-8 -*-
"""雲端版記帳解析評測 harness：同一套 prompt+schema 打 OpenAI 相容端點，對 gold 計分。
與 app 的 BookkeepingParser 文字解析路徑等價（response_format=json_schema，送完整 RESPONSE_SCHEMA）。

用法：
  set GEMINI_API_KEY=...   (或 OPENAI_API_KEY / GROQ_API_KEY)
  python cloud_harness.py <provider:gemini|openai|groq> <model> [case_id ...]

key 一律讀環境變數（GEMINI_API_KEY / OPENAI_API_KEY / GROQ_API_KEY），不寫死、不進 repo。
entities.json / testset.json 與本檔同目錄（含個人資料，gitignore）；prompt+schema 從
app 的 BookkeepingParser.java 現抽，保證與 app 一字不差。now 固定 2026-07-22 12:00（週三）。
"""
import io, json, os, re, sys, time, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA = os.path.normpath(os.path.join(
    HERE, "..", "..", "..", "app", "src", "main", "java",
    "tw", "tib", "financisto", "ai", "BookkeepingParser.java"))

BASE = {
    "gemini": "https://generativelanguage.googleapis.com/v1beta/openai",
    "openai": "https://api.openai.com/v1",
    "groq": "https://api.groq.com/openai/v1",
}
KEY_ENV = {"gemini": "GEMINI_API_KEY", "openai": "OPENAI_API_KEY", "groq": "GROQ_API_KEY"}

def extract_java_string(src, const_name):
    m = re.search(const_name + r"\s*=\s*(.*?);", src, re.S)
    if not m: raise RuntimeError("const not found: " + const_name)
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
    return "".join(parts).replace("\\n", "\n").replace('\\"', '"')

with io.open(JAVA, encoding="utf-8") as f:
    java_src = f.read()
SYSTEM_PROMPT = extract_java_string(java_src, "SYSTEM_PROMPT")
RESPONSE_SCHEMA = json.loads(extract_java_string(java_src, "RESPONSE_SCHEMA"))  # {name,strict,schema}

with io.open(os.path.join(HERE, "entities.json"), encoding="utf-8") as f:
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

def get_key(provider):
    k = os.environ.get(KEY_ENV[provider])
    if not k: raise RuntimeError("需要環境變數 " + KEY_ENV[provider])
    return k

def is_reasoning(model):
    return model.startswith(("gpt-5", "o1", "o3", "o4"))

def call_model(base, key, model, user_text, timeout=180):
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": user_text},
        ],
        "response_format": {"type": "json_schema", "json_schema": RESPONSE_SCHEMA},
    }
    # 推理模型（GPT-5／o 系）多半只吃預設 temperature，送 0 會 400；非推理才固定 0
    if not is_reasoning(model):
        body["temperature"] = 0
    req = urllib.request.Request(base + "/chat/completions",
                                json.dumps(body).encode("utf-8"),
                                {"Content-Type": "application/json",
                                 "Authorization": "Bearer " + key})
    for attempt in range(5):
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                resp = json.load(r)
            return json.loads(resp["choices"][0]["message"]["content"]), time.time() - t0
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < 4:
                time.sleep(8 * (attempt + 1)); continue
            raise

def pick_id(v):
    return v.get("id") if isinstance(v, dict) else v

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
        got = pick_id(parsed.get(k)) if k in ("account", "to_account", "category", "project") \
              else parsed.get(k)
        fields[k] = (match(gv, got), got)
    return fields

def main():
    provider, model = sys.argv[1], sys.argv[2]
    only = set(int(x) for x in sys.argv[3:]) if len(sys.argv) > 3 else None
    base, key = BASE[provider], get_key(provider)
    with io.open(os.path.join(HERE, "testset.json"), encoding="utf-8") as f:
        cases = json.load(f)["cases"]
    results, n_field_ok, n_field, n_case_ok = [], 0, 0, 0
    for c in cases:
        if only and c["id"] not in only: continue
        try:
            parsed, dt = call_model(base, key, model, c["text"])
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
        sys.stderr.write("case %2d %s %.1fs\n" % (c["id"], "OK  " if ok_all else "FAIL", dt))
        time.sleep(0.5)
    total = len(results)
    summary = {"provider": provider, "model": model, "cases": total, "case_pass": n_case_ok,
               "case_acc": round(n_case_ok / total, 3) if total else 0,
               "field_acc": round(n_field_ok / n_field, 3) if n_field else 0}
    fn = os.path.join(HERE, "cloud_result_" + re.sub(r"[^\w.]", "_", model) + ".json")
    with io.open(fn, "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "results": results}, f, ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False))

if __name__ == "__main__":
    main()
