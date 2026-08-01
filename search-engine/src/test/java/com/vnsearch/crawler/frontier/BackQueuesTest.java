package com.vnsearch.crawler.frontier;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test tầng hàng đợi sau: định tuyến theo host, politeness, và nạp lại khi cạn. */
class BackQueuesTest {

    private static final long DELAY = 1000L;

    private static FrontQueues frontWith(String... urls) {
        FrontQueues front = new FrontQueues(1, new StrictPrioritySelector());
        for (String url : urls) {
            front.add(new CrawlTask(url, hostOf(url), 0), 0);
        }
        return front;
    }

    private static String hostOf(String url) {
        return java.net.URI.create(url).getHost();
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new BackQueues(0, DELAY));
        assertThrows(IllegalArgumentException.class, () -> new BackQueues(4, -1));
    }

    @Test
    void emptyBackQueuesHaveNothingToPoll() {
        BackQueues back = new BackQueues(4, DELAY);
        assertNull(back.poll(System.currentTimeMillis()));
        assertEquals(Long.MAX_VALUE, back.earliestAvailableAt());
        assertEquals(0, back.pendingCount());
    }

    /** Bất biến cốt lõi: mỗi hàng đợi sau chỉ chứa URL của đúng một host. */
    @Test
    void eachQueueHoldsExactlyOneHost() {
        BackQueues back = new BackQueues(4, DELAY);
        back.refillFrom(frontWith(
                "https://a.com/1", "https://a.com/2",
                "https://b.com/1", "https://b.com/2"));

        Set<String> hosts = new HashSet<>();
        for (int slot = 0; slot < back.queueCount(); slot++) {
            String host = back.hostOfQueue(slot);
            if (host != null) {
                assertTrue(hosts.add(host), "Host " + host + " chiếm hai hàng đợi cùng lúc");
            }
        }
        assertEquals(2, back.boundHostCount());
        assertEquals(4, back.pendingCount(), "Cả 4 URL đều đã được định tuyến");
    }

    @Test
    void politenessDelayAppliesWithinOneHost() {
        BackQueues back = new BackQueues(4, DELAY);
        back.refillFrom(frontWith("https://a.com/1", "https://a.com/2"));

        long now = 10_000L;
        assertNotNull(back.poll(now), "URL đầu tiên của host phải lấy được ngay");
        assertNull(back.poll(now), "URL thứ hai cùng host phải chờ hết politeness delay");
        assertEquals(now + DELAY, back.earliestAvailableAt());
        assertNotNull(back.poll(now + DELAY), "Hết thời gian hoãn thì lấy được");
    }

    @Test
    void differentHostsAreServedWithoutWaiting() {
        BackQueues back = new BackQueues(4, DELAY);
        back.refillFrom(frontWith("https://a.com/1", "https://b.com/1", "https://c.com/1"));

        long now = 10_000L;
        Set<String> served = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            CrawlTask task = back.poll(now);
            assertNotNull(task, "Host khác nhau không phải chờ nhau");
            served.add(task.host());
        }
        assertEquals(Set.of("a.com", "b.com", "c.com"), served);
    }

    /**
     * Host nhiều hơn số hàng đợi thì host thừa nằm chờ ở tầng trước chứ không
     * bị mất — đây là lý do tầng sau được nạp theo yêu cầu thay vì định tuyến
     * ngay lúc thêm.
     */
    @Test
    void extraHostsWaitInTheFrontTier() {
        BackQueues back = new BackQueues(2, DELAY);
        FrontQueues front = frontWith("https://a.com/1", "https://b.com/1", "https://c.com/1");

        back.refillFrom(front);
        assertEquals(2, back.pendingCount());
        assertEquals(1, front.size(), "Host thứ ba chưa có chỗ nên vẫn nằm ở tầng trước");
        assertEquals(2, back.boundHostCount());
    }

    /** Hàng đợi cạn thì được gán cho một host mới, và Mapping Table không phình. */
    @Test
    void drainedQueueIsReboundToANewHost() {
        BackQueues back = new BackQueues(2, DELAY);
        FrontQueues front = frontWith("https://a.com/1", "https://b.com/1", "https://c.com/1");
        back.refillFrom(front);

        long now = 10_000L;
        CrawlTask first = back.poll(now); // làm cạn một hàng đợi
        assertNotNull(first);

        back.refillFrom(front);
        assertEquals(0, front.size(), "URL cuối cùng đã được kéo sang tầng sau");
        assertEquals(2, back.boundHostCount(),
                "Mapping Table không bao giờ vượt quá số hàng đợi sau");

        Set<String> hosts = new HashSet<>();
        for (int slot = 0; slot < back.queueCount(); slot++) {
            hosts.add(back.hostOfQueue(slot));
        }
        assertTrue(hosts.contains("c.com"), "Host mới phải được gán vào hàng đợi vừa cạn");
        assertFalse(hosts.contains(first.host()), "Host cũ đã nhường chỗ");
    }

    /**
     * Hàng đợi vừa được dùng thì thừa hưởng đồng hồ lịch sự của nó, kể cả khi
     * đã đổi sang host khác. Đây là đánh đổi có chủ ý: chờ thừa thì vô hại,
     * chờ thiếu thì vi phạm politeness.
     */
    @Test
    void reboundQueueInheritsThePolitenessClock() {
        BackQueues back = new BackQueues(1, DELAY);
        FrontQueues front = frontWith("https://a.com/1", "https://b.com/1");
        back.refillFrom(front);

        long now = 10_000L;
        assertEquals("a.com", back.poll(now).host());

        back.refillFrom(front);
        assertNull(back.poll(now), "Hàng đợi vừa dùng vẫn đang trong thời gian hoãn");
        assertEquals("b.com", back.poll(now + DELAY).host());
    }
}
