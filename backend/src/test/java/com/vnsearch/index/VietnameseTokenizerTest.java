package com.vnsearch.index;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VietnameseTokenizerTest {

    private final VietnameseTokenizer tokenizer = new VietnameseTokenizer();

    @Test
    void emptyAndBlankTextReturnsEmptyList() {
        assertTrue(tokenizer.tokenize("").isEmpty());
        assertTrue(tokenizer.tokenize("   ").isEmpty());
        assertTrue(tokenizer.tokenize(null).isEmpty());
    }

    @Test
    void stopwordsAreRemoved() {
        List<String> terms = terms("và của là các có được cho một");
        assertTrue(terms.isEmpty(), "Tat ca deu la stopword nen ket qua phai rong");
    }

    @Test
    void compoundWordsAreMerged() {
        List<String> terms = terms("trình duyệt web rất tốt");
        assertTrue(terms.contains("trình_duyệt_web"), "Phai ghep 3 tieng thanh 1 token compound");
    }

    @Test
    void singleSyllableWordsNotInDictionaryStayUnmerged() {
        List<String> terms = terms("con mèo đang ngủ");
        assertTrue(terms.contains("mèo"));
        assertTrue(terms.contains("ngủ"));
    }

    /**
     * Ca nhap nhang ma Longest Matching tham lam tach sai: tai am tiet dau no thay
     * "nhà hàng" co trong tu dien va lay ngay, an mat am tiet "hàng" ma cach tach dung
     * can den. Quy hoach dong cham diem ca cau nen chon duoc [nhà][hàng_xóm].
     */
    @Test
    void ambiguousPhraseIsSegmentedByGlobalScoreNotGreedily() {
        assertEquals(List.of("nhà", "hàng_xóm"), terms("nhà hàng xóm"));
    }

    /** Doi chieu: "nhà hàng" van phai duoc gop khi no thuc su la mot tu. */
    @Test
    void sameCompoundIsStillMergedInAnUnambiguousSentence() {
        assertTrue(terms("nhà hàng này rất ngon").contains("nhà_hàng"));
    }

    /**
     * Tu ghep khong bi loc stopword, ke ca khi mot am tiet cua no la stopword.
     *
     * <p>"của cải" la tai san, con "của" la stopword. Neu tach roi thi "của" bi bo va
     * chi con "cải" (rau cai) — truy van ve tai san se ra ket qua ve rau. Loi kieu nay
     * <b>im lang</b>: khong ngoai le nao duoc nem, chi la ket qua sai mot cach kho hieu.
     */
    @Test
    void compoundIsNotDroppedWhenOneSyllableIsAStopword() {
        List<String> terms = terms("của cải trong nhà");
        assertTrue(terms.contains("của_cải"), "Nhan duoc: " + terms);
    }

    /** Tu dien dung chung chi duoc nap MOT lan cho ca tien trinh. */
    @Test
    void dictionaryIsSharedAcrossInstances() {
        assertSame(VietnameseTokenizer.sharedDictionary(),
                VietnameseTokenizer.sharedDictionary());
        assertTrue(new VietnameseTokenizer().name().contains("MaxWeightDP"));
    }

    @Test
    void diacriticFormIsProvidedForEachToken() {
        List<VietnameseTokenizer.Token> tokens = tokenizer.tokenize("máy tính");
        assertEquals(1, tokens.size());
        assertEquals("máy_tính", tokens.get(0).term());
        assertEquals("may_tinh", tokens.get(0).noDiacriticTerm());
    }

    @Test
    void stripDiacriticsHandlesDStroke() {
        assertEquals("Y te va giao duc", VietnameseTokenizer.stripDiacritics("Y tế và giáo dục"));
    }

    @Test
    void punctuationIsStripped() {
        List<String> terms = terms("Xin chào, hôm nay trời đẹp quá!!!");
        assertTrue(terms.contains("chào"));
        assertTrue(terms.contains("đẹp"));
        assertTrue(terms.stream().noneMatch(t -> t.contains(",") || t.contains("!")));
    }

    private List<String> terms(String text) {
        return tokenizer.tokenize(text).stream()
                .map(VietnameseTokenizer.Token::term)
                .collect(Collectors.toList());
    }
}
