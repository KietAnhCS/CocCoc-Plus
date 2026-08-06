package com.vnsearch.service;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.Tokenizer;
import com.vnsearch.model.WebDocument;
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
 */
@Component
public class IndexBuilder {

    private final Tokenizer tokenizer;

    public IndexBuilder(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * Dung mot chi muc MOI tinh tu danh sach tai lieu.
     *
     * <p>Luon tao chi muc moi thay vi cap nhat chi muc cu: {@code addDocument}
     * khong idempotent (goi hai lan cung docId se tao posting trung), va viec
     * dung lai chi ton 6,8-9,5 giay nen khong dang danh doi tinh dung dan.
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
        InvertedIndex index = new InvertedIndex(tokenizer);
        List<WebDocument> sorted = new ArrayList<>(documents);
        sorted.sort(Comparator.comparingInt(WebDocument::getDocId)); // TIEN DE bat buoc
        int nextDocId = 0;
        for (WebDocument doc : sorted) {
            doc.setDocId(nextDocId++); // CAP LAI danh tinh — xem Javadoc ham
            index.addDocument(doc);
        }
        return index;
    }
}
