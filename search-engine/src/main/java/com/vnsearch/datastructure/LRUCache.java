package com.vnsearch.datastructure;

/**
 * TODO (PHASE 2): LRU Cache tu cai dat (generic), dung de cache ket qua
 * tim kiem gan day o backend.
 *
 * Cau truc du lieu du kien:
 *   - HashMap<K, Node> de tra cuu O(1) + Doubly Linked List TU VIET (khong
 *     dung LinkedHashMap co san) de duy tri thu tu truy cap gan nhat.
 *   - Sentinel head/tail node de tranh xu ly null rom ra khi them/xoa dau-cuoi.
 *   - Thread-safe bang ReentrantReadWriteLock.
 *
 * Method can cai dat:
 *   - V get(K key)      O(1)
 *   - void put(K key, V value)  O(1)
 *
 * @param <K> loai khoa
 * @param <V> loai gia tri
 */
public class LRUCache<K, V> {
    // TODO: implement in PHASE 2
}
