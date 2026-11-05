package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.service.ForumService;

import java.util.List;
import java.util.Map;

import com.studentforum.repository.DraftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScheduledDraftService {
    private final DraftRepository drafts;
    private final ForumMapper mapper;
    private final ForumService service;
    private final TransactionTemplate transactions;

    public ScheduledDraftService(DraftRepository drafts, ForumMapper mapper, ForumService service, TransactionTemplate transactions) {
        this.drafts = drafts;
        this.mapper = mapper;
        this.service = service;
        this.transactions = transactions;
    }

    public void publishDue() {
        List<Map<String, Object>> due = drafts.dueDrafts();
        for (Map<String, Object> draft : due) {
            try {
                transactions.executeWithoutResult(status -> publish(draft));
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(ScheduledDraftService.class).warn("定时草稿发布失败 #{}", draft.get("id"), error);
            }
        }
    }

    public void publish(Map<String, Object> draft) {
        long draftId = ((Number) draft.get("id")).longValue(), author = ((Number) draft.get("user_id")).longValue();
        if (!drafts.claimDue(draftId)) return;
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
        long postId = ((Number) service.createPost(author, input).get("id")).longValue();
        drafts.deletePublished(draftId);
        mapper.notifyUser(author, "system", "定时帖子已发布：" + String.valueOf(draft.get("title")).substring(0, Math.min(80, String.valueOf(draft.get("title")).length())), postId);
    }
}
