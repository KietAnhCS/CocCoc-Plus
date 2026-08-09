package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.bus.ImageFound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ghi va doc kho anh xuong dia. */
class ImageStorageTest {

    private static ImageFound image(String pageUrl, String imageUrl, String alt) {
        return ImageFound.metadataOnly(pageUrl, "a.vn", imageUrl, alt, 800, 600);
    }

    @Test
    void derivesImagePathFromCorpusPath() {
        assertEquals("data/crawled-documents.images.json",
                ImageStorage.pathFor("data/crawled-documents.json"));
        // Duong dan khong co duoi .json van phai ra mot ten dung duoc, khong
        // duoc cat mat ky tu nao cua ten goc.
        assertEquals("data/corpus.images.json", ImageStorage.pathFor("data/corpus"));
    }

    /**
     * Vong ghi -&gt; doc phai khep kin.
     *
     * <p>Day la bai test dat gia nhat cua lop nay. {@link ImageFound} co hai
     * phuong thuc {@code isDownloaded()} va {@code missingAlt()} mang
     * {@code @JsonIgnore}; neu mot trong hai mat annotation do, Jackson ghi
     * them mot truong khong ung voi component nao cua record, va luc DOC LAI se
     * nem {@code UnrecognizedPropertyException}.
     *
     * <p>Hong theo kieu do khong lo ra o phia ghi — tep van duoc tao, van dung
     * JSON. No chi no o lan khoi dong backend tiep theo.
     */
    @Test
    void writesAndReadsBackEveryField(@TempDir Path dir) throws IOException {
        ImageFound original = new ImageFound(
                "https://a.vn/bai", "a.vn", "https://a.vn/1.jpg", "mo ta", 800, 600, 1234L, "abc123");
        String path = dir.resolve("corpus.images.json").toString();

        ImageStorage.saveToJson(List.of(original), path);
        List<ImageFound> back = ImageStorage.loadFromJson(path);

        assertEquals(1, back.size());
        assertEquals(original, back.get(0));
        assertTrue(back.get(0).isDownloaded());
        assertFalse(back.get(0).missingAlt());
    }

    @Test
    void keepsMetadataOnlyRecordsIntact(@TempDir Path dir) throws IOException {
        ImageFound original = image("https://a.vn/bai", "https://a.vn/1.jpg", "");
        String path = dir.resolve("corpus.images.json").toString();

        ImageStorage.saveToJson(List.of(original), path);
        ImageFound back = ImageStorage.loadFromJson(path).get(0);

        assertEquals(original, back);
        // Hai gia tri suy ra phai giu nguyen y nghia sau mot vong ghi/doc: anh
        // chua tai noi dung, va alt rong nghia la anh trang tri.
        assertFalse(back.isDownloaded());
        assertTrue(back.missingAlt());
    }

    /**
     * Danh sach RONG van phai tao ra tep.
     *
     * <p>Tep chua {@code []} noi "da crawl, khong tim duoc anh nao"; khong co
     * tep noi "chua crawl lan nao". Hai ca do can hai loi khuyen khac nhau o
     * {@code crawl-stats}, nen bo qua viec ghi khi rong se xoa mat su phan biet.
     */
    @Test
    void writesFileEvenWhenThereAreNoImages(@TempDir Path dir) throws IOException {
        String path = dir.resolve("corpus.images.json").toString();

        ImageStorage.saveToJson(List.of(), path);

        assertTrue(Files.exists(Path.of(path)));
        assertEquals(List.of(), ImageStorage.loadFromJson(path));
    }

    @Test
    void createsMissingParentDirectories(@TempDir Path dir) throws IOException {
        String path = dir.resolve("chua-co").resolve("corpus.images.json").toString();

        ImageStorage.saveToJson(List.of(image("https://a.vn/b", "https://a.vn/1.jpg", "x")), path);

        assertEquals(1, ImageStorage.loadFromJson(path).size());
    }

    /**
     * Khong de lai tep {@code .tmp} sau mot lan ghi thanh cong.
     *
     * <p>Ghi nguyen tu la ghi ra {@code .tmp} roi doi ten. Neu buoc doi ten
     * khong xay ra, tep dich van dung nhung rac tich luy mot ban sao day du cho
     * MOI lan ghi diem kiem tra — hang chuc lan moi phien crawl.
     */
    @Test
    void leavesNoTempFileBehind(@TempDir Path dir) throws IOException {
        String path = dir.resolve("corpus.images.json").toString();

        ImageStorage.saveToJson(List.of(image("https://a.vn/b", "https://a.vn/1.jpg", "x")), path);

        assertFalse(Files.exists(Path.of(path + ".tmp")));
    }

    /**
     * Ghi de phai THAY THE hoan toan, khong noi them.
     *
     * <p>Diem kiem tra ghi de len cung mot tep hang chuc lan trong mot phien.
     * Neu lan ghi sau chi noi vao duoi, tep se thanh hai mang JSON noi nhau —
     * khong doc lai duoc, va chi phat hien ra o lan khoi dong sau.
     */
    @Test
    void overwritesInsteadOfAppending(@TempDir Path dir) throws IOException {
        String path = dir.resolve("corpus.images.json").toString();

        ImageStorage.saveToJson(List.of(
                image("https://a.vn/b", "https://a.vn/1.jpg", "x"),
                image("https://a.vn/b", "https://a.vn/2.jpg", "y")), path);
        ImageStorage.saveToJson(List.of(image("https://a.vn/b", "https://a.vn/3.jpg", "z")), path);

        List<ImageFound> back = ImageStorage.loadFromJson(path);
        assertEquals(1, back.size());
        assertEquals("https://a.vn/3.jpg", back.get(0).imageUrl());
    }

    /**
     * Moi truong nam tren MOT DONG rieng.
     *
     * <p>Rang buoc nay den tu ben ngoai Java: {@code crawl-stats.ps1} doc tep
     * bang {@code StreamReader} theo tung dong, doi chieu tien to
     * {@code "imageUrl"} de dem. Tat {@code INDENT_OUTPUT} thi ca mang don lai
     * mot dong, va thong ke anh im lang tra ve 0 — khong co loi bien dich nao
     * bat duoc dieu do, nen no duoc chot o day.
     */
    @Test
    void writesOneFieldPerLineForTheStatsScript(@TempDir Path dir) throws IOException {
        String path = dir.resolve("corpus.images.json").toString();

        ImageStorage.saveToJson(List.of(image("https://a.vn/b", "https://a.vn/1.jpg", "mo ta")), path);

        List<String> trimmed = Files.readAllLines(Path.of(path)).stream()
                .map(String::trim)
                .filter(line -> line.startsWith("\""))
                .toList();
        assertLinesMatch(List.of(
                "\"pageUrl\" : .*",
                "\"host\" : .*",
                "\"imageUrl\" : .*",
                "\"altText\" : .*",
                "\"declaredWidth\" : .*",
                "\"declaredHeight\" : .*",
                "\"sizeBytes\" : .*",
                "\"contentHash\" : .*"), trimmed);
    }

    /**
     * Tep thieu hoac hong thi tra ve danh sach rong, khong nem ngoai le.
     *
     * <p>{@code ImageStorePreloader} goi ham nay tren duong KHOI DONG backend.
     * Nem o day nghia la mot tep anh hong lam ca ung dung khong len duoc — ke ca
     * phan tim kiem van ban von chang lien quan gi toi anh.
     */
    @Test
    void loadQuietlyNeverThrows(@TempDir Path dir) throws IOException {
        assertEquals(List.of(), ImageStorage.loadQuietly(dir.resolve("khong-co.json").toString()));
        assertEquals(List.of(), ImageStorage.loadQuietly(null));

        Path broken = dir.resolve("hong.images.json");
        Files.writeString(broken, "{ day khong phai JSON hop le");
        assertEquals(List.of(), ImageStorage.loadQuietly(broken.toString()));
    }
}
