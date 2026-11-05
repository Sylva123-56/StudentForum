package com.studentforum.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MistakeRepository {
    private final JdbcTemplate db;

    public MistakeRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> reviews(long id) {
        return db.queryForList("SELECT * FROM mistake_reviews WHERE mistake_id=? ORDER BY id DESC LIMIT 100", id);
    }

    public Map<String, Object> ownedImage(long imageId, long user) {
        return db.queryForList("SELECT id FROM mistake_images WHERE id=? AND user_id=?", imageId, user).stream().findFirst().orElse(null);
    }

    public Map<String, Object> publishedPost(long post) {
        return db.queryForList("SELECT id FROM posts WHERE id=? AND status='published'", post).stream().findFirst().orElse(null);
    }

    public Map<String, Object> lockMistake(long id) {
        return db.queryForList("SELECT * FROM mistakes WHERE id=? FOR UPDATE", id).stream().findFirst().orElse(null);
    }

    public int insertReview(long id, long user, String result) {
        return db.update("INSERT INTO mistake_reviews(mistake_id,user_id,result) VALUES(?,?,?)", id, user, result);
    }

    public int updateReviewTotals(int correct, int incorrect, java.time.LocalDate next, long id) {
        return db.update("UPDATE mistakes SET correct_count=correct_count+?,error_count=error_count+?,next_review_date=? WHERE id=?", correct, incorrect, next, id);
    }

    public List<Map<String, Object>> groups(long user) {
        return db.queryForList("SELECT g.id,g.name FROM study_groups g JOIN study_group_members m ON m.group_id=g.id WHERE m.user_id=? AND g.status='active' ORDER BY g.name", user);
    }

    public long insertReviewPlan(long user, String title, Object subject, String goal, java.time.LocalDate start, java.time.LocalDate end) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO study_plans(user_id,title,subject,goal,start_date,end_date) VALUES(?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] fields = {user, title, subject, goal, start, end};
            for (int i = 0; i < fields.length; i++) statement.setObject(i + 1, fields[i]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public int insertReviewTask(long plan, String title, java.time.LocalDate due, int order) {
        return db.update("INSERT INTO study_tasks(plan_id,title,due_date,sort_order) VALUES(?,?,?,?)", plan, title, due, order);
    }

    public long insertMistake(long user, Map<String, Object> r) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO mistakes(user_id,subject,question,answer,reason,knowledge_point,source,tags,image_path,visibility,group_id,post_id,next_review_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_DATE)", java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] fields = {user, r.get("subject"), r.get("question"), r.get("answer"), r.get("reason"), r.get("knowledge_point"), r.get("source"), r.get("tags"), r.get("image_path"), r.get("visibility"), r.get("group_id"), r.get("post_id")};
            for (int i = 0; i < fields.length; i++) statement.setObject(i + 1, fields[i]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public void updateMistake(long id, Map<String, Object> r) {
        db.update("UPDATE mistakes SET subject=?,question=?,answer=?,reason=?,knowledge_point=?,source=?,tags=?,image_path=?,visibility=?,group_id=?,post_id=? WHERE id=?", r.get("subject"), r.get("question"), r.get("answer"), r.get("reason"), r.get("knowledge_point"), r.get("source"), r.get("tags"), r.get("image_path"), r.get("visibility"), r.get("group_id"), r.get("post_id"), id);
    }

    public List<Map<String, Object>> search(String scope, String subject, String q, boolean due, long user, int offset) {
        String sql="SELECT m.*,u.username FROM mistakes m JOIN users u ON u.id=m.user_id WHERE "+switch(scope){case "mine"->"m.user_id=?";case "public"->"m.visibility='public'";default->"m.visibility='group' AND EXISTS(SELECT 1 FROM study_group_members gm JOIN study_groups g ON g.id=gm.group_id WHERE gm.group_id=m.group_id AND gm.user_id=? AND g.status='active')";};
        List<Object> args=new ArrayList<>();if(!scope.equals("public"))args.add(user);
        if(!subject.isBlank()){sql+=" AND m.subject=?";args.add(subject.trim());}if(!q.isBlank()){sql+=" AND (LOCATE(?,m.question)>0 OR LOCATE(?,m.tags)>0 OR LOCATE(?,m.knowledge_point)>0)";args.add(q);args.add(q);args.add(q);}
        if(due)sql+=" AND (m.next_review_date IS NULL OR m.next_review_date<=CURRENT_DATE)";
        sql+=" ORDER BY m.id DESC LIMIT 21 OFFSET ?";args.add(offset);return db.queryForList(sql,args.toArray());
    }

    public Map<String, Object> stats(long user) {
        String stats="COUNT(*) AS total,SUM(error_count) AS errors,SUM(correct_count) AS corrects,ROUND(100.0*SUM(error_count)/GREATEST(1,SUM(error_count+correct_count)),1) AS error_rate";
        return Map.of("subjects",db.queryForList("SELECT subject,"+stats+" FROM mistakes WHERE user_id=? GROUP BY subject ORDER BY total DESC",user),"knowledgePoints",db.queryForList("SELECT subject,knowledge_point,"+stats+" FROM mistakes WHERE user_id=? GROUP BY subject,knowledge_point ORDER BY errors DESC",user));
    }
}
