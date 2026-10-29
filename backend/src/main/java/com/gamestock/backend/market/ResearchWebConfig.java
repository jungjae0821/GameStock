package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.List;

@Configuration
public class ResearchWebConfig implements WebMvcConfigurer {
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for(var converter:converters) if(converter instanceof MappingJackson2HttpMessageConverter jackson) {
            ObjectMapper strict=strictMapper(jackson.getObjectMapper());
            for(Class<?> type:List.of(QuantProjectService.Save.class,QuantProjectService.Source.class,
                QuantProjectService.BacktestRequest.class,ResearchController.State.class,ResearchController.ChartRequest.class,DiscussionService.Write.class))
                jackson.registerObjectMappersForType(type,mapping->mapping.put(MediaType.APPLICATION_JSON,strict));
        }
    }
    static ObjectMapper strictMapper(ObjectMapper mapper) {
        return mapper.copy().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
}
