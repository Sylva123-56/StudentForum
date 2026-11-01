package com.studentforum.controller;

import com.studentforum.service.MistakeImageService;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/mistakes")
public class MistakeImageController {
    private final MistakeImageService mistakeImageService;

    public MistakeImageController(MistakeImageService mistakeImageService) {
        this.mistakeImageService = mistakeImageService;
    }

    @PostMapping("/images")
    public Map<String,Object> upload(Authentication auth,@RequestParam MultipartFile file) throws IOException {
        return mistakeImageService.upload(auth,file);
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<FileSystemResource> preview(Authentication auth,@PathVariable long imageId) throws IOException {
        return mistakeImageService.preview(auth,imageId);
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<FileSystemResource> image(Authentication auth,@PathVariable long id) throws IOException {
        return mistakeImageService.image(auth,id);
    }
}
