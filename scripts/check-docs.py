"""文档门禁：手册/README 的目录、表格、链接、遗留标记与变更记录一致性。

用法：python3 scripts/check-docs.py
退出码非 0 表示存在 FAIL，CI 与提交前本地检查都以此为准。
"""
import os
import re
import sys
import urllib.parse

# 控制台编码非 UTF-8 时（如 Windows GBK），打印含箭头/汉字的 FAIL 行会 UnicodeEncodeError 崩掉门禁
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILES = ["docs/云谕助手项目手册.md", "README.md"]


def slug(t):
    s = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', t.strip().replace('`', ''))
    s = re.sub(r'[^\w一-鿿\s-]', '', s)
    return re.sub(r'\s', '-', s.strip().lower())


def strip_fences(lines):
    out, infence, fence = [], False, None
    for ln in lines:
        st = ln.lstrip()
        if infence:
            if fence and st.startswith(fence):
                infence, fence = False, None
            out.append('')
            continue
        if st.startswith('```') or st.startswith('~~~'):
            infence, fence = True, st[:3]
            out.append('')
            continue
        out.append(ln)
    return out


fail = 0
for rel in FILES:
    raw = open(os.path.join(ROOT, rel), encoding='utf-8').read().split('\n')
    lines = strip_fences(raw)
    plain = [re.sub(r'`[^`]*`', lambda m: ' ' * len(m.group(0)), ln) for ln in lines]
    print(f"\n=== {rel} ({len(raw)} lines) ===")

    # --- 1. TOC <-> headings ---
    headings = []
    infence = False
    for i, ln in enumerate(raw):
        if ln.lstrip().startswith('```'):
            infence = not infence
            continue
        if infence:
            continue
        m = re.match(r'^(#{1,6})\s+(.*)$', ln)
        if m:
            headings.append((len(m.group(1)), m.group(2).strip(), i + 1))
    toc = [(m.group(1), i + 1) for i, ln in enumerate(plain)
           for m in [re.match(r'^\s*-\s*\[[^\]]*\]\(#([^)]+)\)', ln)] if m]
    if len(toc) >= 5:
        body = [(lv, t, l) for lv, t, l in headings if lv >= 2 and t != '目录']
        tocset, hset = set(a for a, _ in toc), set(slug(t) for _, t, _ in body)
        print(f"[toc] toc={len(toc)} body-headings={len(body)}")
        if len(toc) != len(tocset):
            fail += 1
            print(f"[toc] FAIL duplicate toc entries: {[a for a in tocset if [x for x, _ in toc].count(a) > 1]}")
        for lv, t, l in body:
            if slug(t) not in tocset:
                fail += 1
                print(f"[toc] FAIL heading not in toc  :{l} {'#' * lv} {t} -> expected #{slug(t)}")
        for a, l in toc:
            if a not in hset:
                fail += 1
                print(f"[toc] FAIL toc link dead       :{l} -> #{a}")
    else:
        print(f"[toc] SKIP (no in-page TOC); headings={len(headings)}")

    # --- 2. table pipe counts ---
    i, n, tcount, tbad = 0, len(lines), 0, 0
    while i < n:
        if re.match(r'^\s*\|', lines[i]) and i + 1 < n and re.match(r'^\s*\|[\s:|-]+\|\s*$', lines[i + 1]):
            j, hdr = i, None
            while j < n and re.match(r'^\s*\|', lines[j]):
                row = lines[j]
                cnt = re.sub(r'\\\|', '\x00', row).count('|')
                if hdr is None:
                    hdr = cnt
                elif cnt != hdr and not re.match(r'^\s*\|[\s:|-]+\|\s*$', row):
                    tbad += 1
                    fail += 1
                    print(f"[table] FAIL {rel}:{j + 1} pipes={cnt} expected={hdr}\n         {raw[j][:140]}")
                j += 1
            tcount += 1
            i = j
            continue
        i += 1
    print(f"[table] {tcount} tables, mismatches={tbad}")

    # --- 3. relative links (+ cross-file anchors) ---
    broken = 0
    for i, ln in enumerate(plain):
        for m in re.finditer(r'\[[^\]]*\]\((?!#|https?://|mailto:)([^)\s]+)', ln):
            target, anchor = (m.group(1).split('#', 1) + [''])[:2]
            if not target:
                continue
            tp = target if target.startswith('.') else './' + target
            resolved = os.path.normpath(os.path.join(ROOT, os.path.dirname(rel), urllib.parse.unquote(tp)))
            if not os.path.exists(resolved):
                broken += 1
                fail += 1
                print(f"[link] FAIL {rel}:{i + 1} -> {m.group(1)}")
            elif anchor and os.path.isfile(resolved):
                tg = {slug(mm.group(2)) for mm in map(re.compile(r'^(#{1,6})\s+(.*)$').match,
                                                      strip_fences(open(resolved, encoding='utf-8').read().split('\n'))) if mm}
                if anchor not in tg:
                    broken += 1
                    fail += 1
                    print(f"[link] FAIL {rel}:{i + 1} anchor #{anchor} not found in {target}")
    print(f"[link] broken={broken}")

    # --- 4. 遗留标记 ---
    # 注意：本规则要检的字面词不能出现在被检文档的正文里，否则门禁会扫到自己刚写的说明。
    hits = [i + 1 for i in range(len(plain))
            if re.search(r'\b(TODO|FIXME|XXX)\b', plain[i]) or '待补充' in plain[i] or '占位待写' in plain[i]]
    for l in hits[:10]:
        fail += 1
        print(f"[todo] FAIL {rel}:{l} {raw[l - 1][:110]}")
    print(f"[todo] hits={len(hits)}")

    # --- 5. changelog continuity ---
    if '手册' in rel:
        nums = [int(m.group(1)) for ln in raw for m in [re.match(r'^\| v\d+\.(\d+) \| \d{4}-\d\d-\d\d \|', ln)] if m]
        nums = sorted(set(nums))
        gaps = [f"{a}->{b}" for a, b in zip(nums, nums[1:]) if b - a != 1]
        print(f"[changelog] rows={len(nums)} range=v1.{min(nums)}..v1.{max(nums)} gaps={gaps or 'none'}")
        fail += len(gaps)

print(f"\nFAILURES: {fail}")
sys.exit(1 if fail else 0)
