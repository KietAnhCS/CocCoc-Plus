package com.vnsearch.crawler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobotsTxtParserTest {

    private final RobotsTxtParser parser = new RobotsTxtParser();

    @Test
    void emptyRobotsTxtAllowsEverything() {
        List<RobotsTxtParser.Rule> rules = parser.parseForTest("", "VnSearchBot");
        assertTrue(parser.isPathAllowed(rules, "/anything"));
    }

    @Test
    void wildcardDisallowBlocksMatchingPath() {
        String content = """
                User-agent: *
                Disallow: /admin/
                """;
        List<RobotsTxtParser.Rule> rules = parser.parseForTest(content, "VnSearchBot");
        assertFalse(parser.isPathAllowed(rules, "/admin/settings"));
        assertTrue(parser.isPathAllowed(rules, "/public/page"));
    }

    @Test
    void specificUserAgentSectionOverridesWildcard() {
        String content = """
                User-agent: *
                Disallow: /

                User-agent: VnSearchBot
                Disallow: /private/
                Allow: /
                """;
        List<RobotsTxtParser.Rule> rules = parser.parseForTest(content, "VnSearchBot");
        assertTrue(parser.isPathAllowed(rules, "/public/page"),
                "Section rieng cho VnSearchBot phai thay the hoan toan section *");
        assertFalse(parser.isPathAllowed(rules, "/private/data"));
    }

    @Test
    void unmatchedUserAgentFallsBackToWildcard() {
        String content = """
                User-agent: *
                Disallow: /no-bots/

                User-agent: GoogleBot
                Disallow: /google-only/
                """;
        List<RobotsTxtParser.Rule> rules = parser.parseForTest(content, "VnSearchBot");
        assertFalse(parser.isPathAllowed(rules, "/no-bots/page"));
        assertTrue(parser.isPathAllowed(rules, "/google-only/page"),
                "Luat cua GoogleBot khong ap dung cho VnSearchBot");
    }

    @Test
    void longestMatchingPathWins() {
        String content = """
                User-agent: *
                Disallow: /
                Allow: /blog/
                """;
        List<RobotsTxtParser.Rule> rules = parser.parseForTest(content, "VnSearchBot");
        assertTrue(parser.isPathAllowed(rules, "/blog/post-1"), "/blog/ (dai hon) phai thang / (Disallow)");
        assertFalse(parser.isPathAllowed(rules, "/other-page"));
    }

    @Test
    void commentsAndBlankLinesAreIgnored() {
        String content = """
                # day la comment
                User-agent: *
                # chan trang admin
                Disallow: /admin/

                """;
        List<RobotsTxtParser.Rule> rules = parser.parseForTest(content, "VnSearchBot");
        assertFalse(parser.isPathAllowed(rules, "/admin/x"));
        assertTrue(parser.isPathAllowed(rules, "/"));
    }
}
