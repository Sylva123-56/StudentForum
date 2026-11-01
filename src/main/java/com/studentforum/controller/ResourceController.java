package com.studentforum.controller;

import com.studentforum.service.ResourceService;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/api")
public class ResourceController {
    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @GetMapping("/resources")
    public Map<String,Object> list(Authentication auth,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String subject,@RequestParam(defaultValue="") String grade,@RequestParam(defaultValue="") String textbook,@RequestParam(defaultValue="") String exam,@RequestParam(defaultValue="false") boolean mine,@RequestParam(defaultValue="false") boolean favorite,@RequestParam(defaultValue="1") int page) {
        return resourceService.list(auth,q,subject,grade,textbook,exam,mine,favorite,page);
    }

    @PostMapping("/resources/files")
    public Map<String,Object> upload(Authentication auth,@RequestParam MultipartFile file) throws IOException {
        return resourceService.upload(auth,file);
    }

    @PostMapping("/resources")
    public Map<String,Object> create(Authentication auth,@RequestBody Map<String,Object> input) {
        return resourceService.create(auth,input);
    }

    @GetMapping("/resources/{id}")
    public Map<String,Object> detail(Authentication auth,@PathVariable long id) {
        return resourceService.detail(auth,id);
    }

    @PostMapping("/resources/{id}/download")
    public Map<String,Object> download(Authentication auth,@PathVariable long id) {
        return resourceService.download(auth,id);
    }

    @GetMapping("/resources/{id}/files/{fileId}")
    public ResponseEntity<FileSystemResource> file(Authentication auth,@PathVariable long id,@PathVariable long fileId) throws IOException {
        return resourceService.file(auth,id,fileId);
    }

    @PostMapping("/resources/{id}/{action:favorite|like}")
    public void toggle(Authentication auth,@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> input) {
        resourceService.toggle(auth,id,action,input);
    }

    @PostMapping("/resources/{id}/comments")
    public void comment(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        resourceService.comment(auth,id,input);
    }

    @PostMapping("/resources/{id}/report")
    public void report(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        resourceService.report(auth,id,input);
    }

    @GetMapping("/resources/downloads")
    public List<Map<String,Object>> downloads(Authentication auth) {
        return resourceService.downloads(auth);
    }

    @GetMapping("/admin/resources")
    public Map<String,Object> admin(Authentication auth,@RequestParam(defaultValue="1") int page) {
        return resourceService.admin(auth,page);
    }

    @PatchMapping("/admin/resources/{id}")
    public void moderate(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        resourceService.moderate(auth,id,input);
    }

    public static void validateFile(String extension, byte[] bytes) throws IOException {
        ResourceService.validateFile(extension, bytes);
    }
}
