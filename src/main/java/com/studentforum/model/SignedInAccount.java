package com.studentforum.model;

import java.util.Map;

/** Account data returned by authentication; only the controller creates the HTTP session. */
public final class SignedInAccount {
    private final long id;
    private final String role;
    private final Map<String, Object> profile;

    public SignedInAccount(long id, String role, Map<String, Object> profile) {
        this.id = id;
        this.role = role;
        this.profile = profile;
    }

    public long id() { return id; }
    public String role() { return role; }
    public Map<String, Object> profile() { return profile; }
}
