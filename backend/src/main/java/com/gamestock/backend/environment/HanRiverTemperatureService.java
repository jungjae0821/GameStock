package com.gamestock.backend.environment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the current Seonyu (선유) reading from the public Han River temperature
 * page. The page is rendered by Next.js and includes both a small HTML table and
 * the source rows in its server-rendered payload, so we prefer the table and
 * fall back to the payload when the markup changes.
 */
@Service
public class HanRiverTemperatureService {
    private static final Logger log = LoggerFactory.getLogger(HanRiverTemperatureService.class);
    private static final String DEFAULT_SITE_URL = "https://xn--939at9l4tgt7l6ps.com/ko";
    private static final String TARGET_LOCATION = "선유";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Current station row as rendered on the site. */
    private static final Pattern TABLE_ROW = Pattern.compile(
            "(?is)<tr[^>]*>\\s*<th[^>]*>\\s*선유\\s*</th>\\s*"
                    + "<td[^>]*>\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*(?:°C|℃|C)\\s*</td>\\s*"
                    + "<td[^>]*>\\s*([^<]+?)\\s*</td>\\s*</tr>");

    /**
     * Source rows in the Next.js flight payload. Quotes are optionally preceded
     * by a backslash because the payload is embedded inside a JavaScript string.
     */
    private static final Pattern PAYLOAD_ROW = Pattern.compile(
            "\\\\?\"YMD\\\\?\":\\\\?\"(\\d{8})\\\\?\","
                    + "\\\\?\"HR\\\\?\":\\\\?\"([^\"\\\\]+)\\\\?\","
                    + "\\\\?\"MSRSTN_NM\\\\?\":\\\\?\"선유\\\\?\","
                    + "\\\\?\"WATT\\\\?\":\\\\?\"([-+]?\\d+(?:\\.\\d+)?)");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${gamestock.hangang.enabled:true}")
    private boolean enabled;
    @Value("${gamestock.hangang.site-url:" + DEFAULT_SITE_URL + "}")
    private String siteUrl;

    private volatile TemperatureReading cached;
    private volatile Instant lastAttempt = Instant.EPOCH;

    public synchronized TemperatureReading current() {
        if (!enabled) return unavailable("한강 수온 조회가 비활성화되어 있습니다.");
        // Keep the endpoint responsive while still allowing an immediate first read.
        if (cached == null && Duration.between(lastAttempt, Instant.now()).toSeconds() >= 30) refresh();
        return cached == null ? unavailable("한강 수온을 아직 조회하지 못했습니다.") : cached;
    }

    @Scheduled(fixedDelayString = "${gamestock.hangang.refresh-ms:1800000}",
            initialDelayString = "${gamestock.hangang.initial-delay-ms:60000}")
    public synchronized void refreshScheduled() {
        if (enabled) refresh();
    }

    private void refresh() {
        lastAttempt = Instant.now();
        try {
            String url = siteUrl == null || siteUrl.isBlank() ? DEFAULT_SITE_URL : siteUrl.trim();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7")
                    .header("User-Agent", "GameStock/1.0 Han River temperature reader")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());

            ReadingCandidate selected = parseTable(response.body());
            if (selected == null) selected = parsePayload(response.body());
            if (selected == null) throw new IllegalStateException("사이트 응답에서 선유 측정 자료를 찾지 못했습니다.");

            cached = new TemperatureReading(true, selected.temperature(), TARGET_LOCATION,
                    selected.measuredAt(), Instant.now().toString(), url,
                    "한강 수온 사이트 · 선유 측정소");
        } catch (Exception error) {
            log.warn("한강 수온 사이트 조회 실패: {}", error.getMessage());
            // Preserve the last good value so a temporary site/network outage does
            // not make the menu flicker to an empty state.
            if (cached != null) cached = cached.withMessage("최근 조회값 · 수온 사이트 일시 오류");
        }
    }

    private ReadingCandidate parseTable(String html) {
        Matcher matcher = TABLE_ROW.matcher(html);
        if (!matcher.find()) return null;
        try {
            double temperature = Double.parseDouble(matcher.group(1));
            if (!validTemperature(temperature)) return null;
            String displayedTime = decodeHtml(matcher.group(2).trim());
            return new ReadingCandidate(temperature, displayedTime, displayedTime);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private ReadingCandidate parsePayload(String html) {
        Matcher matcher = PAYLOAD_ROW.matcher(html);
        ReadingCandidate selected = null;
        while (matcher.find()) {
            try {
                double temperature = Double.parseDouble(matcher.group(3));
                if (!validTemperature(temperature)) continue;
                ReadingCandidate candidate = fromSourceTimestamp(temperature, matcher.group(1), matcher.group(2));
                if (candidate != null && (selected == null || candidate.sortKey().compareTo(selected.sortKey()) > 0)) {
                    selected = candidate;
                }
            } catch (NumberFormatException ignored) {
                // Skip malformed source rows and keep looking for a valid reading.
            }
        }
        return selected;
    }

    private ReadingCandidate fromSourceTimestamp(double temperature, String date, String time) {
        try {
            LocalDate day = LocalDate.parse(date, BASIC_DATE);
            String normalizedTime = time.trim();
            LocalTime clock;
            if (normalizedTime.startsWith("24:")) {
                day = day.plusDays(1);
                clock = LocalTime.MIDNIGHT;
            } else {
                clock = LocalTime.parse(normalizedTime, DISPLAY_TIME);
            }
            LocalDateTime timestamp = LocalDateTime.of(day, clock);
            return new ReadingCandidate(temperature,
                    timestamp.format(DISPLAY_DATE) + " · " + timestamp.format(DISPLAY_TIME),
                    timestamp.toString());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private boolean validTemperature(double temperature) {
        return temperature >= -5 && temperature <= 45;
    }

    private String decodeHtml(String value) {
        return value.replace("&middot;", "·").replace("&#183;", "·").replace("&nbsp;", " ");
    }

    private TemperatureReading unavailable(String message) {
        String source = siteUrl == null || siteUrl.isBlank() ? DEFAULT_SITE_URL : siteUrl.trim();
        return new TemperatureReading(false, null, TARGET_LOCATION, null, null, source, message);
    }

    public record TemperatureReading(boolean available, Double temperature, String location,
                                     String measuredAt, String updatedAt, String source, String message) {
        private TemperatureReading withMessage(String nextMessage) {
            return new TemperatureReading(available, temperature, location, measuredAt, updatedAt, source, nextMessage);
        }
    }

    private record ReadingCandidate(double temperature, String measuredAt, String sortKey) { }
}
