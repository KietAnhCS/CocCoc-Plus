package com.vnsearch.index;

import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
