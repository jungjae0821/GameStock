package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link NewsExcerptService}에서 분리한 원문 읽기. Google News 기사 토큰을 언론사 주소로 풀고,
 * 언론사 페이지를 내부망 주소인지 확인하며 읽어 문자셋에 맞게 문자열로 돌려준다.
 * Spring 빈이 아니며 서비스가 만들어 HTTP 클라이언트를 함께 쓴다.
 */
final class NewsPageFetcher {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final Pattern SIGNATURE = Pattern.compile("data-n-a-sg=\"([^\"]+)\"");
    private static final Pattern TIMESTAMP = Pattern.compile("data-n-a-ts=\"(\\d+)\"");
    private static final Pattern META_CHARSET = Pattern.compile("<meta[^>]+charset=[\"']?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /* Google News 기사 페이지는 같은 도메인 안에서 한 번 리다이렉트한다. 주소가 고정이라 자동으로 따라간다. */
    private final HttpClient google = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    NewsPageFetcher(ObjectMapper json) {
        this.json = json;
    }

    /** Google News 기사 토큰을 언론사 주소로 푼다. 기사 페이지의 서명으로 batchexecute를 한 번 호출한다. */
    String resolveGoogleNews(String articleId) throws Exception {
        String page = send(HttpRequest.newBuilder(URI.create("https://news.google.com/rss/articles/" + articleId))
                .timeout(Duration.ofSeconds(8)).header("User-Agent", USER_AGENT).GET().build());
        Matcher signature = SIGNATURE.matcher(page);
        Matcher timestamp = TIMESTAMP.matcher(page);
        if (!signature.find() || !timestamp.find()) throw new IllegalStateException("Google News 서명을 찾지 못했습니다.");

        List<Object> request = new ArrayList<>();
        request.add("garturlreq");
        request.add(json.readValue("[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null,null,null,null,0,1],"
                + "\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0]", List.class));
        request.add(articleId);
        request.add(Long.parseLong(timestamp.group(1)));
        request.add(signature.group(1));
        String inner = json.writeValueAsString(request);
        String freq = json.writeValueAsString(List.of(List.of(java.util.Arrays.asList("Fbv4je", inner, null, "generic"))));

        String body = send(HttpRequest.newBuilder(URI.create("https://news.google.com/_/DotsSplashUi/data/batchexecute"))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString("f.req=" + URLEncoder.encode(freq, StandardCharsets.UTF_8)))
                .build());
        String[] chunks = body.split("\n\n", 3);
        if (chunks.length < 2) throw new IllegalStateException("Google News 응답 형식이 바뀌었습니다.");
        JsonNode payload = json.readTree(json.readTree(chunks[1]).path(0).path(2).asText());
        String url = payload.path(1).asText("");
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw new IllegalStateException("원문 주소가 없습니다.");
        return url;
    }

    private String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = google.send(request, BoundedHttpBody.utf8(3_000_000));
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        return response.body();
    }

    /** 언론사 페이지를 읽는다. 리다이렉트는 직접 따라가며 매번 내부망 주소가 아닌지 확인한다. */
    String fetchPage(String url) throws Exception {
        URI target = URI.create(url);
        for (int hop = 0; hop < 4; hop++) {
            requirePublicHost(target);
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.6")
                    .GET().build(), BoundedHttpBody.bytes(3_000_000));
            int status = response.statusCode();
            if (status / 100 == 3) {
                String location = response.headers().firstValue("Location").orElseThrow();
                target = target.resolve(location);
                continue;
            }
            if (status / 100 != 2) throw new IllegalStateException("HTTP " + status);
            byte[] bytes = response.body();
            return new String(bytes, charsetOf(response.headers().firstValue("Content-Type").orElse(""), bytes));
        }
        throw new IllegalStateException("리다이렉트가 너무 많습니다.");
    }

    private static void requirePublicHost(URI uri) throws Exception {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) || uri.getHost() == null || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) {
            throw new IllegalStateException("지원하지 않는 주소입니다.");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (!PublicNetworkAddress.allowed(address)) {
                throw new IllegalStateException("내부망 주소는 읽지 않습니다.");
            }
        }
    }

    private static Charset charsetOf(String contentType, byte[] bytes) {
        Matcher header = Pattern.compile("charset=([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
        String name = header.find() ? header.group(1) : null;
        if (name == null) {
            Matcher meta = META_CHARSET.matcher(new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1));
            if (meta.find()) name = meta.group(1);
        }
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (Exception unsupported) {
            return StandardCharsets.UTF_8;
        }
    }
}
