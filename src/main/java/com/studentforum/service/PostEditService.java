package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.model.Post;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PostEditService {
    private final ForumMapper mapper;
    private final ForumService forum;

    public PostEditService(ForumMapper mapper, ForumService forum) {
        this.mapper = mapper;
        this.forum = forum;
    }

    private Post post(long id) {
        Map<String, Object> row = forum.require(mapper.post(id));
        return new Post(forum.number(row, "author_id"),
                String.valueOf(row.get("type")), String.valueOf(row.get("status")));
    }

    @Transactional
    public void edit(Authentication auth, long id, Map<String, String> input) {
        if (!post(id).editableBy(forum.id(auth)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        forum.text(input.get("title"), 5, 160);
        forum.text(input.get("content"), 10, 10000);
        mapper.editPost(id, input.get("title").trim(), input.get("content").trim());
        forum.revision(auth, id, input.get("title").trim(), input.get("content").trim());
    }

    @Transactional
    public void delete(Authentication auth, long id) {
        if (!post(id).ownedBy(forum.id(auth)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        forum.hardDeletePost(id);
    }
}
