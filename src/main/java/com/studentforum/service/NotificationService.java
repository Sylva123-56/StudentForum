package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.MentionRepository;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private final ForumMapper mapper;
    private final AccountAccessService accounts;
    private final MentionRepository mentions;

    public NotificationService(ForumMapper mapper, AccountAccessService accounts, MentionRepository mentions) {
        this.mapper = mapper;
        this.accounts = accounts;
        this.mentions = mentions;
    }

    public Map<String, Object> notifications(Authentication auth, int page) {
        if (page < 1 || page > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码无效");
        long id = accounts.id(auth);
        List<Map<String, Object>> results = mapper.notifications(id, 21, (page - 1) * 20);
        return Map.of("items", results.subList(0, Math.min(20, results.size())), "hasNext", results.size() > 20, "unread", mapper.unread(id));
    }

    public void read(Authentication auth, long id) {
        mapper.read(accounts.id(auth), id);
    }

    public void readAll(Authentication auth) {
        mapper.readAll(accounts.id(auth));
    }

    public List<Map<String, Object>> unreadAnnouncements(Authentication auth) {
        return mapper.announcements(accounts.id(auth), 20);
    }

    public void readAnnouncements(Authentication auth) {
        mapper.readAnnouncements(accounts.id(auth));
    }

    @Transactional
    public void mentions(long actor, String content, String type, long target, Long postId) {
        Matcher matcher = Pattern.compile("@([\\p{L}\\p{N}_-]{2,40})").matcher(content);
        while (matcher.find()) {
            List<Map<String, Object>> users = mentions.users(matcher.group(1));
            if (users.isEmpty()) continue;
            Map<String, Object> user = users.getFirst();
            long recipient = ((Number) user.get("id")).longValue();
            if (recipient == actor) continue;
            if ("message".equals(type) && !mentions.messageParticipant(target, recipient)) continue;
            if (mentions.insert(recipient, actor, type, target) && Boolean.TRUE.equals(user.get("notify_mention"))) {
                notifyUser(recipient, "mention", "有人在" + ("message".equals(type) ? "私信" : "讨论") + "中提及了你", postId);
            }
        }
    }

    public void notifyUser(long userId, String type, String message, Long postId) {
        mapper.notifyUser(userId, type, message, postId);
    }
}
