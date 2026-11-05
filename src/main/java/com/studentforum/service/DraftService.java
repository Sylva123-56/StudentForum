package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.repository.DraftRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DraftService {
    private final DraftRepository repository;
    private final AccountAccessService accounts;
    private final ForumMapper mapper;

    public DraftService(DraftRepository repository, AccountAccessService accounts, ForumMapper mapper) {
        this.repository = repository;
        this.accounts = accounts;
        this.mapper = mapper;
    }

    private long id(Authentication auth) {
        return accounts.id(auth);
    }

    private Map<String, Object> required(Map<String, Object> row) {
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容不存在");
        return row;
    }

    private String text(Map<String, Object> input, String key, int max) {
        String result = input.get(key) instanceof String value ? value.trim() : "";
        if (result.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容过长");
        return result;
    }

    private void check(boolean allowed, String message) {
        if (!allowed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private LocalDateTime parseTime(Object value) {
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式无效");
        }
    }

    public List<Map<String, Object>> drafts(Authentication auth) {
        return repository.drafts(id(auth));
    }

    public Map<String, Object> draft(Authentication auth, long draftId) {
        return required(repository.ownedDraft(draftId, id(auth)));
    }

    @Transactional
    public Map<String, Object> draft(Authentication auth, Map<String, Object> input) {
        long me = id(auth);
        long draftId = repository.insertDraft(me);
        updateDraft(auth, draftId, input);
        return required(repository.draft(draftId));
    }

    public void updateDraft(Authentication auth, long draftId, Map<String, Object> input) {
        required(repository.ownedDraftId(draftId, id(auth)));
        String type = text(input, "type", 20), title = text(input, "title", 160), content = text(input, "content", 10000);
        if (!title.isEmpty()) com.studentforum.model.ContentRules.text(title, 1, 160);
        if (!content.isEmpty()) com.studentforum.model.ContentRules.text(content, 1, 10000);
        check(Set.of("question", "discussion", "experience").contains(type), "帖子类型无效");
        Long board = input.get("boardId") instanceof Number value ? value.longValue() : null;
        if (board != null) required(mapper.board(board));
        String tags = text(input, "tagIds", 255), image = com.studentforum.model.ContentRules.image(input.get("imagePath")), cover = com.studentforum.model.ContentRules.image(input.get("coverPath")), attachment = com.studentforum.model.ContentRules.attachment(input.get("attachmentPath"));
        check(tags.isEmpty() || tags.matches("[0-9]+(,[0-9]+)*"), "标签格式无效");
        LocalDateTime scheduled = input.get("scheduledAt") == null || "".equals(input.get("scheduledAt")) ? null : parseTime(input.get("scheduledAt"));
        if (scheduled != null) {
            check(scheduled.isAfter(LocalDateTime.now()), "定时发布时间必须在未来");
            com.studentforum.model.ContentRules.text(title, 5, 160);
            com.studentforum.model.ContentRules.text(content, 10, 10000);
            check(board != null, "定时发布需要板块");
        }
        repository.updateDraft(board, type, title, content, image, cover, attachment, tags, scheduled, draftId, id(auth));
    }

    public void deleteDraft(Authentication auth, long draftId) {
        repository.deleteDraft(draftId, id(auth));
    }
}
