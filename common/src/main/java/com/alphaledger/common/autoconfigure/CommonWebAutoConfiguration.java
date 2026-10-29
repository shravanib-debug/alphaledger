package com.alphaledger.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

import com.alphaledger.common.error.GlobalExceptionHandler;
import com.alphaledger.common.web.CorrelationIdFilter;
import com.alphaledger.common.web.SqlDateJsonModule;

/**
 * Registers the correlation ID filter, the standard exception handler and ISO dates for SQL DATE values in servlet
 * services.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /** Picked up by Spring Boot's Jackson auto-configuration, like any JacksonModule bean. */
    @Bean
    @ConditionalOnMissingBean
    public SqlDateJsonModule sqlDateJsonModule() {
        return new SqlDateJsonModule();
    }
}
