package com.studentforum.controller;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.service.ForumService;
import com.studentforum.service.AccountService;
import com.studentforum.model.SignedInAccount;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ForumController {
    private final ForumMapper mapper;
    private final ForumService service;
    private final AccountService accounts;
    private final HttpSessionSecurityContextRepository contexts;

    public ForumController(ForumMapper mapper, ForumService service, AccountService accounts, HttpSessionSecurityContextRepository contexts) {
        this.mapper = mapper;
        this.service = service;
        this.accounts = accounts;
        this.contexts = contexts;
    }

    private void signIn(long id, String role, HttpServletRequest request, HttpServletResponse response) {
        request.getSession();
        request.changeSessionId();
        var auth = new UsernamePasswordAuthenticationToken(String.valueOf(id), null, List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(org.springframework.security.web.csrf.CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> input, HttpServletRequest request, HttpServletResponse response) {
        SignedInAccount account = accounts.register(input);
        signIn(account.id(), account.role(), request, response);
        return account.profile();
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, String> input, HttpServletRequest request, HttpServletResponse response) {
        SignedInAccount account = accounts.login(input);
        signIn(account.id(), account.role(), request, response);
        return account.profile();
    }

    @PostMapping("/auth/logout")
    public void logout(HttpServletRequest request) {
        request.getSession().invalidate();
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        return service.current(auth);
    }

    @PutMapping("/me/profile")
    public void profile(Authentication auth, @RequestBody Map<String, Object> input) {
        accounts.profile(auth, input);
    }

    @PutMapping("/me/password")
    public void password(Authentication auth, @RequestBody Map<String, String> input) {
        accounts.password(auth, input);
    }

    @GetMapping("/boards")
    public List<Map<String, Object>> boards() {
        return mapper.boards();
    }

    @GetMapping("/tags")
    public List<Map<String, Object>> tags() {
        return mapper.tags();
    }

    @GetMapping("/levels")
    public List<Map<String, Object>> levels() {
        return List.of(Map.of("level", 1, "name", "新生", "min", 0), Map.of("level", 2, "name", "学友", "min", 50), Map.of("level", 3, "name", "学长", "min", 200), Map.of("level", 4, "name", "达人", "min", 500), Map.of("level", 5, "name", "学霸", "min", 1000));
    }

    @GetMapping("/boards/{id}/posts")
    public List<Map<String, Object>> boardPosts(@PathVariable long id, @RequestParam(defaultValue = "latest") String sort, @RequestParam(defaultValue = "1") int page) {
        service.require(mapper.board(id));
        return search(null, id, null, null, null, null, sort, page);
    }

    @GetMapping("/tags/{id}/posts")
    public List<Map<String, Object>> tagPosts(@PathVariable long id, @RequestParam(defaultValue = "1") int page) {
        service.require(mapper.tag(id));
        return search(null, null, id, null, null, null, "latest", page);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam(required = false) String q, @RequestParam(required = false) Long board, @RequestParam(required = false) Long tag, @RequestParam(required = false) String type, @RequestParam(required = false) Boolean solved, @RequestParam(required = false) Boolean featured, @RequestParam(defaultValue = "latest") String sort, @RequestParam(defaultValue = "1") int page) {
        if (page < 1 || page > 10000 || q != null && q.length() > 100 || type != null && !Set.of("question", "discussion", "experience").contains(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索条件无效");
        return mapper.posts(board, tag, type, solved, featured, q == null || q.isBlank() ? null : q.trim(), "hot".equals(sort) ? "hot" : "latest", 21, (page - 1) * 20);
    }

    @GetMapping("/posts/{id}")
    public Map<String, Object> post(@PathVariable long id, Authentication auth) {
        Map<String, Object> post = service.require(mapper.post(id));
        if (!"published".equals(post.get("status")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        mapper.view(id);
        post.put("tags", mapper.postTags(id));
        boolean loggedIn = auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
        post.put("favorited", loggedIn && mapper.isFavorite(service.id(auth), id) > 0);
        return post;
    }

    @PostMapping("/posts")
    public Map<String, Object> publish(Authentication auth, @RequestBody Map<String, Object> input) {
        return service.publish(auth, input);
    }

    @PatchMapping("/posts/{id}")
    @Transactional
    public void edit(Authentication auth, @PathVariable long id, @RequestBody Map<String, String> input) {
        Map<String, Object> post = service.require(mapper.post(id));
        if (service.number(post, "author_id") != service.id(auth) || !"published".equals(post.get("status")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        service.text(input.get("title"), 5, 160);
        service.text(input.get("content"), 10, 10000);
        mapper.editPost(id, input.get("title").trim(), input.get("content").trim());
        service.revision(auth, id, input.get("title").trim(), input.get("content").trim());
    }

    @DeleteMapping("/posts/{id}")
    @Transactional
    public void delete(Authentication auth, @PathVariable long id) {
        Map<String, Object> post = service.require(mapper.post(id));
        if (service.number(post, "author_id") != service.id(auth))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        service.hardDeletePost(id);
    }

    @GetMapping("/posts/{id}/replies")
    public List<Map<String, Object>> replies(@PathVariable long id, @RequestParam(defaultValue = "earliest") String sort) {
        if (!"published".equals(service.require(mapper.post(id)).get("status")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return service.sortedReplies(id, sort);
    }

    @PostMapping("/posts/{id}/replies")
    public Map<String, Object> reply(Authentication auth, @PathVariable long id, @RequestBody Map<String, Object> input) {
        return service.reply(auth, id, input);
    }

    @DeleteMapping("/replies/{id}")
    public void deleteReply(Authentication auth, @PathVariable long id) {
        Map<String, Object> reply = service.require(mapper.reply(id));
        if (service.number(reply, "author_id") != service.id(auth) || service.flag(reply, "is_accepted"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        mapper.deleteReply(id);
    }

    @PostMapping("/posts/{id}/accept/{replyId}")
    public void accept(Authentication auth, @PathVariable long id, @PathVariable long replyId) {
        service.accept(auth, id, replyId);
    }

    @PostMapping("/posts/{id}/favorite")
    public void favorite(Authentication auth, @PathVariable long id) {
        if (!"published".equals(service.require(mapper.post(id)).get("status")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        mapper.favorite(service.id(auth), id);
    }

    @DeleteMapping("/posts/{id}/favorite")
    public void unfavorite(Authentication auth, @PathVariable long id) {
        mapper.unfavorite(service.id(auth), id);
    }

    @GetMapping("/me/favorites")
    public List<Map<String, Object>> favorites(Authentication auth, @RequestParam(defaultValue = "1") int page) {
        return mapper.favorites(service.id(auth), 20, (Math.max(page, 1) - 1) * 20);
    }

    @GetMapping("/me/posts")
    public List<Map<String, Object>> authoredPosts(Authentication auth) {
        return mapper.authoredPosts(service.id(auth), 100);
    }

    @GetMapping("/notifications")
    public Map<String, Object> notifications(Authentication auth, @RequestParam(defaultValue = "1") int page) {
        if (page < 1 || page > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码无效");
        long id = service.id(auth);
        List<Map<String, Object>> results = mapper.notifications(id, 21, (page - 1) * 20);
        return Map.of("items", results.subList(0, Math.min(20, results.size())), "hasNext", results.size() > 20, "unread", mapper.unread(id));
    }

    @PatchMapping("/notifications/{id}/read")
    public void read(Authentication auth, @PathVariable long id) {
        mapper.read(service.id(auth), id);
    }

    @PostMapping("/notifications/read-all")
    public void readAll(Authentication auth) {
        mapper.readAll(service.id(auth));
    }

    @GetMapping("/announcements/unread")
    public List<Map<String, Object>> unreadAnnouncements(Authentication auth) {
        return mapper.announcements(service.id(auth), 20);
    }

    @PostMapping("/announcements/read-all")
    public void readAnnouncements(Authentication auth) {
        mapper.readAnnouncements(service.id(auth));
    }

    @PostMapping("/reports")
    public void report(Authentication auth, @RequestBody Map<String, Object> input) {
        service.report(auth, input);
    }

    @GetMapping("/me/points")
    public Map<String, Object> points(Authentication auth) {
        int points = mapper.points(service.id(auth));
        return Map.of("points", points, "level", points >= 1000 ? 5 : points >= 500 ? 4 : points >= 200 ? 3 : points >= 50 ? 2 : 1);
    }

    @GetMapping("/me/point-logs")
    public List<Map<String, Object>> logs(Authentication auth) {
        return mapper.pointLogs(service.id(auth));
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> publicUser(@PathVariable long id) {
        Map<String, Object> user = new HashMap<>(service.require(mapper.publicUser(id)));
        if (!Boolean.TRUE.equals(user.remove("public_school"))) user.remove("school");
        if (!Boolean.TRUE.equals(user.remove("public_grade"))) user.remove("grade");
        return user;
    }
}
