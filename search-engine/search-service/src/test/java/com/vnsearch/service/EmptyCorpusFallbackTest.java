package com.vnsearch.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mot nguon du lieu RONG khong duoc phep chan cac tang du phong phia sau.
 *
 * <p><b>Loi that ma bo test nay khoa lai.</b> Mot phien crawl thu that bai de
 * lai {@code data/crawled-documents.json} chua dung {@code []} va mot
 * {@code data/index.json} 159 byte. Ca hai tep DEU TON TAI, va ca duong nhanh
 * "nap chi muc dung san" lan {@code JsonDocumentStore.isAvailable()} deu chi
 * hoi <i>tep co ton tai khong</i>. Nen ung dung nap tep rong, dung ngay tai do,
 * va khong bao gio doc toi corpus mau di kem repo.
 *
 * <p>Trieu chung khi do rat de doc nham thanh loi khac: moi truy van tra ve 0
 * ket qua, {@code /api/health} bao 503, va trong Docker thi
 * {@code restart: unless-stopped} dua container vao vong khoi dong lai vo han.
 * Khong co ngoai le nao duoc nem ra, khong co log ERROR nao.
 *
 * <p>Bo test nay tro toi tep corpus rong CO THAT trong {@code fixtures/}, va
 * doi he thong van phuc vu duoc — tuc da lui ve {@code seed-documents.json}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // Chi muc dung san: tro toi mot tep KHONG ton tai, de bai test nay chi
        // kiem tra dung mot dieu — chuoi du phong cua corpus.
        "app.index.data-path=target/test-data/empty-corpus-index.json",
        // Corpus "da crawl" RONG. Day la tang du phong DAU TIEN.
        "app.crawler.data-path=src/test/resources/fixtures/empty-corpus.json",
        // Tang du phong CUOI: corpus mau that di kem repo.
        "app.seed.data-path=src/test/resources/fixtures/test-crawled-documents.json"
})
class EmptyCorpusFallbackTest {

    @Autowired
    private SearchEngineFacade facade;

    @Test
    void emptySourceDoesNotShadowTheSeedCorpus() {
        // Neu tep rong van duoc coi la nguon hop le, con so nay se la 0.
        assertTrue(facade.getIndexedDocumentCount() > 0,
                "Corpus rong da chan mat tang du phong: chi muc khong co tai lieu nao");
    }
}
