package com.studentforum;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ForumService {
    private final ForumMapper mapper;
    private final JdbcTemplate db;
    ForumService(ForumMapper mapper,JdbcTemplate db) { this.mapper = mapper; this.db=db; }
    void revision(Authentication auth,long postId,String title,String content) { db.update("INSERT INTO post_revisions(post_id,editor_id,title,content) VALUES(?,?,?,?)",postId,id(auth),title,content); mentions(id(auth),title+" "+content,"post",postId,postId); }
    void mentions(long actor,String content,String type,long target,Long postId) {
        Matcher matcher=Pattern.compile("@([\\p{L}\\p{N}_-]{2,40})").matcher(content);
        while (matcher.find()) {
            List<Map<String,Object>> users=db.queryForList("SELECT id,notify_mention FROM users WHERE username=?",matcher.group(1));
            if (users.isEmpty() || number(users.getFirst(),"id")==actor) continue;
            long recipient=number(users.getFirst(),"id");
            if ("message".equals(type) && db.queryForList("SELECT 1 FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE m.id=? AND (c.user_a_id=? OR c.user_b_id=?)",target,recipient,recipient).isEmpty()) continue;
            if (db.update("INSERT IGNORE INTO mentions(user_id,actor_id,target_type,target_id) VALUES(?,?,?,?)",recipient,actor,type,target)>0 && Boolean.TRUE.equals(users.getFirst().get("notify_mention"))) mapper.notifyUser(recipient,"mention","有人在"+("message".equals(type)?"私信":"讨论")+"中提及了你",postId);
        }
    }
    List<Map<String,Object>> sortedReplies(long postId,String sort) {
        if (!Set.of("earliest","latest","hot").contains(sort)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"回复排序无效");
        return db.queryForList("SELECT r.*,u.username,u.points AS author_points,q.content AS quote_content FROM replies r JOIN users u ON u.id=r.author_id LEFT JOIN replies q ON q.id=r.quote_reply_id AND q.status='published' WHERE r.post_id=? AND r.status='published' ORDER BY r.is_accepted DESC,"+("latest".equals(sort)?"r.created_at DESC":"hot".equals(sort)?"u.points DESC,r.created_at DESC":"r.created_at ASC")+" LIMIT 200",postId);
    }
    long id(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        return Long.parseLong(auth.getName());
    }
    Map<String,Object> require(Map<String,Object> value) {
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"内容不存在");
        return value;
    }
    long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    boolean flag(Map<String,Object> row,String key) { return Boolean.TRUE.equals(row.get(key)); }
    Map<String,Object> current(Authentication auth) { return require(mapper.user(id(auth))); }
    void writable(Authentication auth) {
        if (!"active".equals(current(auth).get("status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号暂不能发布内容");
        if (number(current(auth),"reputation")<40) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"信誉分过低，暂不能发布或私信");
    }
    void moderate(Authentication auth,long boardId) {
        Map<String,Object> user = current(auth);
        if (!"active".equals(user.get("status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号暂不能管理内容");
        if ("admin".equals(user.get("role"))) return;
        if ("moderator".equals(user.get("role")) && user.get("moderator_board_id") != null && number(user,"moderator_board_id") == boardId) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无此板块管理权限");
    }
    void admin(Authentication auth) {
        if (!"admin".equals(current(auth).get("role")) || !"active".equals(current(auth).get("status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可操作");
    }
    void staff(Authentication auth) {
        if (!Set.of("admin","moderator").contains(current(auth).get("role")) || !"active".equals(current(auth).get("status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理人员可操作");
    }
    void text(String value,int min,int max) {
        if (value == null || value.trim().length() < min || value.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"内容长度不符合要求");
        if (value.toLowerCase().matches("(?s).*(赌博|色情广告|代写论文).*") ) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"内容包含不允许的词语");
    }
    String image(Object value) {
        if (value == null || "".equals(value)) return null;
        String path = String.valueOf(value);
        if (!path.matches("/uploads/[0-9a-fA-F-]{36}\\.(jpg|png|webp)")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"图片路径无效");
        return path;
    }
    String attachment(Object value) {
        if (value==null || "".equals(value)) return null;
        String path=String.valueOf(value);
        if (!path.matches("/uploads/[0-9a-fA-F-]{36}\\.(pdf|txt|zip)")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"附件路径无效");
        return path;
    }
    @Transactional void reward(long userId,String action,int amount,String refType,Long refId,int dailyCap) {
        if (dailyCap > 0 && mapper.dailyPoints(userId,action) >= dailyCap) return;
        mapper.addPoints(userId,amount);
        mapper.pointLog(userId,action,amount,mapper.points(userId),refType,refId);
    }
    @Transactional public Map<String,Object> publish(Authentication auth,Map<String,Object> input) {
        writable(auth);
        return createPost(id(auth),input);
    }
    @Transactional Map<String,Object> createPost(long author,Map<String,Object> input) {
        String title = (String)input.get("title"), content = (String)input.get("content"), type = (String)input.get("type");
        text(title,5,160); text(content,10,10000);
        if (!Set.of("question","discussion","experience").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"帖子类型无效");
        long boardId = ((Number)input.get("boardId")).longValue(); require(mapper.board(boardId));
        Map<String,Object> post = new java.util.HashMap<>();
        post.put("boardId",boardId); post.put("authorId",author); post.put("type",type); post.put("title",title.trim()); post.put("content",content.trim()); post.put("imagePath",image(input.get("imagePath")));
        mapper.insertPost(post);
        long postId = ((Number)post.get("id")).longValue();
        String attachment=attachment(input.get("attachmentPath"));
        if (attachment!=null) db.update("UPDATE posts SET attachment_path=? WHERE id=?",attachment,postId);
        String cover=image(input.get("coverPath"));
        if (cover!=null) db.update("UPDATE posts SET cover_path=? WHERE id=?",cover,postId);
        if (input.get("tagIds") instanceof List<?> tags) for (Object tag : tags) {
            long tagId = ((Number)tag).longValue(); require(mapper.tag(tagId)); mapper.postTag(postId,tagId);
        }
        reward(author,"post",2,"post",postId,5);
        db.update("INSERT INTO post_revisions(post_id,editor_id,title,content) VALUES(?,?,?,?)",postId,author,title.trim(),content.trim());
        mentions(author,title+" "+content,"post",postId,postId);
        return mapper.post(postId);
    }
    @Transactional public Map<String,Object> reply(Authentication auth,long postId,Map<String,Object> input) {
        writable(auth);
        Map<String,Object> post = require(mapper.post(postId));
        if (!"published".equals(post.get("status"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"帖子不存在");
        String content = (String)input.get("content"); text(content,2,10000);
        boolean prior = mapper.priorReply(postId,id(auth)) > 0;
        Map<String,Object> reply = new java.util.HashMap<>();
        reply.put("postId",postId); reply.put("authorId",id(auth)); reply.put("content",content.trim()); reply.put("imagePath",image(input.get("imagePath")));
        mapper.insertReply(reply);
        long replyId = ((Number)reply.get("id")).longValue();
        if (input.get("quoteReplyId") instanceof Number quoted) {
            Map<String,Object> source=require(mapper.reply(quoted.longValue()));
            if (number(source,"post_id")!=postId || !"published".equals(source.get("status"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"引用回复无效");
            db.update("UPDATE replies SET quote_reply_id=? WHERE id=?",quoted.longValue(),replyId);
        }
        db.update("INSERT INTO reply_revisions(reply_id,editor_id,content) VALUES(?,?,?)",replyId,id(auth),content.trim());
        mentions(id(auth),content,"reply",replyId,postId);
        if (!prior) reward(id(auth),"reply",1,"reply",replyId,5);
        if (number(post,"author_id") != id(auth)) mapper.notifyUser(number(post,"author_id"),"reply","你的帖子收到了新回复",postId);
        return mapper.reply(replyId);
    }
    @Transactional public void accept(Authentication auth,long postId,long replyId) {
        Map<String,Object> post = require(mapper.post(postId)), reply = require(mapper.reply(replyId));
        if (!"question".equals(post.get("type")) || number(post,"author_id") != id(auth) || !"published".equals(post.get("status")) || number(reply,"post_id") != postId || !"published".equals(reply.get("status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只能采纳自己问答帖中的有效回复");
        if (number(reply,"author_id")==id(auth)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不能采纳自己的回答");
        if (mapper.acceptPost(postId,replyId) == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"此问题已有最佳答案");
        mapper.acceptReply(replyId);
        Map<String,Object> bounty=db.queryForList("SELECT amount FROM bounties WHERE post_id=? AND status='open'",postId).stream().findFirst().orElse(null);
        if (bounty!=null) {
            int amount=((Number)bounty.get("amount")).intValue();
            db.update("UPDATE bounties SET status='awarded',recipient_id=? WHERE post_id=?",number(reply,"author_id"),postId);
            db.update("UPDATE users SET points=points+? WHERE id=?",amount,number(reply,"author_id"));
            mapper.pointLog(number(reply,"author_id"),"bounty_award",amount,mapper.points(number(reply,"author_id")),"post",postId);
        }
        reward(number(reply,"author_id"),"accepted",10,"reply",replyId,0);
        changeReputation(number(reply,"author_id"),id(auth),2,"回答被采纳");
        mapper.notifyUser(number(reply,"author_id"),"accepted","你的回答被采纳为最佳答案",postId);
    }
    @Transactional public void report(Authentication auth,Map<String,Object> input) {
        String type = (String)input.get("targetType"), reason = (String)input.get("reason"), description = (String)input.getOrDefault("description","");
        if (type == null || !Set.of("post","reply","user").contains(type) || reason == null || !Set.of("广告垃圾","人身攻击","抄袭侵权","色情暴力","政治敏感","其他").contains(reason) || description.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"举报内容无效");
        long targetId = ((Number)input.get("targetId")).longValue();
        if ("post".equals(type)) require(mapper.post(targetId));
        if ("reply".equals(type)) require(mapper.reply(targetId));
        if ("user".equals(type)) require(mapper.user(targetId));
        if (mapper.dailyReports(id(auth)) >= 5) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"今日举报次数已达上限");
        mapper.report(id(auth),type,targetId,reason,description);
    }
    @Transactional public void feature(Authentication auth,long postId,boolean featured) {
        Map<String,Object> post = require(mapper.post(postId)); moderate(auth,number(post,"board_id"));
        if (!"published".equals(post.get("status"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"帖子不可加精");
        if (flag(post,"is_featured") == featured) return;
        if (mapper.feature(postId,featured)==0) return;
        if (featured) {
            long author=number(post,"author_id");
            if (mapper.rewardExists(author,"featured","post",postId)==0) { reward(author,"featured",20,"post",postId,0); changeReputation(author,id(auth),2,"帖子加精"); }
            mapper.notifyUser(author,"featured","你的帖子被设为精华",postId);
        }
        mapper.adminLog(id(auth),"feature","post",postId,String.valueOf(featured));
    }
    @Transactional public void handle(Authentication auth,long reportId,Map<String,Object> input) {
        Map<String,Object> report = require(mapper.reportById(reportId));
        String type = (String)report.get("target_type"); long targetId = number(report,"target_id");
        long boardId = -1;
        if ("post".equals(type)) boardId = number(require(mapper.post(targetId)),"board_id");
        if ("reply".equals(type)) boardId = number(require(mapper.post(number(require(mapper.reply(targetId)),"post_id"))),"board_id");
        if (boardId == -1) admin(auth); else moderate(auth,boardId);
        String action = (String)input.get("action"), note = (String)input.getOrDefault("note","");
        if (action == null || !Set.of("reject","hide","delete","mute","ban").contains(action) || note.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"处理方式无效");
        if ("user".equals(type) && Set.of("hide","delete").contains(action) || "message".equals(type) && "hide".equals(action)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"处理方式不适用");
        if (Set.of("mute","ban").contains(action)) admin(auth);
        if (mapper.handleReport(reportId,"reject".equals(action)?"rejected":"resolved",id(auth),note) == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"举报已处理");
        if (Set.of("hide","delete").contains(action)) {
            if ("post".equals(type)) mapper.postStatus(targetId,"hide".equals(action)?"hidden":"deleted");
            if ("reply".equals(type)) mapper.deleteReply(targetId);
            if ("message".equals(type)) db.update("UPDATE messages SET content='[已删除]',type='text' WHERE id=?",targetId);
        }
        if (Set.of("mute","ban").contains(action)) {
            long userId = "user".equals(type)?targetId:"post".equals(type)?number(mapper.post(targetId),"author_id"):"message".equals(type)?number(db.queryForList("SELECT sender_id FROM messages WHERE id=?",targetId).getFirst(),"sender_id"):number(mapper.reply(targetId),"author_id");
            mapper.userStatus(userId,"mute".equals(action)?"muted":"banned");
        }
        if (!"reject".equals(action)) {
            long offender="user".equals(type)?targetId:"post".equals(type)?number(mapper.post(targetId),"author_id"):"message".equals(type)?number(db.queryForList("SELECT sender_id FROM messages WHERE id=?",targetId).getFirst(),"sender_id"):number(mapper.reply(targetId),"author_id");
            changeReputation(offender,id(auth),"ban".equals(action)?-20:-10,"举报处理 #"+reportId);
        }
        String result = switch (action) {
            case "reject" -> "已驳回";
            case "hide" -> "已隐藏内容";
            case "delete" -> "已删除内容";
            case "mute" -> "已禁言用户";
            case "ban" -> "已封禁用户";
            default -> throw new IllegalStateException("未知处理方式");
        };
        String message = "你的举报处理结果："+result+(note.isBlank()?"":"。处理备注："+note);
        mapper.notifyUser(number(report,"reporter_id"),"report",message.length()>255?message.substring(0,255):message,null);
        if (!"reject".equals(action)) reward(number(report,"reporter_id"),"report_valid",2,"report",reportId,2);
        mapper.adminLog(id(auth),"report_"+action,type,targetId,note);
    }
    void changeReputation(long user,long actor,int amount,String reason) {
        db.update("UPDATE users SET reputation=GREATEST(0,LEAST(200,reputation+?)) WHERE id=?",amount,user);
        db.update("INSERT INTO reputation_logs(user_id,actor_id,amount,balance_after,reason) VALUES(?,?,?,?,?)",user,actor,amount,number(require(mapper.user(user)),"reputation"),reason);
    }
}
