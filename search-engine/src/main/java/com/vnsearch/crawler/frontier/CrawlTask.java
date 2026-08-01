package com.vnsearch.crawler.frontier;

/**
 * Một URL đang chờ được crawl, kèm host đã rút sẵn và độ sâu BFS.
 *
 * <p><b>Vì sao mang theo {@code host}.</b> Host được dùng ở ba chỗ khác nhau
 * trên đường đi của một URL: {@link Prioritizer} (ưu tiên domain {@code .vn}),
 * {@link BackQueues} (định tuyến về đúng hàng đợi của host), và
 * {@link UrlFrontier} (đếm số host đang chờ). Nếu không mang theo, mỗi chỗ
 * phải tự gọi {@code URI.create} lại trên cùng một chuỗi — và phép phân tích
 * URI đắt hơn hẳn một tham chiếu.
 *
 * <p>{@code host} không bao giờ {@code null}: URL không phân tích được thì
 * lấy chính chuỗi URL làm khoá, để nó vẫn được xếp vào một hàng đợi riêng
 * thay vì bị gộp nhầm với host khác.
 *
 * @param url   URL đã chuẩn hoá
 * @param host  host của URL, hoặc chính URL nếu không phân tích được
 * @param depth độ sâu BFS tính từ seed
 */
public record CrawlTask(String url, String host, int depth) {

    public CrawlTask {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url không được rỗng");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host không được rỗng");
        }
        if (depth < 0) {
            throw new IllegalArgumentException("depth phải >= 0, nhận được: " + depth);
        }
    }

    @Override
    public String toString() {
        return "CrawlTask{" + url + ", depth=" + depth + "}";
    }
}
