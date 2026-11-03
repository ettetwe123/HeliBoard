#!/usr/bin/env python3
"""
Merge Cangjie 3 dictionaries into HeliBoard Cangjie tables.

Design principles:
1. Base Table (cangjie_base.tsv):
   - Contains standard, common CJK characters (BMP Unified Ideographs U+4E00..U+9FFF and Compatibility U+F900..U+FAFF).
   - Adds CJ3 alternative codes for standard characters (e.g. ysyq -> 遲).
   - Strictly excludes Extension A/B/C/D+ to prevent tofu glyphs (▯) in the default candidate strip.

2. Extended Table (cangjie_extended.tsv):
   - Contains curated rare characters from Rime (mostly Ext A).
   - Contains curated Hong Kong / Cantonese HKSCS vernacular characters (Ext B up to U+2A6DF, e.g. 𨋢, 𥄫, 𡃁, 𠵱, 𠱁, 𠝹, 𡁻, 𤓓, 𢃇, 𢫏, 𠺘, 𨳊, 𨶙, 𨳍).
   - Discards uncurated raw Unihan database dumps that have no font glyphs on mobile devices.
"""

import os
import sys

def is_standard_cjk(ch):
    cp = ord(ch)
    return (0x4E00 <= cp <= 0x9FFF) or (0xF900 <= cp <= 0xFAFF)

def read_cj3_file(path):
    res = []
    if not os.path.exists(path):
        return res
    with open(path, 'r', encoding='utf-8', errors='ignore') as f:
        in_data = False
        for line in f:
            line = line.strip()
            if not line:
                continue
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

def merge():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(base_dir, '..', '..'))
    assets_dir = os.path.join(project_root, 'app', 'src', 'main', 'assets', 'tableime')

    base_tsv = os.path.join(assets_dir, 'cangjie_base.tsv')
    ext_tsv = os.path.join(assets_dir, 'cangjie_extended.tsv')
    words_tsv = os.path.join(assets_dir, 'cangjie_words.tsv')

    cj3_special_path = os.path.join(base_dir, 'cj3-special.txt')
    if not os.path.exists(cj3_special_path):
        cj3_special_path = '/dev/shm/cj3-special.txt'

    cj3_main_path = os.path.join(base_dir, 'cj3.txt')
    if not os.path.exists(cj3_main_path):
        cj3_main_path = '/dev/shm/cj3.txt'

    if not os.path.exists(cj3_special_path):
        print(f"Error: missing cj3-special.txt ({cj3_special_path})")
        sys.exit(1)

    # 1. Load word characters from cangjie_words.tsv
    word_chars = set()
    if os.path.exists(words_tsv):
        with open(words_tsv, 'r', encoding='utf-8') as f:
            for line in f:
                parts = line.strip().split('\t')
                if parts:
                    for ch in parts[0].strip():
                        word_chars.add(ch)

    # 2. Load original base entries (filter to standard CJK to eliminate any previous tofu additions)
    base_entries = []
    base_set = set()
    orig_base_chars = set()
    with open(base_tsv, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line: continue
            parts = line.split('\t')
            if len(parts) >= 2:
                c, t = parts[0].strip().lower(), parts[1].strip()
                if c and t and is_standard_cjk(t):
                    if (c, t) not in base_set:
                        base_entries.append((c, t))
                        base_set.add((c, t))
                        orig_base_chars.add(t)

    # 3. Load original ext entries
    ext_entries = []
    ext_set = set()
    orig_ext_chars = set()
    with open(ext_tsv, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line: continue
            parts = line.split('\t')
            if len(parts) >= 2:
                c, t = parts[0].strip().lower(), parts[1].strip()
                if c and t:
                    ext_entries.append((c, t))
                    ext_set.add((c, t))
                    orig_ext_chars.add(t)

    # 4. Read CJ3 files
    cj3_special = read_cj3_file(cj3_special_path)
    cj3_main = read_cj3_file(cj3_main_path)
    print(f"Loaded cj3_special: {len(cj3_special)} entries, cj3_main: {len(cj3_main)} entries")

    # Curate ext entries: original Rime ext + HKSCS from cj3_special / word_chars
    # Discard non-HKSCS raw Unihan entries
    for c, t in cj3_special:
        cp = ord(t)
        if is_standard_cjk(t):
            if (c, t) not in base_set:
                base_entries.append((c, t))
                base_set.add((c, t))
        elif cp <= 0x2A6DF: # HKSCS Ext B / Ext A
            if (c, t) not in ext_set:
                ext_entries.append((c, t))
                ext_set.add((c, t))

    # For cj3_main, ONLY import characters that are verified to belong to base, ext, or word vocabulary
    allowed_chars = orig_base_chars | orig_ext_chars | word_chars
    added_from_main = 0
    for c, t in cj3_main:
        if t not in allowed_chars:
            continue
        cp = ord(t)
        if is_standard_cjk(t):
            if (c, t) not in base_set:
                base_entries.append((c, t))
                base_set.add((c, t))
                added_from_main += 1
        elif cp <= 0x2A6DF:
            if (c, t) not in ext_set:
                ext_entries.append((c, t))
                ext_set.add((c, t))
                added_from_main += 1

    print(f"Added {added_from_main} verified entries from cj3_main")
    print(f"Final Base entries: {len(base_entries)}")
    print(f"Final Ext entries: {len(ext_entries)}")

    # 5. Stable sort by code
    base_entries.sort(key=lambda x: x[0])
    ext_entries.sort(key=lambda x: x[0])

    # 6. Write back TSV files
    with open(base_tsv, 'w', encoding='utf-8') as f:
        for c, t in base_entries:
            f.write(f"{c}\t{t}\n")

    with open(ext_tsv, 'w', encoding='utf-8') as f:
        for c, t in ext_entries:
            f.write(f"{c}\t{t}\n")

    print(f"Successfully wrote {base_tsv} and {ext_tsv}")

if __name__ == '__main__':
    merge()
