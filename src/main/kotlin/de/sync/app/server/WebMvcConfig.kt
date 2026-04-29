package de.sync.app.server

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(private val tokenAuthInterceptor: TokenAuthInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(tokenAuthInterceptor)
            .addPathPatterns("/**")
            .excludePathPatterns("/auth/login", "/auth/register")
    }
}
