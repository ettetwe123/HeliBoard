#!/usr/bin/env python3
# Convert Rime Cangjie5 YAML dictionaries to sorted TSV for HeliBoard Table IME.

import os

def convert_dict(yaml_path, tsv_path, max_codepoint=None):
    entries = []
    with open(yaml_path, 'r', encoding='utf-8') as f:
        in_header = True
        for line in f:
            line = line.strip()
            if not line:
                continue
            if in_header:
                if line == '...':
                    in_header = False
                continue
            if line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) >= 2:
                text = parts[0].strip()
                code = parts[1].strip().lower()
                if text and code:
                    if max_codepoint is not None and any(ord(c) > max_codepoint for c in text):
                        continue
                    entries.append((code, text))
    
    # Stable sort by code length and then code alphabetically, or purely by code?
    # If purely by code, then prefix searches can be done by binary search!
    # Python sort is stable: entries with the same code preserve original file order.
    entries.sort(key=lambda x: x[0])
    
    with open(tsv_path, 'w', encoding='utf-8') as out:
        for code, text in entries:
            out.write(f"{code}\t{text}\n")
    
    print(f"Converted {yaml_path} -> {tsv_path}: {len(entries)} entries")

def convert_words_dict(yaml_path, tsv_path):
    words_map = {}
    with open(yaml_path, 'r', encoding='utf-8') as f:
        in_header = True
        for line in f:
            line = line.strip()
            if not line:
                continue
            if in_header:
                if line == '...':
                    in_header = False
                continue
            if line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) >= 1:
                word = parts[0].strip()
                pron = parts[1].strip() if len(parts) > 1 else ""
                if word and word not in words_map:
                    words_map[word] = pron
    
    sorted_words = sorted(words_map.keys())
    with open(tsv_path, 'w', encoding='utf-8') as out:
        for word in sorted_words:
            pron = words_map[word]
            if pron:
                out.write(f"{word}\t{pron}\n")
            else:
                out.write(f"{word}\n")
    print(f"Converted {yaml_path} -> {tsv_path}: {len(sorted_words)} unique words")

def main():
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
    base_yaml = os.path.join(repo_root, "cangjie5.base.dict.yaml")
    extended_yaml = os.path.join(repo_root, "cangjie5.extended.dict.yaml")
    words_yaml = os.path.join(repo_root, "jyut6ping3.words.dict.yaml")
    out_dir = os.path.join(repo_root, "app/src/main/assets/tableime")
    os.makedirs(out_dir, exist_ok=True)
    
    if os.path.exists(base_yaml):
        convert_dict(base_yaml, os.path.join(out_dir, "cangjie5_base.tsv"))
    if os.path.exists(extended_yaml):
        # Filter characters above 0xFFFF (Ext B/C/D/E) that cannot be rendered on Android or PC
        convert_dict(extended_yaml, os.path.join(out_dir, "cangjie5_extended.tsv"), max_codepoint=0xFFFF)
    elif os.path.exists(os.path.join(out_dir, "cangjie5_extended.tsv")):
        # If TSV already exists, ensure code points above 0xFFFF are filtered
        ext_tsv = os.path.join(out_dir, "cangjie5_extended.tsv")
        with open(ext_tsv, "r", encoding="utf-8") as f:
            lines = f.readlines()
        filtered = [l for l in lines if l.strip() and all(ord(c) <= 0xFFFF for c in l.split('\t')[1].strip())]
        if len(filtered) != len(lines):
            with open(ext_tsv, "w", encoding="utf-8") as f:
                f.writelines(filtered)
            print(f"Filtered {ext_tsv}: {len(lines)} -> {len(filtered)} entries")
    if os.path.exists(words_yaml):
        convert_words_dict(words_yaml, os.path.join(out_dir, "jyut6ping3_words.tsv"))
    
    readme_path = os.path.join(out_dir, "README.txt")
    with open(readme_path, "w", encoding="utf-8") as f:
        f.write("Cangjie 5 tables derived from Rime cangjie5.dict.yaml\n")
        f.write("License: GPL\n")
        f.write("Original credit:\n")
        f.write("單字碼表《五倉世紀》來自 www.chinesecj.com\n")
        f.write("倉頡五代構詞碼碼表 由惜緣兄製作 佛振修訂\n")
        f.write("粵拼詞庫來自 rime-cantonese (CanCLID/rime-cantonese-upstream)\n")
    print("Created README.txt attribution")

if __name__ == "__main__":
    main()
