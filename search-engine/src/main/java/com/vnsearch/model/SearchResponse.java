package com.vnsearch.model;

import java.util.List;

/**
 * Response tra ve cho GET /api/search.
 *
 * <p>Cung ly do lam record voi {@link SearchResult}: ban truoc la 65 dong
 * getter/setter khong noi nao trong ma nguon goi toi.
 *
 * @param droppedTerms cac term he thong da tu bo de tim duoc ket qua (xem
 *                     {@code CandidateResolver}). Rong trong truong hop thuong.
 *                     Bao ra thay vi giau di: nguoi dung co quyen biet ket qua
 *                     ho dang xem ung voi mot truy van HEP HON truy van ho vua
 *                     go — im lang ve chuyen do la de ho ket luan sai rang moi
 *                     tu khoa ho nhap deu co trong corpus.
 */
public record SearchResponse(String query, int totalResults, int page, long timeTakenMs,
                              List<SearchResult> results, List<String> droppedTerms) {
}
