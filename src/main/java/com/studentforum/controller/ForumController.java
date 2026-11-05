package com.studentforum.controller;

import com.studentforum.service.ForumService;
import com.studentforum.service.AccountService;
import com.studentforum.service.PostEditService;
import com.studentforum.service.ForumQueryService;
import com.studentforum.service.NotificationService;
import com.studentforum.service.PointService;
import com.studentforum.model.SignedInAccount;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Map;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ForumController {
    private final ForumQueryService queries;
    private final NotificationService notifications;
    private final PointService points;
    private final ForumService service;
    private final AccountService accounts;
    private final PostEditService postEdits;
    private final HttpSessionSecurityContextRepository contexts;

    public ForumController(ForumService service, AccountService accounts, PostEditService postEdits, ForumQueryService queries, NotificationService notifications, PointService points, HttpSessionSecurityContextRepository contexts) {
        this.queries = queries;
        this.notifications = notifications;
        this.points = points;
        this.service = service;
        this.accounts = accounts;
        this.postEdits = postEdits;
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
        return queries.boards();
    }

    @GetMapping("/tags")
    public List<Map<String, Object>> tags() {
        return queries.tags();
    }

    @GetMapping("/levels")
    public List<Map<String, Object>> levels() {
        return queries.levels();
    }

    @GetMapping("/boards/{id}/posts")
    public List<Map<String, Object>> boardPosts(@PathVariable long id, @RequestParam(defaultValue = "latest") String sort, @RequestParam(defaultValue = "1") int page) {
        return queries.boardPosts(id, sort, page);
    }

    @GetMapping("/tags/{id}/posts")
    public List<Map<String, Object>> tagPosts(@PathVariable long id, @RequestParam(defaultValue = "1") int page) {
        return queries.tagPosts(id, page);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam(required = false) String q, @RequestParam(required = false) Long board, @RequestParam(required = false) Long tag, @RequestParam(required = false) String type, @RequestParam(required = false) Boolean solved, @RequestParam(required = false) Boolean featured, @RequestParam(defaultValue = "latest") String sort, @RequestParam(defaultValue = "1") int page) {
        return queries.search(q, board, tag, type, solved, featured, sort, page);
    }

    @GetMapping("/posts/{id}")
    public Map<String, Object> post(@PathVariable long id, Authentication auth) {
        return queries.post(id, auth);
    }

    @PostMapping("/posts")
    public Map<String, Object> publish(Authentication auth, @RequestBody Map<String, Object> input) {
        return service.publish(auth, input);
    }

    @PatchMapping("/posts/{id}")
    public void edit(Authentication auth, @PathVariable long id, @RequestBody Map<String, String> input) {
        postEdits.edit(auth, id, input);
    }

    @DeleteMapping("/posts/{id}")
    public void delete(Authentication auth, @PathVariable long id) {
        postEdits.delete(auth, id);
    }

    @GetMapping("/posts/{id}/replies")
    public List<Map<String, Object>> replies(@PathVariable long id, @RequestParam(defaultValue = "earliest") String sort) {
        return queries.replies(id, sort);
    }

    @PostMapping("/posts/{id}/replies")
    public Map<String, Object> reply(Authentication auth, @PathVariable long id, @RequestBody Map<String, Object> input) {
        return service.reply(auth, id, input);
    }

    @DeleteMapping("/replies/{id}")
    public void deleteReply(Authentication auth, @PathVariable long id) {
        service.deleteReply(auth, id);
    }

    @PostMapping("/posts/{id}/accept/{replyId}")
    public void accept(Authentication auth, @PathVariable long id, @PathVariable long replyId) {
        service.accept(auth, id, replyId);
    }

    @PostMapping("/posts/{id}/favorite")
    public void favorite(Authentication auth, @PathVariable long id) {
        service.favorite(auth, id);
    }

    @DeleteMapping("/posts/{id}/favorite")
    public void unfavorite(Authentication auth, @PathVariable long id) {
        service.unfavorite(auth, id);
    }

    @GetMapping("/me/favorites")
    public List<Map<String, Object>> favorites(Authentication auth, @RequestParam(defaultValue = "1") int page) {
        return queries.favorites(auth, page);
    }

    @GetMapping("/me/posts")
    public List<Map<String, Object>> authoredPosts(Authentication auth) {
        return queries.authoredPosts(auth);
    }

    @GetMapping("/notifications")
    public Map<String, Object> notifications(Authentication auth, @RequestParam(defaultValue = "1") int page) {
        return notifications.notifications(auth, page);
    }

    @PatchMapping("/notifications/{id}/read")
    public void read(Authentication auth, @PathVariable long id) {
        notifications.read(auth, id);
    }

    @PostMapping("/notifications/read-all")
    public void readAll(Authentication auth) {
        notifications.readAll(auth);
    }

    @GetMapping("/announcements/unread")
    public List<Map<String, Object>> unreadAnnouncements(Authentication auth) {
        return notifications.unreadAnnouncements(auth);
    }

    @PostMapping("/announcements/read-all")
    public void readAnnouncements(Authentication auth) {
        notifications.readAnnouncements(auth);
    }

    @PostMapping("/reports")
    public void report(Authentication auth, @RequestBody Map<String, Object> input) {
        service.report(auth, input);
    }

    @GetMapping("/me/points")
    public Map<String, Object> points(Authentication auth) {
        return points.points(auth);
    }

    @GetMapping("/me/point-logs")
    public List<Map<String, Object>> logs(Authentication auth) {
        return points.logs(auth);
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> publicUser(@PathVariable long id) {
        return queries.publicUser(id);
    }
}
