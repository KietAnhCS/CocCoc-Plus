package com.vnsearch.datastructure;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
 *
 * <p><b>Thread-safe bang {@link ReentrantReadWriteLock}.</b> Day KHONG phai
 * cau thi thua: {@code SearchEngineFacade.search()} goi {@link #insert} moi
 * lan nguoi dung tim kiem (de hoc tu chinh truy van that), trong khi
 * {@code /api/suggest} goi {@link #getSuggestions} — ca hai chay tren cac
 * thread HTTP KHAC NHAU, dong thoi. {@code HashMap} vua doc vua ghi co the
 * lam hong cau truc bucket (tung la loi noi tieng gay vong lap vo han o
 * HashMap Java 7).
 *
 * <p>Khac voi {@link LRUCache} — noi {@code get()} thuc chat la thao tac GHI
 * vi no cap nhat thu tu recency nen buoc phai dung write lock — o day
 * {@link #getSuggestions} la doc THUAN TUY (khong sua node nao), nen no dung
 * read lock that su va nhieu thread doc duoc phep chay song song. Chi
 * {@link #insert} va {@link #clear} moi can write lock.
 */
public class Trie {

    private static class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        boolean isEndOfWord = false;
        int frequency = 0;
        /**
         * Chuỗi sẽ được HIỂN THỊ cho người dùng, có thể khác với khoá tra
         * cứu dẫn tới node này. Null nghĩa là hiển thị đúng bằng khoá.
         */
        String display = null;
    }

    private static class WordFrequency {
        final String word;
        final int frequency;

        WordFrequency(String word, int frequency) {
            this.word = word;
            this.frequency = frequency;
        }
    }

    private TrieNode root = new TrieNode();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * O(1) - xoá sạch trie, dùng khi dựng lại gợi ý sau mỗi lần reindex.
     *
     * <p>Chỉ cần bỏ tham chiếu tới gốc cũ là toàn bộ cây con trở thành rác
     * và được bộ gom rác thu hồi — không cần duyệt để giải phóng từng node.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            root = new TrieNode();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** O(L) - them mot tu vao trie, tang frequency neu da ton tai. */
    public void insert(String word) {
        insert(word, word, 1);
    }

    /**
     * O(L) - them mot muc voi KHOA TRA CUU tach roi khoi CHUOI HIEN THI.
     *
     * <p>Vi sao can tach: nguoi Viet thuong go khong dau tren ban phim quoc
     * te, nen prefix "cong" phai tim ra duoc goi y "cong nghe" co dau. Ma
     * Trie khop tien to theo TUNG KY TU chinh xac, nen "cong" khong bao gio
     * di toi duoc nhanh cua "cong nghe". Giai phap: chen cung mot muc hai
     * lan - mot lan duoi khoa co dau, mot lan duoi khoa khong dau - nhung
     * ca hai node deu ghi nho cung mot chuoi hien thi co dau. Nho vay go
     * kieu nao cung ra goi y, ma thu nguoi dung nhin thay luon la ban co dau
     * dung chinh ta.
     *
     * @param key       khoa dung de khop tien to
     * @param display   chuoi tra ve cho nguoi dung
     * @param frequency so lan xuat hien, dung de xep hang goi y
     */
    public void insert(String key, String display, int frequency) {
        if (key == null || key.isEmpty() || frequency <= 0) {
            return;
        }
        String normalized = normalize(key);
        String normalizedDisplay = display == null ? null : normalize(display);
        lock.writeLock().lock();
        try {
            TrieNode node = root;
            for (int i = 0; i < normalized.length(); i++) {
                char c = normalized.charAt(i);
                node = node.children.computeIfAbsent(c, k -> new TrieNode());
            }
            node.isEndOfWord = true;
            node.frequency += frequency;
            node.display = normalizedDisplay;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** O(L) - kiem tra tu co ton tai chinh xac trong trie khong. */
    public boolean search(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        String normalized = normalize(word);
        lock.readLock().lock();
        try {
            TrieNode node = findNode(normalized);
            return node != null && node.isEndOfWord;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** O(L) - kiem tra co tu nao bat dau bang prefix nay khong. */
    public boolean startsWith(String prefix) {
        lock.readLock().lock();
        try {
            if (prefix == null || prefix.isEmpty()) {
                return !root.children.isEmpty();
            }
            return findNode(normalize(prefix)) != null;
        } finally {
            lock.readLock().unlock();
        }
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

        // Doc THUAN TUY: khong sua node nao, nen read lock that su — nhieu
        // thread /api/suggest chay song song duoc. Toan bo phan doc cay nam
        // trong khoa; phan tinh top-K sau do chi lam viec tren ban sao cuc bo.
        List<WordFrequency> candidates = new ArrayList<>();
        lock.readLock().lock();
        try {
            TrieNode prefixNode = findNode(normalizedPrefix);
            if (prefixNode == null) {
                return result;
            }
            collectWords(prefixNode, new StringBuilder(normalizedPrefix), candidates);
        } finally {
            lock.readLock().unlock();
        }

        // Gop cac muc trung chuoi hien thi: cung mot goi y duoc chen hai lan
        // (khoa co dau va khoa khong dau) nen mot tien to ngan co the cham
        // toi ca hai node va lam goi y bi lap.
        Map<String, Integer> bestFrequency = new LinkedHashMap<>();
        for (WordFrequency wf : candidates) {
            bestFrequency.merge(wf.word, wf.frequency, Math::max);
        }
        List<WordFrequency> deduplicated = new ArrayList<>(bestFrequency.size());
        for (Map.Entry<String, Integer> entry : bestFrequency.entrySet()) {
            deduplicated.add(new WordFrequency(entry.getKey(), entry.getValue()));
        }

        List<WordFrequency> top = MinHeap.topK(
                deduplicated,
                limit,
                Comparator.comparingInt(wf -> wf.frequency));

        for (WordFrequency wf : top) {
            result.add(wf.word);
        }
        return result;
    }

    private void collectWords(TrieNode node, StringBuilder prefix, List<WordFrequency> out) {
        if (node.isEndOfWord) {
            out.add(new WordFrequency(
                    node.display != null ? node.display : prefix.toString(), node.frequency));
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
