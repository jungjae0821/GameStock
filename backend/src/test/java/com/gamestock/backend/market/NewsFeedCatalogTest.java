package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewsFeedCatalogTest {
    @Test
    void searchesFollowLiveCatalogAndRespectExplicitOverrides() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            List<String> codes = List.of("BA", "AK", "LT");
            List<String> names = List.of("블루 아카이브", "명일방주: 엔드필드", "발로란트");
            for (int index = 0; index < codes.size(); index++) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("stock_code")).thenReturn(codes.get(index));
                when(rs.getString("name")).thenReturn(names.get(index));
                mapper.mapRow(rs, index);
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class));

        NewsFeedService service = new NewsFeedService(jdbc);
        service.setFeeds(List.of("BA|https://example.com/custom-rss", "REMOVED|https://example.com/old", "invalid"));
        List<String> feeds = service.resolveFeeds();
        assertEquals(3, feeds.size());
        assertEquals("BA|https://example.com/custom-rss", feeds.get(0));
        assertTrue(URLDecoder.decode(feeds.get(1), StandardCharsets.UTF_8).contains("q=명일방주: 엔드필드&"));
        assertTrue(URLDecoder.decode(feeds.get(2), StandardCharsets.UTF_8).contains("q=발로란트&"));
        assertFalse(feeds.stream().anyMatch(feed -> feed.startsWith("REMOVED|")));
        assertEquals(feeds, service.resolveFeeds(), "refresh must not accumulate duplicate feeds");
    }
}
