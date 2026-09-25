package com.studentforum;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DraftPublisher {
    private final JdbcTemplate db;
    private final ForumMapper mapper;
    private final ForumService service;
    private final TransactionTemplate transactions;

    DraftPublisher(JdbcTemplate db, ForumMapper mapper, ForumService service, TransactionTemplate transactions) {
        this.db = db;
        this.mapper = mapper;
        this.service = service;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelay = 60000)
    public void publishDue() {
        List<Map<String, Object>> due = db.queryForList("SELECT * FROM drafts WHERE scheduled_at<=NOW() ORDER BY scheduled_at LIMIT 20");
        for (Map<String, Object> draft : due) {
            try {
                transactions.executeWithoutResult(status -> publish(draft));
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(DraftPublisher.class).warn("定时草稿发布失败 #{}", draft.get("id"), error);
            }
        }
    }

    public void publish(Map<String, Object> draft) {
        long draftId = ((Number) draft.get("id")).longValue(), author = ((Number) draft.get("user_id")).longValue();
        if (db.update("UPDATE drafts SET scheduled_at=NULL WHERE id=? AND scheduled_at<=NOW()", draftId) != 1) return;
        Map<String, Object> user = mapper.user(author);
        if (user == null || !"active".equals(user.get("status")) || ((Number) user.get("reputation")).intValue() < 40 || draft.get("board_id") == null || mapper.board(((Number) draft.get("board_id")).longValue()) == null)
            return;
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("boardId", draft.get("board_id"));
        input.put("type", draft.get("type"));
        input.put("title", draft.get("title"));
        input.put("content", draft.get("content"));
        input.put("imagePath", draft.get("image_path"));
        input.put("coverPath", draft.get("cover_path"));
        input.put("attachmentPath", draft.get("attachment_path"));
        input.put("tagIds", java.util.Arrays.stream(String.valueOf(draft.get("tag_ids")).split(",")).filter(tag -> tag.matches("[0-9]+")).map(Long::parseLong).toList());
        long postId = service.number(service.createPost(author, input), "id");
        db.update("DELETE FROM drafts WHERE id=?", draftId);
        mapper.notifyUser(author, "system", "定时帖子已发布：" + String.valueOf(draft.get("title")).substring(0, Math.min(80, String.valueOf(draft.get("title")).length())), postId);
    }
}
