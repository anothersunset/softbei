package com.zhiqian.ops.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * B/S 架构跨域配置（便于前端分离部署调试）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final ApiTokenInterceptor apiTokenInterceptor;

    public CorsConfig(ApiTokenInterceptor apiTokenInterceptor) {
        this.apiTokenInterceptor = apiTokenInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
        registry.addMapping("/mcp/**")
                .allowedOriginPatterns("*")
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("*");
        registry.addMapping("/actuator/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiTokenInterceptor)
                .addPathPatterns("/api/**", "/mcp/**", "/actuator/**");
    }
}
