# -*- coding: utf-8 -*-
"""補充模式回歸 harness：把 AiLog 匯出檔裡的「補充模式」case 原樣重放給模型，
檢查新的 type_change_quote 閘門會怎麼判。

與 app 的補充路徑等價：system = SYSTEM_PROMPT + SUPPLEMENT_RULE + now + 實體清單 + 表單狀態，
response_format 送同一份 RESPONSE_SCHEMA（都從 BookkeepingParser.java 現抽，保證一字不差）。

用法：
  set GEMINI_API_KEY=...
  python supplement_harness.py <provider> <model> <log.txt> [log2.txt ...]

輸出每一筆：說了什麼 / 表單型別 / 模型回的型別 / 引文 / 引文是否真的在原句裡 / app 會怎麼做。
"""
import io, json, os, re, sys, urllib.request, urllib.error

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
    if not m:
        raise RuntimeError("const not found: " + const_name)
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
    return "".join(parts).replace("\\n", "\n").replace('\\"', '"')


with io.open(JAVA, encoding="utf-8") as f:
    java_src = f.read()
SYSTEM_PROMPT = extract_java_string(java_src, "SYSTEM_PROMPT")
SUPPLEMENT_RULE = extract_java_string(java_src, "SUPPLEMENT_RULE")
RESPONSE_SCHEMA = json.loads(extract_java_string(java_src, "RESPONSE_SCHEMA"))

with io.open(os.path.join(HERE, "entities.json"), encoding="utf-8") as f:
    ENT = json.load(f)


def build_context():
    acc = json.dumps(ENT["accounts"], ensure_ascii=False, separators=(",", ":"))
    cat = json.dumps(ENT["categories"], ensure_ascii=False, separators=(",", ":"))
    prj = json.dumps(ENT["projects"], ensure_ascii=False, separators=(",", ":"))
    return ("[帳戶清單]（hint＝該帳戶的口語別名/辨識提示，見下方規則）\n" + acc
            + "\n\n[分類清單]（path 只是層級背景；請直接對使用者說的名稱）\n" + cat
            + "\n\n[專案清單]\n" + prj)


NOW = "【現在時間】2026-08-01 20:00（星期六）\n"


def parse_log(path):
    """從 AiLog 匯出檔撈出補充模式的 case：原句 + 當時的表單狀態區塊。"""
    with io.open(path, encoding="utf-8") as f:
        raw = f.read()
    out = []
    for b in re.split(r"(?m)^(?=\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})", raw):
        b = b.strip()
        if not b or "（補充模式）" not in b:
            continue
        said = re.search(r"(?m)^說：(.*)$", b)
        form = re.search(r"(?s)(【目前表單已填內容】.*?)\n\n", b)
        if not said or not form:
            continue
        ftype = re.search(r"(?m)^型別：(.*)$", form.group(1))
        out.append({
            "at": b[:16],
            "said": said.group(1),
            "form": form.group(1).strip(),
            "form_type": ftype.group(1).strip() if ftype else "?",
        })
    return out


def call_model(base, key, model, system, user_text, timeout=120):
    body = json.dumps({
        "model": model,
        "temperature": 0,
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": user_text}],
        "response_format": {"type": "json_schema", "json_schema": RESPONSE_SCHEMA},
    }, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        base + "/chat/completions", data=body,
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.loads(r.read().decode("utf-8"))
    return json.loads(data["choices"][0]["message"]["content"])


PUNCT = re.compile(r"[\s，,。.、；;：:！!？?（）()「」\"'~-]")


def squeeze(s):
    return PUNCT.sub("", s or "")


def predict(form_type, ty, quote_ok):
    """複製 app 的判斷：型別 null 就不動；要換整張表單才需要引文，純方向不用。"""
    if ty is None:
        return "不動型別（只套欄位）"
    form_is_transfer = form_type == "轉帳"
    form_is_balance = form_type == "調整餘額"
    want_transfer = ty == "transfer"
    want_balance = ty == "balance"
    need_switch = form_is_balance or want_balance or (want_transfer != form_is_transfer)
    if not need_switch:
        return "同一張表單 → 方向設為 " + ("收入" if ty == "income" else "支出")
    return ("換表單 → " + ty) if quote_ok else "★擋下換表單（引文驗不過）"


def main():
    provider, model = sys.argv[1], sys.argv[2]
    logs = sys.argv[3:]
    key = os.environ.get(KEY_ENV[provider])
    if not key:
        raise SystemExit("需要環境變數 " + KEY_ENV[provider])
    system_base = SYSTEM_PROMPT + SUPPLEMENT_RULE + "\n" + NOW + "\n" + build_context()

    cases = []
    for p in logs:
        cases.extend(parse_log(p))
    print("共 %d 個補充模式 case，model=%s\n" % (len(cases), model))
    print("%-16s %-28s %-6s %-9s %-5s %s" % ("時間", "說的話", "表單", "模型型別", "引文", "app 行為"))
    print("-" * 130)
    for c in cases:
        system = system_base + "\n" + c["form"]
        try:
            d = call_model(BASE[provider], key, model, system, c["said"])
        except urllib.error.HTTPError as e:
            print("%-16s %-28s  HTTP %s %s" % (c["at"], c["said"][:28], e.code,
                                               e.read().decode("utf-8", "replace")[:120]))
            continue
        ty = d.get("transaction_type")
        quote = d.get("type_change_quote")
        quote_ok = bool(quote) and squeeze(quote) in squeeze(c["said"])
        mark = "-" if not quote else ("✓" if quote_ok else "✗編")
        print("%-16s %-28s %-6s %-9s %-5s %s" % (
            c["at"], c["said"][:28], c["form_type"], ty or "null", mark,
            predict(c["form_type"], ty, quote_ok)))
        if quote:
            print("%-16s   引文：%s" % ("", quote))


if __name__ == "__main__":
    main()
