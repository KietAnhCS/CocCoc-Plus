package com.vnsearch.storage;

import com.vnsearch.model.WebDocument;

import java.io.IOException;
import java.util.List;

/**
 * <b>Strategy pattern</b> — mot nguon corpus, de {@code SearchEngineFacade}
 * khong phai biet du lieu den tu dau.
 *
 * <p><b>Van de cua ban cu.</b> Bon nguon du lieu la bon nhanh {@code else if}
 * chon cung trong {@code init()}:
 * <pre>
 *   if (postgresEnabled &amp;&amp; loadFromPostgres()) { ... }
 *   else if (Files.exists(indexDataPath))   { ... }
 *   else if (Files.exists(crawledDataPath)) { ... }
 *   else if (Files.exists(seedDataPath))    { ... }
 * </pre>
 * Hau qua: them nguon thu nam (S3, MongoDB, Redis) la them mot nhanh nua;
 * test khong thay bang nguon gia lap duoc; va {@code DocumentRepository} chi la
 * lop cu the, khong cai dat gi.
 *
 * <p><b>Ban nay.</b> Chuoi du phong tro thanh <b>du lieu</b> (mot
 * {@code List<DocumentStore>}) thay vi <b>cau truc dieu khien</b> (chuoi
 * {@code else if}). Them nguon = them mot lop, khong sua {@code init()}.
 */
public interface DocumentStore extends AutoCloseable {

    /** Nguon nay co san du lieu khong (co file? ket noi duoc CSDL? co ban ghi?). */
    boolean isAvailable();

    /** Nap toan bo corpus. Chi goi khi {@link #isAvailable()} tra ve true. */
    List<WebDocument> loadAll() throws IOException;

    /** Mo ta nguon, dung cho log — thay cho cac dong {@code System.out.println} rai rac. */
    String describe();

    @Override
    default void close() {
    }
}
