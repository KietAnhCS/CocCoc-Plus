package com.vnsearch.storage;

import com.vnsearch.crawler.CrawlerService;
import com.vnsearch.model.WebDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link DocumentStore} doc corpus tu mot file JSON da crawl.
 *
 * <p>Dung cho ca hai muc dich trong chuoi du phong:
 * <ul>
 *   <li>{@code data/crawled-multi.json} — corpus that 5.011 trang;</li>
 *   <li>{@code data/seed-documents.json} — mau ~40 tai lieu di kem repo, de
 *       nguoi vua clone ve chay duoc NGAY ma khong can crawl mang that hay
 *       cai PostgreSQL.</li>
 * </ul>
 * Tang du phong cuoi cung nay la chi tiet dang khen ve trai nghiem: nhieu do
 * an bo qua no va nguoi cham khong chay noi.
 */
public final class JsonDocumentStore implements DocumentStore {

    private final String path;
    private final String label;

    public JsonDocumentStore(String path) {
        this(path, "JSON");
    }

    public JsonDocumentStore(String path, String label) {
        this.path = path;
        this.label = label;
    }

    @Override
    public boolean isAvailable() {
        return path != null && !path.isBlank() && Files.exists(Path.of(path));
    }

    @Override
    public List<WebDocument> loadAll() throws IOException {
        return CrawlerService.loadFromJson(path);
    }

    @Override
    public String describe() {
        return label + " @ " + path;
    }
}
