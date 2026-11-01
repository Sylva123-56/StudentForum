package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.model.SignedInAccount;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {
    private final ForumMapper mapper;
    private final ForumService forum;
    private final PasswordEncoder encoder;

    public AccountService(ForumMapper mapper, ForumService forum, PasswordEncoder encoder) {
        this.mapper = mapper;
        this.forum = forum;
        this.encoder = encoder;
    }

    @Transactional
    public SignedInAccount register(Map<String, Object> input) {
        String email = String.valueOf(input.getOrDefault("email", "")).trim().toLowerCase();
        String username = String.valueOf(input.getOrDefault("username", "")).trim();
        String password = String.valueOf(input.getOrDefault("password", ""));
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
        forum.reward(id, "register", 5, "user", id, 0);
        return new SignedInAccount(id, "student", mapper.user(id));
    }

    @Transactional
    public SignedInAccount login(Map<String, String> input) {
        Map<String, Object> found = mapper.credentials(input.getOrDefault("email", "").trim().toLowerCase());
        if (found == null || !encoder.matches(input.getOrDefault("password", ""), String.valueOf(found.get("password_hash"))))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
        long id = forum.number(found, "id");
        mapper.login(id);
        forum.reward(id, "login", 1, "user", id, 1);
        return new SignedInAccount(id, String.valueOf(found.get("role")), mapper.user(id));
    }

    public void profile(Authentication auth, Map<String, Object> input) {
        mapper.profile(forum.id(auth), field(input, "school", 100), field(input, "grade", 40), field(input, "major", 100), field(input, "subjectPreference", 100), Boolean.TRUE.equals(input.get("publicSchool")), Boolean.TRUE.equals(input.get("publicGrade")));
    }

    public void password(Authentication auth, Map<String, String> input) {
        long id = forum.id(auth);
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
}
