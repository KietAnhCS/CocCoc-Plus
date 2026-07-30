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
     */
    public InvertedIndex build(List<WebDocument> documents) {
        InvertedIndex index = new InvertedIndex(tokenizer);
        List<WebDocument> sorted = new ArrayList<>(documents);
        sorted.sort(Comparator.comparingInt(WebDocument::getDocId)); // TIEN DE bat buoc
        for (WebDocument doc : sorted) {
            index.addDocument(doc);
        }
        return index;
    }
}
