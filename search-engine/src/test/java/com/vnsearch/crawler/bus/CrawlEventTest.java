package com.vnsearch.crawler.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cac bat bien cua bon thong diep tren bus.
 *
 * <p>Vi sao dang test cho mot record: cac phep kiem tra trong constructor
 * chinh la thu chan mot thong diep hong LOT LEN bus. Mot PageEvent thieu host
 * khong dinh tuyen duoc, va neu khong chan tai cho tao thi loi chi lo ra o
 * phia consumer — xa cho sinh loi, thuong la luc dang chay that.
 */
class CrawlEventTest {

    private static PageEvent page(String url, String host) {
        return new PageEvent(url, host, 0, "Tieu de", "Than bai", "vi",
                "<html><body>xin chao</body></html>", "hash", Instant.EPOCH, "job-1");
    }

    @Nested
    class PageEventRules {

        @Test
        void rejectsBlankUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> page("", "vnexpress.net"));
            assertThrows(IllegalArgumentException.class,
                    () -> page(null, "vnexpress.net"));
        }

        /** Host la KHOA PHAN HOACH — thieu no thi Kafka khong dinh tuyen duoc. */
        @Test
        void rejectsBlankHost() {
            assertThrows(IllegalArgumentException.class,
                    () -> page("https://a.com/x", ""));
            assertThrows(IllegalArgumentException.class,
                    () -> page("https://a.com/x", null));
        }

        @Test
        void rejectsNegativeDepth() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PageEvent("https://a.com", "a.com", -1, "t", "b", "vi",
                            "<html></html>", "h", Instant.EPOCH, "job-1"));
        }

        @Test
        void htmlSizeIsCountedInUtf8Bytes() {
            PageEvent event = new PageEvent("https://a.com", "a.com", 0, "t", "b", "vi",
                    "Đường", "h", Instant.EPOCH, "job-1");
            // 6 ky tu nhung 9 byte UTF-8: Đ = 2 byte, ơ = 3 byte, còn lại 1 byte.
            assertEquals("Đường".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    event.htmlSizeBytes());
        }

        @Test
        void htmlSizeIsZeroWhenHtmlAbsent() {
            assertEquals(0, page("https://a.com", "a.com").withoutHtml().htmlSizeBytes());
        }

        /**
         * withoutHtml phai giu MOI truong khac, dac biet la jobId — mat no thi
         * su kien khong tim duoc duong ve phien crawl cua minh.
         */
        @Test
        void withoutHtmlKeepsEveryOtherField() {
            PageEvent full = page("https://a.com/x", "a.com");
            PageEvent slim = full.withoutHtml();

            assertNull(slim.html());
            assertEquals(full.url(), slim.url());
            assertEquals(full.host(), slim.host());
            assertEquals(full.depth(), slim.depth());
            assertEquals(full.title(), slim.title());
            assertEquals(full.bodyText(), slim.bodyText());
            assertEquals(full.language(), slim.language());
            assertEquals(full.contentHash(), slim.contentHash());
            assertEquals(full.crawledAt(), slim.crawledAt());
            assertEquals(full.jobId(), slim.jobId());
        }

        /**
         * toString KHONG duoc chua HTML. Mot dong log vo tinh in ca su kien se
         * do 80 KB vao tep log cho MOI trang — du de lam day o dia trong mot
         * phien crawl.
         */
        @Test
        void toStringNeverLeaksHtml() {
            PageEvent event = page("https://a.com/x", "a.com");
            assertFalse(event.toString().contains("xin chao"));
            assertTrue(event.toString().contains("https://a.com/x"));
            assertTrue(event.toString().contains("htmlBytes="));
        }
    }

    @Nested
    class DiscoveredUrlRules {

        @Test
        void rejectsBlankUrlOrHost() {
            assertThrows(IllegalArgumentException.class,
                    () -> new DiscoveredUrl("", "a.com", 1, "https://a.com", "job"));
            assertThrows(IllegalArgumentException.class,
                    () -> new DiscoveredUrl("https://a.com/x", " ", 1, "https://a.com", "job"));
        }

        @Test
        void rejectsNegativeDepth() {
            assertThrows(IllegalArgumentException.class,
                    () -> new DiscoveredUrl("https://a.com/x", "a.com", -1, "https://a.com", "j"));
        }
    }

    @Nested
    class OutlinksRules {

        /**
         * Sao chep phong thu: nguoi goi con giu tham chieu toi danh sach goc va
         * co the sua no SAU khi thong diep da tao — pha tinh bat bien ma ca bus
         * dua vao.
         */
        @Test
        void copiesTheListDefensively() {
            List<String> mutable = new ArrayList<>(List.of("https://a.com/1"));
            OutlinksExtracted event =
                    new OutlinksExtracted("https://a.com", "a.com", mutable, "job");

            mutable.add("https://a.com/2");

            assertEquals(1, event.size(), "Sua danh sach goc khong duoc anh huong thong diep");
            assertThrows(UnsupportedOperationException.class,
                    () -> event.outlinks().add("https://a.com/3"));
        }

        @Test
        void nullListBecomesEmpty() {
            OutlinksExtracted event =
                    new OutlinksExtracted("https://a.com", "a.com", null, "job");
            assertEquals(0, event.size());
            assertNotNull(event.outlinks());
        }

        @Test
        void rejectsBlankSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> new OutlinksExtracted("", "a.com", List.of(), "job"));
        }
    }

    /**
     * Vòng tròn Jackson: ghi ra JSON rồi đọc lại phải cho đúng đối tượng cũ.
     *
     * <p><b>Vì sao nhóm test này tồn tại.</b> Một lỗi thật đã lọt qua toàn bộ
     * bộ test in-process và chỉ bị {@code KafkaCrawlBusIT} bắt:
     * {@code ImageFound.isDownloaded()} bị Jackson coi là một thuộc tính (mọi
     * phương thức {@code isXxx()} đều vậy), nên nó ghi thêm trường
     * {@code "downloaded"} vào JSON. Trường đó không ứng với component nào của
     * record, nên khi đọc lại:
     *
     * <pre>UnrecognizedPropertyException: Unrecognized field "downloaded"</pre>
     *
     * <p>Hậu quả ở môi trường thật: MỌI thông điệp ảnh chết ở consumer rồi
     * rơi vào dead-letter topic.
     *
     * <p>Bài học không phải "đã sửa xong". Bài học là <b>bộ test tích hợp phát
     * hiện muộn</b> — nó cần Docker và chạy ở một job riêng. Nhóm test này đưa
     * phép kiểm ấy về bộ test nhanh, nơi nó chạy trong vài mili-giây mỗi lần
     * {@code mvnw test}.
     */
    @Nested
    class JsonRoundTrip {

        private final ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        @Test
        void pageEventRoundTripsUnchanged() throws Exception {
            PageEvent goc = page("https://a.vn/bai", "a.vn");
            PageEvent lai = mapper.readValue(mapper.writeValueAsString(goc), PageEvent.class);
            assertEquals(goc, lai);
        }

        /** Instant cần JavaTimeModule — thiếu nó thì hỏng ngay thông điệp đầu tiên. */
        @Test
        void instantSurvivesTheRoundTrip() throws Exception {
            Instant luc = Instant.parse("2026-08-08T10:15:30Z");
            PageEvent goc = new PageEvent("https://a.vn/x", "a.vn", 0, "t", "b", "vi",
                    "<html></html>", "h", luc, "job");
            assertEquals(luc,
                    mapper.readValue(mapper.writeValueAsString(goc), PageEvent.class).crawledAt());
        }

        @Test
        void vietnameseDiacriticsSurviveTheRoundTrip() throws Exception {
            String noiDung = "Đội tuyển Việt Nam thắng 2-0";
            PageEvent goc = new PageEvent("https://a.vn/x", "a.vn", 0, noiDung, noiDung,
                    "vi", "<p>" + noiDung + "</p>", "h", Instant.EPOCH, "job");
            PageEvent lai = mapper.readValue(mapper.writeValueAsString(goc), PageEvent.class);
            assertEquals(noiDung, lai.title());
            assertEquals(noiDung, lai.bodyText());
        }

        /** ĐÂY là bài test bắt được lỗi thật. */
        @Test
        void imageFoundRoundTripsUnchanged() throws Exception {
            ImageFound goc = ImageFound.metadataOnly("https://a.vn/bai", "a.vn",
                    "https://a.vn/anh.jpg", "mô tả", 800, 600);
            ImageFound lai = mapper.readValue(mapper.writeValueAsString(goc), ImageFound.class);
            assertEquals(goc, lai);
            assertFalse(lai.isDownloaded());
        }

        @Test
        void downloadedImageRoundTripsUnchanged() throws Exception {
            ImageFound goc = new ImageFound("https://a.vn/bai", "a.vn",
                    "https://a.vn/anh.jpg", "alt", 100, 100, 2048L, "abc123");
            assertEquals(goc,
                    mapper.readValue(mapper.writeValueAsString(goc), ImageFound.class));
        }

        @Test
        void discoveredUrlRoundTripsUnchanged() throws Exception {
            DiscoveredUrl goc = new DiscoveredUrl("https://a.vn/x", "a.vn", 2,
                    "https://a.vn", "job-1");
            assertEquals(goc,
                    mapper.readValue(mapper.writeValueAsString(goc), DiscoveredUrl.class));
        }

        @Test
        void outlinksRoundTripUnchanged() throws Exception {
            OutlinksExtracted goc = new OutlinksExtracted("https://a.vn/bai", "a.vn",
                    List.of("https://a.vn/1", "https://a.vn/2"), "job-1");
            OutlinksExtracted lai =
                    mapper.readValue(mapper.writeValueAsString(goc), OutlinksExtracted.class);
            assertEquals(goc, lai);
            assertEquals(2, lai.size());
        }

        /**
         * Chặn cứng việc thêm một accessor dẫn xuất mà quên {@code @JsonIgnore}.
         *
         * <p>Bốn thông điệp chỉ được phép ghi ra ĐÚNG các component của record.
         * Bất kỳ trường nào khác đều là một giá trị dẫn xuất lọt vào thông điệp
         * — vừa thừa, vừa có thể lệch với nguồn của nó, và nếu bên nhận là một
         * phiên bản cũ hơn thì nó làm hỏng cả luồng.
         */
        @Test
        void noDerivedFieldLeaksIntoTheJson() throws Exception {
            assertEquals(
                    Set.of("url", "host", "depth", "title", "bodyText", "language",
                            "html", "contentHash", "crawledAt", "jobId"),
                    fieldNames(page("https://a.vn/x", "a.vn")));

            assertEquals(
                    Set.of("pageUrl", "host", "imageUrl", "altText", "declaredWidth",
                            "declaredHeight", "sizeBytes", "contentHash"),
                    fieldNames(ImageFound.metadataOnly("https://a.vn/b", "a.vn",
                            "https://a.vn/x.jpg", "alt", 1, 1)));

            assertEquals(
                    Set.of("url", "host", "depth", "sourceUrl", "jobId"),
                    fieldNames(new DiscoveredUrl("https://a.vn/x", "a.vn", 1, "s", "j")));

            assertEquals(
                    Set.of("sourceUrl", "host", "outlinks", "jobId"),
                    fieldNames(new OutlinksExtracted("https://a.vn/b", "a.vn", List.of(), "j")));
        }

        private Set<String> fieldNames(Object thongDiep) throws Exception {
            JsonNode node = mapper.readTree(mapper.writeValueAsString(thongDiep));
            Set<String> ten = new HashSet<>();
            node.fieldNames().forEachRemaining(ten::add);
            return ten;
        }
    }

    @Nested
    class ImageFoundRules {

        @Test
        void metadataOnlyIsNotMarkedAsDownloaded() {
            ImageFound image = ImageFound.metadataOnly(
                    "https://a.com/bai", "a.com", "https://a.com/anh.jpg", "mo ta", 800, 600);

            assertFalse(image.isDownloaded());
            assertEquals(-1L, image.sizeBytes());
            assertNull(image.contentHash());
            assertFalse(image.missingAlt());
        }

        @Test
        void nullAltBecomesEmptyAndCountsAsMissing() {
            ImageFound image = ImageFound.metadataOnly(
                    "https://a.com/bai", "a.com", "https://a.com/anh.jpg", null, -1, -1);

            assertEquals("", image.altText());
            assertTrue(image.missingAlt());
        }

        @Test
        void downloadedImageCarriesHash() {
            ImageFound image = new ImageFound("https://a.com/bai", "a.com",
                    "https://a.com/anh.jpg", "alt", 100, 100, 2048L, "abc123");
            assertTrue(image.isDownloaded());
            assertEquals(2048L, image.sizeBytes());
        }

        @Test
        void rejectsBlankUrls() {
            assertThrows(IllegalArgumentException.class,
                    () -> ImageFound.metadataOnly("", "a.com", "https://a.com/x.jpg", "", 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> ImageFound.metadataOnly("https://a.com", "a.com", "", "", 1, 1));
        }
    }
}
