package com.vnsearch.index;

import com.vnsearch.datastructure.SyllableTrie;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiem thu phan doan cuc dai trong so.
 *
 * <p>Phan lon test o day dung trie <b>tu dung tay</b> thay vi tu dien that: co the
 * dat trong so chinh xac de kiem tra thuat toan quy hoach dong <i>rieng no</i>, khong
 * lan voi cau hoi "tu dien co du tot khong". Nhung test cuoi cung moi dung tu dien
 * that de xac nhan ca hai phoi hop dung.
 */
class MaxWeightSegmenterTest {

    private static final double UNKNOWN = VietnameseWordDictionary.UNKNOWN_SYLLABLE_WEIGHT;

    /** Tach chuoi thanh am tiet, chay phan doan, roi noi lai bang "_" de doc cho de. */
    private static List<String> segment(MaxWeightSegmenter segmenter, String text) {
        String[] syllables = text.split(" ");
        int[] boundaries = segmenter.segment(syllables);
        List<String> tokens = new ArrayList<>();
        for (int k = 0; k + 1 < boundaries.length; k++) {
            tokens.add(String.join("_",
                    java.util.Arrays.copyOfRange(syllables, boundaries[k], boundaries[k + 1])));
        }
        return tokens;
    }

    private static MaxWeightSegmenter segmenterOf(Object... wordThenWeight) {
        SyllableTrie trie = new SyllableTrie(64);
        for (int i = 0; i < wordThenWeight.length; i += 2) {
            trie.insert(((String) wordThenWeight[i]).split(" "), (Double) wordThenWeight[i + 1]);
        }
        return new MaxWeightSegmenter(trie, UNKNOWN);
    }

    @Test
    void emptyInputGivesNoTokens() {
        int[] boundaries = segmenterOf().segment(new String[0]);
        assertEquals(1, boundaries.length, "Chi co moc 0, tuc khong co token nao");
        assertEquals(0, boundaries[0]);
    }

    @Test
    void unknownSyllablesEachBecomeTheirOwnToken() {
        MaxWeightSegmenter segmenter = segmenterOf();
        assertEquals(List.of("khủng", "long", "bạo", "chúa"),
                segment(segmenter, "khủng long bạo chúa"));
    }

    /**
     * <b>Ca kiem thu quan trong nhat cua ca lop.</b> Ca hai cach tach deu hop le ve tu
     * dien, nen Longest Matching buoc phai doan bang "lay tu dai nhat truoc" va no
     * doan SAI. Quy hoach dong so tong diem ca cau nen chon dung.
     */
    @Test
    void resolvesAmbiguityThatGreedyLongestMatchingGetsWrong() {
        MaxWeightSegmenter segmenter = segmenterOf(
                "nhà hàng", 9.59,   // quan an
                "hàng xóm", 9.44,   // nguoi o canh nha
                "nhà", 3.69,
                "xóm", 3.46);

        // Tham lam: tai i=0 thay "nhà hàng" -> [nhà_hàng][xóm] = 9,59 + 3,46 = 13,05
        // QHD    : con so sanh voi         -> [nhà][hàng_xóm] = 3,69 + 9,44 = 13,13
        assertEquals(List.of("nhà", "hàng_xóm"), segment(segmenter, "nhà hàng xóm"));
    }

    /** Doi chieu: khi tu dai THUC SU tot hon thi QHD cung phai chon no. */
    @Test
    void stillPrefersLongWordWhenItScoresHigher() {
        MaxWeightSegmenter segmenter = segmenterOf(
                "nhà hàng", 40.0,
                "hàng xóm", 9.44,
                "nhà", 3.69,
                "xóm", 3.46);
        assertEquals(List.of("nhà_hàng", "xóm"), segment(segmenter, "nhà hàng xóm"));
    }

    @Test
    void mergesUpToFourSyllables() {
        MaxWeightSegmenter segmenter = segmenterOf("công cụ tìm kiếm", 100.0);
        assertEquals(List.of("công_cụ_tìm_kiếm"), segment(segmenter, "công cụ tìm kiếm"));
    }

    /**
     * Tu 5 am tiet vuot {@link VietnameseWordDictionary#MAX_SYLLABLES} nen khong the
     * duoc gop lam mot, du co nam trong trie di nua.
     */
    @Test
    void neverMergesBeyondMaxSyllables() {
        MaxWeightSegmenter segmenter = segmenterOf("một hai ba bốn năm", 1000.0);
        List<String> tokens = segment(segmenter, "một hai ba bốn năm");
        assertTrue(tokens.size() > 1, "Khong duoc gop qua " + VietnameseWordDictionary.MAX_SYLLABLES
                + " am tiet, nhung nhan duoc: " + tokens);
    }

    /**
     * Am tiet la va khong co trong tu dien nam GIUA cau khong duoc lam dut do thi quy
     * hoach dong — neu dut thi {@code best[n]} khong bao gio den duoc va ket qua rong.
     */
    @Test
    void unknownSyllableInTheMiddleDoesNotBreakThePath() {
        MaxWeightSegmenter segmenter = segmenterOf("máy tính", 9.5, "giá rẻ", 9.0);
        assertEquals(List.of("máy_tính", "zzz", "giá_rẻ"), segment(segmenter, "máy tính zzz giá rẻ"));
    }

    @Test
    void boundariesCoverEveryInputSyllableExactlyOnce() {
        MaxWeightSegmenter segmenter = segmenterOf("máy tính", 9.5, "xách tay", 9.2);
        String[] syllables = "tôi mua máy tính xách tay mới".split(" ");
        int[] boundaries = segmenter.segment(syllables);

        assertEquals(0, boundaries[0], "Phai bat dau tu 0");
        assertEquals(syllables.length, boundaries[boundaries.length - 1], "Phai phu het day");
        for (int k = 0; k + 1 < boundaries.length; k++) {
            assertTrue(boundaries[k] < boundaries[k + 1], "Moc gioi han phai tang that su");
        }
    }

    /** Phoi hop that: tu dien that + tham so trong so that. */
    @Test
    void realDictionaryResolvesNhaHangXom() {
        MaxWeightSegmenter segmenter =
                new MaxWeightSegmenter(VietnameseTokenizer.sharedDictionary());
        assertEquals(List.of("nhà", "hàng_xóm"), segment(segmenter, "nhà hàng xóm"),
                "Tu dien that phai cho ra cung ket qua nhu vi du dung tay");
    }
}
