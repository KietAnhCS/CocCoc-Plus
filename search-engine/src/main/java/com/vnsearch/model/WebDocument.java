package com.vnsearch.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mo hinh du lieu mot trang web da crawl. Day la POJO thuan (khong dung
 * Lombok) de moi truong deu hien ro trong bao cao.
 */
public class WebDocument {

    private int docId;
    private String url;
    private String title;
    private String metaDescription;
    private String bodyText;
    private List<String> outlinks = new ArrayList<>();
    private Instant crawledAt;

    /**
     * Ma ngon ngu cua trang: "vi", "en", hoac "und" khi trang qua ngan de ket
     * luan. Do {@link com.vnsearch.crawler.ContentParser} dien tam gia tri khai
     * bao trong {@code <html lang>}, roi
     * {@link com.vnsearch.crawler.LanguageFilter} ghi de bang ket qua nhan dien
     * theo NOI DUNG - thu dang tin hon nhieu (xem Javadoc cua lop do).
     *
     * <p>Giu lai trong corpus chu khong vut sau khi loc: khau danh chi muc can
     * biet de chon bo tach tu dung cho tieng Anh va tieng Viet, va giao dien co
     * the loc ket qua theo ngon ngu.
     */
    private String language = "";

    public WebDocument() {
    }

    public WebDocument(int docId, String url, String title, String metaDescription,
                        String bodyText, List<String> outlinks, Instant crawledAt) {
        this.docId = docId;
        this.url = url;
        this.title = title;
        this.metaDescription = metaDescription;
        this.bodyText = bodyText;
        this.outlinks = outlinks != null ? outlinks : new ArrayList<>();
        this.crawledAt = crawledAt;
    }

    public int getDocId() {
        return docId;
    }

    public void setDocId(int docId) {
        this.docId = docId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMetaDescription() {
        return metaDescription;
    }

    public void setMetaDescription(String metaDescription) {
        this.metaDescription = metaDescription;
    }

    public String getBodyText() {
        return bodyText;
    }

    public void setBodyText(String bodyText) {
        this.bodyText = bodyText;
    }

    public List<String> getOutlinks() {
        return outlinks;
    }

    public void setOutlinks(List<String> outlinks) {
        this.outlinks = outlinks;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language == null ? "" : language;
    }

    public Instant getCrawledAt() {
        return crawledAt;
    }

    public void setCrawledAt(Instant crawledAt) {
        this.crawledAt = crawledAt;
    }

    /**
     * Ban sao KHONG kem {@code bodyText}.
     *
     * <p>Dung khi dua tai lieu vao chi muc: chi muc can moi truong khac
     * ({@code url}, {@code title}, {@code outlinks} cho PageRank) nhung khong
     * can giu van ban day du — phan do duoc luu rieng o dang <b>da nen</b>
     * (xem {@link com.vnsearch.index.CompressedText}).
     *
     * <p><b>Vi sao la BAN SAO chu khong phai gan {@code bodyText = null} tai
     * cho.</b> Danh sach tai lieu di vao chi muc thuoc ve NGUOI GOI, va nguoi
     * goi con dung no sau do — {@code MultiDomainCrawlRunner} ghi corpus ra tep,
     * {@code EvaluationRunner} sinh truy van tu than bai. Sua doi tuong cua ho
     * la mot tac dung phu tu xa: no se lam mat du lieu o mot noi hoan toan khac,
     * va khong ai doc ma o day doan duoc dieu do.
     */
    public WebDocument withoutBodyText() {
        WebDocument copy = new WebDocument(docId, url, title, metaDescription, null,
                outlinks, crawledAt);
        copy.setLanguage(language);
        return copy;
    }

    @Override
    public String toString() {
        return "WebDocument{docId=" + docId + ", url='" + url + "', title='" + title
                + "', outlinks=" + outlinks.size() + "}";
    }
}
