package com.vnsearch.crawler.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bus chạy <b>trong cùng một tiến trình</b> — bản cài mặc định của
 * {@link CrawlEventBus}.
 *
 * <p>Không broker, không serialize, không mạng: {@code publishPage} gọi thẳng
 * từng {@link PageEventHandler} đã đăng ký. Về mặt hiệu năng nó tương đương
 * với việc gọi hàm trực tiếp như mã cũ; cái thêm vào chỉ là một tầng gián
 * tiếp và một khối {@code try/catch}.
 *
 * <p><b>Vậy tầng gián tiếp đó mua được gì?</b> Nó mua khả năng đổi sang Kafka
 * mà không sửa {@code CrawlerService} lẫn ba service. Cùng một mã, hai kiểu
 * triển khai. Đó là toàn bộ lý do tồn tại của lớp này.
 *
 * <h2>Cô lập lỗi</h2>
 *
 * <p>Mỗi handler được gọi trong một {@code try/catch} riêng. Vì sao bắt buộc:
 * ba service chạy nối đuôi nhau trên cùng một luồng, nên nếu
 * {@code ImageDownloadService} ném ngoại lệ mà không ai bắt, hai hệ quả xảy
 * ra cùng lúc:
 *
 * <ul>
 *   <li>{@code UrlExtractorService} đăng ký sau nó <b>không bao giờ chạy</b>,
 *       nên frontier ngừng được nạp và cả phiên crawl chết đứng;</li>
 *   <li>ngoại lệ bay ngược lên {@code CrawlerService.processPage} và trang đó
 *       bị tính là lỗi tải, dù nó đã tải xong từ lâu.</li>
 * </ul>
 *
 * <p>Một service phụ làm chết cả crawler là kiểu phụ thuộc mà việc tách
 * service sinh ra để phá bỏ. Ở chế độ Kafka thì các service vốn đã cô lập
 * thật sự — mỗi consumer group một tiến trình. Lớp này <b>mô phỏng lại đúng
 * mức cô lập đó</b> trong một tiến trình, để hai chế độ hành xử giống nhau và
 * một lỗi không lộ ra chỉ ở môi trường thật.
 *
 * <h2>Vì sao {@code CopyOnWriteArrayList}</h2>
 *
 * <p>Danh sách handler được <b>đọc</b> từ mọi worker thread cho từng trang,
 * nhưng chỉ được <b>ghi</b> vài lần lúc khởi động. Đó đúng là ca sử dụng mà
 * cấu trúc này được thiết kế cho: đọc không cần khoá, ghi thì sao chép cả
 * mảng. Cùng lý do mà {@code CrawlerService} dùng nó cho danh sách listener.
 */
public class InProcessCrawlEventBus implements CrawlEventBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessCrawlEventBus.class);

    private final List<PageEventHandler> pageHandlers = new CopyOnWriteArrayList<>();
    private final List<Consumer<DiscoveredUrl>> urlHandlers = new CopyOnWriteArrayList<>();
    private final List<Consumer<OutlinksExtracted>> outlinkHandlers = new CopyOnWriteArrayList<>();
    private final List<Consumer<ImageFound>> imageHandlers = new CopyOnWriteArrayList<>();

    private final AtomicLong publishFailures = new AtomicLong();
    private final AtomicLong pagesPublished = new AtomicLong();
    private final AtomicLong urlsPublished = new AtomicLong();
    private final AtomicLong imagesPublished = new AtomicLong();

    /** Đăng ký một Modular Service nhận luồng trang. */
    public InProcessCrawlEventBus subscribePages(PageEventHandler handler) {
        if (handler != null) {
            pageHandlers.add(handler);
        }
        return this;
    }

    /** Đăng ký bên nạp URL vào frontier — thường là {@code CrawlerService} tự nó. */
    public InProcessCrawlEventBus subscribeDiscoveredUrls(Consumer<DiscoveredUrl> handler) {
        if (handler != null) {
            urlHandlers.add(handler);
        }
        return this;
    }

    /** Đăng ký bên ghi outlinks vào Content Storage. */
    public InProcessCrawlEventBus subscribeOutlinks(Consumer<OutlinksExtracted> handler) {
        if (handler != null) {
            outlinkHandlers.add(handler);
        }
        return this;
    }

    /** Đăng ký bên lưu bản ghi ảnh. */
    public InProcessCrawlEventBus subscribeImages(Consumer<ImageFound> handler) {
        if (handler != null) {
            imageHandlers.add(handler);
        }
        return this;
    }

    @Override
    public void publishPage(PageEvent event) {
        if (event == null) {
            return;
        }
        pagesPublished.incrementAndGet();
        for (PageEventHandler handler : pageHandlers) {
            try {
                handler.onPage(event);
            } catch (Exception e) {
                publishFailures.incrementAndGet();
                // Ghi URL chứ KHÔNG ghi cả sự kiện: toString() của PageEvent đã
                // cố tình bỏ HTML, nhưng ghi rõ url ở đây vẫn dễ đọc hơn khi
                // dò log, và không có đường nào để 80 KB lọt vào tệp log.
                log.warn("Modular Service {} ném ngoại lệ khi xử lý {} — bỏ qua trang này, "
                                + "các service khác vẫn chạy",
                        handler.handlerName(), event.url(), e);
            }
        }
    }

    @Override
    public void publishDiscoveredUrl(DiscoveredUrl url) {
        if (url == null) {
            return;
        }
        urlsPublished.incrementAndGet();
        dispatch(urlHandlers, url, "DiscoveredUrl", url.url());
    }

    @Override
    public void publishOutlinks(OutlinksExtracted outlinks) {
        if (outlinks == null) {
            return;
        }
        dispatch(outlinkHandlers, outlinks, "OutlinksExtracted", outlinks.sourceUrl());
    }

    @Override
    public void publishImage(ImageFound image) {
        if (image == null) {
            return;
        }
        imagesPublished.incrementAndGet();
        dispatch(imageHandlers, image, "ImageFound", image.imageUrl());
    }

    /** Gọi từng handler, cô lập lỗi — cùng chính sách với {@link #publishPage}. */
    private <T> void dispatch(List<Consumer<T>> handlers, T payload, String kind, String subject) {
        for (Consumer<T> handler : handlers) {
            try {
                handler.accept(payload);
            } catch (Exception e) {
                publishFailures.incrementAndGet();
                log.warn("Bên nhận {} ném ngoại lệ khi xử lý {} — bỏ qua", kind, subject, e);
            }
        }
    }

    @Override
    public long getPublishFailureCount() {
        return publishFailures.get();
    }

    public long getPagesPublishedCount() {
        return pagesPublished.get();
    }

    public long getUrlsPublishedCount() {
        return urlsPublished.get();
    }

    public long getImagesPublishedCount() {
        return imagesPublished.get();
    }

    /** Số Modular Service đang lắng nghe luồng trang. */
    public int pageHandlerCount() {
        return pageHandlers.size();
    }
}
