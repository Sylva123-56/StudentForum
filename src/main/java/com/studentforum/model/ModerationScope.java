package com.studentforum.model;

import java.util.List;

/** Determines which board a staff member may see in moderation lists. */
public final class ModerationScope {
    private final boolean administrator;
    private final Long boardId;

    public ModerationScope(String role, Long boardId) {
        this.administrator = "admin".equals(role);
        this.boardId = boardId;
    }

    public List<Long> postBoards() {
        return administrator ? null : List.of(boardId == null ? -1L : boardId);
    }

    public Long reportBoard() {
        return administrator ? null : boardId == null ? -1L : boardId;
    }
}
