#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AI 解析語料庫：累積 + 回歸檢測。

手機端的解析紀錄只留最後 1000 筆，會被捲掉；這支腳本把每次匯出的紀錄併進電腦端一份
只增不減的語料庫，並對它跑一組**不變式檢查**，依 prompt 版本分組出報表。

回歸怎麼看：每筆紀錄都帶 `pv`（規則文字的指紋）與 `sha`（建置當下的 commit）。改完
prompt 或解析程式、出新版、用幾天之後再 ingest，report 就會多出一組新的 pv——把兩列
並排看，哪條檢查變好、哪條變壞一目了然。沒有這個分組，語料只能證明「模型某天答錯了」。

不變式是「機器驗得出對錯」的規則。它們同時是一份清單，說明**哪些規則不該留在 prompt 裡**
——驗證只需要字串比對或查表的，程式做得比模型穩。每條檢查標了 owner：
  prompt = 目前只寫在 prompt、靠模型自覺
  code   = 已經在程式裡強制
違規率高又 owner=prompt 的，就是下一個該搬進程式的。

⚠️ 紀錄存的是**模型的原始輸出**，不是後處理過的結果。所以 owner=code 那幾條的數字不是
「使用者受害幾次」，而是「程式攔下來幾次」——數字不會因為搬進程式就歸零，它量的是模型
在這件事上有多不可靠。真正該歸零的是使用者看到的錯誤。

用法：
    python tools/ai_corpus.py ingest [--source DIR|FILE ...] [--ledger DIR]
    python tools/ai_corpus.py report [--by pv|sha|month] [--limit N] [--json OUT]
    python tools/ai_corpus.py checks

第一次跑 ingest 時給 --source / --ledger，之後會記在 local-backups/ai-corpus/config.json
（gitignored），不必再打。
"""

import argparse
import glob
import gzip
import hashlib
import io
import json
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORPUS_DIR = os.path.join(ROOT, "local-backups", "ai-corpus")
CORPUS = os.path.join(CORPUS_DIR, "corpus.jsonl")
CONFIG = os.path.join(CORPUS_DIR, "config.json")
EXPORT_NAME = "ai-log.jsonl"          # 與 AiLog.EXPORT_FILE_NAME 一致


# --------------------------------------------------------------------------- 讀取

def load_config():
    if os.path.exists(CONFIG):
        with io.open(CONFIG, encoding="utf-8") as f:
            return json.load(f)
    return {"sources": [], "ledger": None}


def save_config(cfg):
    os.makedirs(CORPUS_DIR, exist_ok=True)
    with io.open(CONFIG, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


def read_jsonl(path):
    out = []
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except ValueError:
                pass
    return out


LEGACY_HEAD = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)(?:\s+\[([^\]]*)\])?(?:\s+\[聽:([^\]]*)\])?")


def read_legacy(path):
    """吃 2026-08-09 之前那種「人看的排版」匯出檔。

    這個格式是給人讀的，反解本來就脆——只在 ingest 舊檔時用一次，之後手機直接吐 JSONL。
    解不出來的區塊寧可丟掉也不要猜，語料裡混進錯資料比少幾筆糟得多。
    """
    txt = io.open(path, encoding="utf-8").read()
    out = []
    for blk in re.split(r"\n(?=\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)", txt):
        m = LEGACY_HEAD.match(blk)
        if not m:
            continue
        e = {"at": m.group(1), "pv": "legacy"}
        if m.group(2):
            e["model"] = m.group(2)
        if m.group(3):
            e["stt"] = m.group(3)
        said = re.search(r"^說：(.*?)(?=\n(?:解析：|失敗：|（補充模式）|【|$))", blk, re.S | re.M)
        if said:
            e["said"] = said.group(1).strip()
        if "（補充模式）" in blk:
            e["mode"] = "supplement"
        got = re.search(r"^解析：(\{.*)", blk, re.S | re.M)
        if got:
            raw = got.group(1).strip()
            try:
                json.loads(raw)
            except ValueError:
                continue
            e["got"] = raw
        err = re.search(r"^失敗：(.*)$", blk, re.M)
        if err:
            e["error"] = err.group(1).strip()
        if "got" in e or "error" in e:
            out.append(e)
    return out


def entry_key(e):
    """去重鍵。時間戳幾乎唯一，但同秒可能兩筆，所以連內容一起雜湊。"""
    raw = "|".join([str(e.get("at", "")), str(e.get("said", "")),
                    str(e.get("got", "")), str(e.get("error", "")),
                    str(e.get("kind", "")), str(e.get("attempt", ""))])
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()


def collect_sources(paths):
    files = []
    for p in paths:
        if os.path.isdir(p):
            # 名字用 glob 抓：SAF 建檔時會依 mime type 自己補副檔名，
            # 匯出的檔實際落地可能是 ai-log.jsonl.json 而不是 ai-log.jsonl
            files.extend(sorted(glob.glob(os.path.join(p, "ai-log*"))))
            files.extend(sorted(glob.glob(os.path.join(p, "*.txt"))))
        else:
            files.append(p)
    return [f for f in files if os.path.exists(f)]


def read_any(path):
    """看內容決定怎麼讀，不看副檔名——副檔名不是我們說了算（見 collect_sources）。"""
    try:
        with io.open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                json.loads(line)      # 第一行就是一個 JSON 物件＝JSONL
                return read_jsonl(path)
    except (ValueError, OSError):
        pass
    return read_legacy(path)


# --------------------------------------------------------------------------- 實體清單

def load_snapshots(ledger_dir):
    """把資料夾裡**每一份**帳本備份都讀成一個「當時的實體清單」快照，依時間排好。

    為什麼不是只讀最新一份：帳戶與分類會長出來。拿今天的名單去驗三週前的紀錄，
    那天還不存在的分類會被誤判成「模型亂編 id」——第一次跑就踩到（category=59）。
    每筆紀錄要對照的是**它發生當下**的名單。
    """
    if not ledger_dir:
        return []
    snaps = []
    for path in sorted(glob.glob(os.path.join(ledger_dir, "*.backup")), key=os.path.getmtime):
        ents = parse_backup(path)
        if ents:
            snaps.append(ents)
    return snaps


def snapshot_for(snaps, at):
    """取「不晚於這筆紀錄」的最後一份快照；比最舊備份還早的紀錄就用最舊那份。"""
    if not snaps:
        return None
    chosen = snaps[0]
    for s in snaps:
        if s["at"] <= (at or ""):
            chosen = s
        else:
            break
    return chosen


def parse_backup(path):
    try:
        with gzip.open(path, "rt", encoding="utf-8") as f:
            txt = f.read()
    except OSError:
        with io.open(path, encoding="utf-8", errors="replace") as f:
            txt = f.read()
    ents = defaultdict(list)
    for blk in txt.split("$ENTITY:"):
        nl = blk.find("\n")
        if nl < 0:
            continue
        name, body = blk[:nl], blk[nl + 1:]
        d = {}
        for line in body.split("\n"):
            if line == "$$":
                break
            k, _, v = line.partition(":")
            if k:
                d[k] = v
        if d:
            ents[name].append(d)
    def names(kind, active_only=True):
        out = {}
        for d in ents.get(kind, []):
            if active_only and d.get("is_active") == "0":
                continue
            if d.get("title") and d.get("_id"):
                out[d["title"]] = int(d["_id"])
        return out
    import datetime
    return {
        "source": os.path.basename(path),
        "at": datetime.datetime.fromtimestamp(os.path.getmtime(path)).strftime("%Y-%m-%d %H:%M:%S"),
        "accounts": names("account"),
        "categories": names("category", active_only=False),
        "projects": names("project"),
    }


# --------------------------------------------------------------------------- 不變式

def _t(e):
    try:
        return json.loads(e["got"]) if e.get("got") else None
    except ValueError:
        return None


def _pick(d, field):
    v = (d or {}).get(field) or {}
    return v.get("id") if isinstance(v, dict) else None


def _supplement(e):
    return e.get("mode") == "supplement"


def chk_note_is_account(e, ent):
    """備註整段等於某個帳戶的名字＝帳戶詞被丟進備註了。

    只在 prompt 裡交代（§帳戶名是一般語詞），模型照樣會犯——這種「字串等於清單裡某個名字」
    的判斷是程式的強項，是最該搬進程式的一條。
    """
    d = _t(e)
    if not d or not ent:
        return None
    note = (d.get("note") or "").strip()
    if note and note in ent["accounts"]:
        chosen = _pick(d, "account")
        aimed = ent["accounts"][note]
        if chosen == aimed:
            return "note=帳戶名「%s」（與所選帳戶相同，純贅字）" % note
        return "note=帳戶名「%s」(id=%s) 但帳戶選了 id=%s" % (note, aimed, chosen)
    return None


def chk_note_is_category(e, ent):
    """備註整段等於某個分類名＝那是在指定分類，不是描述。"""
    d = _t(e)
    if not d or not ent:
        return None
    note = (d.get("note") or "").strip()
    if note and note in ent["categories"] and _pick(d, "category") is None:
        return "note=分類名「%s」但 category 空著" % note
    return None


def chk_to_account_without_transfer(e, ent):
    """非轉帳卻填了轉入帳戶。一行 if 就能保證的事，不必占 prompt。"""
    d = _t(e)
    if not d:
        return None
    if d.get("transaction_type") not in (None, "transfer") and _pick(d, "to_account") is not None:
        return "type=%s 卻有 to_account=%s" % (d.get("transaction_type"), _pick(d, "to_account"))
    return None


def chk_invalid_id(e, ent):
    """模型編出清單裡沒有的 id。程式已經擋掉（readPick），這裡量的是模型有多常這麼做。"""
    d = _t(e)
    if not d or not ent:
        return None
    bad = []
    for field, key in (("account", "accounts"), ("to_account", "accounts"),
                       ("category", "categories"), ("project", "projects")):
        i = _pick(d, field)
        if i is not None and i not in set(ent[key].values()):
            bad.append("%s=%s" % (field, i))
    return "清單外的 id：" + "、".join(bad) if bad else None


def chk_split_sum(e, ent):
    """各份分割加總對不上總額。"""
    d = _t(e)
    if not d:
        return None
    splits = d.get("splits") or []
    top = d.get("amount")
    if not splits or top is None:
        return None
    s = 0
    for x in splits:
        if x.get("amount") is None:
            return "有一份分割沒有金額"
        s += x["amount"]
    if abs(s - top) > 0.001:
        return "分割加總 %s ≠ 總額 %s" % (s, top)
    return None


def chk_transfer_missing_side(e, ent):
    """轉帳少一邊。轉出帳戶解不出來是這個功能的老毛病。"""
    d = _t(e)
    if not d or _supplement(e) or d.get("transaction_type") != "transfer":
        return None
    miss = [n for n, f in (("轉出", "account"), ("轉入", "to_account")) if _pick(d, f) is None]
    return "轉帳缺少" + "與".join(miss) + "帳戶" if miss else None


def chk_account_unresolved(e, ent):
    """帳戶完全沒解出來。不是「違規」而是品質指標，但同樣要能逐版比較。"""
    d = _t(e)
    if not d or _supplement(e) or d.get("transaction_type") is None:
        return None
    if _pick(d, "account") is None:
        return "account 未解出"
    return None


def chk_amount_missing(e, ent):
    d = _t(e)
    if not d or _supplement(e) or d.get("transaction_type") is None:
        return None
    if d.get("amount") is None and not (d.get("splits") or []):
        return "沒有金額也沒有分割"
    return None


def chk_degenerate_capture(e, ent):
    """產樣板：{{e}} 這類非貪婪捕捉後面直接接 {{*}} 或收尾＝只會抓到一個字元。"""
    if e.get("kind") != "template" or not e.get("got"):
        return None
    tpl = e["got"]
    for ph in ("{{c}}", "{{e}}", "{{r}}", "{{t}}", "{{x}}"):
        i = tpl.find(ph)
        if i < 0:
            continue
        rest = tpl[i + len(ph):]
        if rest == "" or rest.startswith("{{*}}"):
            return "%s 後面沒有結束標記" % ph
    return None


# id, owner, 說明, 函式
CHECKS = [
    ("note_is_account", "code", "備註被填成帳戶名", chk_note_is_account),
    ("note_is_category", "prompt", "備註被填成分類名且分類空著", chk_note_is_category),
    ("to_account_no_transfer", "code", "非轉帳卻填了轉入帳戶", chk_to_account_without_transfer),
    ("invalid_id", "code", "用了清單外的 id", chk_invalid_id),
    ("split_sum", "prompt", "分割加總對不上總額", chk_split_sum),
    ("transfer_missing_side", "prompt", "轉帳缺一邊帳戶", chk_transfer_missing_side),
    ("account_unresolved", "-", "帳戶未解出（品質指標）", chk_account_unresolved),
    ("amount_missing", "-", "沒有金額（品質指標）", chk_amount_missing),
    ("degenerate_capture", "code", "產樣板：捕捉佔位符沒有結束標記", chk_degenerate_capture),
]


# --------------------------------------------------------------------------- 指令

def cmd_ingest(args):
    cfg = load_config()
    if args.source:
        cfg["sources"] = sorted(set(cfg.get("sources", []) + args.source))
    if args.ledger:
        cfg["ledger"] = args.ledger
    if not cfg.get("sources"):
        sys.exit("沒有來源。第一次請給 --source <備份資料夾或匯出檔>")
    save_config(cfg)

    existing = read_jsonl(CORPUS) if os.path.exists(CORPUS) else []
    seen = {entry_key(e) for e in existing}
    added, per_file = [], []
    for path in collect_sources(cfg["sources"]):
        entries = read_any(path)
        new = [e for e in entries if entry_key(e) not in seen]
        for e in new:
            seen.add(entry_key(e))
        added.extend(new)
        per_file.append((os.path.basename(path), len(entries), len(new)))

    if added:
        os.makedirs(CORPUS_DIR, exist_ok=True)
        with io.open(CORPUS, "a", encoding="utf-8") as f:
            for e in added:
                f.write(json.dumps(e, ensure_ascii=False) + "\n")

    for name, total, new in per_file:
        print("  %-28s 讀到 %4d  新增 %4d" % (name, total, new))
    print("語料庫：%d 筆（本次 +%d）" % (len(existing) + len(added), len(added)))
    print("位置：%s" % CORPUS)


def prompt_commits():
    """改動過 prompt / 解析程式的 commit，用來替沒有 pv 的舊語料做粗略定位。

    ⚠️ 只是粗略：手機上跑的是「上次裝的那包 APK」，不是 commit 當下的碼。舊語料只能說
    「不早於某個 commit」，精確對應從帶 pv 的那一版開始才有。
    """
    files = ["app/src/main/java/tw/tib/financisto/ai/BookkeepingParser.java"]
    try:
        out = subprocess.check_output(
            ["git", "log", "--date=short", "--format=%h %ad %s", "--"] + files,
            cwd=ROOT, stderr=subprocess.DEVNULL).decode("utf-8", "replace")
    except Exception:
        return []
    rows = []
    for line in out.strip().split("\n"):
        parts = line.split(" ", 2)
        if len(parts) == 3:
            rows.append(tuple(parts))
    return rows


def group_of(e, by):
    if by == "sha":
        return e.get("sha", "legacy")
    if by == "month":
        return (e.get("at") or "")[:7] or "?"
    return e.get("pv", "legacy")


def cmd_report(args):
    cfg = load_config()
    if not os.path.exists(CORPUS):
        sys.exit("語料庫還是空的，先跑 ingest")
    entries = read_jsonl(CORPUS)
    snaps = load_snapshots(cfg.get("ledger"))
    print("語料 %d 筆" % len(entries))
    print("實體清單快照 %d 份%s" % (
        len(snaps),
        "（%s ～ %s）" % (snaps[0]["at"][:10], snaps[-1]["at"][:10]) if snaps
        else "：未提供 --ledger，需要名單的檢查會跳過"))

    def ent_for(e):
        return snapshot_for(snaps, e.get("at"))

    groups = defaultdict(list)
    for e in entries:
        groups[group_of(e, args.by)].append(e)

    def sort_key(g):
        return min((x.get("at") or "") for x in groups[g])

    print("\n== 依 %s 分組 ==" % args.by)
    header = "%-10s %-19s %5s" % (args.by, "首見", "筆數")
    for cid, _, _, _ in CHECKS:
        header += " %14s" % cid[:14]
    print(header)
    for g in sorted(groups, key=sort_key):
        rows = groups[g]
        line = "%-10s %-19s %5d" % (g[:10], sort_key(g), len(rows))
        for cid, _, _, fn in CHECKS:
            hits = sum(1 for e in rows if fn(e, ent_for(e)))
            applicable = sum(1 for e in rows if _t(e) is not None or e.get("kind") == "template")
            pct = (hits * 100 // applicable) if applicable else 0
            line += " %8d %4s" % (hits, ("%d%%" % pct) if applicable else "-")
        print(line)

    print("\n== 檢查清單 ==")
    for cid, owner, desc, _ in CHECKS:
        print("  %-22s [%-6s] %s" % (cid, owner, desc))
    print("  owner=prompt 且違規率高的，就是下一條該搬進程式的規則。")

    print("\n== 違規明細（每項最多 %d 筆）==" % args.limit)
    for cid, owner, desc, fn in CHECKS:
        if owner == "-":
            continue
        bad = [(e, fn(e, ent_for(e))) for e in entries]
        bad = [(e, r) for e, r in bad if r]
        if not bad:
            continue
        print("\n-- %s（%d 筆）" % (cid, len(bad)))
        for e, reason in bad[-args.limit:]:
            said = (e.get("said") or "").replace("\n", " ")[:40]
            print("   %s [%s] %s" % (e.get("at"), e.get("pv", "legacy")[:8], said))
            print("      %s" % reason)

    legacy = [e for e in entries if e.get("pv", "legacy") == "legacy"]
    if legacy:
        print("\n== 沒有版本戳的舊語料 %d 筆的粗略定位 ==" % len(legacy))
        print("⚠️ 手機跑的是上次安裝的 APK，不是 commit 當下的碼；只能當「不早於」看待。")
        for sha, date, subj in prompt_commits():
            n = sum(1 for e in legacy if (e.get("at") or "")[:10] >= date)
            print("   %s %s  之後有 %4d 筆  %s" % (sha, date, n, subj[:50]))

    if args.json:
        snap = {"total": len(entries), "by": args.by, "groups": {}}
        for g, rows in groups.items():
            snap["groups"][g] = {"n": len(rows),
                                 "checks": {cid: sum(1 for e in rows if fn(e, ent_for(e)))
                                            for cid, _, _, fn in CHECKS}}
        with io.open(args.json, "w", encoding="utf-8") as f:
            json.dump(snap, f, ensure_ascii=False, indent=2)
        print("\n快照已寫入 %s" % args.json)


def cmd_checks(args):
    for cid, owner, desc, fn in CHECKS:
        print("%-22s [%-6s] %s" % (cid, owner, desc))
        doc = (fn.__doc__ or "").strip()
        for line in doc.split("\n"):
            print("    " + line.strip())
        print()


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    pi = sub.add_parser("ingest", help="把新的匯出併進語料庫（去重、只增不減）")
    pi.add_argument("--source", action="append", help="備份資料夾或匯出檔，可重複給")
    pi.add_argument("--ledger", help="放帳本 .backup 的資料夾（取實體清單用）")
    pi.set_defaults(func=cmd_ingest)

    pr = sub.add_parser("report", help="不變式報表，依版本分組")
    pr.add_argument("--by", choices=["pv", "sha", "month"], default="pv")
    pr.add_argument("--limit", type=int, default=5, help="每項違規列幾筆明細")
    pr.add_argument("--json", help="把數字快照寫成 JSON")
    pr.set_defaults(func=cmd_report)

    pc = sub.add_parser("checks", help="列出不變式清單與理由")
    pc.set_defaults(func=cmd_checks)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
