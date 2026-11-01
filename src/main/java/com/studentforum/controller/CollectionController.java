package com.studentforum.controller;

import com.studentforum.service.CollectionService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CollectionController {
    private final CollectionService collectionService;

    public CollectionController(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @GetMapping("/{kind:collections|columns}")
    public Map<String,Object> list(Authentication auth,@PathVariable String kind,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="false") boolean mine,@RequestParam(defaultValue="false") boolean featured,@RequestParam(defaultValue="false") boolean favorite,@RequestParam(defaultValue="false") boolean following,@RequestParam(defaultValue="1") int page) {
        return collectionService.list(auth,kind,q,mine,featured,favorite,following,page);
    }

    @PostMapping("/{kind:collections|columns}")
    public Map<String,Object> create(Authentication auth,@PathVariable String kind,@RequestBody Map<String,Object> input) {
        return collectionService.create(auth,kind,input);
    }

    @PatchMapping("/{kind:collections|columns}/{id}")
    public void edit(Authentication auth,@PathVariable String kind,@PathVariable long id,@RequestBody Map<String,Object> input) {
        collectionService.edit(auth,kind,id,input);
    }

    @GetMapping("/collections/{id}")
    public Map<String,Object> detail(Authentication auth,@PathVariable long id) {
        return collectionService.detail(auth,id);
    }

    @PostMapping("/collections/{id}/items")
    public void add(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        collectionService.add(auth,id,input);
    }

    @PatchMapping("/collections/{id}/items/{itemId}")
    public void reorder(Authentication auth,@PathVariable long id,@PathVariable long itemId,@RequestBody Map<String,Object> input) {
        collectionService.reorder(auth,id,itemId,input);
    }

    @DeleteMapping("/collections/{id}/items/{itemId}")
    public void remove(Authentication auth,@PathVariable long id,@PathVariable long itemId) {
        collectionService.remove(auth,id,itemId);
    }

    @PostMapping("/collections/{id}/{action:follow|favorite}")
    public void toggle(Authentication auth,@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> input) {
        collectionService.toggle(auth,id,action,input);
    }

    @GetMapping("/columns/{id}")
    public Map<String,Object> column(Authentication auth,@PathVariable long id) {
        return collectionService.column(auth,id);
    }

    @PostMapping("/columns/{id}/episodes")
    public Map<String,Object> episode(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        return collectionService.episode(auth,id,input);
    }

    @PatchMapping("/columns/{id}/episodes/{episodeId}")
    public void editEpisode(Authentication auth,@PathVariable long id,@PathVariable long episodeId,@RequestBody Map<String,Object> input) {
        collectionService.editEpisode(auth,id,episodeId,input);
    }

    @GetMapping("/admin/collections")
    public Map<String,Object> admin(Authentication auth,@RequestParam(defaultValue="1") int page) {
        return collectionService.admin(auth,page);
    }

    @PatchMapping("/admin/collections/{id}")
    public void recommend(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        collectionService.recommend(auth,id,input);
    }
}
