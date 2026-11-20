package com.vnsearch.datastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomFilterTest {

    @Test
    void constructorRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(100, 0));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(100, 1));
    }

    @Test
    void mightContainOnEmptyFilterIsFalse() {
        BloomFilter filter = new BloomFilter(100, 0.01);
        assertFalse(filter.mightContain("khong ton tai"));
    }

    @Test
    void singleItemAddedIsAlwaysFound() {
        BloomFilter filter = new BloomFilter(100, 0.01);
        filter.add("https://vnexpress.net/");
        assertTrue(filter.mightContain("https://vnexpress.net/"));
    }

    @Test
    void addingSameItemTwiceIsIdempotent() {
        BloomFilter filter = new BloomFilter(100, 0.01);
        filter.add("dup");
        filter.add("dup");
        assertTrue(filter.mightContain("dup"));
    }

    @Test
    void neverProducesFalseNegative() {
        // Them 5000 chuoi, kiem tra TAT CA deu duoc tim thay (khong false negative).
        BloomFilter filter = new BloomFilter(5000, 0.01);
        for (int i = 0; i < 5000; i++) {
            filter.add("https://example.vn/page/" + i);
        }
        for (int i = 0; i < 5000; i++) {
            assertTrue(filter.mightContain("https://example.vn/page/" + i),
                    "Bloom Filter khong bao gio duoc co false negative");
        }
    }

    @Test
    void falsePositiveRateIsApproximatelyAsConfigured() {
        int n = 10_000;
        double targetFpr = 0.01;
        BloomFilter filter = new BloomFilter(n, targetFpr);
        for (int i = 0; i < n; i++) {
            filter.add("item-" + i);
        }
        int falsePositives = 0;
        int trials = 10_000;
        for (int i = 0; i < trials; i++) {
            if (filter.mightContain("not-inserted-" + i)) {
                falsePositives++;
            }
        }
        double observedFpr = (double) falsePositives / trials;
        // Cho phep sai so bien dong (che do thong ke), quan trong la khong lech qua xa muc tieu.
        assertTrue(observedFpr < targetFpr * 3,
                "False positive rate quan sat duoc (" + observedFpr + ") lech qua xa muc tieu (" + targetFpr + ")");
    }

    @Test
    void vietnameseUrlsWithDiacritics() {
        BloomFilter filter = new BloomFilter(100, 0.01);
        filter.add("https://vnexpress.net/tin-tuc/khoa-hoc");
        filter.add("https://example.vn/máy-tính");
        assertTrue(filter.mightContain("https://example.vn/máy-tính"));
        assertFalse(filter.mightContain("https://example.vn/khong-them"));
    }
}
