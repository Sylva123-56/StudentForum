package com.studentforum.service;

import com.studentforum.repository.NoteRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NoteService {
    private final KnowledgeService knowledge;
    private final NoteRepository notes;

    public NoteService(KnowledgeService knowledge, NoteRepository notes) {
        this.knowledge = knowledge;
        this.notes = notes;
    }

    public Map<String, Object> list(Authentication auth, boolean mine, String query,
                                    String subject, int page) {
        knowledge.check(!mine || knowledge.user(auth) > 0, "请先登录");
        knowledge.check(query.length() <= 100, "关键词过长");
        return knowledge.page(notes.search(mine, knowledge.user(auth), query, subject,
                knowledge.offset(page)));
    }

    public Map<String, Object> detail(Authentication auth, long id) {
        return knowledge.note(auth, id);
    }

    @Transactional
    public Map<String, Object> create(Authentication auth, Map<String, Object> input) {
        knowledge.forum.writable(auth);
        String title = knowledge.text(input, "title", 2, 160);
        String content = knowledge.text(input, "content", 1, 30000);
        String status = knowledge.choice(input, "status", "draft", "draft", "published");
        long id = notes.insert(knowledge.user(auth), title, content,
                knowledge.text(input, "subject", 0, 40),
                knowledge.text(input, "tags", 0, 500), status);
        notes.addRevision(id, knowledge.user(auth), title, content);
        return Map.of("id", id);
    }

    @Transactional
    public void edit(Authentication auth, long id, Map<String, Object> input) {
        knowledge.owner(auth, knowledge.note(auth, id), "user_id");
        if (!notes.lock(id))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容不存在或无权查看");
        String title = knowledge.text(input, "title", 2, 160);
        String content = knowledge.text(input, "content", 1, 30000);
        String status = knowledge.choice(input, "status", "draft", "draft", "published");
        notes.update(id, title, content,
                knowledge.text(input, "subject", 0, 40),
                knowledge.text(input, "tags", 0, 500), status);
        notes.addRevision(id, knowledge.user(auth), title, content);
    }

    public List<Map<String, Object>> revisions(Authentication auth, long id) {
        knowledge.owned(auth, knowledge.note(auth, id), "user_id");
        return notes.revisions(id);
    }
}
