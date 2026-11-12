package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * 게임사 공식 채널의 글을 "공식 발표"로 수집한다. 지금 받는 곳은 세 군데다.
 * <ul>
 *   <li>니케: 한국 공식 사이트(nikke-kr.com/news.html)의 공지사항 탭. 사이트가 Level Infinite
 *       커뮤니티 서버의 공개 JSON API로 목록을 받으므로 그 API를 그대로 호출한다.</li>
 *   <li>우마무스메 프리티 더비: 다음 공식 카페(cafe.daum.net/umamusume-kor)의 공지사항·업데이트
 *       게시판. 모바일 게시판 페이지가 글 목록을 스크립트 변수로 내려 주므로 그것을 읽고,
 *       본문은 글 페이지에서 뽑는다.</li>
 *   <li>블루 아카이브: 넥슨 공식 포럼(forum.nexon.com/bluearchive)의 공지사항·업데이트 게시판.
 *       포럼 화면이 쓰는 공개 JSON API로 목록과 글 본문을 받는다.</li>
 * </ul>
 *
 * 공식 글은 언론 기사와 달리 게임 이름이 제목에 없어도 해당 게임의 글이므로 관련성 필터를
 * 거치지 않고, 주가에는 {@link NewsValuation}이 {@link #WEIGHT}배로 반영한다. 출처별로 처음
 * 수집할 때 이미 올라와 있던 글은 목록에만 넣고 주가에는 반영하지 않는다.
 *
 * HTTP 수집은 {@link OfficialNoticeFetcher}, 응답 해석은 {@link OfficialNoticeParser},
 * DB 저장은 {@link OfficialNoticeStore}가 맡고 이 클래스는 일정과 출처별 실패 격리만 한다.
 */
@Service
@ConfigurationProperties(prefix = "gamestock.news.official")
public class OfficialNoticeService {
    private static final Logger log = LoggerFactory.getLogger(OfficialNoticeService.class);

    static final String SOURCE = "공식 발표";
    /** 공식 발표가 같은 크기의 언론 기사보다 주가를 얼마나 더 움직이는지. */
    static final double WEIGHT = 1.5;

    static final String NIKKE_CODE = "GOV";
    static final String NIKKE_DETAIL = "https://nikke-kr.com/newsdetail.html?content_id=";

    static final String UMA_CODE = "UMA";
    static final String UMA_CAFE = "https://cafe.daum.net/umamusume-kor/";
    /** 공지사항(Z4os)과 업데이트(ZBhu) 게시판. */
    static final List<String> UMA_BOARDS = List.of("Z4os", "ZBhu");

    static final String BA_CODE = "BA";
    static final String BA_FORUM = "https://forum.nexon.com/bluearchive/";
    /** 공지사항(1018)과 업데이트(1076) 게시판. */
    static final List<String> BA_BOARDS = List.of("1018", "1076");

    record Notice(String id, String title, String body, Instant publishedAt, String link) {
        Notice withBody(String body) { return new Notice(id, title, body, publishedAt, link); }
    }

    private final ApplicationEventPublisher events;
    private final OfficialNoticeFetcher fetcher = new OfficialNoticeFetcher();
    private final OfficialNoticeStore store;

    private boolean enabled;

    public OfficialNoticeService(JdbcTemplate jdbc, NewsFeedService newsFeed,
                                 PlatformTransactionManager transactions, ApplicationEventPublisher events) {
        this.store = new OfficialNoticeStore(jdbc, newsFeed, new TransactionTemplate(transactions));
        this.events = events;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void fetchOnStartup() {
        fetch();
    }

    /* 시작할 때 한 번 받으므로 첫 예약 실행은 주기 하나 뒤에 돈다. */
    @Scheduled(fixedDelayString = "${gamestock.news.official.refresh-ms:3600000}",
            initialDelayString = "${gamestock.news.official.refresh-ms:3600000}")
    public void fetchScheduled() {
        fetch();
    }

    private void fetch() {
        if (!enabled) return;
        // 비공개 API나 카페 화면은 예고 없이 바뀔 수 있다. 한쪽이 깨져도 다른 쪽과
        // 언론 기사 수집에는 영향이 없도록 출처마다 따로 끝낸다.
        try {
            List<Notice> notices = fetcher.fetchNikke();
            log.info("니케 공식 발표 수집 완료: {}개 중 새 글 {}개", notices.size(), save(NIKKE_CODE, NIKKE_DETAIL, notices));
        } catch (Exception error) {
            log.warn("니케 공식 발표 수집 실패", error);
        }
        try {
            List<Notice> notices = fetcher.fetchUmamusume();
            log.info("우마무스메 공식 발표 수집 완료: {}개 중 새 글 {}개", notices.size(), save(UMA_CODE, UMA_CAFE, notices));
        } catch (Exception error) {
            log.warn("우마무스메 공식 발표 수집 실패", error);
        }
        try {
            List<Notice> notices = fetcher.fetchBlueArchive();
            log.info("블루 아카이브 공식 발표 수집 완료: {}개 중 새 글 {}개", notices.size(), save(BA_CODE, BA_FORUM, notices));
        } catch (Exception error) {
            log.warn("블루 아카이브 공식 발표 수집 실패", error);
        }
        events.publishEvent(new NewsChangedEvent());
    }

    /**
     * 원문 주소로 기존 행을 찾아 고친다. 공지는 게시 뒤에도 제목이 바뀌므로("…(10/29 내용 업데이트)")
     * 제목이 아니라 원문 주소로 같은 글을 알아본다. linkPrefix는 출처의 원문 주소 앞부분으로,
     * 그 출처의 글을 처음 받는지 판단하는 데 쓴다. 반환값은 새로 넣은 글 수.
     */
    int save(String stockCode, String linkPrefix, List<Notice> notices) {
        return store.save(stockCode, linkPrefix, notices);
    }

    /** 원문 주소로 공식 발표인지 판단한다. 가치 계산 표에는 출처 열이 없어 설명의 주소로 알아본다. */
    static boolean isOfficialLink(String link) {
        return link != null && (link.startsWith(NIKKE_DETAIL) || link.startsWith(UMA_CAFE) || link.startsWith(BA_FORUM));
    }

    // 아래는 테스트가 이 클래스 이름으로 부르는 해석 함수들이다. 구현은 OfficialNoticeParser에 있다.

    static List<Notice> parseNikke(String response) throws Exception {
        return OfficialNoticeParser.parseNikke(response);
    }

    static List<Notice> parseCafeBoard(String html, String board, Instant now) {
        return OfficialNoticeParser.parseCafeBoard(html, board, now);
    }

    static Instant cafeTime(String value, Instant now) {
        return OfficialNoticeParser.cafeTime(value, now);
    }

    static String cafeArticleBody(String html) {
        return OfficialNoticeParser.cafeArticleBody(html);
    }

    static List<Notice> parseForumBoard(String response, String board, Instant now) throws Exception {
        return OfficialNoticeParser.parseForumBoard(response, board, now);
    }

    static String forumThreadBody(String response) throws Exception {
        return OfficialNoticeParser.forumThreadBody(response);
    }

    static List<String> paragraphs(String body) {
        return OfficialNoticeParser.paragraphs(body);
    }
}
