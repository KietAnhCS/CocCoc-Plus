package com.vnsearch.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Luu/nap {@link InvertedIndex} ra file JSON (Jackson) trong thu muc
 * data/, de khong phai crawl + index lai moi lan khoi dong ung dung.
 *
 * <p>Luu toan bo {@link InvertedIndex.IndexData} (posting list cua tung
 * term, danh sach WebDocument, do dai tai lieu) trong MOT file JSON duy
 * nhat — don gian hoa viec nap lai dung thu tu, chap nhan danh doi la file
 * co the lon (chua ca noi dung WebDocument) de doi lay tinh don gian.
 */
public class IndexPersistence {

    private static ObjectMapper createMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public static void save(InvertedIndex index, String path) throws IOException {
        Path filePath = Path.of(path);
        if (filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }
        createMapper().writeValue(new File(path), index.exportData());
    }

    public static InvertedIndex load(String path, VietnameseTokenizer tokenizer) throws IOException {
        InvertedIndex.IndexData data = createMapper().readValue(new File(path), InvertedIndex.IndexData.class);
        return InvertedIndex.importData(data, tokenizer);
    }

    public static InvertedIndex load(String path) throws IOException {
        return load(path, new VietnameseTokenizer());
    }
}
