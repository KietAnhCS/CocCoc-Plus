package com.vnsearch.index;

import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressedTextTest {

    @Test
    @DisplayName("Nen roi giai nen tra lai dung chuoi ban dau, ke ca dau tieng Viet")
    void roundTripPreservesVietnameseText() {
        String original = "Máy tính xách tay cấu hình mạnh phù hợp cho sinh viên. "
                + "Trình duyệt web giúp người dùng truy cập internet dễ dàng.";
        assertEquals(original, CompressedText.decompress(CompressedText.compress(original)));
    }

    @Test
    @DisplayName("Chuoi rong va null cho ra chuoi rong, khong nem ngoai le")
    void handlesEmptyAndNull() {
        assertEquals("", CompressedText.decompress(CompressedText.compress(null)));
        assertEquals("", CompressedText.decompress(CompressedText.compress("")));
        assertEquals("", CompressedText.decompress(null));
    }

    @Test
    @DisplayName("Van ban that su nho di dang ke")
    void actuallyCompresses() {
        // Van ban bao chi that co nhieu tu lap lai, nen ty le nen cao. Dung mot
        // doan du dai de vuot qua chi phi co dinh cua bo tu dien deflate.
        String text = ("Công nghệ thông tin đang thay đổi cách con người làm việc "
                + "và học tập mỗi ngày trong xã hội hiện đại. ").repeat(40);

        int rawBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int compressedBytes = CompressedText.compress(text).length;

        assertTrue(compressedBytes * 4 < rawBytes,
                "Phai nho hon it nhat 4 lan; thuc te " + rawBytes + " -> " + compressedBytes);
    }

    @Test
    @DisplayName("Chi muc khong con giu bodyText trong WebDocument, nhung van tra ra duoc")
    void indexKeepsBodyTextOutOfTheDocument() {
        WebDocument doc = new WebDocument();
        doc.setDocId(0);
        doc.setUrl("https://vnsearch.example/a");
        doc.setTitle("Máy tính");
        doc.setBodyText("Máy tính xách tay cấu hình mạnh dành cho sinh viên.");

        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc);

        // Tai lieu LUU TRONG chi muc khong con van ban than bai...
        assertNull(index.getDocument(0).getBodyText(),
                "WebDocument trong chi muc khong duoc giu bodyText nua");

        // ...nhung chi muc van tra ra duoc, tu kho nen rieng.
        assertEquals("Máy tính xách tay cấu hình mạnh dành cho sinh viên.",
                index.getBodyText(0));

        // Va doi tuong NGUOI GOI dua vao KHONG bi sua doi - day la ban sao.
        assertEquals("Máy tính xách tay cấu hình mạnh dành cho sinh viên.",
                doc.getBodyText(),
                "addDocument khong duoc phep sua doi tuong cua nguoi goi");
    }
}
