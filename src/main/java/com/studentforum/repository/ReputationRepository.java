package com.studentforum.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReputationRepository {
    private final JdbcTemplate db;

    public ReputationRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void change(long user, int amount) {
        db.update("UPDATE users SET reputation=GREATEST(0,LEAST(200,reputation+?)) WHERE id=?", amount, user);
    }

    public void log(long user, long actor, int amount, long balance, String reason) {
        db.update("INSERT INTO reputation_logs(user_id,actor_id,amount,balance_after,reason) VALUES(?,?,?,?,?)", user, actor, amount, balance, reason);
    }
}
