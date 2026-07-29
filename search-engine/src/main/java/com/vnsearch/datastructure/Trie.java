package com.vnsearch.datastructure;

/**
 * TODO (PHASE 2): Trie (cay tien to) tu cai dat, dung cho tinh nang goi y
 * tu khoa (autocomplete) khi nguoi dung go vao o tim kiem.
 *
 * Cau truc du lieu du kien:
 *   - TrieNode: Map<Character, TrieNode> children, boolean isEndOfWord,
 *     int frequency (so lan tu nay duoc tim).
 *
 * Method can cai dat:
 *   - insert(String word)                              O(L)
 *   - boolean search(String word)                       O(L)
 *   - List<String> getSuggestions(String prefix, int k)  O(L + m*log(k))
 *     (L = do dai prefix, m = so tu duoi cay con prefix; dung MinHeap
 *     kich thuoc k thay vi sort toan bo de lay top-k theo frequency).
 *
 * Phai ho tro Unicode tieng Viet co dau.
 */
public class Trie {
    // TODO: implement in PHASE 2
}
