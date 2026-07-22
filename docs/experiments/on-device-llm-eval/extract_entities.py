# -*- coding: utf-8 -*-
"""從 Financisto .backup 抽 AI context 需要的清單，照 EntityContextBuilder 的格式輸出 JSON。"""
import gzip, json, sys, io

BACKUP = r"C:\Users\yeh15\dev\financisto-ai\local-backups\20260717_083935_修改前原始.backup"
OUT = r"C:\Users\yeh15\AppData\Local\Temp\claude\C--Users-yeh15-dev-financisto-ai\16622c8c-fe3c-4d25-98b9-7d67faab8703\scratchpad\entities.json"

def parse_backup(path):
    entities = []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        cur = None
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("$ENTITY:"):
                cur = {"_type": line[len("$ENTITY:"):]}
            elif line == "$$":
                if cur: entities.append(cur); cur = None
            elif cur is not None and ":" in line:
                k, v = line.split(":", 1)
                cur[k] = v
    return entities

ents = parse_backup(BACKUP)

# --- currencies (for cur name) ---
currencies = {e["_id"]: e.get("name", "") for e in ents if e["_type"] == "currency"}

# --- accounts: active only, id/name/type/cur/hint(note) ---
accounts = []
for e in ents:
    if e["_type"] != "account": continue
    if e.get("is_active") != "1": continue
    o = {"id": int(e["_id"]), "name": e.get("title",""), "type": e.get("type","")}
    cur = currencies.get(e.get("currency_id",""))
    if cur: o["cur"] = cur
    note = (e.get("note") or "").strip()
    if note: o["hint"] = note
    accounts.append(o)

# --- categories: nested set (left/right) → path ---
cats = [e for e in ents if e["_type"] == "category"]
for c in cats:
    c["left"] = int(c.get("left", 0)); c["right"] = int(c.get("right", 0))
    c["id"] = int(c["_id"])
cats = [c for c in cats if c["id"] > 0]
cats.sort(key=lambda c: c["left"])
categories = []
stack = []  # (right, title)
for c in cats:
    while stack and c["left"] > stack[-1][0]:
        stack.pop()
    path = [t for _, t in stack] + [c.get("title","")]
    ctype = "income" if c.get("type") == "1" else "expense"
    categories.append({"id": c["id"], "path": " > ".join(path), "type": ctype})
    stack.append((c["right"], c.get("title","")))

# --- projects: active ---
projects = []
for e in ents:
    if e["_type"] != "project": continue
    if e.get("is_active") != "1": continue
    pid = int(e["_id"])
    if pid <= 0: continue
    projects.append({"id": pid, "name": e.get("title","")})

out = {"accounts": accounts, "categories": categories, "projects": projects}
with io.open(OUT, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
print("accounts:", len(accounts), "categories:", len(categories), "projects:", len(projects))
