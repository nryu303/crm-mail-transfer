package com.crm.config;

import com.crm.interceptor.AuthInterceptor;
import com.crm.interceptor.CsrfInterceptor;
import com.crm.interceptor.MediaAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final CsrfInterceptor csrfInterceptor;
    private final MediaAuthInterceptor mediaAuthInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor,
                        CsrfInterceptor csrfInterceptor,
                        MediaAuthInterceptor mediaAuthInterceptor) {
        this.authInterceptor = authInterceptor;
        this.csrfInterceptor = csrfInterceptor;
        this.mediaAuthInterceptor = mediaAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // CSRF runs first so it can reject state-changing requests before auth work kicks in.
        registry.addInterceptor(csrfInterceptor);
        registry.addInterceptor(authInterceptor).addPathPatterns("/manager/**");
        // /media/** is the public agency dashboard — Basic Auth, no admin session.
        registry.addInterceptor(mediaAuthInterceptor).addPathPatterns("/media/**");
    }
}
