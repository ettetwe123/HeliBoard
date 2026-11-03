#!/usr/bin/env python3
"""
Convert HeliBoard Table IME TSV dictionaries into fast binary (.bin) format.

1. Code Table TSV (cangjie_base.tsv, cangjie_extended.tsv):
   Input: code \t character
   Output: CJTB binary format (Header + 16-byte aligned Index + UTF-8 Data Pool)

2. Word Predictor TSV (cangjie_words.tsv):
   Input: word \t pronunciation (or word only)
   Output: CJWP binary format (Header + Offset Table + UTF-8 Word Pool)
"""

import sys
import os
import struct
from collections import OrderedDict

def convert_code_table(tsv_path, bin_path):
    # Map code -> list of unique candidate strings, preserving original TSV order
    table = OrderedDict()
    total_entries = 0
    with open(tsv_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split('\t')
            if len(parts) >= 2:
                code = parts[0].strip().lower()
                text = parts[1].strip()
                if code and text:
                    if code not in table:
                        table[code] = []
                    if text not in table[code]:
                        table[code].append(text)
                    total_entries += 1

    # Sort codes strictly lexicographically
    sorted_codes = sorted(table.keys())

    # Build Candidate Data section
    candidate_data = bytearray()
    index_entries = [] # (code_bytes_8, offset_4, count_2)

    for code in sorted_codes:
        code_bytes = code.encode('ascii')
        if len(code_bytes) > 7:
            raise ValueError(f"Code too long for 8-byte index slot: {code}")
        # Pad with \0 to exactly 8 bytes
        code_padded = code_bytes.ljust(8, b'\x00')
        
        candidates = table[code]
        offset = len(candidate_data)
        count = len(candidates)
        
        for cand in candidates:
            cand_bytes = cand.encode('utf-8')
            if len(cand_bytes) > 255:
                raise ValueError(f"Candidate too long for 1-byte length prefix: {cand}")
            candidate_data.append(len(cand_bytes))
            candidate_data.extend(cand_bytes)
            
        index_entries.append((code_padded, offset, count))

    # Header: Magic "CJTB" (4B), version (2B), flags (2B), entry_count (4B), data_offset (4B)
    header_size = 16
    index_size = len(index_entries) * 16  # 8 (code) + 4 (offset) + 2 (count) + 2 (padding) = 16 bytes
    data_offset = header_size + index_size

    with open(bin_path, 'wb') as f:
        # Write header
        f.write(b'CJTB')
        f.write(struct.pack('<HHII', 1, 0, len(index_entries), data_offset))

        # Write index entries
        for code_padded, offset, count in index_entries:
            f.write(code_padded)  # 8 bytes
            f.write(struct.pack('<IH', offset, count))  # 4 + 2 = 6 bytes
            f.write(b'\x00\x00')  # 2 bytes padding to reach 16 bytes

        # Write candidate data
        f.write(candidate_data)

    print(f"[CodeTable] {tsv_path} -> {bin_path}")
    print(f"  Entries: {total_entries}, Unique codes: {len(index_entries)}")
    print(f"  File size: {os.path.getsize(bin_path):,} bytes (Index: {index_size:,} B, Data: {len(candidate_data):,} B)")


def convert_word_predictor(tsv_path, bin_path):
    words_set = OrderedDict()
    with open(tsv_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split('\t')
            word = parts[0].strip()
            if word and word not in words_set:
                words_set[word] = True

    # Sort words strictly in UTF-8 lexicographical order
    sorted_words = sorted(words_set.keys(), key=lambda w: w.encode('utf-8'))

    word_data = bytearray()
    offsets = []

    for w in sorted_words:
        w_bytes = w.encode('utf-8')
        if len(w_bytes) > 255:
            continue
        offsets.append(len(word_data))
        word_data.append(len(w_bytes))
        word_data.extend(w_bytes)

    header_size = 16
    index_size = len(offsets) * 4
    data_offset = header_size + index_size

    with open(bin_path, 'wb') as f:
        # Magic "CJWP" (4B), version (2B), flags (2B), word_count (4B), data_offset (4B)
        f.write(b'CJWP')
        f.write(struct.pack('<HHII', 1, 0, len(offsets), data_offset))

        # Write offsets (4B each)
        for off in offsets:
            f.write(struct.pack('<I', off))

        # Write word data
        f.write(word_data)

    print(f"[WordPredictor] {tsv_path} -> {bin_path}")
    print(f"  Words: {len(offsets)}")
    print(f"  File size: {os.path.getsize(bin_path):,} bytes (Offsets: {index_size:,} B, Data: {len(word_data):,} B)")


if __name__ == '__main__':
    base_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(base_dir, '..', '..'))
    assets_dir = os.path.join(project_root, 'app', 'src', 'main', 'assets', 'tableime')

    cangjie_base_tsv = os.path.join(assets_dir, 'cangjie_base.tsv')
    cangjie_base_bin = os.path.join(assets_dir, 'cangjie_base.bin')
    convert_code_table(cangjie_base_tsv, cangjie_base_bin)

    cangjie_ext_tsv = os.path.join(assets_dir, 'cangjie_extended.tsv')
    cangjie_ext_bin = os.path.join(assets_dir, 'cangjie_extended.bin')
    convert_code_table(cangjie_ext_tsv, cangjie_ext_bin)

    words_tsv = os.path.join(assets_dir, 'cangjie_words.tsv')
    words_bin = os.path.join(assets_dir, 'cangjie_words.bin')
    convert_word_predictor(words_tsv, words_bin)
