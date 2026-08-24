package com.vnsearch.service;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.Tokenizer;
import com.vnsearch.index.VietnameseTokenizer;
import com.vnsearch.model.WebDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dung chi muc dao tu mot danh sach tai lieu.
 *
 * <p><b>Vi sao tach thanh lop rieng:</b> viec nay co MOT tien de bat buoc phai
 * giu — {@code addDocument} phai duoc goi theo thu tu docId TANG DAN de bat
 * bien "posting list sap xep theo docId" duoc dam bao mien phi. Truoc day tien
 * de do duoc lap lai o BA noi ({@code SearchEngineFacade}, {@code EvaluationRunner},
 * {@code GinBaselineRunner}), moi noi tu nho sort. Quen mot cho la he thong tra
 * ket qua SAI mot cach im lang.
 *
 * <p>Gom ve day de chi con MOT cho phai nho. ({@code InvertedIndex} nay cung
 * tu EP tien de do bang cach nem ngoai le neu bi goi sai thu tu — hai lop bao
 * ve doc lap.)
 *
 * <p><b>Tach tu chay SONG SONG, nap chi muc chay TUAN TU.</b> Do tren corpus
 * 30.017 trang, buoc dung chi muc chiem phan lon trong 58 giay khoi dong, va
 * gan nhu toan bo thoi gian do nam o phep tach tu — mot cong viec thuan tinh
 * toan, khong cham I/O, va <b>doc lap giua cac tai lieu</b>. Dieu kien de song
 * song hoa an toan:
 * <ul>
 *   <li>{@link VietnameseTokenizer} bat bien sau khi dung (moi truong deu
 *       {@code final}, tu dien va trie chi doc), nen nhieu luong goi
 *       {@code tokenize} cung luc la an toan;</li>
 *   <li>phep <b>nap</b> vao chi muc thi khong the song song — no phai theo dung
 *       thu tu docId tang dan. Nen chi buoc tach tu duoc chia ra nhieu nhan,
 *       con buoc nap van chay tuan tu tren luong goi.</li>
 * </ul>
 * Danh doi: giu toan bo token cua ca corpus trong bo nho mot luc se ton them
 * rat nhieu RAM. De tranh, corpus duoc chia thanh <b>lo</b>: moi lo tach tu
 * song song roi nap ngay, nen luong token song cung luc bi chan o kich thuoc
 * mot lo chu khong phai ca corpus.
 */
@Component
public class IndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(IndexBuilder.class);

    /**
     * So tai lieu tach tu song song trong mot lo.
     *
     * <p>Du lon de chi phi dieu phoi song song khong dang ke so voi cong viec
     * thuc, du nho de dinh bo nho tam khong phinh: 512 tai lieu bao chi tuong
     * duong khoang mot phan sau mươi corpus hien tai.
     */
    private static final int BATCH_SIZE = 512;

    /**
     * Duoi nguong nay thi chay tuan tu.
     *
     * <p>Voi vai chuc tai lieu, chi phi khoi dong bo dieu phoi song song lon
     * hon chinh cong viec. Nguong nay giu cho cac bai kiem thu (thuong 2-3 tai
     * lieu) khong phai tra gia do.
     */
    private static final int PARALLEL_THRESHOLD = 2_000;

    private final Tokenizer tokenizer;

    public IndexBuilder(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * Dung mot chi muc MOI tinh tu danh sach tai lieu.
     *
     * <p>Luon tao chi muc moi thay vi cap nhat chi muc cu: {@code addDocument}
     * khong idempotent (goi hai lan cung docId se tao posting trung), va viec
     * dung lai chi ton vai giay nen khong dang danh doi tinh dung dan.
     *
     * <p><b>docId duoc CAP LAI thanh 0..n-1 tai day</b>, khong tin dung so co
     * san trong tai lieu. Ly do: docId la danh tinh cua tai lieu TRONG mot chi
     * muc cu the — chi so vao posting list — chu khong phai thuoc tinh cua
     * trang web. Corpus di vao day den tu ben ngoai (tep JSON cua phien crawl
     * truoc, bang PostgreSQL, tham chi tep nguoi dung tu ghep) nen khong co gi
     * bao dam no danh so duy nhat. Truoc day mot corpus co hai tai lieu trung
     * docId lam {@code addDocument} nem ngoai le ngay trong {@code @PostConstruct},
     * va ung dung KHONG khoi dong duoc — mot tep du lieu khong hoan hao khong
     * duoc phep gay ra hau qua do.
     */
    public InvertedIndex build(List<WebDocument> documents) {
        long start = System.currentTimeMillis();
        InvertedIndex index = new InvertedIndex(tokenizer);

        List<WebDocument> sorted = new ArrayList<>(documents);
        sorted.sort(Comparator.comparingInt(WebDocument::getDocId)); // TIEN DE bat buoc

        int nextDocId = 0;
        for (WebDocument doc : sorted) {
            doc.setDocId(nextDocId++); // CAP LAI danh tinh — xem Javadoc ham
        }

        if (sorted.size() < PARALLEL_THRESHOLD) {
            for (WebDocument doc : sorted) {
                index.addDocument(doc);
            }
        } else {
            buildInBatches(index, sorted);
        }

        log.info("Da dung chi muc: {} tai lieu, {} term, {} ms",
                index.getTotalDocs(), index.getTermCount(), System.currentTimeMillis() - start);
        return index;
    }

    /** Tach tu song song theo lo, nap tuan tu — xem Javadoc lop. */
    private void buildInBatches(InvertedIndex index, List<WebDocument> sorted) {
        for (int from = 0; from < sorted.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, sorted.size());
            List<WebDocument> batch = sorted.subList(from, to);

            // parallelStream giu nguyen THU TU khi thu ket qua bang toList(),
            // nen token thu i van ung voi tai lieu thu i cua lo.
            List<List<VietnameseTokenizer.Token>> tokensPerDoc = batch.parallelStream()
                    .map(doc -> tokenizer.tokenize(InvertedIndex.indexableText(doc)))
                    .toList();

            for (int i = 0; i < batch.size(); i++) {
                index.addDocument(batch.get(i), tokensPerDoc.get(i));
            }
        }
    }
}
