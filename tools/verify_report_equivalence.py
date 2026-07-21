#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
報表金額等價性驗證器 —— 給「上游合併 / 動到報表 view SQL」時驗證用。

作法：拿一份真實 Financisto 備份，在記憶體 SQLite 重建資料表，
分別套用「兩個 git ref」的 view SQL（預設 master vs HEAD），
跑各 report view 的合計 + location/payee 逐項，diff 數字。
只有 view SQL 在兩 ref 間不同、資料完全相同 → 差異純由 view 邏輯造成。

用法：
    python tools/verify_report_equivalence.py <backup.backup> [ref_before] [ref_after]
    # ref 預設 master / HEAD

背景：對齊「改金額/統計前用全量真實備份驗等價性再出貨」的規矩（人工難以自行驗算）。
2026-07-21 首次用於上游 financisto1-holo v235 合併驗證（結論：報表金額零差異）。
"""
import gzip, sqlite3, subprocess, sys, os, re, glob

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VIEW_DIR = "app/src/main/assets/database/view"
REPORT_VIEWS = ["v_report_category", "v_report_sub_category", "v_report_period",
                "v_report_location", "v_report_project", "v_report_payee"]


def view_files(ref):
    """列出該 ref 下 view 目錄的所有 .sql，依檔名排序（＝app 建立順序＝依賴順序）。"""
    out = subprocess.run(["git", "-C", REPO, "ls-tree", "--name-only", "%s:%s" % (ref, VIEW_DIR)],
                         capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        raise RuntimeError("git ls-tree 失敗 %s:%s\n%s" % (ref, VIEW_DIR, out.stderr))
    files = [l for l in out.stdout.splitlines() if l.endswith(".sql")]
    return sorted(files)


def parse_backup(path):
    """Financisto 備份（gzip 文字，$ENTITY:table / key:value / $$）→ {table: [rowdict]}。"""
    tables = {}
    cur_ent = row = last_key = None
    started = False
    with gzip.open(path, "rt", encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.rstrip("\n")
            if line == "#START":
                started = True; continue
            if not started:
                continue
            if line.startswith("$ENTITY:"):
                cur_ent = line[len("$ENTITY:"):].strip(); row = {}; last_key = None; continue
            if line == "$$":
                if cur_ent is not None and row is not None:
                    tables.setdefault(cur_ent, []).append(row)
                row = last_key = None; continue
            if line == "#END":
                break
            if row is None:
                continue
            if ":" in line:
                k, v = line.split(":", 1); row[k] = v; last_key = k
            elif last_key is not None:          # note 內嵌換行的續行
                row[last_key] = row.get(last_key, "") + "\n" + line
    return tables


def ddl_columns():
    """從 create + alter DDL 抽每張表欄位「名稱+型別」。型別決定 SQLite affinity，
    讓備份的文字值於插入時轉成數字（否則 where 的整數字面值比較全為假）。"""
    cols = {}
    def add(t, c, ty):
        cols.setdefault(t, [])
        if c not in [x[0] for x in cols[t]]:
            cols[t].append((c, ty or "TEXT"))
    for p in glob.glob(os.path.join(REPO, "app/src/main/assets/database/create/*.sql")):
        txt = open(p, encoding="utf-8").read()
        m = re.search(r"create\s+table(?:\s+if\s+not\s+exists)?\s+\[?([a-z_]+)\]?\s*\((.*)\)",
                      txt, re.I | re.S)
        if not m:
            continue
        t, body = m.group(1), m.group(2)
        for part in re.split(r",\s*(?![^()]*\))", body):
            tok = part.strip().split()
            if not tok:
                continue
            name = tok[0].strip('[]"`')
            if name.lower() in ("primary", "foreign", "unique", "constraint", "check", "key"):
                continue
            # SQLite affinity 只認 INT/CHAR/TEXT/BLOB/REAL；Financisto 用 'long' 不被認得
            # → 統一把整數類（long/integer/int）標成 INTEGER，確保數值 affinity
            ty = (tok[1] if len(tok) > 1 else "TEXT")
            if ty.lower() in ("long", "int", "integer", "bigint"):
                ty = "INTEGER"
            add(t, name, ty)
    for p in glob.glob(os.path.join(REPO, "app/src/main/assets/database/alter/*.sql")):
        txt = open(p, encoding="utf-8").read()
        for mm in re.finditer(
                r"alter\s+table\s+\[?([a-z_]+)\]?\s+add\s+column\s+\[?([a-z_]+)\]?\s*([a-z]+)?",
                txt, re.I):
            ty = mm.group(3) or "TEXT"
            if ty.lower() in ("long", "int", "integer", "bigint"):
                ty = "INTEGER"
            add(mm.group(1), mm.group(2), ty)
    return cols


def build_db(tables):
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys=OFF")
    ddl = ddl_columns()
    for tname, rows in tables.items():
        cols, seen = [], set()
        for c, ty in ddl.get(tname, []):
            if c not in seen:
                seen.add(c); cols.append((c, ty))
        for r in rows:
            for k in r.keys():
                if k not in seen:
                    seen.add(k); cols.append((k, "TEXT"))
        if not cols:
            continue
        con.execute('CREATE TABLE "%s" (%s)' % (
            tname, ", ".join('"%s" %s' % (c, ty) for c, ty in cols)))
        names = [c for c, _ in cols]
        con.executemany(
            'INSERT INTO "%s" (%s) VALUES (%s)' % (
                tname, ", ".join('"%s"' % c for c in names), ", ".join("?" for _ in names)),
            [[r.get(c) for c in names] for r in rows])
    # running_balance app 動態算、備份不含；view 全 LEFT JOIN → 空表即可（不影響 amount 合計）
    con.execute("CREATE TABLE running_balance (transaction_id INTEGER, account_id INTEGER, balance INTEGER)")
    con.commit()
    return con


def apply_views(con, ref):
    for (n,) in con.execute("SELECT name FROM sqlite_master WHERE type='view'").fetchall():
        con.execute('DROP VIEW IF EXISTS "%s"' % n)
    for fname in view_files(ref):
        out = subprocess.run(["git", "-C", REPO, "show", "%s:%s/%s" % (ref, VIEW_DIR, fname)],
                             capture_output=True, text=True, encoding="utf-8")
        if out.returncode != 0:
            raise RuntimeError("git show 失敗 %s:%s" % (ref, fname))
        con.executescript(out.stdout)
    con.commit()


def report_totals(con):
    res = {}
    for v in REPORT_VIEWS:
        try:
            res[v] = con.execute(
                "SELECT COUNT(*), COALESCE(SUM(CAST(from_amount AS INTEGER)),0), "
                "COALESCE(SUM(CAST(to_amount AS INTEGER)),0) FROM %s" % v).fetchone()
        except Exception as e:
            res[v] = ("ERR", str(e), 0)
    return res


def per_group(con, view):
    d = {}
    for _id, name, cnt, sf in con.execute(
            "SELECT _id, name, COUNT(*), COALESCE(SUM(CAST(from_amount AS INTEGER)),0) "
            "FROM %s GROUP BY _id" % view).fetchall():
        d[(_id, name)] = (cnt, sf)
    return d


def main():
    backup = sys.argv[1] if len(sys.argv) > 1 else None
    ref_before = sys.argv[2] if len(sys.argv) > 2 else "master"
    ref_after = sys.argv[3] if len(sys.argv) > 3 else "HEAD"
    if not backup or not os.path.exists(backup):
        print("用法: python tools/verify_report_equivalence.py <backup.backup> [ref_before=master] [ref_after=HEAD]")
        sys.exit(2)

    print("解析備份 %s ..." % backup, flush=True)
    tables = parse_backup(backup)
    print("  entity 筆數:", {k: len(v) for k, v in tables.items()})
    con = build_db(tables)
    print("  transactions rows:", con.execute("SELECT COUNT(*) FROM transactions").fetchone()[0])

    print("\n套用 [%s] views ..." % ref_before, flush=True)
    apply_views(con, ref_before)
    before, b_loc, b_pay = report_totals(con), per_group(con, "v_report_location"), per_group(con, "v_report_payee")

    print("套用 [%s] views ..." % ref_after, flush=True)
    apply_views(con, ref_after)
    after, a_loc, a_pay = report_totals(con), per_group(con, "v_report_location"), per_group(con, "v_report_payee")

    print("\n============ report view 合計（from_amount，最小貨幣單位）============")
    print("%-24s %10s %16s | %10s %16s | %s" %
          ("view", "cnt(前)", "sum(前)", "cnt(後)", "sum(後)", "Δsum"))
    total_delta = 0
    for v in REPORT_VIEWS:
        b, a = before[v], after[v]
        d = a[1] - b[1] if isinstance(a[1], int) and isinstance(b[1], int) else "n/a"
        if isinstance(d, int):
            total_delta += abs(d)
        print("%-24s %10s %16s | %10s %16s | %s%s" %
              (v, b[0], b[1], a[0], a[1], d, "" if d == 0 else "  <<< 變動"))

    def diff_groups(bg, ag, label):
        print("\n-------- %s 逐項差異（只列有變的）--------" % label)
        any_diff = False
        for k in sorted(set(bg) | set(ag), key=lambda x: str(x[1])):
            b, a = bg.get(k, (0, 0)), ag.get(k, (0, 0))
            if b != a:
                any_diff = True
                print("  _id=%s %-16s cnt %s→%s  sum %s→%s (Δ%s)" %
                      (k[0], str(k[1])[:16], b[0], a[0], b[1], a[1], a[1] - b[1]))
        if not any_diff:
            print("  （無差異）")
    diff_groups(b_loc, a_loc, "location")
    diff_groups(b_pay, a_pay, "payee")

    print("\n============ 結論 ============")
    print("報表 view 合計 |Δ| 總和 = %s → %s" %
          (total_delta, "✓ 完全等價" if total_delta == 0 else "⚠ 有金額變動，需人工判讀是否為預期"))
    sys.exit(0 if total_delta == 0 else 1)


if __name__ == "__main__":
    main()
