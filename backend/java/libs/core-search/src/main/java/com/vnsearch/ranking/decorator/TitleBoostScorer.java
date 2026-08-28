package com.vnsearch.ranking.decorator;

import com.vnsearch.index.SearchIndex;
import com.vnsearch.model.WebDocument;
import com.vnsearch.ranking.QuerySyllables;
import com.vnsearch.ranking.RelevanceScorer;

import java.util.Map;

/**
 * <b>Decorator pattern</b> — boc mot {@link RelevanceScorer} va thuong them cho
 * tai lieu co TIEU DE khop tu khoa truy van.
 *
 * <p><b>Vi sao tin hieu nay manh.</b> Do dac tren 200 truy van known-item:
 * <pre>
 *   TF-IDF thuan       : MRR 0,8537
 *   TF-IDF + PageRank  : MRR 0,8625   (+0,0088)
 *   TF-IDF + title     : MRR 0,9083   (+0,0546)   <- gap 6 lan PageRank
 * </pre>
 * Tieu de la ban tom tat do CHINH NGUOI VIET dat cho bai, nen no la tin hieu
 * lien quan rat manh — va khac voi PageRank, no cung thang do voi diem lien
 * quan (ca hai nam trong khoang tuong tu) nen ket hop de hon nhieu.
 *
 * <p><b>Vi sao van dung phep NHAN nhu {@link PageRankBoostScorer}.</b> De cong
 * thuc BAT BIEN voi thang diem cua scorer duoc boc: doi TF-IDF sang BM25 khong
 * phai chinh lai trong so. Va {@code titleBonus} da nam san trong {@code [0,1]}
 * nen khong can chuan hoa them.
 *
 * <p>Tai lieu co diem co so 0 van bang 0 — tieu de khop khong cuu duoc mot tai
 * lieu ma noi dung hoan toan khong lien quan.
 */
public final class TitleBoostScorer implements RelevanceScorer {

    private final RelevanceScorer inner;
    private final double weight;

    /**
     * @param inner  scorer duoc boc
     * @param weight muc anh huong toi da, 0 = tat
     */
    public TitleBoostScorer(RelevanceScorer inner, double weight) {
        if (inner == null) {
            throw new IllegalArgumentException("inner scorer khong duoc null");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight phai >= 0, nhan duoc: " + weight);
        }
        this.inner = inner;
        this.weight = weight;
    }

    @Override
    public double score(Map<String, Integer> queryTermFrequency, int docId, SearchIndex index) {
        return prepare(queryTermFrequency, index).score(docId);
    }

    /**
     * Dung {@link QuerySyllables} <b>mot lan cho ca truy van</b>.
     *
     * <p>Truoc day dong {@code QuerySyllables.from(...)} nam TRONG
     * {@code score}, tuc chay lai cho MOI tai lieu ung vien: moi lan la hai
     * {@code HashSet} moi, cong voi mot phep bo dau cho tung tieng truy van —
     * roi bi vut di ngay sau khi cham xong mot tai lieu. Voi 5.000 ung vien do
     * la 5.000 lan dung cung mot doi tuong khong he doi. Day la truong hop kinh
     * dien cua "bat bien vong lap bi ket ben trong vong lap".
     */
    @Override
    public DocumentScorer prepare(Map<String, Integer> queryTermFrequency, SearchIndex index) {
        DocumentScorer base = inner.prepare(queryTermFrequency, index);
        if (weight == 0.0) {
            return base; // tin hieu bi tat: khong boc them lop nao
        }
        QuerySyllables syllables = QuerySyllables.from(queryTermFrequency.keySet()); // ← MOT lan
        if (syllables.isEmpty()) {
            return base;
        }

        return docId -> {
            double baseScore = base.score(docId);
            if (baseScore == 0.0) {
                return baseScore;
            }
            WebDocument document = index.getDocument(docId);
            if (document == null) {
                return baseScore;
            }
            double bonus = syllables.titleMatchRatio(document.getTitle()); // thuoc [0, 1]
            return baseScore * (1 + weight * bonus);
        };
    }

    @Override
    public String name() {
        return String.format("%s + title x%.2f", inner.name(), weight);
    }
}
