package com.vnsearch.query.ast;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.model.WebDocument;
import com.vnsearch.query.QueryParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kiem thu cay bieu thuc truy van (Composite pattern). */
class QueryAstTest {

    private InvertedIndex index;

    private static WebDocument doc(int id, String title, String body) {
        WebDocument d = new WebDocument();
        d.setDocId(id);
        d.setUrl("https://example.vn/" + id);
        d.setTitle(title);
        d.setBodyText(body);
        return d;
    }

    @BeforeEach
    void setUp() {
        index = new InvertedIndex();
        index.addDocument(doc(0, "A", "máy tính xách tay"));
        index.addDocument(doc(1, "B", "laptop giá rẻ"));
        index.addDocument(doc(2, "C", "máy tính giá rẻ"));
        index.addDocument(doc(3, "D", "điện thoại"));
    }

    @Test
    void termNodeReturnsPostingList() {
        assertEquals(List.of(0, 2), new TermNode("máy_tính").evaluate(index));
    }

    @Test
    void termNodeEstimatedSizeIsDocumentFrequency() {
        assertEquals(2, new TermNode("máy_tính").estimatedSize(index));
        assertEquals(0, new TermNode("khong-ton-tai").estimatedSize(index));
    }

    @Test
    void andNodeIntersects() {
        QueryNode ast = new AndNode(List.of(new TermNode("máy_tính"), new TermNode("giá")));
        assertEquals(List.of(2), ast.evaluate(index));
    }

    @Test
    void andNodeShortCircuitsOnEmptyChild() {
        QueryNode ast = new AndNode(List.of(new TermNode("khong-ton-tai"), new TermNode("máy_tính")));
        assertTrue(ast.evaluate(index).isEmpty());
    }

    @Test
    void orNodeUnitesAndKeepsSortedOrder() {
        QueryNode ast = new OrNode(List.of(new TermNode("máy_tính"), new TermNode("laptop")));
        assertEquals(List.of(0, 1, 2), ast.evaluate(index),
                "Hop phai giu bat bien sap xep tang dan");
    }

    @Test
    void orInsideAndWorks() {
        // (máy_tính OR laptop) AND giá
        QueryNode ast = new AndNode(List.of(
                new OrNode(List.of(new TermNode("máy_tính"), new TermNode("laptop"))),
                new TermNode("giá")));
        assertEquals(List.of(1, 2), ast.evaluate(index));
    }

    @Test
    void notInsideAndSubtracts() {
        // giá AND NOT laptop
        QueryNode ast = new AndNode(List.of(
                new TermNode("giá"),
                new NotNode(new TermNode("laptop"))));
        assertEquals(List.of(2), ast.evaluate(index));
    }

    @Test
    void standaloneNotIsRejectedWithClearMessage() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> new NotNode(new TermNode("laptop")).evaluate(index));
        assertTrue(e.getMessage().contains("NOT"));
    }

    @Test
    void andOfOnlyNegationsIsRejected() {
        QueryNode ast = new AndNode(List.of(new NotNode(new TermNode("laptop"))));
        assertThrows(UnsupportedOperationException.class, () -> ast.evaluate(index));
    }

    @Test
    void phraseNodeRequiresConsecutivePositions() {
        // doc0 = "máy tính xách tay" -> token: [0]máy_tính [1]xách_tay
        //
        // Truoc day test nay ky vong [0]máy_tính [1]xách [2]tay, kem chu thich
        // "'xách tay' khong co trong tu dien tu ghep 154 muc nen khong duoc gop".
        // Tu dien nay gio co 40.000 tu ghep va "xách tay" nam trong do, nen tien de
        // cu khong con dung. Day la thay doi DUNG huong — "xách tay" la mot tu that
        // — nen test duoc cap nhat theo, khong phai bug.
        assertEquals(List.of(0), new PhraseNode(List.of("máy_tính", "xách_tay")).evaluate(index));
        // Dao thu tu thi khong con lien tiep nua.
        assertTrue(new PhraseNode(List.of("xách_tay", "máy_tính")).evaluate(index).isEmpty());
    }

    @Test
    void phraseNodeRejectsNonAdjacentTerms() {
        // doc2 = "máy tính giá rẻ" -> [0]máy_tính [1]giá [2]rẻ  ("giá rẻ" khong
        // co trong tu dien). "máy_tính" va "rẻ" cung co mat nhung KHONG lien tiep.
        assertTrue(new PhraseNode(List.of("máy_tính", "rẻ")).evaluate(index).isEmpty(),
                "Cung co mat la chua du; phai LIEN TIEP");
    }

    @Test
    void describeProducesReadableTree() {
        QueryNode ast = new AndNode(List.of(
                new OrNode(List.of(new TermNode("máy_tính"), new TermNode("laptop"))),
                new TermNode("giá")));
        assertEquals("((máy_tính OR laptop) AND giá)", ast.describe());
    }

    @Test
    void parserBuildsOrTreeFromKeyword() {
        QueryParser parser = new QueryParser();
        QueryParser.ParsedQuery parsed = parser.parse("laptop OR máy tính");
        assertEquals(1, parsed.orGroups().size(), "Phai nhan ra mot nhom OR");

        QueryNode ast = parser.buildAst(parsed);
        assertTrue(ast.describe().contains("OR"), "AST phai chua nut OR: " + ast.describe());
    }

    @Test
    void parserExtractsSiteOperator() {
        QueryParser.ParsedQuery parsed = new QueryParser().parse("công nghệ site:vnexpress.net");
        assertEquals("vnexpress.net", parsed.siteFilter());
        assertTrue(parsed.mustTerms().stream().noneMatch(t -> t.contains("site")));
    }
}
