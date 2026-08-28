package com.vnsearch.datastructure;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LRU (Least Recently Used) Cache tự cài đặt, dùng để cache kết quả tìm kiếm
 * gần đây và trang đã ghé trong trình duyệt.
 *
 * <p>Cấu trúc: {@code HashMap<K, Node>} để tra cứu O(1) kết hợp Doubly Linked
 * List TỰ VIẾT (không dùng {@link java.util.LinkedHashMap} có sẵn, để chứng
 * minh hiểu rõ cơ chế O(1) bên dưới). Danh sách liên kết có 2 node lính canh
 * (sentinel head/tail) không chứa dữ liệu thật, chỉ để đánh dấu 2 đầu — nhờ
 * vậy mọi thao tác thêm/xoá node đầu tiên hoặc cuối cùng không cần kiểm tra
 * null riêng, giảm hẳn số nhánh if/else.
 *
 * <p>Quy ước thứ tự: node ngay sau {@code head} là phần tử được dùng GẦN ĐÂY
 * NHẤT (MRU), node ngay trước {@code tail} là phần tử ít dùng nhất (LRU) — sẽ
 * bị loại bỏ đầu tiên khi cache đầy.
 *
 * <p><b>Vì sao phải là danh sách liên kết ĐÔI.</b> Xoá một node ở <i>giữa</i>
 * trong O(1) đòi hỏi biết <b>cả</b> node trước và node sau. Danh sách đơn phải
 * duyệt từ đầu để tìm node trước, tức O(n) — và khi đó cache LRU mất hoàn toàn
 * ưu điểm, vì mỗi lần truy cập đều thành O(n).
 *
 * <p>Thread-safe bằng {@link ReentrantReadWriteLock}. Lưu ý quan trọng:
 * {@code get()} về bản chất KHÔNG phải thao tác đọc thuần tuý vì nó di chuyển
 * node lên đầu danh sách (cập nhật recency), nên phải dùng write lock giống
 * như {@code put()} — nếu dùng read lock cho {@code get()} thì nhiều luồng đọc
 * đồng thời sẽ cùng sửa đổi danh sách liên kết và làm hỏng cấu trúc dữ liệu.
 * Đây là điểm khác biệt so với {@link Trie}, nơi {@code getSuggestions} là đọc
 * thật sự nên dùng được read lock.
 *
 * <p>Độ phức tạp thời gian: {@link #get(Object)} và {@link #put(Object, Object)}
 * đều O(1) (tra cứu HashMap O(1) + thao tác danh sách liên kết đôi tại một vị
 * trí đã biết là O(1)). Độ phức tạp không gian: O(capacity).
 *
 * @param <K> loại khoá
 * @param <V> loại giá trị
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
    private final Node<K, V> head; // lính canh, đầu là MRU
    private final Node<K, V> tail; // lính canh, cuối là LRU
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public LRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity phải > 0");
        }
        this.capacity = capacity;
        this.map = new HashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /** O(1) — trả về giá trị và đánh dấu là vừa được dùng (MRU). */
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

    /** O(1) — thêm/cập nhật giá trị, đẩy node ở LRU-tail ra nếu vượt capacity. */
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

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) {
        LRUCache<String, String> cache = new LRUCache<>(2);
        cache.put("q=máy tính", "kết quả A");
        cache.put("q=trình duyệt", "kết quả B");
        System.out.println("get(máy tính) = " + cache.get("q=máy tính")); // MRU hoá lại "máy tính"
        cache.put("q=bloom filter", "kết quả C"); // đẩy "trình duyệt" ra vì là LRU
        System.out.println("get(trình duyệt) sau khi bị đẩy = " + cache.get("q=trình duyệt")); // null
        System.out.println("get(máy tính) vẫn còn = " + cache.get("q=máy tính"));
        System.out.println("get(bloom filter) = " + cache.get("q=bloom filter"));
    }
}
