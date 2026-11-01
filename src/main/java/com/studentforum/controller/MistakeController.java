package com.studentforum.controller;

import com.studentforum.service.MistakeService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mistakes")
public class MistakeController {
    private final MistakeService mistakeService;

    public MistakeController(MistakeService mistakeService) {
        this.mistakeService = mistakeService;
    }

    @GetMapping
    public Map<String,Object> list(Authentication auth,@RequestParam(defaultValue="mine") String scope,@RequestParam(defaultValue="") String subject,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="false") boolean due,@RequestParam(defaultValue="1") int page) {
        return mistakeService.list(auth,scope,subject,q,due,page);
    }

    @GetMapping("/{id}")
    public Map<String,Object> detail(Authentication auth,@PathVariable long id) {
        return mistakeService.detail(auth,id);
    }

    @PostMapping
    public Map<String,Object> create(Authentication auth,@RequestBody Map<String,Object> input) {
        return mistakeService.create(auth,input);
    }

    @PatchMapping("/{id}")
    public void edit(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        mistakeService.edit(auth,id,input);
    }

    @PostMapping("/{id}/review")
    public void review(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        mistakeService.review(auth,id,input);
    }

    @GetMapping("/groups")
    public List<Map<String,Object>> groups(Authentication auth) {
        return mistakeService.groups(auth);
    }

    @GetMapping("/stats")
    public Map<String,Object> stats(Authentication auth) {
        return mistakeService.stats(auth);
    }

    @PostMapping("/review-plan")
    public Map<String,Object> plan(Authentication auth,@RequestBody Map<String,Object> input) {
        return mistakeService.plan(auth,input);
    }
}
