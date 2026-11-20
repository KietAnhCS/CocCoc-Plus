package com.vnsearch.datastructure;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LRUCacheTest {

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LRUCache<String, String>(0));
    }

    @Test
    void getOnEmptyCacheReturnsNull() {
        LRUCache<String, String> cache = new LRUCache<>(2);
        assertNull(cache.get("missing"));
    }

    @Test
    void singleEntryPutAndGet() {
        LRUCache<String, Integer> cache = new LRUCache<>(1);
        cache.put("a", 1);
        assertEquals(1, cache.get("a"));
    }

    @Test
    void evictsLeastRecentlyUsedWhenOverCapacity() {
        LRUCache<Integer, String> cache = new LRUCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");
        cache.get(1); // 1 tro thanh MRU, 2 la LRU
        cache.put(3, "three"); // day 2 ra vi la LRU

        assertNull(cache.get(2));
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
    }

    @Test
    void puttingExistingKeyUpdatesValueAndRecency() {
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("a", 100); // cap nhat gia tri, "a" thanh MRU
        cache.put("c", 3); // day "b" ra (LRU)

        assertEquals(100, cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals(3, cache.get("c"));
    }

    @Test
    void duplicatePutsDoNotGrowSize() {
        LRUCache<String, Integer> cache = new LRUCache<>(5);
        cache.put("x", 1);
        cache.put("x", 2);
        cache.put("x", 3);
        assertEquals(1, cache.size());
        assertEquals(3, cache.get("x"));
    }

    @Test
    void concurrentAccessDoesNotCorruptState() throws InterruptedException {
        LRUCache<Integer, Integer> cache = new LRUCache<>(50);
        int threadCount = 8;
        int opsPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = (threadId * opsPerThread + i) % 20;
                        cache.put(key, key * 10);
                        cache.get(key);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertEquals(true, latch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        // Neu cau truc bi hong (vong lap, mat lien ket...), size() se khong hop le.
        assertEquals(true, cache.size() <= 50 && cache.size() >= 0);
    }
}
