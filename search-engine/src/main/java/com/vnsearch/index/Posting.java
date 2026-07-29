package com.vnsearch.index;

import java.util.List;

/**
 * Mot muc trong posting list cua inverted index: mot tai lieu (docId) co
 * chua mot term nao do, kem so lan xuat hien (termFrequency) va danh sach
 * vi tri xuat hien (positions - la chi so thu tu token trong tai lieu,
 * dung cho phrase search sau nay: 2 term "canh nhau" khi position cua
 * term sau = position cua term truoc + 1).
 *
 * <p>Record (immutable) vi mot Posting khong bao gio thay doi sau khi tao
 * (tai lieu duoc index lai thi tao Posting moi thay vi sua Posting cu).
 */
public record Posting(int docId, int termFrequency, List<Integer> positions) {
}
