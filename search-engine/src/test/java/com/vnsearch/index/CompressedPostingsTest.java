package com.vnsearch.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm thử dạng nén của posting list.
 *
 * <p>Trọng tâm là <b>vòng lặp mã hoá → giải mã phải khôi phục nguyên vẹn</b>,
 * kể cả trường {@code termFrequency} vốn cố ý không được lưu.
 */
class CompressedPostingsTest {

    private static Posting posting(int docId, int... positions) {
        List<Integer> list = new ArrayList<>(positions.length);
        for (int position : positions) {
            list.add(position);
        }
        return new Posting(docId, positions.length, list);
    }

    @Test
    @DisplayName("Ví dụ tính tay trong tài liệu: 3 posting nén còn đúng 13 byte")
    void handWorkedExample() {
        List<Posting> postings = List.of(
                posting(3, 5, 9),
                posting(17, 0),
                posting(19, 2, 7, 40));

        CompressedPostings compressed = CompressedPostings.of(postings);

        // Mọi delta trong ví dụ này đều <= 127 nên mỗi số đúng 1 byte.
        assertEquals(3, compressed.docIds().length, "docIds: 3 số");
        assertEquals(4, compressed.offsets().length, "offsets: count+1 = 4 số");
        assertEquals(6, compressed.positions().length, "positions: 2+1+3 = 6 số");
        assertEquals(13, compressed.totalBytes());

        // Dạng int thuần cần 12 số x 4 byte = 48 byte.
        assertTrue(compressed.totalBytes() < 48);
    }

    @Test
    @DisplayName("Giải nén khôi phục nguyên vẹn cả termFrequency vốn không được lưu")
    void roundTripRestoresTermFrequency() {
        List<Posting> original = List.of(
                posting(3, 5, 9),
                posting(17, 0),
                posting(19, 2, 7, 40));

        assertEquals(original, CompressedPostings.of(original).toPostings());
    }

    @Test
    @DisplayName("Posting list rỗng nén và giải nén không ném ngoại lệ")
    void emptyList() {
        CompressedPostings compressed = CompressedPostings.of(List.of());
        assertEquals(0, compressed.count());
        assertEquals(0, compressed.totalBytes());
        assertTrue(compressed.toPostings().isEmpty());
    }

    @Test
    @DisplayName("Posting không có vị trí nào vẫn khôi phục đúng")
    void postingWithoutPositions() {
        List<Posting> original = List.of(posting(0), posting(5), posting(9, 1));
        assertEquals(original, CompressedPostings.of(original).toPostings());
    }

    @Test
    @DisplayName("Vị trí giảm ở ranh giới posting vẫn đúng — đây là lý do phải nén theo đoạn")
    void positionsResetAtPostingBoundary() {
        // Nối liền thành [100, 1] rồi delta hoá sẽ cho -99: VByte không mã hoá được.
        List<Posting> original = List.of(posting(0, 100), posting(1, 1));
        assertEquals(original, CompressedPostings.of(original).toPostings());
    }

    @Test
    @DisplayName("Bất biến termFrequency == |positions| bị vi phạm thì ném ngoại lệ NGAY")
    void enforcesTermFrequencyInvariant() {
        List<Posting> broken = List.of(new Posting(7, 99, List.of(1, 2)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CompressedPostings.of(broken));
        assertTrue(error.getMessage().contains("7"), "thông báo phải chỉ ra docId sai");
    }

    @Test
    @DisplayName("Vòng lặp mã hoá → giải mã đúng trên 200 posting list ngẫu nhiên")
    void randomisedRoundTrip() {
        Random random = new Random(20260731L); // seed cố định: hỏng thì tái lập được

        for (int trial = 0; trial < 200; trial++) {
            int count = random.nextInt(40);
            List<Posting> postings = new ArrayList<>(count);
            int docId = random.nextInt(5);
            for (int i = 0; i < count; i++) {
                int frequency = random.nextInt(6);
                int[] positions = new int[frequency];
                int position = random.nextInt(3);
                for (int j = 0; j < frequency; j++) {
                    positions[j] = position;
                    position += 1 + random.nextInt(500); // tăng dần nghiêm ngặt
                }
                postings.add(posting(docId, positions));
                docId += 1 + random.nextInt(1000); // docId cũng tăng dần
            }

            assertEquals(postings, CompressedPostings.of(postings).toPostings(),
                    "sai ở lần thử " + trial);
        }
    }

    @Test
    @DisplayName("Delta lớn (> 127) buộc VByte dùng nhiều byte nhưng vẫn đúng")
    void largeGapsUseMultipleBytes() {
        List<Posting> original = List.of(
                posting(0, 0),
                posting(1_000_000, 999_999),
                posting(2_000_000_000, 3));

        CompressedPostings compressed = CompressedPostings.of(original);
        assertEquals(original, compressed.toPostings());
        assertTrue(compressed.docIds().length > 3, "docId cách xa nhau phải tốn hơn 1 byte mỗi số");
    }
}
