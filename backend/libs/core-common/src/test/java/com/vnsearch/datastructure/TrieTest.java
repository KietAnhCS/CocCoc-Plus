package com.vnsearch.datastructure;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrieTest {

    @Test
    void searchOnEmptyTrieReturnsFalse() {
        Trie trie = new Trie();
        assertFalse(trie.search("khong ton tai"));
        assertTrue(trie.getSuggestions("a", 5).isEmpty());
    }

    @Test
    void insertAndSearchSingleWord() {
        Trie trie = new Trie();
        trie.insert("java");
        assertTrue(trie.search("java"));
        assertFalse(trie.search("jav"), "Prefix chua phai la tu hoan chinh");
        assertFalse(trie.search("javas"));
    }

    @Test
    void prefixOfAnInsertedWordIsNotItselfAWord() {
        Trie trie = new Trie();
        trie.insert("hello");
        assertFalse(trie.search("hell"));
        assertTrue(trie.startsWith("hell"));
    }

    @Test
    void duplicateInsertsIncreaseFrequencyAndRankHigher() {
        Trie trie = new Trie();
        trie.insert("may tinh");
        trie.insert("may tinh");
        trie.insert("may tinh");
        trie.insert("may bay");

        List<String> suggestions = trie.getSuggestions("may", 1);
        assertEquals(List.of("may tinh"), suggestions, "Tu duoc insert nhieu lan nhat phai xep dau tien");
    }

    @Test
    void vietnameseUnicodeDiacritics() {
        Trie trie = new Trie();
        trie.insert("máy tính"); // "máy tính" precomposed (NFC)
        trie.insert("trình duyệt web");

        assertTrue(trie.search("máy tính"));
        List<String> suggestions = trie.getSuggestions("máy", 5);
        assertEquals(List.of("máy tính"), suggestions);
    }

    @Test
    void nfcAndNfdInputsOfSameWordAreTreatedAsEqual() {
        Trie trie = new Trie();
        String nfc = Normalizer.normalize("máy tính", Normalizer.Form.NFC);
        String nfd = Normalizer.normalize("máy tính", Normalizer.Form.NFD);

        trie.insert(nfd); // go bang to hop (dung/nfd)
        assertTrue(trie.search(nfc), "NFC va NFD cua cung 1 tu phai duoc coi la giong nhau");
    }

    @Test
    void getSuggestionsRespectsLimit() {
        Trie trie = new Trie();
        trie.insert("test1");
        trie.insert("test2");
        trie.insert("test3");
        trie.insert("test4");

        List<String> suggestions = trie.getSuggestions("test", 2);
        assertEquals(2, suggestions.size());
    }

    @Test
    void nonExistentPrefixReturnsEmptyList() {
        Trie trie = new Trie();
        trie.insert("hello");
        assertTrue(trie.getSuggestions("xyz", 5).isEmpty());
    }

    @Test
    void clearRemovesAllWords() {
        // Bảo vệ chống lỗi đã gặp: rebuildSuggestTrie() chỉ insert thêm mà
        // không xoá, khiến tiêu đề của corpus CŨ vẫn được gợi ý sau reindex.
        Trie trie = new Trie();
        trie.insert("may tinh");
        trie.insert("may bay");
        assertTrue(trie.search("may tinh"));

        trie.clear();

        assertFalse(trie.search("may tinh"), "Sau clear() không từ nào còn tồn tại");
        assertFalse(trie.startsWith("may"), "Sau clear() không tiền tố nào còn tồn tại");
        assertTrue(trie.getSuggestions("may", 10).isEmpty());

        trie.insert("hoan toan moi");
        assertTrue(trie.search("hoan toan moi"), "Vẫn insert lại được sau khi clear");
    }

    @Test
    void lookupKeyCanDifferFromDisplayString() {
        // Người Việt hay gõ không dấu, nhưng gợi ý hiện ra phải có dấu.
        Trie trie = new Trie();
        trie.insert("công nghệ", "công nghệ", 10);
        trie.insert("cong nghe", "công nghệ", 10);

        assertEquals(List.of("công nghệ"), trie.getSuggestions("công", 5),
                "Gõ có dấu phải ra gợi ý có dấu");
        assertEquals(List.of("công nghệ"), trie.getSuggestions("cong", 5),
                "Gõ KHÔNG dấu cũng phải ra gợi ý CÓ dấu");
    }

    @Test
    void duplicateDisplayStringsAreMergedInSuggestions() {
        // Cùng một gợi ý được chèn 2 lần (khoá có dấu + khoá không dấu) nên
        // một tiền tố ngắn có thể chạm cả hai node; không được hiện trùng.
        Trie trie = new Trie();
        trie.insert("kinh tế", "kinh tế", 5);
        trie.insert("kinh te", "kinh tế", 5);

        List<String> suggestions = trie.getSuggestions("kin", 10);
        assertEquals(1, suggestions.size(), "Gợi ý bị lặp: " + suggestions);
        assertEquals("kinh tế", suggestions.get(0));
    }

    @Test
    void frequencyArgumentDrivesRanking() {
        Trie trie = new Trie();
        trie.insert("thể thao", "thể thao", 2);
        trie.insert("thể dục", "thể dục", 50);

        assertEquals("thể dục", trie.getSuggestions("thể", 1).get(0),
                "Cụm xuất hiện nhiều hơn phải được gợi ý trước");
    }
}
