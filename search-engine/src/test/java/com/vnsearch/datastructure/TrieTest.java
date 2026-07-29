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
}
