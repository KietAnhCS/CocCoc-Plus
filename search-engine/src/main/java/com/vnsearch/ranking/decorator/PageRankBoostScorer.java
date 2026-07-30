package com.vnsearch.ranking.decorator;

import com.vnsearch.index.SearchIndex;
import com.vnsearch.ranking.RelevanceScorer;

import java.util.Map;

/**
 * <b>Decorator pattern</b> — boc mot {@link RelevanceScorer} va nhan them tin
 * hieu uy tin PageRank vao diem co so.
 *
 * <p><b>Van de that ma lop nay giai — do duoc bang so.</b> Cong thuc cu la
 * mot phep cong tuyen tinh chon cung trong {@code ResultRanker}:
 * <pre>
 *   final = alpha*relevance + beta*pageRank + gamma*titleBonus
 * </pre>
 * Do dac tren corpus 5.011 trang:
 * <pre>
 *   TF-IDF cosine : trung binh 0,177687   -> x0,6 = 0,106612
 *   PageRank      : trung binh 0,00035388 -> x0,3 = 0,00010616
 *
 *   ty le dong gop cua PageRank = 0,00010616 / 0,106612 ~= 0,1 %
 * </pre>
 * <b>PageRank dong gop MOT PHAN NGHIN du trong so danh nghia la 30 %.</b>
 * Bang chung thuc nghiem: quet beta tu 0,05 den 0,80 (gap 16 lan) chi lam MRR
 * doi 0,0040 — tuc 0,4 %.
 *
 * <p><b>Vi sao khong phai "chon beta chua toi uu".</b> PageRank la mot PHAN
 * PHOI XAC SUAT: {@code sum(PR) = 1}, nen voi N = 5.011 trang, gia tri trung
 * binh <i>buoc phai</i> la {@code 1/5011 = 0,0002}. Va no CO LAI khi corpus lon
 * hon — voi 1 trieu trang, PageRank trung binh la {@code 10^-6}, dong gop giam
 * them 200 lan nua. Cong mot do tuong tu voi mot phan phoi xac suat la phep
 * toan khong co y nghia, bat ky beta nao cung khong sua duoc.
 *
 * <p><b>Cach lam o day: nhan, khong cong.</b>
 * <pre>
 *   final = base * (1 + weight * normalized)
 *   normalized = log1p(pr / prMin) / log1p(prMax / prMin)   thuoc [0, 1]
 * </pre>
 * Hai ly do:
 * <ul>
 *   <li><b>Logarit nen dai dong.</b> PageRank trai tren nhieu bac do lon
 *       (tu {@code 10^-4} den {@code 7,7*10^-3}); {@code log} bien no thanh
 *       dai luong cong duoc, va chuan hoa ve {@code [0,1]} lam {@code weight}
 *       tro thanh ty le dong gop THAT.</li>
 *   <li><b>Phep nhan BAT BIEN voi thang do cua base.</b> Doi scorer tu TF-IDF
 *       sang BM25 (thang diem khac han: 0,18 so voi 12,1) KHONG can chinh lai
 *       trong so. Day chinh la ly do bang danh gia cu cho thay "BM25 + PR +
 *       title" (MRR 0,9089) <i>thua</i> "TF-IDF + PR + title" (0,9229): bo
 *       trong so duoc tinh chinh cho thang TF-IDF, khong dung lai duoc cho
 *       BM25.</li>
 * </ul>
 *
 * <p>Tai lieu co diem co so bang 0 van duoc giu bang 0 ({@code 0 * bat ky = 0})
 * — dung y muon: uy tin cao khong cuu duoc mot tai lieu hoan toan khong lien quan.
 */
public final class PageRankBoostScorer implements RelevanceScorer {

    private final RelevanceScorer inner;
    private final Map<Integer, Double> pageRankScores;
    private final double weight;
    private final double minPageRank;
    private final double logRange;

    /**
     * @param inner          scorer duoc boc (TF-IDF, BM25, hoac mot Decorator khac)
     * @param pageRankScores diem PageRank da tinh san cho toan corpus
     * @param weight         muc anh huong toi da, 0 = tat, 1 = co the nhan doi diem
     */
    public PageRankBoostScorer(RelevanceScorer inner, Map<Integer, Double> pageRankScores, double weight) {
        if (inner == null) {
            throw new IllegalArgumentException("inner scorer khong duoc null");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight phai >= 0, nhan duoc: " + weight);
        }
        this.inner = inner;
        this.pageRankScores = pageRankScores == null ? Map.of() : pageRankScores;
        this.weight = weight;

        double min = this.pageRankScores.values().stream()
                .mapToDouble(Double::doubleValue).filter(v -> v > 0).min().orElse(1e-9);
        double max = this.pageRankScores.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(min);
        this.minPageRank = min;
        // Chuan hoa: chia cho log cua ty le max/min de normalized nam trong [0,1].
        this.logRange = Math.max(Math.log1p(max / min), 1e-9);
    }

    @Override
    public double score(Map<String, Integer> queryTermFrequency, int docId, SearchIndex index) {
        double base = inner.score(queryTermFrequency, docId, index);
        if (base == 0.0 || weight == 0.0) {
            return base; // thoat som: khong tinh gi them
        }
        double pageRank = pageRankScores.getOrDefault(docId, minPageRank);
        double normalized = Math.log1p(pageRank / minPageRank) / logRange; // thuoc [0, 1]
        return base * (1 + weight * normalized);
    }

    @Override
    public String name() {
        return String.format("%s + PR x%.2f", inner.name(), weight);
    }
}
