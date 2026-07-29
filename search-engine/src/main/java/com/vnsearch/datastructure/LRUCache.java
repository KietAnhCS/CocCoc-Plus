package com.vnsearch.datastructure;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LRU (Least Recently Used) Cache tu cai dat, dung de cache ket qua tim
 * kiem gan day va trang da ghe trong trinh duyet.
 *
 * <p>Cau truc: {@code HashMap<K, Node>} de tra cuu O(1) ket hop Doubly
 * Linked List TU VIET (khong dung {@link java.util.LinkedHashMap} co san,
 * de chung minh hieu ro co che O(1) ben duoi). Danh sach lien ket co 2
 * sentinel node (head/tail) khong chua du lieu that, chi de danh dau 2
 * dau — nho vay moi thao tac them/xoa node dau tien hoac cuoi cung khong
 * can kiem tra null rieng, giam nhieu nhanh if/else.
 *
 * <p>Quy uoc thu tu: node ngay sau {@code head} la phan tu duoc dung GAN
 * DAY NHAT (MRU), node ngay truoc {@code tail} la phan tu it dung nhat
 * (LRU) — se bi loai bo dau tien khi cache day.
 *
 * <p>Thread-safe bang {@link ReentrantReadWriteLock}. Luu y: {@code get()}
 * ve ban chat KHONG phai thao tac doc thuan tuy vi no di chuyen node len
 * dau danh sach (cap nhat recency), nen phai dung write lock giong nhu
 * {@code put()} — neu dung read lock cho get() nhieu thread doc dong thoi
 * se cung sua doi danh sach lien ket va lam hong cau truc du lieu.
 *
 * <p>Do phuc tap thoi gian: {@link #get(Object)} va {@link #put(Object, Object)}
 * deu O(1) (tra cuu HashMap O(1) + thao tac danh sach lien ket doi cho tai
 * mot vi tri da biet la O(1)).
 * Do phuc tap khong gian: O(capacity).
 *
 * @param <K> loai khoa
 * @param <V> loai gia tri
 */
public class LRUCache<K, V> {

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head; // sentinel, dau la MRU
    private final Node<K, V> tail; // sentinel, cuoi la LRU
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public LRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity phai > 0");
        }
        this.capacity = capacity;
        this.map = new HashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /** O(1) - tra ve gia tri va danh dau la vua duoc dung (MRU). */
    public V get(K key) {
        lock.writeLock().lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToFront(node);
            return node.value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** O(1) - them/cap nhat gia tri, day node xuong LRU-tail neu vuot capacity. */
    public void put(K key, V value) {
        lock.writeLock().lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                moveToFront(existing);
                return;
            }
            Node<K, V> node = new Node<>(key, value);
            map.put(key, node);
            addToFront(node);
            if (map.size() > capacity) {
                Node<K, V> lru = tail.prev;
                removeNode(lru);
                map.remove(lru.key);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return map.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean containsKey(K key) {
        lock.readLock().lock();
        try {
            return map.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void addToFront(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToFront(Node<K, V> node) {
        removeNode(node);
        addToFront(node);
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        LRUCache<String, String> cache = new LRUCache<>(2);
        cache.put("q=may tinh", "ket qua A");
        cache.put("q=trinh duyet", "ket qua B");
        System.out.println("get(may tinh) = " + cache.get("q=may tinh")); // MRU hoa lai "may tinh"
        cache.put("q=bloom filter", "ket qua C"); // day "trinh duyet" ra vi la LRU
        System.out.println("get(trinh duyet) sau khi day = " + cache.get("q=trinh duyet")); // null
        System.out.println("get(may tinh) van con = " + cache.get("q=may tinh"));
        System.out.println("get(bloom filter) = " + cache.get("q=bloom filter"));
    }
}
