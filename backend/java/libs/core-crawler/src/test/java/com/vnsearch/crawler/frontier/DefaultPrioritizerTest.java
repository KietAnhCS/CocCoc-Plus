package com.vnsearch.crawler.frontier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test chính sách xếp mức ưu tiên mặc định. */
class DefaultPrioritizerTest {

    private final DefaultPrioritizer prioritizer = new DefaultPrioritizer();

    @Test
    void rejectsNonPositiveLevels() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultPrioritizer(0));
        assertThrows(IllegalArgumentException.class, () -> new DefaultPrioritizer(-1));
    }

    @Test
    void depthIsTheStartingPoint() {
        assertEquals(0, prioritizer.levelOf("https://a.com", "a.com", 0, 0));
        assertEquals(1, prioritizer.levelOf("https://a.com/x", "a.com", 1, 0));
        assertEquals(2, prioritizer.levelOf("https://a.com/x", "a.com", 2, 0));
    }

    @Test
    void vnDomainMovesUpOneLevel() {
        assertEquals(2, prioritizer.levelOf("https://a.com/x", "a.com", 2, 0));
        assertEquals(1, prioritizer.levelOf("https://a.vn/x", "a.vn", 2, 0));
    }

    @Test
    void manyBacklinksMoveUpOneLevel() {
        int threshold = DefaultPrioritizer.BACKLINK_BOOST_THRESHOLD;
        assertEquals(2, prioritizer.levelOf("https://a.com/x", "a.com", 2, threshold - 1));
        assertEquals(1, prioritizer.levelOf("https://a.com/x", "a.com", 2, threshold));
    }

    @Test
    void bothSignalsStackButAreClampedAtZero() {
        assertEquals(0, prioritizer.levelOf("https://a.vn/x", "a.vn", 2, 50));
        assertEquals(0, prioritizer.levelOf("https://a.vn/x", "a.vn", 0, 50),
                "Không có mức nào cao hơn 0");
    }

    @Test
    void deepUrlsAreClampedToTheLowestLevel() {
        int lowest = prioritizer.levels() - 1;
        assertEquals(lowest, prioritizer.levelOf("https://a.com/x", "a.com", 99, 0));
    }

    /**
     * Ràng buộc thiết kế: hai tín hiệu phụ chỉ nâng ĐÚNG một bậc mỗi cái, nên
     * chúng không bao giờ lật ngược được thứ tự theo độ sâu quá 2 bậc. Bản
     * trước cộng điểm số nên 50 backlink đủ để một trang sâu 12 lớp vượt lên
     * trên seed.
     */
    @Test
    void sideSignalsCannotOverturnDepthByMoreThanTwoLevels() {
        int best = prioritizer.levelOf("https://a.vn/x", "a.vn", 4, 50);
        int plain = prioritizer.levelOf("https://a.com/x", "a.com", 4, 0);
        assertEquals(2, plain - best);
    }

    @Test
    void everyLevelIsWithinRange() {
        for (int depth = 0; depth < 20; depth++) {
            for (int backlinks : new int[] {0, 4, 5, 100}) {
                for (String host : new String[] {"a.com", "a.vn"}) {
                    int level = prioritizer.levelOf("https://" + host, host, depth, backlinks);
                    assertTrue(level >= 0 && level < prioritizer.levels(),
                            "Mức " + level + " nằm ngoài khoảng hợp lệ");
                }
            }
        }
    }
}
