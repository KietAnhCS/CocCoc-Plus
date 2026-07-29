package com.vnsearch.crawler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tu parse robots.txt (khong dung thu vien co san nhu crawler-commons) de
 * biet duong dan nao duoc phep/khong duoc phep crawl cho tung domain.
 *
 * <p>Chi ho tro tap luat pho bien nhat can cho mot crawler don gian:
 * cac dong {@code User-agent:}, {@code Disallow:}, {@code Allow:} va so
 * khop tien to duong dan (path prefix matching) — day la phan lon logic
 * ma cac crawler thuc te dung, bo qua wildcard {@code *}/{@code $} nang
 * cao trong duong dan de giu code don gian, de giai thich.
 *
 * <p>Ket qua parse cua moi domain duoc cache lai (Map trong bo nho) vi
 * fetch robots.txt qua mang la thao tac cham, khong the goi lai cho moi
 * URL cua cung 1 domain.
 *
 * <p>Do phuc tap thoi gian: fetch+parse 1 lan cho moi domain la O(kich
 * thuoc file robots.txt); {@link #isAllowed} sau do la O(so luat) do voi
 * cache, thuong duoi 50 luat nen coi nhu O(1) trong thuc te.
 */
public class RobotsTxtParser {

    record Rule(String path, boolean isAllow) {
    }

    private final Map<String, List<Rule>> cache = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Kiem tra userAgent co duoc phep truy cap {@code url} theo robots.txt cua domain do khong. */
    public boolean isAllowed(String userAgent, String url) {
        try {
            URI uri = URI.create(url);
            String domainKey = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            List<Rule> rules = cache.computeIfAbsent(domainKey, key -> fetchAndParse(key, userAgent));

            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return isPathAllowed(rules, path);
        } catch (Exception e) {
            // Neu khong fetch/parse duoc robots.txt (loi mang, domain khong ton tai...),
            // mac dinh CHO PHEP de khong chan crawl vi loi ha tang, dung nhu hanh vi
            // khuyen nghi trong dac ta Robots Exclusion Protocol khi khong co robots.txt.
            return true;
        }
    }

    /** Luat co duong dan CU THE (dai) nhat khop se thang (chuan robots.txt). */
    boolean isPathAllowed(List<Rule> rules, String path) {
        Rule best = null;
        for (Rule rule : rules) {
            if (path.startsWith(rule.path())) {
                if (best == null || rule.path().length() > best.path().length()) {
                    best = rule;
                }
            }
        }
        return best == null || best.isAllow();
    }

    /** Package-private: parse noi dung robots.txt truc tiep, khong can fetch mang (dung de unit test). */
    List<Rule> parseForTest(String content, String userAgent) {
        List<Rule> rules = new ArrayList<>();
        parseInto(content, userAgent, rules);
        return rules;
    }

    private List<Rule> fetchAndParse(String domainKey, String userAgent) {
        List<Rule> rules = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(domainKey + "/robots.txt"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                parseInto(response.body(), userAgent, rules);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Khong co robots.txt hoac khong fetch duoc -> coi nhu khong co luat gioi han.
        }
        return rules;
    }

    /**
     * Parse noi dung robots.txt, chi giu lai cac luat ap dung cho
     * {@code userAgent} truyen vao HOAC cho {@code User-agent: *} (mac
     * dinh) neu khong co section rieng cho userAgent do.
     */
    private void parseInto(String content, String userAgent, List<Rule> out) {
        String[] lines = content.split("\r?\n");
        List<Rule> wildcardRules = new ArrayList<>();
        List<Rule> specificRules = new ArrayList<>();
        boolean inWildcardSection = false;
        boolean inSpecificSection = false;

        for (String rawLine : lines) {
            String line = rawLine.split("#", 2)[0].trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();

            switch (key) {
                case "user-agent" -> {
                    inWildcardSection = value.equals("*");
                    inSpecificSection = value.equalsIgnoreCase(userAgent);
                }
                case "disallow" -> {
                    if (!value.isEmpty()) {
                        if (inSpecificSection) {
                            specificRules.add(new Rule(value, false));
                        } else if (inWildcardSection) {
                            wildcardRules.add(new Rule(value, false));
                        }
                    }
                }
                case "allow" -> {
                    if (!value.isEmpty()) {
                        if (inSpecificSection) {
                            specificRules.add(new Rule(value, true));
                        } else if (inWildcardSection) {
                            wildcardRules.add(new Rule(value, true));
                        }
                    }
                }
                default -> {
                    // bo qua Crawl-delay, Sitemap, ... (khong can cho de bai)
                }
            }
        }

        // Neu co section rieng cho userAgent, no thay the hoan toan section "*".
        out.addAll(specificRules.isEmpty() ? wildcardRules : specificRules);
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        RobotsTxtParser parser = new RobotsTxtParser();
        String ua = "VnSearchBot";
        String[] testUrls = {
                "https://vnexpress.net/",
                "https://vnexpress.net/tin-tuc/khoa-hoc",
                "https://vnexpress.net/wp-admin/"
        };
        for (String url : testUrls) {
            System.out.println("isAllowed(" + url + ") = " + parser.isAllowed(ua, url));
        }
    }
}
