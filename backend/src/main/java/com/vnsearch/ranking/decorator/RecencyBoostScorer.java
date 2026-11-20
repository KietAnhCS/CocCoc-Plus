package com.vnsearch.ranking.decorator;

import com.vnsearch.index.SearchIndex;
import com.vnsearch.ranking.RelevanceScorer;

import java.util.Map;

/**
 * <b>Decorator pattern</b> — boc mot {@link RelevanceScorer} va thuong them cho
 * tai lieu duoc crawl GAN DAY.
 *
 * <p>Moc thoi gian lay tu {@code crawledAt} — tuc theo LAN CRAWLER chay, khong
 * phai ngay xuat ban ghi trong bai. Chuan hoa tuyen tinh ve {@code [0, 1]}:
 * tai lieu moi nhat trong corpus duoc 1, cu nhat duoc 0.
 *
 * <p>Van dung phep NHAN nhu {@link PageRankBoostScorer} va
 * {@link TitleBoostScorer} de cong thuc BAT BIEN voi thang diem cua scorer
 * duoc boc (TF-IDF ~0,18 hay BM25 ~12 khong phai chinh lai trong so):
 * <pre>
 *   final = base * (1 + weight * normalizedRecency)
 * </pre>
 *
 * <p>Tai lieu co diem co so 0 van bang 0 — moi khong cuu duoc mot tai lieu
 * khong lien quan. Tai lieu thieu {@code crawledAt} bi coi nhu cu nhat (0).
 */
public final class RecencyBoostScorer implements RelevanceScorer {

    private final RelevanceScorer inner;
    private final double weight;
    private final long minEpochMillis;
    private final long rangeMillis;
    private final Map<Integer, Long> crawledAtEpochMillis;

    /**
     * @param inner               scorer duoc boc
     * @param crawledAtEpochMillis moc crawl (epoch millis) theo docId cho toan corpus
     * @param weight              muc anh huong toi da, 0 = tat
     */
    public RecencyBoostScorer(RelevanceScorer inner,
                               Map<Integer, Long> crawledAtEpochMillis,
                               double weight) {
        if (inner == null) {
            throw new IllegalArgumentException("inner scorer khong duoc null");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight phai >= 0, nhan duoc: " + weight);
        }
        this.inner = inner;
        this.weight = weight;
        this.crawledAtEpochMillis = crawledAtEpochMillis == null ? Map.of() : crawledAtEpochMillis;

        long min = this.crawledAtEpochMillis.values().stream()
                .mapToLong(Long::longValue).min().orElse(0L);
        long max = this.crawledAtEpochMillis.values().stream()
                .mapToLong(Long::longValue).max().orElse(min);
        this.minEpochMillis = min;
        this.rangeMillis = Math.max(max - min, 1L);
    }

    @Override
    public double score(Map<String, Integer> queryTermFrequency, int docId, SearchIndex index) {
        return prepare(queryTermFrequency, index).score(docId);
    }

    @Override
    public DocumentScorer prepare(Map<String, Integer> queryTermFrequency, SearchIndex index) {
        DocumentScorer base = inner.prepare(queryTermFrequency, index);
        if (weight == 0.0 || crawledAtEpochMillis.isEmpty()) {
            return base;
        }
        return docId -> {
            double baseScore = base.score(docId);
            if (baseScore == 0.0) {
                return baseScore;
            }
            Long millis = crawledAtEpochMillis.get(docId);
            if (millis == null) {
                return baseScore;
            }
            double normalized = (double) (millis - minEpochMillis) / rangeMillis;
            if (normalized < 0.0) {
                normalized = 0.0;
            } else if (normalized > 1.0) {
                normalized = 1.0;
            }
            return baseScore * (1 + weight * normalized);
        };
    }

    @Override
    public String name() {
        return String.format("%s + recency x%.2f", inner.name(), weight);
    }
}
