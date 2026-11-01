package com.studentforum.model;

/** Only state needed to decide whether a post may be changed or accepted. */
public final class Post {
    private final long authorId;
    private final String type;
    private final String status;

    public Post(long authorId, String type, String status) {
        this.authorId = authorId;
        this.type = type;
        this.status = status;
    }

    public boolean ownedBy(long userId) {
        return authorId == userId;
    }

    public boolean editableBy(long userId) {
        return ownedBy(userId) && "published".equals(status);
    }

    public boolean acceptsAnswerFrom(long userId) {
        return editableBy(userId) && "question".equals(type);
    }
}
