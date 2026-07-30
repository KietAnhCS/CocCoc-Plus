package com.vnsearch.ranking;

import com.vnsearch.index.SearchIndex;

import java.util.Map;

/**
 * <b>Strategy pattern</b> — giao dien chung cho cac mo hinh tinh diem lien quan
 * giua truy van va tai lieu.
 *
 * <p><b>Dong co khoa hoc, khong phai "dung pattern cho co".</b> Day la dieu
 * kien CAN de lam thi nghiem ablation trong bao cao: chay CUNG mot bo truy van,
 * CUNG mot chi muc, chi thay dung mot mo hinh tinh diem, roi so sanh cac do do
 * chat luong. Neu khong tach duoc ra sau mot giao dien thi moi so sanh deu lan
 * them bien so khac va mat gia tri khoa hoc.
 *
 * <p>Ket qua ma no mo khoa (200 truy van known-item):
 * <pre>
 *   TF-IDF thuan : MRR 0,8537   Success@1 78,0%
 *   BM25 thuan   : MRR 0,8989   Success@1 85,0%
 * </pre>
 *
 * <p><b>Ket hop nhieu tin hieu bang Decorator.</b> Cac lop
 * {@link com.vnsearch.ranking.decorator.PageRankBoostScorer} va
 * {@link com.vnsearch.ranking.decorator.TitleBoostScorer} <i>boc</i> mot scorer
 * khac va nhan them tin hieu vao diem co so. Nho vay bat/tat tung tin hieu chi
 * la them/bot mot lop boc, khong phai sua cong thuc chon cung o
 * {@link ResultRanker}.
 */
public interface RelevanceScorer {

    /**
     * Tinh diem lien quan cua {@code docId} doi voi truy van.
     *
     * @param queryTermFrequency so lan moi term xuat hien trong truy van
     * @param docId              tai lieu can cham diem
     * @param index              chi muc chua posting list va thong ke corpus
     */
    double score(Map<String, Integer> queryTermFrequency, int docId, SearchIndex index);

    /**
     * Ten ngan gon cua mo hinh, dung lam nhan trong bang ket qua danh gia.
     *
     * <p>Cac lop Decorator tu GHEP ten cua lop ben trong, nen mot cau hinh
     * long nhau cho ra nhan mo ta day du, vi du:
     * {@code "BM25(k1=1.2,b=0.75) + PR x0.30 + title x0.10"}.
     */
    String name();
}
