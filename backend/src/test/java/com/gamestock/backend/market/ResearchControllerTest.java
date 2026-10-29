package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamestock.backend.auth.AuthService;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResearchControllerTest {
    AuthService auth=mock(AuthService.class);
    DiscussionService discussions=mock(DiscussionService.class);
    StockTalkService talk=mock(StockTalkService.class);
    QuantProjectService projects=mock(QuantProjectService.class);
    ResearchChartService charts=mock(ResearchChartService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new ResearchController(projects,charts,auth),new DiscussionController(discussions,auth),new StockTalkController(talk,auth))
            .addFilters(new ResearchBodyFilter()).setMessageConverters(new MappingJackson2HttpMessageConverter(ResearchWebConfig.strictMapper(new ObjectMapper()))).build();
        when(auth.requireUser("Bearer user")).thenReturn(new AuthService.LoginUser(7,"alice",null,null,0,0,false,"USER"));
        when(auth.requireUser(isNull())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"로그인이 필요합니다."));
    }
    @Test void publicReadsDoNotNeedTokenButWritesDo() throws Exception {
        when(discussions.list("GI",null,0L,50)).thenReturn(new DiscussionService.Page(List.of(),null,false));
        mvc.perform(get("/api/stocks/GI/community/chat").param("after","0")).andExpect(status().isOk());
        mvc.perform(post("/api/stocks/GI/community/chat").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hello\"}")).andExpect(status().isUnauthorized());
        verify(discussions,never()).create(anyLong(),anyString(),any());
    }
    @Test void credentialDeterminesProjectOwner() throws Exception {
        when(projects.list(7)).thenReturn(List.of());
        mvc.perform(get("/api/quant/projects").header("Authorization","Bearer user").param("userId","999")).andExpect(status().isOk());
        verify(projects).list(7);
    }
    @Test void fractionalLimitsAndUnknownFieldsCannotBeSilentlyAccepted() throws Exception {
        String base="{\"name\":\"test\",\"stockCode\":\"GI\",\"range\":\"1h\",\"code\":{\"language\":\"SNSCRIPT\",\"source\":\"buy when true quantity 1\"},\"maxOrders\":1.5,\"maxNotional\":100}";
        mvc.perform(post("/api/quant/projects").header("Authorization","Bearer user").contentType(MediaType.APPLICATION_JSON).content(base)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/stocks/GI/community/chat").header("Authorization","Bearer user").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\",\"userId\":999}")).andExpect(status().isBadRequest());
        verifyNoInteractions(projects,discussions);
    }
    @Test void oversizedEditorPayloadIsRejectedBeforeParsing() throws Exception {
        mvc.perform(post("/api/quant/compile").header("Authorization","Bearer user").contentType(MediaType.APPLICATION_JSON)
            .content("x".repeat(ResearchBodyFilter.LIMIT+1))).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(projects,auth);
    }
    @Test void deleteUsesVerifiedRole() throws Exception {
        mvc.perform(delete("/api/stocks/GI/community/entries/42").header("Authorization","Bearer user").param("admin","true"))
            .andExpect(status().isNoContent());
        verify(discussions).delete(7,false,"GI",42);
    }

    @Test void discussionUsesUpstreamRoutesAndOldDuplicateRouteIsGone() throws Exception {
        when(talk.list("GI",0,0)).thenReturn(new StockTalkService.Page(List.of(),false,300,7));
        mvc.perform(get("/api/stocks/GI/talk")).andExpect(status().isOk())
            .andExpect(jsonPath("$.maxLength").value(300)).andExpect(jsonPath("$.retentionDays").value(7));
        mvc.perform(post("/api/stocks/GI/talk").header("Authorization","Bearer user")
            .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hello\"}")).andExpect(status().isOk());
        verify(talk).write(7,"alice","GI",new StockTalkService.Write("hello",null));
        mvc.perform(get("/api/stocks/GI/community/posts")).andExpect(status().isNotFound());
        verifyNoInteractions(discussions);
    }
}
