package com.vnsearch.config;

import com.vnsearch.index.Tokenizer;
import com.vnsearch.index.VietnameseTokenizer;
import com.vnsearch.crawler.modular.ImageStore;
import com.vnsearch.ranking.PageRankService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Khai bao cac bean dung chung cua tang tim kiem.
 *
 * <p><b>Vi sao {@link Tokenizer} phai la MOT bean dung chung.</b> Day la bat
 * bien quan trong nhat noi tang chi muc voi tang truy van: neu luc index tao ra
 * {@code máy_tính} ma luc truy van tao ra {@code máy} + {@code tính} thi
 * <b>khong bao gio khop</b> — va loi nay IM LANG, khong nem ngoai le nao, chi
 * la ket qua rong mot cach kho hieu.
 *
 * <p>Truoc day moi lop tu goi {@code new VietnameseTokenizer()} trong
 * constructor khong tham so. O quy mo hien tai chung deu nap cung mot file
 * resource nen tinh co van dung, nhung do la mot canh cua mo: chi can mot ngay
 * tokenizer nhan tham so cau hinh la hai instance khac nhau va bat bien vo ma
 * khong ai nhan ra. Khai bao mot bean duy nhat dong canh cua do lai.
 */
@Configuration
public class SearchConfig {

    /**
     * Tokenizer dung chung cho CA tang chi muc lan tang truy van.
     *
     * <p>Nap tu dien tu ghep va danh sach tu dung tu classpath mot lan duy nhat
     * (truoc day moi {@code new VietnameseTokenizer()} deu doc lai hai file).
     */
    @Bean
    public Tokenizer tokenizer() {
        return new VietnameseTokenizer();
    }

    @Bean
    public PageRankService pageRankService() {
        return new PageRankService();
    }

    /**
     * Kho anh dung chung — nguon du lieu cho {@code GET /api/images}.
     *
     * <p>Khai o day, KHONG danh @Component tren chinh lop do: cac lop trong
     * package {@code crawler} deu la POJO thuan, khong biet gi ve Spring. Nho
     * vay chung chay duoc tu cong cu dong lenh
     * ({@code MultiDomainCrawlRunner}) va test duoc bang JUnit thuan. Viec noi
     * day thuoc ve tang cau hinh — cung nguyen tac ma
     * {@code CrawlEventBus} da theo.
     *
     * <p>MOT ban duy nhat cho ca ung dung, va do la dieu bat buoc: o che do
     * in-process thi {@code CrawlJobManager} do anh vao day, con o che do Kafka
     * thi {@code CrawlKafkaListeners} do vao. Hai duong ghi, mot kho doc.
     */
    @Bean
    public ImageStore imageStore() {
        return new ImageStore();
    }
}
