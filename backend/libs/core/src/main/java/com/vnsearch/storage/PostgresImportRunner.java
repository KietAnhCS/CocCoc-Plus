package com.vnsearch.storage;

import com.vnsearch.crawler.ContentStorage;
import com.vnsearch.model.WebDocument;

import java.util.List;

/**
 * Nạp corpus đã crawl từ file JSON vào PostgreSQL.
 *
 * <pre>
 *   docker compose up -d
 *   mvnw compile exec:java -Dexec.mainClass=com.vnsearch.storage.PostgresImportRunner \
 *        -Dexec.args="data/crawled-multi.json"
 * </pre>
 */
public class PostgresImportRunner {

    public static void main(String[] args) throws Exception {
        String corpusPath = args.length > 0 ? args[0] : "data/crawled-multi.json";

        System.out.println("Doc corpus tu " + corpusPath + " ...");
        List<WebDocument> docs = ContentStorage.loadFromJson(corpusPath);
        long outlinkCount = docs.stream().mapToInt(d -> d.getOutlinks().size()).sum();
        System.out.printf("  %d tai lieu, %d lien ket%n", docs.size(), outlinkCount);

        try (DocumentRepository repo = DocumentRepository.connectDefault()) {
            System.out.println("Xoa du lieu cu ...");
            repo.deleteAll();

            System.out.println("Ghi vao PostgreSQL (theo lo, mot giao dich) ...");
            long start = System.currentTimeMillis();
            repo.saveAll(docs);
            long elapsedMs = System.currentTimeMillis() - start;

            int savedDocs = repo.countDocuments();
            long savedLinks = repo.countOutlinks();
            long tableBytes = repo.totalRelationSizeBytes("documents");
            long ginBytes = repo.indexSizeBytes("idx_documents_tsv");

            System.out.println();
            System.out.println("=== KET QUA NAP ===");
            System.out.printf("Tai lieu da ghi     : %d%n", savedDocs);
            System.out.printf("Lien ket da ghi     : %d%n", savedLinks);
            System.out.printf("Thoi gian           : %.1f giay (%.0f tai lieu/giay)%n",
                    elapsedMs / 1000.0, docs.size() / (elapsedMs / 1000.0));
            System.out.printf("Bang documents      : %.1f MB (ke ca moi chi muc)%n", tableBytes / 1048576.0);
            System.out.printf("Rieng chi muc GIN   : %.1f MB%n", ginBytes / 1048576.0);

            if (savedDocs != docs.size()) {
                System.out.printf("CANH BAO: ghi thieu %d tai lieu%n", docs.size() - savedDocs);
            }

            // Kiem chung doc lai duoc nguyen ven - quan trong vi chinh du lieu
            // nay se duoc dung de dung lai chi muc dao trong bo nho.
            System.out.println();
            System.out.println("Doc lai de kiem chung ...");
            long readStart = System.currentTimeMillis();
            List<WebDocument> reloaded = repo.findAll();
            long readMs = System.currentTimeMillis() - readStart;
            long reloadedLinks = reloaded.stream().mapToInt(d -> d.getOutlinks().size()).sum();
            System.out.printf("  doc lai %d tai lieu, %d lien ket trong %.1f giay%n",
                    reloaded.size(), reloadedLinks, readMs / 1000.0);
            System.out.println(reloaded.size() == docs.size() && reloadedLinks == outlinkCount
                    ? "  OK: du lieu doc lai khop hoan toan voi du lieu ghi vao"
                    : "  SAI LECH: du lieu doc lai KHONG khop");
        }
    }
}
