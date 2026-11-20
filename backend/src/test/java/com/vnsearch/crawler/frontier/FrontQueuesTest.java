package com.vnsearch.crawler.frontier;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test tầng hàng đợi trước và hai chính sách chọn hàng đợi. */
class FrontQueuesTest {

    private static CrawlTask task(String url) {
        return new CrawlTask(url, "a.com", 0);
    }

    @Test
    void strictSelectorAlwaysTakesHighestLevelFirst() {
        FrontQueues queues = new FrontQueues(3, new StrictPrioritySelector());
        queues.add(task("https://a.com/low"), 2);
        queues.add(task("https://a.com/high"), 0);
        queues.add(task("https://a.com/mid"), 1);

        assertEquals("https://a.com/high", queues.poll().url());
        assertEquals("https://a.com/mid", queues.poll().url());
        assertEquals("https://a.com/low", queues.poll().url());
        assertNull(queues.poll());
    }

    @Test
    void sameLevelIsFifo() {
        FrontQueues queues = new FrontQueues(2, new StrictPrioritySelector());
        queues.add(task("https://a.com/1"), 0);
        queues.add(task("https://a.com/2"), 0);
        queues.add(task("https://a.com/3"), 0);

        assertEquals("https://a.com/1", queues.poll().url());
        assertEquals("https://a.com/2", queues.poll().url());
        assertEquals("https://a.com/3", queues.poll().url());
    }

    @Test
    void tracksSizePerLevel() {
        FrontQueues queues = new FrontQueues(3, new StrictPrioritySelector());
        assertTrue(queues.isEmpty());
        queues.add(task("https://a.com/1"), 0);
        queues.add(task("https://a.com/2"), 2);

        assertEquals(2, queues.size());
        assertEquals(1, queues.sizeOfLevel(0));
        assertEquals(0, queues.sizeOfLevel(1));
        assertEquals(1, queues.sizeOfLevel(2));
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new FrontQueues(0, new StrictPrioritySelector()));
        assertThrows(IllegalArgumentException.class, () -> new FrontQueues(3, null));

        FrontQueues queues = new FrontQueues(2, new StrictPrioritySelector());
        assertThrows(IllegalArgumentException.class, () -> queues.add(task("https://a.com/x"), 2));
        assertThrows(IllegalArgumentException.class, () -> queues.add(task("https://a.com/x"), -1));
    }

    /**
     * Tính chất quan trọng nhất của bộ chọn ngẫu nhiên có trọng số: mức thấp
     * nhất vẫn được phục vụ. Bộ chọn tất định KHÔNG có tính chất này.
     */
    @Test
    void weightedSelectorNeverStarvesTheLowestLevel() {
        int levels = 5;
        FrontQueues queues = new FrontQueues(levels, new WeightedRandomSelector());
        int perLevel = 200;
        for (int level = 0; level < levels; level++) {
            for (int i = 0; i < perLevel; i++) {
                queues.add(task("https://a.com/" + level + "-" + i), level);
            }
        }

        // Lấy 200 URL đầu tiên; nếu bộ chọn tất định thì cả 200 đều thuộc mức 0.
        int[] taken = new int[levels];
        for (int i = 0; i < perLevel; i++) {
            CrawlTask t = queues.poll();
            taken[levelOf(t.url())]++;
        }

        for (int level = 0; level < levels; level++) {
            assertTrue(taken[level] > 0,
                    "Mức " + level + " không được lấy lần nào -> bị bỏ đói");
        }
    }

    /** Mức cao vẫn phải được ưu ái rõ rệt, không phải chia đều. */
    @Test
    void weightedSelectorStillFavoursHighLevels() {
        int levels = 5;
        FrontQueues queues = new FrontQueues(levels, new WeightedRandomSelector());
        int perLevel = 4000;
        for (int level = 0; level < levels; level++) {
            for (int i = 0; i < perLevel; i++) {
                queues.add(task("https://a.com/" + level + "-" + i), level);
            }
        }

        int[] taken = new int[levels];
        for (int i = 0; i < 2000; i++) {
            taken[levelOf(queues.poll().url())]++;
        }

        assertTrue(taken[0] > taken[levels - 1] * 5,
                "Mức 0 phải được lấy nhiều hơn hẳn mức thấp nhất, thực tế "
                        + taken[0] + " so với " + taken[levels - 1]);
    }

    /** Cùng hạt giống thì hai lần chạy cho ra đúng cùng một thứ tự. */
    @Test
    void weightedSelectorIsReproducibleWithTheSameSeed() {
        assertEquals(drainWithSeed(7L), drainWithSeed(7L));
    }

    private static List<String> drainWithSeed(long seed) {
        FrontQueues queues = new FrontQueues(4, new WeightedRandomSelector(seed));
        for (int level = 0; level < 4; level++) {
            for (int i = 0; i < 25; i++) {
                queues.add(task("https://a.com/" + level + "-" + i), level);
            }
        }
        List<String> order = new java.util.ArrayList<>();
        CrawlTask t;
        while ((t = queues.poll()) != null) {
            order.add(t.url());
        }
        return order;
    }

    /** URL được đặt tên "https://a.com/&lt;level&gt;-&lt;i&gt;" nên đọc ngược lại được. */
    private static int levelOf(String url) {
        String tail = url.substring(url.lastIndexOf('/') + 1);
        return Integer.parseInt(tail.substring(0, tail.indexOf('-')));
    }
}
