package com.gamestock.backend.market;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Observe the existing HTTP contract; the health check must not keep simulations at full speed. */
@Configuration
class MarketActivityWebConfig implements WebMvcConfigurer {
    private final MarketService market;
    MarketActivityWebConfig(MarketService market){this.market=market;}
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                market.recordHumanActivity();return true;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/health","/api/health/**","/api/market-metrics");
    }
}
