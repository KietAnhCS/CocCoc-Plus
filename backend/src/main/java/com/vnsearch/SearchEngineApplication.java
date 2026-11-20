package com.vnsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * VnSearch — search engine monolith.
 *
 * <p>Một tiến trình, một cổng (8080). Toàn bộ cấu trúc dữ liệu và thuật toán
 * (Trie, BloomFilter, LRUCache, MinHeap, InvertedIndex + VByte, PageRank,
 * TF-IDF/BM25, tách từ tiếng Việt, crawler frontier) đều tự cài trong các gói
 * {@code datastructure}, {@code index}, {@code query}, {@code ranking},
 * {@code crawler}. Lớp này chỉ khởi động Spring để phơi chúng ra qua REST.
 */
@SpringBootApplication
public class SearchEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchEngineApplication.class, args);
    }
}
