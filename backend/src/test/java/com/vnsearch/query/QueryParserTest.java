package com.vnsearch.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryParserTest {

    private final QueryParser parser = new QueryParser();

    @Test
    void emptyQueryReturnsAllEmpty() {
        QueryParser.ParsedQuery parsed = parser.parse("");
        assertTrue(parsed.mustTerms().isEmpty());
        assertTrue(parsed.phrases().isEmpty());
        assertTrue(parsed.excludedTerms().isEmpty());
    }

    @Test
    void simpleQueryProducesMustTermsOnly() {
        QueryParser.ParsedQuery parsed = parser.parse("máy tính");
        assertEquals(1, parsed.mustTerms().size());
        assertEquals("máy_tính", parsed.mustTerms().get(0));
        assertTrue(parsed.phrases().isEmpty());
        assertTrue(parsed.excludedTerms().isEmpty());
    }

    @Test
    void quotedPhraseIsExtractedSeparately() {
        QueryParser.ParsedQuery parsed = parser.parse("\"trình duyệt web\"");
        assertTrue(parsed.mustTerms().isEmpty());
        assertEquals(1, parsed.phrases().size());
        assertEquals("trình_duyệt_web", parsed.phrases().get(0).get(0));
    }

    @Test
    void dashExcludesSingleWord() {
        QueryParser.ParsedQuery parsed = parser.parse("tin tức -giá");
        assertTrue(parsed.excludedTerms().contains("giá"));
        assertTrue(parsed.mustTerms().stream().noneMatch(t -> t.equals("giá")));
    }

    @Test
    void combinedQueryWithPhraseMustAndExclusion() {
        QueryParser.ParsedQuery parsed = parser.parse("\"máy tính\" giá -cũ");
        assertEquals(1, parsed.phrases().size());
        assertEquals("máy_tính", parsed.phrases().get(0).get(0));
        assertTrue(parsed.mustTerms().contains("giá"));
        assertTrue(parsed.excludedTerms().contains("cũ"));
    }

    @Test
    void dashOnlyExcludesTheSingleFollowingSyllable() {
        // Gioi han da biet: "-quảng cáo" chi loai tru "quảng", "cáo" van la mustTerm.
        QueryParser.ParsedQuery parsed = parser.parse("-quảng cáo");
        assertEquals(java.util.List.of("quảng"), parsed.excludedTerms());
        assertEquals(java.util.List.of("cáo"), parsed.mustTerms());
    }

    @Test
    void multipleQuotedPhrasesAreAllExtracted() {
        QueryParser.ParsedQuery parsed = parser.parse("\"trình duyệt\" \"máy tính\"");
        assertEquals(2, parsed.phrases().size());
    }

    @Test
    void blankPhraseIsIgnored() {
        QueryParser.ParsedQuery parsed = parser.parse("tin tức \"\"");
        assertTrue(parsed.phrases().isEmpty());
    }
}
