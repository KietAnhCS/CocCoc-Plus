# -*- coding: utf-8 -*-
"""
Trich tu dien tieng Viet co tan suat tu coccoc-tokenizer.

Hai nguon (deu LGPL-3.0, coccoc-tokenizer/dicts/tokenizer/):
  - vndic_multiterm : tu dien chung, 590k muc, "<tu> <tan_suat>"
  - keyword.freq    : 142k TRUY VAN tim kiem that, tat ca deu nhieu am tiet

dict_compiler.cpp cua Coc Coc nap CA HAI vao cung mot trie
(load_vndic_multiterm + load_keywords), nen o day cung gop lai.

Dau ra: vietnamese-words.txt, "<tu>\t<tan_suat>".
  - Tu ghep 2..4 am tiet: giu tat ca (4 = MAX_COMPOUND_LENGTH cua tokenizer,
    cung la gioi han an toan cua bang tham so trong so - xem VietnameseWordDictionary).
  - Am tiet don: chi giu am tiet co dau tieng Viet hoac co xuat hien trong mot
    tu ghep da giu. Neu bo han am tiet don thi chung deu nhan trong so mac dinh
    0.5 va QHD se thien vi tu ghep mot cach vo ly.
  - Bo moi muc chua ky tu ngoai [chu cai, chu so, dau cach]: tokenizer da thay
    moi ky tu khac bang dau cach truoc khi tra tu dien, nen nhung muc nhu
    ".net framework 4.5" khong bao gio khop duoc.
"""
import io
import os
import sys

SRC_DIR = sys.argv[1]
DST = sys.argv[2]

MAX_SYLLABLES = 4

VN_CHARS = set(
    "àáảãạăằắẳẵặâầấẩẫậ"
    "èéẻẽẹêềếểễệ"
    "ìíỉĩị"
    "òóỏõọôồốổỗộơờớởỡợ"
    "ùúủũụưừứửữự"
    "ỳýỷỹỵ"
    "đ"
)


def has_vietnamese_char(word):
    return any(c in VN_CHARS for c in word)


def is_clean(word):
    """Chi gom chu cai / chu so / dau cach — khop voi buoc chuan hoa cua tokenizer."""
    return all(c.isalnum() or c == " " for c in word)


def parse(line):
    """'<tu> <tan_suat>' — tan suat la token cuoi va phai la so nguyen."""
    line = line.rstrip("\n\r")
    cut = line.rfind(" ")
    if cut <= 0:
        return None
    freq_part = line[cut + 1:]
    if not freq_part.isdigit():
        return None
    word = line[:cut].strip().lower()
    if not word:
        return None
    return word, int(freq_part)


compounds = {}
singles = {}
syllables_in_use = set()


def feed(path, keep_singles, min_freq=0):
    kept = 0
    with io.open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            parsed = parse(line)
            if parsed is None:
                continue
            word, freq = parsed
            if freq < min_freq:
                continue
            if not is_clean(word):
                continue
            parts = word.split()
            word = " ".join(parts)          # go bo dau cach thua
            n = len(parts)
            if n == 1:
                if keep_singles and singles.get(word, -1) < freq:
                    singles[word] = freq
                    kept += 1
            elif 2 <= n <= MAX_SYLLABLES:
                if compounds.get(word, -1) < freq:
                    compounds[word] = freq
                syllables_in_use.update(parts)
                kept += 1
    return kept


KEYWORD_MIN_FREQ = int(sys.argv[3]) if len(sys.argv) > 3 else -1

n1 = feed(os.path.join(SRC_DIR, "vndic_multiterm"), keep_singles=True)
before = len(compounds)
if KEYWORD_MIN_FREQ >= 0:
    n2 = feed(os.path.join(SRC_DIR, "keyword.freq"), keep_singles=False,
              min_freq=KEYWORD_MIN_FREQ)

kept_singles = {
    w: f for w, f in singles.items()
    if has_vietnamese_char(w) or w in syllables_in_use
}

rows = list(compounds.items()) + list(kept_singles.items())
rows.sort(key=lambda r: (-r[1], r[0]))

with io.open(DST, "w", encoding="utf-8", newline="\n") as out:
    out.write("# Tu dien tieng Viet co TAN SUAT, dung cho phan doan cuc dai trong so.\n")
    out.write("# Sinh tu coccoc-tokenizer (LGPL-3.0):\n")
    out.write("#   dicts/tokenizer/vndic_multiterm  — tu dien chung\n")
    out.write("#   dicts/tokenizer/keyword.freq     — truy van tim kiem that\n")
    out.write("# Dinh dang: <tu><TAB><tan suat>. Tu ghep noi cac am tiet bang dau cach.\n")
    for word, freq in rows:
        out.write(u"%s\t%d\n" % (word, freq))

print("tu ghep tu vndic_multiterm : %d" % before)
print("tu ghep sau khi gop keyword: %d  (+%d moi)" % (len(compounds), len(compounds) - before))
print("am tiet don giu lai        : %d / %d" % (len(kept_singles), len(singles)))
print("TONG SO MUC                : %d" % len(rows))
