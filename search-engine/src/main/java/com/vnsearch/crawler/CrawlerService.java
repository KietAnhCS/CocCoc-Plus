package com.vnsearch.crawler;

/**
 * TODO (PHASE 3): Dich vu crawl web, duyet theo BFS bang UrlFrontier.
 *
 * Tham so cau hinh: maxDepth, maxPages, threadCount, allowedDomains.
 * Da luong bang ExecutorService (fixed pool); dung ConcurrentHashMap +
 * BloomFilter (com.vnsearch.datastructure.BloomFilter) de dedupe URL.
 * Ton trong robots.txt (RobotsTxtParser). Timeout 10s/request, retry toi
 * da 2 lan. Trich xuat title/meta description/body/outlinks bang
 * HtmlExtractor, dong goi thanh WebDocument.
 *
 * Log tien do dang: [123/1000] Crawled: <url> (depth=2, 45 links)
 */
public class CrawlerService {
    // TODO: implement in PHASE 3
}
