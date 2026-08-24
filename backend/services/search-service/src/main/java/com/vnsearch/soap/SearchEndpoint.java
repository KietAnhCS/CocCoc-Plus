package com.vnsearch.soap;

import com.vnsearch.model.SearchResponse;
import com.vnsearch.model.SearchResult;
import com.vnsearch.service.SearchEngineFacade;
import com.vnsearch.soap.generated.GoiYRequest;
import com.vnsearch.soap.generated.GoiYResponse;
import com.vnsearch.soap.generated.KetQua;
import com.vnsearch.soap.generated.TimKiemRequest;
import com.vnsearch.soap.generated.TimKiemResponse;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/**
 * Cửa <b>SOAP</b> của máy tìm kiếm.
 *
 * <p>Cùng một {@link SearchEngineFacade} mà {@code SearchController} (REST)
 * gọi. Đây là điểm quan trọng nhất của lớp này: <b>không có một dòng logic tìm
 * kiếm nào ở đây</b>, chỉ có phép dịch giữa hai dạng thông điệp. Hai giao thức,
 * một hành vi — nếu SOAP có một nhánh xử lý riêng thì sớm muộn kết quả của hai
 * cửa sẽ khác nhau, và không ai biết cửa nào đúng.
 *
 * <pre>
 *   REST   GET  /api/search?q=...            JSON   máy khách web, di động
 *   SOAP   POST /ws  (SOAPAction)            XML    hệ thống tích hợp nội bộ
 * </pre>
 *
 * <h2>Vì sao chặn tham số ở đây nữa dù XSD đã chặn</h2>
 *
 * <p>XSD đã ràng buộc {@code soLuong} trong khoảng 1..100, và trình phân tích
 * XML từ chối thông điệp vi phạm trước khi tới lớp này. Nhưng phần tử đó là
 * {@code minOccurs="0"}: khi bên gọi <i>bỏ trống</i>, JAXB trả về {@code null}
 * chứ không trả giá trị {@code default} ghi trong XSD — thuộc tính
 * {@code default} của XSD chỉ có ý nghĩa khi phần tử có mặt nhưng rỗng. Đây là
 * cái bẫy kinh điển của contract-first, và hậu quả là một
 * {@code NullPointerException} ở dòng đầu tiên chạm tới tham số.
 */
@Endpoint
public class SearchEndpoint {

    /** Phải khớp {@code targetNamespace} trong tim-kiem.xsd. */
    private static final String NAMESPACE = "http://vnsearch.com/soap/tim-kiem/v1";

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_SUGGEST_SIZE = 10;
    private static final int MAX_SUGGEST_SIZE = 50;

    private final SearchEngineFacade facade;

    public SearchEndpoint(SearchEngineFacade facade) {
        this.facade = facade;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "TimKiemRequest")
    @ResponsePayload
    public TimKiemResponse search(@RequestPayload TimKiemRequest request) {
        int page = clamp(request.getTrang(), DEFAULT_PAGE, 1, Integer.MAX_VALUE);
        int pageSize = clamp(request.getSoLuong(), DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);

        SearchResponse result = facade.search(request.getTuKhoa(), page, pageSize);

        TimKiemResponse response = new TimKiemResponse();
        response.setTuKhoa(result.query());
        response.setTongSoKetQua(result.totalResults());
        response.setTrang(result.page());
        response.setThoiGianMs(result.timeTakenMs());
        for (SearchResult item : result.results()) {
            response.getKetQua().add(toResult(item));
        }
        if (result.droppedTerms() != null) {
            response.getTuBiBoQua().addAll(result.droppedTerms());
        }
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "GoiYRequest")
    @ResponsePayload
    public GoiYResponse suggest(@RequestPayload GoiYRequest request) {
        int limit = clamp(request.getSoLuong(), DEFAULT_SUGGEST_SIZE, 1, MAX_SUGGEST_SIZE);

        GoiYResponse response = new GoiYResponse();
        response.setTienTo(request.getTienTo());
        response.getGoiY().addAll(facade.suggest(request.getTienTo(), limit));
        return response;
    }

    private static KetQua toResult(SearchResult item) {
        KetQua result = new KetQua();
        result.setTieuDe(item.title());
        result.setDuongDan(item.url());
        result.setTrichDoan(item.snippet());
        result.setDiem(item.score());
        result.setDiemPageRank(item.pageRankScore());
        // Chuỗi ISO-8601, và null vẫn là null: XSD khai phần tử này
        // minOccurs="0" nên vắng mặt là hợp lệ. Điền một chuỗi rỗng thay cho
        // null sẽ khiến bên gọi tưởng có giá trị mà giá trị đó vô nghĩa.
        result.setThoiDiemThuThap(item.crawledAt() == null ? null : item.crawledAt().toString());
        return result;
    }

    /**
     * Ép một tham số tuỳ chọn về khoảng hợp lệ.
     *
     * <p>Nhận {@link Integer} chứ không {@code int}: JAXB trả {@code null} cho
     * phần tử vắng mặt, và tự động unbox một {@code null} là
     * {@code NullPointerException} — xem Javadoc lớp.
     */
    private static int clamp(Integer value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        return Math.min(Math.max(value, min), max);
    }
}
