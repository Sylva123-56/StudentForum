package com.studentforum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Configuration;

@RestController
public class UploadController {
    private final Path directory;
    private final ForumService service;
    UploadController(@Value("${forum.uploads}") String path,ForumService service) { this.directory=Path.of(path).toAbsolutePath().normalize(); this.service=service; }
    @PostMapping("/api/uploads") Map<String,String> upload(Authentication auth,@RequestParam MultipartFile file) throws IOException {
        service.writable(auth);
        if (file.isEmpty() || file.getSize()>5_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"图片不能超过 5MB");
        byte[] bytes=file.getBytes(); String extension;
        if (bytes.length>3 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255) extension="jpg";
        else if (bytes.length>8 && (bytes[0]&255)==137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71 && bytes[4]==13 && bytes[5]==10 && bytes[6]==26 && bytes[7]==10) extension="png";
        else if (bytes.length>12 && bytes[0]=='R' && bytes[1]=='I' && bytes[2]=='F' && bytes[3]=='F' && bytes[8]=='W' && bytes[9]=='E' && bytes[10]=='B' && bytes[11]=='P') extension="webp";
        else throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"仅支持 JPG、PNG 或 WebP 图片");
        Files.createDirectories(directory);
        String name=UUID.randomUUID()+"."+extension; Files.write(directory.resolve(name),bytes);
        return Map.of("path","/uploads/"+name);
    }
    @Configuration static class UploadResources implements WebMvcConfigurer {
        private final String path;
        UploadResources(@Value("${forum.uploads}") String path) { this.path=path; }
        @Override public void addResourceHandlers(ResourceHandlerRegistry registry) { registry.addResourceHandler("/uploads/**").addResourceLocations(Path.of(path).toAbsolutePath().normalize().toUri().toString()); }
    }
}
