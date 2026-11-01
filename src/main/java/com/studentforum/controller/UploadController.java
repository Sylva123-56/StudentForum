package com.studentforum.controller;

import com.studentforum.service.UploadService;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class UploadController {
    private final UploadService uploads;

    public UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/api/uploads")
    public Map<String,String> upload(Authentication auth,@RequestParam MultipartFile file) throws IOException {
        return uploads.upload(auth,file);
    }

    @PostMapping("/api/attachments")
    public Map<String,String> attachment(Authentication auth,@RequestParam MultipartFile file) throws IOException {
        return uploads.attachment(auth,file);
    }
}
