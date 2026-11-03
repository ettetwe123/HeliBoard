#!/usr/bin/env python3
"""
Merge Cangjie 3 dictionaries (cj3-special.txt & cj3.txt) into HeliBoard Cangjie tables.
- Deduplicates strictly: no duplicate (code, character) pairs.
- Preserves original Cangjie 5 ordering for existing codes.
- Common / BMP characters go to cangjie5_base.tsv (e.g. ysyq -> 遲).
- Supplementary / rare characters go to cangjie5_extended.tsv.
"""

import os
import sys

def merge():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(base_dir, '..', '..'))
    assets_dir = os.path.join(project_root, 'app', 'src', 'main', 'assets', 'tableime')

    base_tsv = os.path.join(assets_dir, 'cangjie5_base.tsv')
    ext_tsv = os.path.join(assets_dir, 'cangjie5_extended.tsv')

    cj3_special = os.path.join(base_dir, 'cj3-special.txt')
    cj3_main = os.path.join(base_dir, 'cj3.txt')

    if not os.path.exists(cj3_special) or not os.path.exists(cj3_main):
        print(f"Error: missing {cj3_special} or {cj3_main}")
        sys.exit(1)

    # 1. Load existing base entries
    base_entries = []
    base_chars = set()
    seen = set()

    with open(base_tsv, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line: continue
            parts = line.split('\t')
            if len(parts) >= 2:
                c, t = parts[0].strip().lower(), parts[1].strip()
                if c and t:
                    base_entries.append((c, t))
                    base_chars.add(t)
                    seen.add((c, t))

    # 2. Load existing extended entries
    ext_entries = []
    with open(ext_tsv, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line: continue
            parts = line.split('\t')
            if len(parts) >= 2:
                c, t = parts[0].strip().lower(), parts[1].strip()
                if c and t:
                    ext_entries.append((c, t))
                    seen.add((c, t))

    print(f"Initial: base={len(base_entries)}, ext={len(ext_entries)}")

    # 3. Read CJ3 files
    def read_cj3_file(path):
        res = []
        with open(path, 'r', encoding='utf-8', errors='ignore') as f:
            in_data = False
            for line in f:
                line = line.strip()
                if not line: continue
                if '[DATA]' in line:
                    in_data = True
                    continue
                if not in_data and ('=' in line or line.startswith('#')):
                    continue
                parts = line.split('\t') if '\t' in line else line.split()
                if len(parts) >= 2:
                    c = parts[0].strip().lower()
                    t = parts[1].strip()
                    if c.isalpha() and len(c) <= 5 and len(t) == 1:
                        res.append((c, t))
        return res

    cj3_entries = read_cj3_file(cj3_special) + read_cj3_file(cj3_main)
    print(f"Loaded {len(cj3_entries)} CJ3 entries")

    added_base = 0
    added_ext = 0

    for c, t in cj3_entries:
        if (c, t) in seen:
            continue
        seen.add((c, t))
        if t in base_chars or ord(t) <= 0xFFFF:
            base_entries.append((c, t))
            added_base += 1
        else:
            ext_entries.append((c, t))
            added_ext += 1

    print(f"Added {added_base} new entries to base (Total: {len(base_entries)})")
    print(f"Added {added_ext} new entries to ext (Total: {len(ext_entries)})")

    # 4. Stable sort by code
    base_entries.sort(key=lambda x: x[0])
    ext_entries.sort(key=lambda x: x[0])

    # 5. Write back TSV files
    with open(base_tsv, 'w', encoding='utf-8') as f:
        for c, t in base_entries:
            f.write(f"{c}\t{t}\n")

    with open(ext_tsv, 'w', encoding='utf-8') as f:
        for c, t in ext_entries:
            f.write(f"{c}\t{t}\n")

    print(f"Successfully updated {base_tsv} and {ext_tsv}")

if __name__ == '__main__':
    merge()
