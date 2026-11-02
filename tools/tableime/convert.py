#!/usr/bin/env python3
# Convert Rime Cangjie5 YAML dictionaries to sorted TSV for HeliBoard Table IME.

import os

def convert_dict(yaml_path, tsv_path):
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
                    entries.append((code, text))
    
    # Stable sort by code length and then code alphabetically, or purely by code?
    # If purely by code, then prefix searches can be done by binary search!
    # Python sort is stable: entries with the same code preserve original file order.
    entries.sort(key=lambda x: x[0])
    
    with open(tsv_path, 'w', encoding='utf-8') as out:
        for code, text in entries:
            out.write(f"{code}\t{text}\n")
    
    print(f"Converted {yaml_path} -> {tsv_path}: {len(entries)} entries")

def main():
    base_yaml = "cangjie5.base.dict.yaml"
    extended_yaml = "cangjie5.extended.dict.yaml"
    out_dir = "app/src/main/assets/tableime"
    os.makedirs(out_dir, exist_ok=True)
    
    convert_dict(base_yaml, os.path.join(out_dir, "cangjie5_base.tsv"))
    convert_dict(extended_yaml, os.path.join(out_dir, "cangjie5_extended.tsv"))
    
    readme_path = os.path.join(out_dir, "README.txt")
    with open(readme_path, "w", encoding="utf-8") as f:
        f.write("Cangjie 5 tables derived from Rime cangjie5.dict.yaml\n")
        f.write("License: GPL\n")
        f.write("Original credit:\n")
        f.write("單字碼表《五倉世紀》來自 www.chinesecj.com\n")
        f.write("倉頡五代構詞碼碼表 由惜緣兄製作 佛振修訂\n")
    print("Created README.txt attribution")

if __name__ == "__main__":
    main()
