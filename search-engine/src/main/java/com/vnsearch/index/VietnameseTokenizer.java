package com.vnsearch.index;

/**
 * TODO (PHASE 3): Tokenizer tieng Viet tu cai dat.
 *
 * Buoc xu ly:
 *   1. Chuan hoa Unicode ve NFC (tieng Viet co 2 cach go: to hop / dung san).
 *   2. Lowercase, bo dau cau.
 *   3. Tach token theo khoang trang.
 *   4. Ghep tu ghep tieng Viet bang Longest Matching dua tren tu dien
 *      resources/vietnamese-bigrams.txt.
 *   5. Loai stopword tieng Viet (resources/vietnamese-stopwords.txt).
 *   6. Sinh them ban khong dau cua moi token (Normalizer.NFD + regex bo
 *      dau) de ho tro tim khong dau ("may tinh" -> "may tinh" va
 *      "may tinh" -> tim thay "may tinh"/"máy tính").
 */
public class VietnameseTokenizer {
    // TODO: implement in PHASE 3
}
