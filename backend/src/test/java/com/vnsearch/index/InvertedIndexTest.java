package com.vnsearch.index;

import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvertedIndexTest {

    private WebDocument doc(int id, String title, String body) {
        WebDocument d = new WebDocument();
        d.setDocId(id);
        d.setTitle(title);
        d.setBodyText(body);
        return d;
    }

    @Test
    void emptyIndexReturnsEmptyPostings() {
        InvertedIndex index = new InvertedIndex();
        assertTrue(index.getPostings("khong-ton-tai").isEmpty());
        assertEquals(0, index.getDocumentFrequency("khong-ton-tai"));
        assertEquals(0, index.getTotalDocs());
    }

    @Test
    void singleDocumentIndexing() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "Máy tính", "Máy tính rất hữu ích"));

        List<Posting> postings = index.getPostings("máy_tính");
        assertEquals(1, postings.size());
        assertEquals(0, postings.get(0).docId());
        assertEquals(2, postings.get(0).termFrequency());
    }

    @Test
    void postingListsStayOrderedByDocId() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "tin tuc", "công nghệ hôm nay"));
        index.addDocument(doc(1, "tin tuc", "công nghệ ngày mai"));
        index.addDocument(doc(2, "tin tuc", "công nghệ tuần này"));

        List<Posting> postings = index.getPostings("công_nghệ");
        assertEquals(3, postings.size());
        assertEquals(0, postings.get(0).docId());
        assertEquals(1, postings.get(1).docId());
        assertEquals(2, postings.get(2).docId());
    }

    @Test
    void diacriticAndNoDiacriticFormsShareThePosting() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "Trình duyệt web", "Trình duyệt web rất nhanh"));

        List<Posting> withDiacritics = index.getPostings("trình_duyệt_web");
        List<Posting> withoutDiacritics = index.getPostings("trinh_duyet_web");
        assertEquals(1, withDiacritics.size());
        assertEquals(1, withoutDiacritics.size());
        assertEquals(withDiacritics.get(0).docId(), withoutDiacritics.get(0).docId());
        assertEquals(withDiacritics.get(0).termFrequency(), withoutDiacritics.get(0).termFrequency());
    }

    @Test
    void documentFrequencyCountsDistinctDocuments() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "máy tính", "máy tính máy tính")); // 3 lan trong 1 tai lieu
        index.addDocument(doc(1, "khác", "không liên quan"));

        assertEquals(1, index.getDocumentFrequency("máy_tính"), "DF dem so TAI LIEU, khong dem so lan xuat hien");
    }

    @Test
    void docLengthTracksTokenCountAfterStopwordRemoval() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "", "và của là"));
        assertEquals(0, index.getDocLength(0), "Toan stopword -> docLength = 0");
    }
}
