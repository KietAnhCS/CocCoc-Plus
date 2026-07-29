package com.vnsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point cua backend search engine (do an DSA).
 *
 * Toan bo cau truc du lieu va thuat toan loi (Trie, BloomFilter, LRUCache,
 * MinHeap, InvertedIndex, PageRank, ...) duoc tu cai dat trong cac package
 * con: {@code datastructure}, {@code crawler}, {@code index}, {@code ranking},
 * {@code query}. Spring Boot chi dong vai tro lop ha tang (REST controller,
 * dependency injection), khong thay the cho bat ky thuat toan nao.
 */
@SpringBootApplication
public class VnSearchApplication {

	public static void main(String[] args) {
		SpringApplication.run(VnSearchApplication.class, args);
	}

}
