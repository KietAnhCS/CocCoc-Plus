package com.vnsearch.index;

import java.util.ArrayList;
import java.util.List;

/**
 * Dạng <b>nén</b> của một posting list, dùng khi ghi chỉ mục ra đĩa.
 *
 * <p>{@link VByteCodec} biết nén <i>một</i> dãy số nguyên tăng dần. Nhưng một
 * posting list không phải một dãy — nó là ba loại dữ liệu trộn vào nhau, và
 * chỉ một trong ba là tăng dần:
 *
 * <table border="1">
 *   <tr><th>Thành phần</th><th>Tăng dần?</th></tr>
 *   <tr><td>{@code docId} qua các posting</td><td>LUÔN — bất biến của {@link InvertedIndex}</td></tr>
 *   <tr><td>{@code termFrequency}</td><td>KHÔNG — lung tung (3, 1, 2, 5…)</td></tr>
 *   <tr><td>{@code positions} nối liền nhiều posting</td><td>KHÔNG — reset về 0 mỗi tài liệu</td></tr>
 * </table>
 *
 * <p>Lớp này khử hai chỗ "KHÔNG" đó bằng hai ý tưởng:
 *
 * <p><b>1. Bỏ hẳn {@code termFrequency} — chứng minh nó thừa.</b> Mọi Posting
 * do {@link InvertedIndex#addDocument} tạo ra đều thoả
 * {@code termFrequency == positions.size()}, vì hàm đó gom vị trí trước rồi mới
 * dựng Posting. Nên trường này không mang thông tin nào mới. Cách rẻ nhất để
 * nén một trường không phải là tìm thuật toán tốt hơn, mà là chứng minh trường
 * đó thừa — tỷ lệ nén của việc bỏ hẳn là vô hạn.
 *
 * <p>Nhưng "thừa" là một BẤT BIẾN, và bất biến phải được ép: nếu về sau ai đó
 * dựng Posting với {@code tf != |positions|}, dữ liệu sẽ sai âm thầm lúc giải
 * nén và lỗi chỉ lộ ra ở điểm BM25 lệch, hàng tháng sau. Vì vậy {@link #of}
 * kiểm tra và ném ngoại lệ ngay tại chỗ sai — cùng triết lý với việc
 * {@code addDocument} ép thứ tự docId tăng dần.
 *
 * <p><b>2. Tổng tích luỹ biến dãy bất kỳ thành dãy đơn điệu.</b> Bỏ được
 * {@code tf} rồi vẫn phải biết mỗi posting có bao nhiêu vị trí. Dãy số lượng đó
 * không tăng dần, nhưng TỔNG TÍCH LUỸ của nó thì luôn không giảm:
 * <pre>
 *   tf mỗi posting  : [3, 1, 2, 5]        ← không tăng dần
 *   offset tích luỹ : [0, 3, 4, 6, 11]    ← LUÔN tăng dần
 *   nghịch đảo      : tf[i] = offset[i+1] - offset[i]
 * </pre>
 * Nhờ vậy đúng MỘT codec ({@link VByteCodec#encodeSorted}) dùng được cho cả ba
 * mảng. Đây chính là kỹ thuật {@code rowPtr} của định dạng CSR mà
 * {@link com.vnsearch.datastructure.SparseMatrix#freeze()} dùng để nén ma trận
 * thưa — cùng một ý tưởng xuất hiện hai lần ở hai chỗ không liên quan trong đồ
 * án, dấu hiệu nó là kỹ thuật nền tảng chứ không phải thủ thuật riêng lẻ.
 *
 * <p>Mảng {@code offsets} có {@code count + 1} phần tử chứ không phải
 * {@code count}: phần tử canh biên làm công thức hiệu số đúng cho MỌI {@code i}
 * kể cả phần tử cuối, khỏi cần một nhánh {@code if} riêng — cùng lý do với
 * sentinel node của {@link com.vnsearch.datastructure.LRUCache}.
 *
 * <p><b>Vì sao {@code positions} phải nén theo ĐOẠN.</b> Vị trí là chỉ số token
 * trong tài liệu nên mỗi tài liệu bắt đầu lại từ 0. Nối tất cả rồi delta hoá
 * một lần sẽ cho delta ÂM tại mỗi ranh giới posting, mà VByte chỉ mã hoá được
 * số không âm. {@link VByteCodec#encodeSegments} vì vậy reset mốc delta ở đầu
 * mỗi đoạn; chiều giải nén đọc tuần tự nên không cần lưu vị trí byte bắt đầu
 * của từng đoạn — chỉ cần số phần tử, mà số đó đã suy được từ mảng offset.
 *
 * <p><b>Vì sao không dùng GZIP cho xong.</b> GZIP nén tốt hơn và tốn ba dòng
 * code, nhưng phá vỡ một tính chất quan trọng hơn tỷ lệ nén: đọc MỘT term thì
 * phải giải nén TOÀN BỘ file. Giữ mỗi term là một đơn vị độc lập mở đường cho
 * việc nạp posting list theo yêu cầu thay vì nạp cả chỉ mục vào RAM lúc khởi
 * động. Nén CỘNG truy cập ngẫu nhiên là thứ nén tổng quát không cho.
 *
 * <p>Độ phức tạp: cả {@link #of} và {@link #toPostings} đều một lượt
 * {@code O(n + tổng tf)}, không sắp xếp, không cấu trúc trung gian nào ngoài bộ
 * đệm kết quả.
 *
 * @param count     số posting — phải lưu vì VByte không tự mô tả độ dài
 * @param docIds    dãy docId, delta + VByte
 * @param offsets   tổng tích luỹ số vị trí ({@code count + 1} phần tử), delta + VByte
 * @param positions các đoạn vị trí nối tiếp nhau, mỗi đoạn delta hoá độc lập
 */
public record CompressedPostings(int count, byte[] docIds, byte[] offsets, byte[] positions) {

    private static final CompressedPostings EMPTY =
            new CompressedPostings(0, new byte[0], new byte[0], new byte[0]);

    /**
     * Nén một posting list ở dạng bộ nhớ.
     *
     * @throws IllegalArgumentException nếu bất biến {@code termFrequency ==
     *         positions.size()} bị vi phạm ở bất kỳ posting nào
     */
    public static CompressedPostings of(List<Posting> postings) {
        int count = postings.size();
        if (count == 0) {
            return EMPTY;
        }

        int[] docIds = new int[count];
        int[] offsets = new int[count + 1];
        List<int[]> segments = new ArrayList<>(count);

        int running = 0;
        for (int i = 0; i < count; i++) {
            Posting posting = postings.get(i);
            List<Integer> positions = posting.positions();
            int size = positions == null ? 0 : positions.size();

            if (posting.termFrequency() != size) {
                throw new IllegalArgumentException(
                        "Bat bien 'termFrequency == positions.size()' bi vi pham tai docId "
                                + posting.docId() + ": termFrequency = " + posting.termFrequency()
                                + " nhung positions.size() = " + size
                                + ". Dang nen KHONG luu termFrequency ma suy lai tu so vi tri,"
                                + " nen mot Posting sai bat bien se bi giai nen SAI mot cach im lang.");
            }

            docIds[i] = posting.docId();
            int[] segment = new int[size];
            for (int j = 0; j < size; j++) {
                segment[j] = positions.get(j);
            }
            segments.add(segment);

            running += size;
            offsets[i + 1] = running;
        }

        return new CompressedPostings(count,
                VByteCodec.encodeSorted(docIds),
                VByteCodec.encodeSorted(offsets),
                VByteCodec.encodeSegments(segments));
    }

    /** Khôi phục posting list nguyên vẹn, kể cả {@code termFrequency} vốn không hề được lưu. */
    public List<Posting> toPostings() {
        if (count == 0) {
            return List.of();
        }
        int[] ids = VByteCodec.decodeSorted(docIds, count);
        int[] cumulative = VByteCodec.decodeSorted(offsets, count + 1);

        int[] sizes = new int[count];
        for (int i = 0; i < count; i++) {
            sizes[i] = cumulative[i + 1] - cumulative[i]; // nghịch đảo của tổng tích luỹ
        }
        int[][] segments = VByteCodec.decodeSegments(positions, sizes);

        List<Posting> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int[] segment = segments[i];
            List<Integer> positionList = new ArrayList<>(segment.length);
            for (int position : segment) {
                positionList.add(position);
            }
            result.add(new Posting(ids[i], segment.length, positionList));
        }
        return result;
    }

    /** Tổng số byte của ba mảng — dùng cho thống kê kích thước chỉ mục. */
    public int totalBytes() {
        return docIds.length + offsets.length + positions.length;
    }
}
