#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把分類排序從一份舊備份移植到另一份備份。

用途：分類頁的「A-Z 排序」按下去就回不去了——Financisto 的分類順序不存 sort_order，
而是存在 nested set 的 left/right 裡，所以「恢復排序」等於把每個分類的 left/right 重算。
誤觸之後唯一的救法就是拿排序前的備份當順序來源，套回現在的資料上。

    python tools/restore_category_order.py --data <新備份> --order <排序前的備份> \
        [--hoist 收入] [--out 檔名] [--dry-run]

--data   提供**資料**（交易、帳戶……）的備份，通常是最新那份
--order  提供**分類順序**的備份，也就是誤觸排序之前的那份
--hoist  把某個頂層分類移到最前面，可重複給（給的順序就是它們排在最前面的順序）

輸出是一份新的 .backup，不動任何既有檔案。除了分類的 left/right 之外一個位元組都不改，
寫檔前會把這件事連同 nested set 的合法性一起驗證過，驗不過就不寫。
"""

import argparse
import collections
import gzip
import os
import re
import sys


def read(path):
    return gzip.open(path, "rt", encoding="utf-8").read()


def categories(txt):
    """回傳依 left 排序的分類列表。"""
    out = []
    for blk in txt.split("$ENTITY:"):
        if not blk.startswith("category\n"):
            continue
        d = {}
        for line in blk.split("\n")[1:]:
            if line == "$$":
                break
            k, _, v = line.partition(":")
            if k:
                d[k] = v
        out.append({"id": int(d["_id"]), "title": d.get("title", ""),
                    "left": int(d["left"]), "right": int(d["right"]),
                    "income": d.get("type") == "1"})
    out.sort(key=lambda c: c["left"])
    return out


def tree(cs):
    """nested set → (每個節點的子節點依序, 每個節點的父節點)。頂層掛在 0 底下。"""
    kids, parent, stack = {0: []}, {}, []
    for c in cs:
        while stack and c["left"] > stack[-1]["right"]:
            stack.pop()
        p = stack[-1]["id"] if stack else 0
        kids.setdefault(p, []).append(c["id"])
        kids.setdefault(c["id"], [])
        parent[c["id"]] = p
        stack.append(c)
    return kids, parent


def merge_order(order_kids, data_kids, data_parent, titles, log):
    """以 order 的順序為準，把只存在於 data 的分類補在它父層的最後面。

    為什麼要補而不是報錯：排序誤觸之後到現在，中間很可能又新增過分類——那些在順序來源
    裡查不到，但不能因此就不修。放在最後面是最不意外的位置。
    """
    merged = {}
    for node in data_kids:
        ordered = [i for i in order_kids.get(node, []) if i in data_kids]
        extra = [i for i in data_kids[node] if i not in ordered]
        for i in extra:
            log.append("  新分類「%s」不在順序來源裡，排在其父層最後" % titles[i])
        merged[node] = ordered + extra
    return merged


def assign(kids, roots):
    """依 kids 的順序做 DFS 重編 left/right。"""
    pos, counter = {}, [1]

    def walk(node):
        left = counter[0]
        counter[0] += 1
        for child in kids[node]:
            walk(child)
        pos[node] = (left, counter[0])
        counter[0] += 1

    for r in roots:
        walk(r)
    return pos


def rewrite(txt, pos):
    """只改 category 區塊的 left/right 兩行。"""
    parts = []
    for i, blk in enumerate(txt.split("$ENTITY:")):
        if i == 0 or not blk.startswith("category\n"):
            parts.append(blk)
            continue
        cid = int(re.search(r"^_id:(-?\d+)$", blk, re.M).group(1))
        l, r = pos[cid]
        blk = re.sub(r"^left:\d+$", "left:%d" % l, blk, count=1, flags=re.M)
        blk = re.sub(r"^right:\d+$", "right:%d" % r, blk, count=1, flags=re.M)
        parts.append(blk)
    return "$ENTITY:".join(parts)


def verify(before, after, order_kids, expect_tops, titles):
    """寫檔前的把關。回傳 (每項檢查的結果, 全部通過與否)。

    這是整支腳本最重要的部分：還原備份會整個蓋掉資料庫，出錯的代價是整本帳。
    """
    checks = []
    a, b = before.split("\n"), after.split("\n")
    checks.append(("行數不變", len(a) == len(b), "%d 行" % len(b)))
    diff = [(x, y) for x, y in zip(a, b) if x != y]
    off = [d for d in diff if not re.match(r"^(left|right):\d+$", d[0])]
    checks.append(("只有 left/right 被改", not off,
                   "%d 行有差異，非 left/right 的 %d 行" % (len(diff), len(off))))

    cs = categories(after)
    n = len(cs)
    bounds = sorted([c["left"] for c in cs] + [c["right"] for c in cs])
    checks.append(("nested set 連續無重複", bounds == list(range(1, n * 2 + 1)),
                   "1..%d" % (n * 2)))
    checks.append(("每個節點區間合法",
                   all(c["right"] > c["left"] and (c["right"] - c["left"]) % 2 == 1 for c in cs),
                   "%d 個分類" % n))

    kids, _ = tree(cs)
    same_children = all(sorted(order_kids[k]) == sorted(kids.get(k, []))
                        for k in order_kids if k in kids)
    checks.append(("父子關係與順序來源相同", same_children, ""))
    sub_same = all(kids[k] == [i for i in order_kids.get(k, []) if i in kids]
                   + [i for i in kids[k] if i not in order_kids.get(k, [])]
                   for k in kids if k != 0)
    checks.append(("各層子分類順序與順序來源相同", sub_same, ""))
    checks.append(("頂層順序符合預期", kids[0] == expect_tops,
                   " → ".join(titles[i] for i in kids[0])))

    cnt = lambda t: collections.Counter(x.split("\n")[0] for x in t.split("$ENTITY:")[1:])
    same_counts = cnt(before) == cnt(after)
    checks.append(("各類實體數量不變", same_counts,
                   "交易 %d 筆" % cnt(after).get("transactions", 0)))
    return checks, all(ok for _, ok, _ in checks)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True, help="提供資料的備份（通常是最新那份）")
    ap.add_argument("--order", required=True, help="提供分類順序的備份（誤觸排序之前那份）")
    ap.add_argument("--hoist", action="append", default=[], help="移到最前面的頂層分類名，可重複")
    ap.add_argument("--out", help="輸出檔名，預設為 <data 同目錄>/分類排序修復_<日期>.backup")
    ap.add_argument("--dry-run", action="store_true", help="只驗證不寫檔")
    args = ap.parse_args()

    data_txt, order_txt = read(args.data), read(args.order)
    data_cs, order_cs = categories(data_txt), categories(order_txt)
    titles = {c["id"]: c["title"] for c in data_cs}
    print("資料來源 %s（分類 %d）" % (os.path.basename(args.data), len(data_cs)))
    print("順序來源 %s（分類 %d）" % (os.path.basename(args.order), len(order_cs)))

    order_kids, order_parent = tree(order_cs)
    data_kids, data_parent = tree(data_cs)

    moved = [i for i in set(order_parent) & set(data_parent)
             if order_parent[i] != data_parent[i]]
    if moved:
        sys.exit("兩份備份的父子關係不同，無法安全移植：%s"
                 % "、".join(titles.get(i, str(i)) for i in moved))

    log = []
    kids = merge_order(order_kids, data_kids, data_parent, titles, log)
    for line in log:
        print(line)

    tops = list(kids[0])
    for name in reversed(args.hoist):
        hit = [i for i in tops if titles.get(i) == name]
        if len(hit) != 1:
            sys.exit("找不到（或不只一個）頂層分類「%s」" % name)
        tops.remove(hit[0])
        tops.insert(0, hit[0])
    kids[0] = tops

    out_txt = rewrite(data_txt, assign(kids, tops))
    checks, ok = verify(data_txt, out_txt, order_kids, tops, titles)
    print()
    for name, passed, detail in checks:
        print("  %s %-24s %s" % ("✓" if passed else "✗", name, detail))
    if not ok:
        sys.exit("\n驗證未通過，不寫檔。")

    if args.dry_run:
        print("\n--dry-run：驗證通過，未寫檔。")
        return
    out = args.out or os.path.join(os.path.dirname(os.path.abspath(args.data)),
                                   "分類排序修復_%s.backup"
                                   % os.path.basename(args.data)[:8])
    with gzip.open(out, "wt", encoding="utf-8", newline="") as f:
        f.write(out_txt)
    print("\n寫出 %s" % out)
    print("還原前務必先在 app 內做一次備份——還原會整個蓋掉資料庫。")


if __name__ == "__main__":
    main()
