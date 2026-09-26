package com.studentforum;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ForumController {
    private final ForumMapper mapper;
    private final ForumService service;
    private final PasswordEncoder encoder;
    private final HttpSessionSecurityContextRepository contexts;

    ForumController(ForumMapper mapper, ForumService service, PasswordEncoder encoder, HttpSessionSecurityContextRepository contexts) {
        this.mapper = mapper;
        this.service = service;
        this.encoder = encoder;
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
    Map<String, String> csrf(org.springframework.security.web.csrf.CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @PostMapping("/auth/register")
    @Transactional
    Map<String, Object> register(@RequestBody Map<String, Object> input, HttpServletRequest request, HttpServletResponse response) {
        String email = String.valueOf(input.getOrDefault("email", "")).trim().toLowerCase(), username = String.valueOf(input.getOrDefault("username", "")).trim(), password = String.valueOf(input.getOrDefault("password", ""));
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || username.length() < 2 || username.length() > 40 || password.length() < 8 || password.length() > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写有效的邮箱、昵称和至少 8 位密码");
        if (mapper.userExists(email, username) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "邮箱或昵称已被使用");
        Map<String, Object> user = new HashMap<>();
        user.put("email", email);
        user.put("username", username);
        user.put("hash", encoder.encode(password));
        mapper.insertUser(user);
        long id = ((Number) user.get("id")).longValue();
        service.reward(id, "register", 5, "user", id, 0);
        signIn(id, "student", request, response);
        return mapper.user(id);
    }

    @PostMapping("/auth/login")
    @Transactional
    Map<String, Object> login(@RequestBody Map<String, String> input, HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> found = mapper.credentials(input.getOrDefault("email", "").trim().toLowerCase());
        if (found == null || !encoder.matches(input.getOrDefault("password", ""), String.valueOf(found.get("password_hash"))))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
        long id = service.number(found, "id");
        mapper.login(id);
        service.reward(id, "login", 1, "user", id, 1);
        signIn(id, String.valueOf(found.get("role")), request, response);
        return mapper.user(id);
    }

    @PostMapping("/auth/logout")
    void logout(HttpServletRequest request) {
        request.getSession().invalidate();
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    Map<String, Object> me(Authentication auth) {
        return service.current(auth);
    }

    @PutMapping("/me/profile")
    void profile(Authentication auth, @RequestBody Map<String, Object> input) {
        mapper.profile(service.id(auth), field(input, "school", 100), field(input, "grade", 40), field(input, "major", 100), field(input, "subjectPreference", 100), Boolean.TRUE.equals(input.get("publicSchool")), Boolean.TRUE.equals(input.get("publicGrade")));
    }

    @PutMapping("/me/password")
    void password(Authentication auth, @RequestBody Map<String, String> input) {
        long id = service.id(auth);
        String next = input.getOrDefault("newPassword", "");
        if (!encoder.matches(input.getOrDefault("oldPassword", ""), mapper.hash(id)) || next.length() < 8 || next.length() > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原密码错误或新密码长度无效");
        mapper.password(id, encoder.encode(next));
    }

    private String field(Map<String, Object> input, String name, int max) {
        String value = String.valueOf(input.getOrDefault(name, ""));
        if (value.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资料内容过长");
        return value.trim();
    }

    @GetMapping("/boards")
    List<Map<String, Object>> boards() {
        return mapper.boards();
    }

    @GetMapping("/tags")
    List<Map<String, Object>> tags() {
        return mapper.tags();
    }

    @GetMapping("/levels")
    List<Map<String, Object>> levels() {
        return List.of(Map.of("level", 1, "name", "新生", "min", 0), Map.of("level", 2, "name", "学友", "min", 50), Map.of("level", 3, "name", "学长", "min", 200), Map.of("level", 4, "name", "达人", "min", 500), Map.of("level", 5, "name", "学霸", "min", 1000));
    }

    @GetMapping("/boards/{id}/posts")
    List<Map<String, Object>> boardPosts(@PathVariable long id, @RequestParam(defaultValue = "latest") String sort, @RequestParam(defaultValue = "1") int page) {
        service.require(mapper.board(id));
        return search(null, id, null, null, null, null, sort, page);
    }

    @GetMapping("/tags/{id}/posts")
    List<Map<String, Object>> tagPosts(@PathVariable long id, @RequestParam(defaultValue = "1") int page) {
        service.require(mapper.tag(id));
        return search(null, null, id, null, null, null, "latest", page);
    }

    @GetMapping("/search")
    List<Map<String, Object>> search(@RequestParam(required = false) String q, @RequestParam(required = false) Long board, @RequestParam(required = false) Long tag, @RequestParam(required = false) String type, @RequestParam(required = false) Boolean solved, @RequestParam(required = false) Boolean featured, @RequestParam(defaultValue = "latest") String sort, @RequestParam(defaultValue = "1") int page) {
        if (page < 1 || page > 10000 || q != null && q.length() > 100 || type != null && !Set.of("question", "discussion", "experience").contains(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索条件无效");
        return mapper.posts(board, tag, type, solved, featured, q == null || q.isBlank() ? null : q.trim(), "hot".equals(sort) ? "hot" : "latest", 21, (page - 1) * 20);
    }

    @GetMapping("/posts/{id}")
    Map<String, Object> post(@PathVariable long id, Authentication auth) {
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
    Map<String, Object> publish(Authentication auth, @RequestBody Map<String, Object> input) {
        return service.publish(auth, input);
    }

    @PatchMapping("/posts/{id}")
    @Transactional
    void edit(Authentication auth, @PathVariable long id, @RequestBody Map<String, String> input) {
        Map<String, Object> post = service.require(mapper.post(id));
        if (service.number(post, "author_id") != service.id(auth) || !"published".equals(post.get("status")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        service.text(input.get("title"), 5, 160);
        service.text(input.get("content"), 10, 10000);
        mapper.editPost(id, input.get("title").trim(), input.get("content").trim());
        service.revision(auth, id, input.get("title").trim(), input.get("content").trim());
    }

    @DeleteMapping("/posts/{id}")
    void delete(Authentication auth, @PathVariable long id) {
        Map<String, Object> post = service.require(mapper.post(id));
        if (service.number(post, "author_id") != service.id(auth))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        mapper.postStatus(id, "deleted");
    }

    @GetMapping("/posts/{id}/replies")
    List<Map<String, Object>> replies(@PathVariable long id, @RequestParam(defaultValue = "earliest") String sort) {
        if (!"published".equals(service.require(mapper.post(id)).get("status")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return service.sortedReplies(id, sort);
    }

    @PostMapping("/posts/{id}/replies")
    Map<String, Object> reply(Authentication auth, @PathVariable long id, @RequestBody Map<String, Object> input) {
        return service.reply(auth, id, input);
    }

    @DeleteMapping("/replies/{id}")
    void deleteReply(Authentication auth, @PathVariable long id) {
        Map<String, Object> reply = service.require(mapper.reply(id));
        if (service.number(reply, "author_id") != service.id(auth) || service.flag(reply, "is_accepted"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        mapper.deleteReply(id);
    }

    @PostMapping("/posts/{id}/accept/{replyId}")
    void accept(Authentication auth, @PathVariable long id, @PathVariable long replyId) {
        service.accept(auth, id, replyId);
    }

    @PostMapping("/posts/{id}/favorite")
    void favorite(Authentication auth, @PathVariable long id) {
        if (!"published".equals(service.require(mapper.post(id)).get("status")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        mapper.favorite(service.id(auth), id);
    }

    @DeleteMapping("/posts/{id}/favorite")
    void unfavorite(Authentication auth, @PathVariable long id) {
        mapper.unfavorite(service.id(auth), id);
    }

    @GetMapping("/me/favorites")
    List<Map<String, Object>> favorites(Authentication auth, @RequestParam(defaultValue = "1") int page) {
        return mapper.favorites(service.id(auth), 20, (Math.max(page, 1) - 1) * 20);
    }

    @GetMapping("/notifications")
    Map<String, Object> notifications(Authentication auth, @RequestParam(defaultValue = "1") int page) {
        if (page < 1 || page > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码无效");
        long id = service.id(auth);
        List<Map<String, Object>> results = mapper.notifications(id, 21, (page - 1) * 20);
        return Map.of("items", results.subList(0, Math.min(20, results.size())), "hasNext", results.size() > 20, "unread", mapper.unread(id));
    }

    @PatchMapping("/notifications/{id}/read")
    void read(Authentication auth, @PathVariable long id) {
        mapper.read(service.id(auth), id);
    }

    @PostMapping("/notifications/read-all")
    void readAll(Authentication auth) {
        mapper.readAll(service.id(auth));
    }

    @PostMapping("/reports")
    void report(Authentication auth, @RequestBody Map<String, Object> input) {
        service.report(auth, input);
    }

    @GetMapping("/me/points")
    Map<String, Object> points(Authentication auth) {
        int points = mapper.points(service.id(auth));
        return Map.of("points", points, "level", points >= 1000 ? 5 : points >= 500 ? 4 : points >= 200 ? 3 : points >= 50 ? 2 : 1);
    }

    @GetMapping("/me/point-logs")
    List<Map<String, Object>> logs(Authentication auth) {
        return mapper.pointLogs(service.id(auth));
    }

    @GetMapping("/users/{id}")
    Map<String, Object> publicUser(@PathVariable long id) {
        Map<String, Object> user = new HashMap<>(service.require(mapper.publicUser(id)));
        if (!Boolean.TRUE.equals(user.remove("public_school"))) user.remove("school");
        if (!Boolean.TRUE.equals(user.remove("public_grade"))) user.remove("grade");
        return user;
    }
}
