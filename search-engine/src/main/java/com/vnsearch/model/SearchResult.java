package com.vnsearch.model;

import java.time.Instant;

/**
 * Mot ket qua tim kiem tra ve cho client, khop voi hop dong REST API
 * GET /api/search (xem docs/api-examples.http).
 *
 * <p><b>Vi sao la record.</b> Ban truoc la mot POJO 90 dong gom constructor
 * rong, constructor day du, bay getter va bay setter — trong do <b>khong mot
 * getter/setter nao duoc goi tu ma nguon</b>, chung chi ton tai cho Jackson, ma
 * Jackson doc record truc tiep duoc tu 2.12. Ten khoa JSON sinh ra y het ban cu
 * nen hop dong API khong doi.
 *
 * <p><b>Truong {@code tfidfScore} da bi bo.</b> No tung mang so diem TF-IDF
 * rieng, nhung ke tu khi cac tin hieu duoc gom bang Decorator thi no nhan dung
 * CUNG MOT gia tri voi {@code score} — giao dien trinh duyet in ra cung mot con
 * so hai lan duoi hai nhan khac nhau. Bao mot dai luong hai lan duoi hai ten
 * khac nhau la noi sai, khong phai du thong tin.
 *
 * @param score         diem cuoi cung dung de xep hang (da gom moi tin hieu)
 * @param pageRankScore diem PageRank cua tai lieu, bao rieng de quan sat
 */
public record SearchResult(String title, String url, String snippet,
                            double score, double pageRankScore, Instant crawledAt) {
}
