package com.studentforum.controller;

import com.studentforum.service.WikiService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WikiController {
    private final WikiService wikiService;

    public WikiController(WikiService wikiService) {
        this.wikiService = wikiService;
    }

    @GetMapping("/wiki/{boardId}")
    public Map<String,Object> list(Authentication auth,@PathVariable long boardId,@RequestParam(defaultValue="") String q) {
        return wikiService.list(auth,boardId,q);
    }

    @PostMapping("/wiki/{boardId}")
    public Map<String,Object> create(Authentication auth,@PathVariable long boardId,@RequestBody Map<String,Object> input) {
        return wikiService.create(auth,boardId,input);
    }

    @PatchMapping("/wiki/{pageId}")
    public void edit(Authentication auth,@PathVariable long pageId,@RequestBody Map<String,Object> input) {
        wikiService.edit(auth,pageId,input);
    }

    @GetMapping("/wiki/pages/{pageId}/revisions")
    public List<Map<String,Object>> history(Authentication auth,@PathVariable long pageId) {
        return wikiService.history(auth,pageId);
    }

    @GetMapping("/admin/wiki")
    public Map<String,Object> admin(Authentication auth,@RequestParam(defaultValue="1") int page) {
        return wikiService.admin(auth,page);
    }

    @PatchMapping("/admin/wiki/{id}")
    public void review(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        wikiService.review(auth,id,input);
    }
}
