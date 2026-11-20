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

    /**
     * Ham cham diem da <b>gan san voi mot truy van</b>: chi con nhan docId.
     *
     * <p>Ton tai de tach doi hai loai cong viec von bi tron lam mot trong
     * {@link #score}.
     */
    @FunctionalInterface
    interface DocumentScorer {
        double score(int docId);
    }

    /**
     * Tinh truoc MOT LAN moi thu chi phu thuoc TRUY VAN, tra ve ham cham diem
     * cho tung tai lieu.
     *
     * <p><b>Van de that ma no giai.</b> Chu ky mot truy van la: lay {@code c}
     * ung vien roi cham diem tung cai. Nhung {@link #score} nhan
     * {@code queryTermFrequency} o MOI lan goi, nen moi dai luong suy ra tu
     * truy van bi tinh lai {@code c} lan du chung khong he doi:
     * <ul>
     *   <li>{@code TfIdfScorer} tinh lai {@code idf} va trong so truy van —
     *       hai {@code Math.log10} cho MOI (term, ung vien);</li>
     *   <li>{@code BM25Scorer} tinh lai {@code idf} — mot {@code Math.log};</li>
     *   <li>{@code TitleBoostScorer} dung lai ca doi tuong
     *       {@link QuerySyllables} — hai {@code HashSet} moi, cong voi mot lan
     *       bo dau cho tung tieng — cho MOI ung vien.</li>
     * </ul>
     * Voi 5.000 ung vien va 3 term, do la 30.000 phep logarit va 5.000 doi tap
     * bam bi vut di ngay sau khi tao. Chuan bi truoc dua chung ve dung mot lan
     * moi truy van, tuc tu {@code O(c*q)} xuong {@code O(q)}.
     *
     * <p>Cai dat mac dinh khong chuan bi gi — dung cho scorer khong co phan
     * nao tach ra duoc. Cac Decorator boc lai {@code DocumentScorer} cua lop
     * ben trong, nen ca chuoi chi phai chuan bi mot lan.
     */
    default DocumentScorer prepare(Map<String, Integer> queryTermFrequency, SearchIndex index) {
        return docId -> score(queryTermFrequency, docId, index);
    }
}
