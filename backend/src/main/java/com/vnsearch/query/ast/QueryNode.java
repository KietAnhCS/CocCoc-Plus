package com.vnsearch.query.ast;

import com.vnsearch.index.SearchIndex;

import java.util.List;

/**
 * <b>Composite pattern</b> — mot nut trong cay bieu thuc truy van.
 *
 * <p><b>Van de cua cau truc phang.</b> {@code ParsedQuery} luu ba danh sach
 * phang ({@code mustTerms}, {@code phrases}, {@code excludedTerms}), tuc no
 * MA HOA SAN gia dinh "moi mustTerm noi voi nhau bang AND". Cau truc do khong
 * bieu dien duoc:
 * <pre>
 *   (máy tính OR laptop) AND giá rẻ
 *   NOT (quảng cáo OR khuyến mãi)
 * </pre>
 * Dang chu y: {@code PostingListMerger.union} DA TON TAI va da co test, nhung
 * truoc day <b>khong co duong nao goi toi no</b> tu tang truy van — mot cau
 * truc du lieu bi bo phi hoan toan.
 *
 * <p><b>Cay bieu thuc</b> cho phep long nhau bat ky do sau. Vi du cay cho
 * {@code (máy tính OR laptop) AND giá rẻ}:
 * <pre>
 *               AndNode
 *              /       \
 *         OrNode      TermNode(giá_rẻ)
 *         /     \
 *   TermNode   TermNode
 *  (máy_tính)  (laptop)
 * </pre>
 *
 * <p><b>{@code sealed} + {@code record}:</b> Java 17 cho phep khai bao kin tap
 * cai dat, nen {@code switch} tren {@code QueryNode} co kiem tra DAY DU nhanh —
 * them mot loai nut moi thi trinh bien dich nhac moi cho can sua.
 *
 * <p><b>Bat bien:</b> moi {@link #evaluate} deu tra ve danh sach docId
 * <b>sap xep tang dan</b>, de cac nut cha ghep lai duoc bang two-pointer.
 */
public sealed interface QueryNode
        permits TermNode, PhraseNode, AndNode, OrNode, NotNode {

    /**
     * Danh gia nut nay tren chi muc, tra ve danh sach docId SAP XEP TANG DAN.
     *
     * <p>De quy tu nhien: nut la tra posting list, nut trong ghep ket qua cua
     * cac con.
     */
    List<Integer> evaluate(SearchIndex index);

    /**
     * Uoc luong so ket qua, dung de sap xep shortest-first ma khong phai danh
     * gia that. Voi {@link TermNode} do chinh la document frequency.
     */
    int estimatedSize(SearchIndex index);

    /** Bieu dien dang chuoi, dung de gio loi va lam nhan trong bao cao. */
    String describe();
}
