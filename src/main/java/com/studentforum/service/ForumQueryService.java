package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.ForumRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ForumQueryService {
    private final ForumMapper mapper;
    private final AccountAccessService accounts;
    private final ForumRepository posts;

    public ForumQueryService(ForumMapper mapper, AccountAccessService accounts, ForumRepository posts) {
        this.mapper = mapper;
        this.accounts = accounts;
        this.posts = posts;
    }

    public List<Map<String, Object>> boards() {
        return mapper.boards();
    }

    public List<Map<String, Object>> tags() {
        return mapper.tags();
    }

    public List<Map<String, Object>> levels() {
        return List.of(
                Map.of("level", 1, "name", "新生", "min", 0),
                Map.of("level", 2, "name", "学友", "min", 50),
                Map.of("level", 3, "name", "学长", "min", 200),
                Map.of("level", 4, "name", "达人", "min", 500),
                Map.of("level", 5, "name", "学霸", "min", 1000));
    }

    public List<Map<String, Object>> boardPosts(long id, String sort, int page) {
        require(mapper.board(id));
        return search(null, id, null, null, null, null, sort, page);
    }

    public List<Map<String, Object>> tagPosts(long id, int page) {
        require(mapper.tag(id));
        return search(null, null, id, null, null, null, "latest", page);
    }

    public List<Map<String, Object>> search(String q, Long board, Long tag, String type, Boolean solved, Boolean featured, String sort, int page) {
        if (page < 1 || page > 10000 || q != null && q.length() > 100
                || type != null && !Set.of("question", "discussion", "experience").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索条件无效");
        }
        return mapper.posts(board, tag, type, solved, featured, q == null || q.isBlank() ? null : q.trim(), "hot".equals(sort) ? "hot" : "latest", 21, (page - 1) * 20);
    }

    public Map<String, Object> post(long id, Authentication auth) {
        Map<String, Object> post = require(mapper.post(id));
        if (!"published".equals(post.get("status")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        mapper.view(id);
        post.put("tags", mapper.postTags(id));
        boolean loggedIn = auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
        post.put("favorited", loggedIn && mapper.isFavorite(accounts.id(auth), id) > 0);
        return post;
    }

    public List<Map<String, Object>> replies(long id, String sort) {
        if (!"published".equals(require(mapper.post(id)).get("status")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!Set.of("earliest", "latest", "hot").contains(sort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回复排序无效");
        }
        return posts.replies(id, sort);
    }

    public List<Map<String, Object>> favorites(Authentication auth, int page) {
        return mapper.favorites(accounts.id(auth), 20, (Math.max(page, 1) - 1) * 20);
    }

    public List<Map<String, Object>> authoredPosts(Authentication auth) {
        return mapper.authoredPosts(accounts.id(auth), 100);
    }

    public Map<String, Object> publicUser(long id) {
        Map<String, Object> user = new HashMap<>(require(mapper.publicUser(id)));
        if (!Boolean.TRUE.equals(user.remove("public_school"))) user.remove("school");
        if (!Boolean.TRUE.equals(user.remove("public_grade"))) user.remove("grade");
        return user;
    }

    private Map<String, Object> require(Map<String, Object> value) {
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容不存在");
        return value;
    }
}
