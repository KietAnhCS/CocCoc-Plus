package com.vnsearch.model;

import java.util.List;

/**
 * Response tra ve cho GET /api/search.
 *
 * <p>Cung ly do lam record voi {@link SearchResult}: ban truoc la 65 dong
 * getter/setter khong noi nao trong ma nguon goi toi.
 *
 * @param pageSize     so ket qua MOI TRANG that su duoc dung.
 *
 *                     <p>Truong nay tung thieu, va no thieu mot cach kin dao:
 *                     {@code searchApi.ts} khai bao {@code pageSize} roi doc
 *                     bang {@code raw.pageSize ?? pageSize} — khong thay duoc
 *                     truong thi lang le thay bang chinh gia tri vua gui di.
 *                     Dung trong moi truong hop, cho toi khi may chu KHONG dung
 *                     gia tri client gui: {@code SearchController} thay mot
 *                     {@code size} ngoai khoang 1..100 bang mac dinh 20. Khi do
 *                     client hien "20 ket qua moi trang" trong khi tin rang no
 *                     dang xem 500 — va tinh so trang sai theo.
 *
 *                     <p>Bai hoc chung: hop dong API nen tra ve <b>gia tri da
 *                     duoc ap dung</b>, khong phai de ben goi tu suy ra tu thu
 *                     minh vua gui.
 * @param droppedTerms cac term he thong da tu bo de tim duoc ket qua (xem
 *                     {@code CandidateResolver}). Rong trong truong hop thuong.
 *                     Bao ra thay vi giau di: nguoi dung co quyen biet ket qua
 *                     ho dang xem ung voi mot truy van HEP HON truy van ho vua
 *                     go — im lang ve chuyen do la de ho ket luan sai rang moi
 *                     tu khoa ho nhap deu co trong corpus.
 */
public record SearchResponse(String query, int totalResults, int page, int pageSize,
                              long timeTakenMs, List<SearchResult> results,
                              List<String> droppedTerms) {
}
