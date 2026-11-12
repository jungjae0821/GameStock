package com.gamestock.backend.market;

import com.gamestock.backend.market.OfficialNoticeService.Notice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link OfficialNoticeService}에서 분리한 HTTP 수집 부분. 출처마다 목록과 본문을 받아
 * {@link OfficialNoticeParser}로 해석한다. 저장과 실패 처리(출처별 try/catch)는 서비스에 남긴다.
 * Spring 빈이 아니며 서비스가 만들어 HttpClient 하나를 계속 쓴다. 로그 이름은 서비스 것을 그대로 쓴다.
 */
final class OfficialNoticeFetcher {
    private static final Logger log = LoggerFactory.getLogger(OfficialNoticeService.class);

    private static final String NIKKE_API =
            "https://na-community.playerinfinite.com/api/gpts.information_feeds_svr.InformationFeedsSvr/GetContentByLabel";
    /* 사이트의 NEWS 칼럼(309) 아래 "공지사항" 탭(892). 사이트 개편으로 바뀌면 목록이 비어 돌아온다. */
    private static final String NIKKE_REQUEST =
            "{\"language\":[\"ko\"],\"gameid\":\"16\",\"offset\":0,\"get_num\":10,\"ext_info_type_list\":[0,1,2],"
                    + "\"secondary_label_id\":892,\"primary_label_id\":309}";

    private static final String UMA_MOBILE = "https://m.cafe.daum.net/umamusume-kor/";
    /* 게시판마다 최신 몇 개까지 본문을 읽을지. 1시간에 한 번이라 카페에 주는 부담은 작다. */
    private static final int UMA_PER_BOARD = 10;
    private static final String BROWSER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36";

    private static final String BA_API = "https://forum.nexon.com/api/v1/";
    private static final int BA_PER_BOARD = 10;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    List<Notice> fetchNikke() throws Exception {
        return OfficialNoticeParser.parseNikke(post(NIKKE_API, NIKKE_REQUEST));
    }

    List<Notice> fetchUmamusume() throws Exception {
        List<Notice> notices = new ArrayList<>();
        Instant now = Instant.now();
        for (String board : OfficialNoticeService.UMA_BOARDS) {
            List<Notice> listed = OfficialNoticeParser.parseCafeBoard(get(UMA_MOBILE + board), board, now);
            for (Notice notice : listed.subList(0, Math.min(UMA_PER_BOARD, listed.size()))) {
                String body = "";
                try {
                    body = OfficialNoticeParser.cafeArticleBody(get(UMA_MOBILE + board + "/" + notice.id().substring(board.length() + 1)));
                } catch (Exception error) {
                    log.debug("카페 글 본문을 읽지 못했습니다: {}", notice.link(), error);
                }
                notices.add(notice.withBody(body));
                Thread.sleep(500);
            }
        }
        return notices;
    }

    List<Notice> fetchBlueArchive() throws Exception {
        List<Notice> notices = new ArrayList<>();
        Instant now = Instant.now();
        for (String board : OfficialNoticeService.BA_BOARDS) {
            List<Notice> listed = OfficialNoticeParser.parseForumBoard(get(BA_API + "board/" + board
                    + "/threads?alias=bluearchive&paginationType=PAGING&pageSize=" + BA_PER_BOARD
                    + "&pageNo=1&blockSize=5&hideType=WEB"), board, now);
            for (Notice notice : listed) {
                String body = "";
                try {
                    String thread = notice.id().substring(board.length() + 1);
                    body = OfficialNoticeParser.forumThreadBody(get(BA_API + "thread/" + thread + "?alias=bluearchive"));
                } catch (Exception error) {
                    log.debug("포럼 글 본문을 읽지 못했습니다: {}", notice.link(), error);
                }
                notices.add(notice.withBody(body));
                Thread.sleep(500);
            }
        }
        return notices;
    }

    private String post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json;charset=utf-8")
                .header("User-Agent", "GameStock/1.0 news reader")
                .header("X-AreaId", "na")
                .header("X-GameId", "16")
                .header("X-Language", "ko")
                .header("X-Source", "pc_web")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", BROWSER_AGENT)
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .GET()
                .build();
        return send(request);
    }

    private String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, BoundedHttpBody.utf8(2_000_000));
        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + request.uri());
        return response.body();
    }
}
