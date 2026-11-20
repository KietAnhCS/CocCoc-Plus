package com.vnsearch.index;

import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexPersistenceTest {

    @Test
    void saveAndLoadRoundTripPreservesIndexState(@TempDir Path tempDir) throws IOException {
        InvertedIndex index = new InvertedIndex();
        WebDocument doc = new WebDocument();
        doc.setDocId(0);
        doc.setUrl("https://vnexpress.net/bai-viet");
        doc.setTitle("Công nghệ mới");
        doc.setBodyText("Công nghệ trí tuệ nhân tạo đang phát triển");
        doc.setOutlinks(List.of("https://vnexpress.net/khac"));
        doc.setCrawledAt(Instant.parse("2026-01-01T00:00:00Z"));
        index.addDocument(doc);

        String path = tempDir.resolve("index.json").toString();
        IndexPersistence.save(index, path);

        InvertedIndex loaded = IndexPersistence.load(path);

        assertEquals(1, loaded.getTotalDocs());
        assertEquals(index.getDocumentFrequency("công_nghệ"), loaded.getDocumentFrequency("công_nghệ"));
        assertEquals("https://vnexpress.net/bai-viet", loaded.getDocument(0).getUrl());
        assertEquals(index.getPostings("công_nghệ").size(), loaded.getPostings("công_nghệ").size());
    }

    /**
     * Bất biến sống còn: chỉ mục và truy vấn phải dùng cùng một bộ tách từ.
     *
     * <p>Nếu không chặn, lỗi này <b>hoàn toàn im lặng</b> — file vẫn đúng định dạng,
     * vẫn nạp trót lọt, chỉ có điều term hai bên không khớp nhau nữa và mọi truy vấn
     * trả về rỗng. Test dùng một tokenizer giả có {@code name()} khác để mô phỏng
     * đúng tình huống "từ điển đã đổi kể từ lần lưu chỉ mục".
     */
    @Test
    void loadRejectsIndexBuiltByADifferentTokenizer(@TempDir Path tempDir) throws IOException {
        InvertedIndex index = new InvertedIndex();
        WebDocument doc = new WebDocument();
        doc.setDocId(0);
        doc.setUrl("https://vnexpress.net/bai-viet");
        doc.setTitle("Công nghệ mới");
        doc.setBodyText("Công nghệ trí tuệ nhân tạo đang phát triển");
        index.addDocument(doc);

        String path = tempDir.resolve("index.json").toString();
        IndexPersistence.save(index, path);

        // Nạp lại bằng CHÍNH tokenizer đã dựng: phải chạy trót lọt.
        assertEquals(1, IndexPersistence.load(path).getTotalDocs());

        // Nạp lại bằng tokenizer KHÁC: phải bị chặn, kèm thông báo nói rõ việc gì.
        IOException e = assertThrows(IOException.class,
                () -> IndexPersistence.load(path, new RenamedTokenizer()));
        assertTrue(e.getMessage().contains("KHÁC"), "Nhan duoc: " + e.getMessage());
        assertTrue(e.getMessage().contains("tu dien khac"), "Thong bao phai in ra ca hai dau"
                + " van tay de nguoi doc biet cai gi da doi. Nhan duoc: " + e.getMessage());
    }

    /**
     * Tokenizer giả: tách từ y hệt bản thật nhưng khai một {@code name()} khác —
     * đúng cái xảy ra khi từ điển được mở rộng.
     */
    private static final class RenamedTokenizer implements Tokenizer {
        private final VietnameseTokenizer delegate = new VietnameseTokenizer();

        @Override
        public List<VietnameseTokenizer.Token> tokenize(String text) {
            return delegate.tokenize(text);
        }

        @Override
        public String name() {
            return "VietnameseTokenizer(MaxWeightDP, tu dien khac)";
        }
    }
}
