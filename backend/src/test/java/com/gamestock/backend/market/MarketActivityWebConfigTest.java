package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

class MarketActivityWebConfigTest {
    @Configuration @EnableWebMvc static class Config {
        @Bean MarketService market(){return mock(MarketService.class);}
        @Bean MarketActivityWebConfig activity(MarketService market){return new MarketActivityWebConfig(market);}
        @Bean Endpoints endpoints(){return new Endpoints();}
    }
    @RestController static class Endpoints {
        @GetMapping({"/api/health","/api/health/live","/api/stocks"}) String read(){return "ok";}
    }
    @Test void healthChecksCannotKeepTheMarketAwakeButExistingVisitorRequestsCan() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
            var mvc=MockMvcBuilders.webAppContextSetup(context).build();var market=context.getBean(MarketService.class);
            clearInvocations(market); // Ignore Spring's bean lifecycle callback on the mock.
            mvc.perform(get("/api/health")).andExpect(status().isOk());mvc.perform(get("/api/health/live")).andExpect(status().isOk());
            verifyNoInteractions(market);
            mvc.perform(get("/api/stocks")).andExpect(status().isOk());verify(market).recordHumanActivity();
        }
    }
}
