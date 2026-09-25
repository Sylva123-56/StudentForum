package com.studentforum;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ForumService {
    private final ForumMapper mapper;
    ForumService(ForumMapper mapper) { this.mapper = mapper; }
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
    }
    void moderate(Authentication auth,long boardId) {
        Map<String,Object> user = current(auth);
        if ("admin".equals(user.get("role"))) return;
        if ("moderator".equals(user.get("role")) && user.get("moderator_board_id") != null && number(user,"moderator_board_id") == boardId) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无此板块管理权限");
    }
    void admin(Authentication auth) {
        if (!"admin".equals(current(auth).get("role"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可操作");
    }
    void staff(Authentication auth) {
        if (!Set.of("admin","moderator").contains(current(auth).get("role"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理人员可操作");
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
    @Transactional void reward(long userId,String action,int amount,String refType,Long refId,int dailyCap) {
        if (dailyCap > 0 && mapper.dailyPoints(userId,action) >= dailyCap) return;
        mapper.addPoints(userId,amount);
        mapper.pointLog(userId,action,amount,mapper.points(userId),refType,refId);
    }
    @Transactional public Map<String,Object> publish(Authentication auth,Map<String,Object> input) {
        writable(auth);
        String title = (String)input.get("title"), content = (String)input.get("content"), type = (String)input.get("type");
        text(title,5,160); text(content,10,10000);
        if (!Set.of("question","discussion","experience").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"帖子类型无效");
        long boardId = ((Number)input.get("boardId")).longValue(); require(mapper.board(boardId));
        Map<String,Object> post = new java.util.HashMap<>();
        post.put("boardId",boardId); post.put("authorId",id(auth)); post.put("type",type); post.put("title",title.trim()); post.put("content",content.trim()); post.put("imagePath",image(input.get("imagePath")));
        mapper.insertPost(post);
        long postId = ((Number)post.get("id")).longValue();
        if (input.get("tagIds") instanceof List<?> tags) for (Object tag : tags) {
            long tagId = ((Number)tag).longValue(); require(mapper.tag(tagId)); mapper.postTag(postId,tagId);
        }
        reward(id(auth),"post",2,"post",postId,5);
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
        if (!prior) reward(id(auth),"reply",1,"reply",replyId,5);
        if (number(post,"author_id") != id(auth)) mapper.notifyUser(number(post,"author_id"),"reply","你的帖子收到了新回复",postId);
        return mapper.reply(replyId);
    }
    @Transactional public void accept(Authentication auth,long postId,long replyId) {
        Map<String,Object> post = require(mapper.post(postId)), reply = require(mapper.reply(replyId));
        if (!"question".equals(post.get("type")) || number(post,"author_id") != id(auth) || !"published".equals(post.get("status")) || number(reply,"post_id") != postId || !"published".equals(reply.get("status"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只能采纳自己问答帖中的有效回复");
        if (mapper.acceptPost(postId,replyId) == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"此问题已有最佳答案");
        mapper.acceptReply(replyId);
        reward(number(reply,"author_id"),"accepted",10,"reply",replyId,0);
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
            if (mapper.rewardExists(author,"featured","post",postId)==0) reward(author,"featured",20,"post",postId,0);
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
        if ("user".equals(type) && Set.of("hide","delete").contains(action)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"用户举报不能执行内容操作");
        if (Set.of("mute","ban").contains(action)) admin(auth);
        if (mapper.handleReport(reportId,"reject".equals(action)?"rejected":"resolved",id(auth),note) == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"举报已处理");
        if (Set.of("hide","delete").contains(action)) {
            if ("post".equals(type)) mapper.postStatus(targetId,"hide".equals(action)?"hidden":"deleted");
            if ("reply".equals(type)) mapper.deleteReply(targetId);
        }
        if (Set.of("mute","ban").contains(action)) {
            long userId = "user".equals(type)?targetId:"post".equals(type)?number(mapper.post(targetId),"author_id"):number(mapper.reply(targetId),"author_id");
            mapper.userStatus(userId,"mute".equals(action)?"muted":"banned");
        }
        mapper.notifyUser(number(report,"reporter_id"),"report","你的举报已有处理结果："+action,null);
        if (!"reject".equals(action)) reward(number(report,"reporter_id"),"report_valid",2,"report",reportId,2);
        mapper.adminLog(id(auth),"report_"+action,type,targetId,note);
    }
}
