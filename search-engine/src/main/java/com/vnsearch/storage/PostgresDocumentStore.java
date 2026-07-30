package com.vnsearch.storage;

import com.vnsearch.model.WebDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * {@link DocumentStore} doc corpus tu PostgreSQL, boc quanh
 * {@link DocumentRepository} co san.
 *
 * <p>CSDL chi dong vai tro KHO LUU TRU: viec tim kiem van do chi muc dao tu
 * cai dam nhiem. (Chi muc GIN cua PostgreSQL duoc dung rieng lam <i>doi chung
 * ben ngoai</i> trong {@code docs/GIN-BASELINE.md}, khong phai duong phuc vu
 * nguoi dung.)
 *
 * <p>{@link #isAvailable()} tra ve {@code false} thay vi nem ngoai le khi
 * khong ket noi duoc, de chuoi du phong tu dong lui ve nguon JSON — ung dung
 * van khoi dong binh thuong khi khong co CSDL, dieu quan trong cho test va cho
 * demo nhanh.
 */
public final class PostgresDocumentStore implements DocumentStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresDocumentStore.class);

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public PostgresDocumentStore(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    @Override
    public boolean isAvailable() {
        try (DocumentRepository repo = new DocumentRepository(jdbcUrl, user, password)) {
            return repo.countDocuments() > 0;
        } catch (Exception e) {
            log.info("PostgreSQL khong san sang ({}), se dung nguon du phong", e.getMessage());
            return false;
        }
    }

    @Override
    public List<WebDocument> loadAll() throws IOException {
        try (DocumentRepository repo = new DocumentRepository(jdbcUrl, user, password)) {
            return repo.findAll();
        } catch (Exception e) {
            // Boc SQLException thanh IOException de nguoi goi chi phai xu ly
            // MOT loai loai loi cho moi nguon du lieu (Strategy dong nhat).
            throw new IOException("Khong nap duoc corpus tu PostgreSQL: " + e.getMessage(), e);
        }
    }

    @Override
    public String describe() {
        return "PostgreSQL @ " + jdbcUrl;
    }
}
