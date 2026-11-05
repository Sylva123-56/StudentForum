package com.studentforum.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VoteRepository {
    private final JdbcTemplate db;

    public VoteRepository(JdbcTemplate db) {
        this.db = db;
    }

    public int createVote(long postId, boolean multiple, LocalDateTime closes, String visibility) {
        return db.update("INSERT INTO votes(post_id,multiple,closes_at,visibility) VALUES(?,?,?,?)", postId, multiple, closes, visibility);
    }

    public int insertVoteOption(long voteId, String label) {
        return db.update("INSERT INTO vote_options(vote_id,label) VALUES(?,?)", voteId, label);
    }

    public int insertBallot(long voteId, long option, long user) {
        return db.update("INSERT INTO vote_ballots(vote_id,option_id,user_id) VALUES(?,?,?)", voteId, option, user);
    }

    public int deductBounty(long amount, long user) {
        return db.update("UPDATE users SET points=points-? WHERE id=? AND points>=?", amount, user, amount);
    }

    public int createBounty(long postId, long amount) {
        return db.update("INSERT INTO bounties(post_id,amount) VALUES(?,?)", postId, amount);
    }

    public Map<String, Object> voteId(long postId) {
        return db.queryForList("SELECT id FROM votes WHERE post_id=?", postId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> vote(long postId) {
        return db.queryForList("SELECT * FROM votes WHERE post_id=?", postId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> ballot(long voteId, long user) {
        return db.queryForList("SELECT 1 FROM vote_ballots WHERE vote_id=? AND user_id=?", voteId, user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> lockVote(long postId) {
        return db.queryForList("SELECT * FROM votes WHERE post_id=? FOR UPDATE", postId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> voteOption(long voteId, long optionId) {
        return db.queryForList("SELECT 1 FROM vote_options WHERE vote_id=? AND id=?", voteId, optionId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> bountyExists(long postId) {
        return db.queryForList("SELECT 1 FROM bounties WHERE post_id=?", postId).stream().findFirst().orElse(null);
    }

    public Map<String, Object> bounty(long postId) {
        return db.queryForList("SELECT * FROM bounties WHERE post_id=?", postId).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> voteOptions(long voteId, boolean visible) {
        return db.queryForList("SELECT o.id,o.label," + (visible ? "(SELECT COUNT(DISTINCT user_id) FROM vote_ballots WHERE option_id=o.id)" : "0") + " ballots FROM vote_options o WHERE o.vote_id=?", voteId);
    }
}
