package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountAccessService {
    private final ForumMapper mapper;

    public AccountAccessService(ForumMapper mapper) {
        this.mapper = mapper;
    }

    public long id(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return Long.parseLong(auth.getName());
    }

    public Map<String, Object> current(Authentication auth) {
        Map<String, Object> user = mapper.user(id(auth));
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容不存在");
        return user;
    }

    public void writable(Authentication auth) {
        Map<String, Object> user = current(auth);
        if (!"active".equals(user.get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号暂不能发布内容");
        }
        if (((Number) user.get("reputation")).longValue() < 40) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "信誉分过低，暂不能发布或私信");
        }
    }

    public void moderate(Authentication auth, long boardId) {
        Map<String, Object> user = current(auth);
        if (!"active".equals(user.get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号暂不能管理内容");
        }
        if ("admin".equals(user.get("role"))) return;
        if ("moderator".equals(user.get("role")) && user.get("moderator_board_id") instanceof Number board
                && board.longValue() == boardId) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无此板块管理权限");
    }

    public void admin(Authentication auth) {
        Map<String, Object> user = current(auth);
        if (!"admin".equals(user.get("role")) || !"active".equals(user.get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可操作");
        }
    }

    public void staff(Authentication auth) {
        Map<String, Object> user = current(auth);
        if (!Set.of("admin", "moderator").contains(user.get("role")) || !"active".equals(user.get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理人员可操作");
        }
    }
}
