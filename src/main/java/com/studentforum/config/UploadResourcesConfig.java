package com.studentforum.config;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class UploadResourcesConfig implements WebMvcConfigurer {
    private final String path;

    public UploadResourcesConfig(@Value("${forum.uploads}") String path) {
        this.path = path;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(Path.of(path).toAbsolutePath().normalize().toUri().toString());
    }
}
