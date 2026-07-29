package com.vnsearch.datastructure;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trie (cay tien to) tu cai dat, dung cho tinh nang goi y tu khoa
 * (autocomplete) khi nguoi dung go vao o tim kiem.
 *
 * <p>Cau truc: moi {@link TrieNode} co {@code Map<Character, TrieNode>}
 * children (HashMap primitive co san cua Java, dung lam nen tang - khong
 * phai thu vien Trie lam san), co (@code isEndOfWord) va {@code frequency}
 * (so lan tu do duoc them/tim, dung de xep hang goi y).
 *
 * <p>Chuoi dau vao duoc chuan hoa Unicode NFC truoc khi xu ly de dam bao
 * tieng Viet co dau (du go bang to hop hay dung san) deu tro ve cung mot
 * chuoi ky tu, tranh tao 2 nhanh khac nhau cho cung mot tu.
 *
 * <p>Do phuc tap thoi gian (L = do dai chuoi, k = so goi y can lay,
 * m = so tu ket thuc trong cay con cua prefix):
 * <ul>
 *   <li>{@link #insert(String)}: O(L)</li>
 *   <li>{@link #search(String)}: O(L)</li>
 *   <li>{@link #getSuggestions(String, int)}: O(L) de tim node cua prefix,
 *       O(m) de DFS thu thap tat ca tu duoi cay con, roi O(m log k) de lay
 *       top-k theo frequency bang {@link MinHeap#topK} (khong sort toan bo
 *       m tu, chi giu heap kich thuoc k) -&gt; tong O(L + m log k).</li>
 * </ul>
 * Do phuc tap khong gian: O(tong so ky tu cua tat ca tu da insert) trong
 * truong hop xau nhat (khong co canh chung), toi uu hon khi nhieu tu chung
 * tien to.
 */
public class Trie {

    private static class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        boolean isEndOfWord = false;
        int frequency = 0;
    }

    private static class WordFrequency {
        final String word;
        final int frequency;

        WordFrequency(String word, int frequency) {
            this.word = word;
            this.frequency = frequency;
        }
    }

    private final TrieNode root = new TrieNode();

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** O(L) - them mot tu vao trie, tang frequency neu da ton tai. */
    public void insert(String word) {
        if (word == null || word.isEmpty()) {
            return;
        }
        String normalized = normalize(word);
        TrieNode node = root;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            node = node.children.computeIfAbsent(c, k -> new TrieNode());
        }
        node.isEndOfWord = true;
        node.frequency++;
    }

    /** O(L) - kiem tra tu co ton tai chinh xac trong trie khong. */
    public boolean search(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        TrieNode node = findNode(normalize(word));
        return node != null && node.isEndOfWord;
    }

    /** O(L) - kiem tra co tu nao bat dau bang prefix nay khong. */
    public boolean startsWith(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return !root.children.isEmpty();
        }
        return findNode(normalize(prefix)) != null;
    }

    private TrieNode findNode(String s) {
        TrieNode node = root;
        for (int i = 0; i < s.length(); i++) {
            node = node.children.get(s.charAt(i));
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /**
     * O(L + m log limit) - lay toi da {@code limit} goi y bat dau bang
     * {@code prefix}, sap xep theo frequency giam dan. Dung
     * {@link MinHeap#topK} thay vi sort toan bo m tu duoi cay con.
     */
    public List<String> getSuggestions(String prefix, int limit) {
        List<String> result = new ArrayList<>();
        if (limit <= 0) {
            return result;
        }
        String normalizedPrefix = prefix == null ? "" : normalize(prefix);
        TrieNode prefixNode = findNode(normalizedPrefix);
        if (prefixNode == null) {
            return result;
        }

        List<WordFrequency> candidates = new ArrayList<>();
        collectWords(prefixNode, new StringBuilder(normalizedPrefix), candidates);

        List<WordFrequency> top = MinHeap.topK(
                candidates,
                limit,
                Comparator.comparingInt(wf -> wf.frequency));

        for (WordFrequency wf : top) {
            result.add(wf.word);
        }
        return result;
    }

    private void collectWords(TrieNode node, StringBuilder prefix, List<WordFrequency> out) {
        if (node.isEndOfWord) {
            out.add(new WordFrequency(prefix.toString(), node.frequency));
        }
        for (Map.Entry<Character, TrieNode> entry : node.children.entrySet()) {
            prefix.append(entry.getKey());
            collectWords(entry.getValue(), prefix, out);
            prefix.deleteCharAt(prefix.length() - 1);
        }
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        Trie trie = new Trie();
        String[] words = {"may tinh", "may tinh", "may bay", "may anh", "trinh duyet", "trinh duyet web"};
        for (String w : words) {
            trie.insert(w);
        }
        System.out.println("search(\"may bay\") = " + trie.search("may bay"));
        System.out.println("search(\"may\") = " + trie.search("may"));
        System.out.println("suggestions(\"may\", 2) = " + trie.getSuggestions("may", 2));
        System.out.println("suggestions(\"trinh\", 5) = " + trie.getSuggestions("trinh", 5));
    }
}
