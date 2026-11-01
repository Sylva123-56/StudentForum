package com.studentforum.controller;

import com.studentforum.service.NoteService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notes")
public class NoteController {
    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    @GetMapping
    public Map<String,Object> list(Authentication auth,@RequestParam(defaultValue="false") boolean mine,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String subject,@RequestParam(defaultValue="1") int page) {
        return noteService.list(auth,mine,q,subject,page);
    }

    @GetMapping("/{id}")
    public Map<String,Object> detail(Authentication auth,@PathVariable long id) {
        return noteService.detail(auth,id);
    }

    @PostMapping
    public Map<String,Object> create(Authentication auth,@RequestBody Map<String,Object> input) {
        return noteService.create(auth,input);
    }

    @PatchMapping("/{id}")
    public void edit(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        noteService.edit(auth,id,input);
    }

    @GetMapping("/{id}/revisions")
    public List<Map<String,Object>> revisions(Authentication auth,@PathVariable long id) {
        return noteService.revisions(auth,id);
    }
}
