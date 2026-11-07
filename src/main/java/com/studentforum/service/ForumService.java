package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.model.Post;
import com.studentforum.model.ContentRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import com.studentforum.repository.ForumRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ForumService {
    private final ForumMapper mapper;
    private final ForumRepository repository;
    private final AccountAccessService accounts;
    private final PointService points;
    private final NotificationService notifications;
    private Path directory;
    public ForumService(ForumMapper mapper, ForumRepository repository, AccountAccessService accounts,
                        PointService points, NotificationService notifications) {
        this.mapper = mapper;
        this.repository = repository;
        this.accounts = accounts;
        this.points = points;
        this.notifications = notifications;
    }
    @Value("${forum.uploads}") public void uploads(String uploadPath) { this.directory = Path.of(uploadPath).toAbsolutePath().normalize(); }
    /**
     * 作者自助删除帖子：真的是从数据库里删掉，而不是把 status 改成 deleted。
     * replies / post_tags / favorites 有 V6 迁移加上的 ON DELETE CASCADE 外键兜底，这里也显式删一遍，
     * 这样即使迁移没跑也不会因为外键报 1451。
     * 其余没有外键、又指着这篇帖子的表在这里手动清；积分流水(point_logs)、审计(admin_logs)、
     * 举报与申诉记录(reports/appeals) 保留不动。
     */
    @Transactional public void hardDeletePost(long postId) {
        Map<String, Object> post = repository.uploads(postId);
        repository.deletePost(postId);
        // 数据库回滚时必须保留文件，仅在最外层事务提交后清理。
        Runnable cleanup = () -> {
            for (String column : List.of("image_path", "cover_path", "attachment_path")) removeUpload(post.get(column));
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup.run(); }
            });
        } else {
            cleanup.run();
        }
    }
    private void removeUpload(Object path) {
        if (path == null || directory == null) return;
        String value = String.valueOf(path);
        if (!value.matches("/uploads/[0-9a-fA-F-]{36}\\.[A-Za-z0-9]{1,5}")) return;
        try { Files.deleteIfExists(directory.resolve(value.substring("/uploads/".length()))); } catch (IOException ignored) { }
    }
    public void revision(Authentication auth,long postId,String title,String content) {
        repository.revision(postId, accounts.id(auth), title, content);
        notifications.mentions(accounts.id(auth), title + " " + content, "post", postId, postId);
    }

    public Map<String,Object> require(Map<String,Object> value) {
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"内容不存在");
        return value;
    }
    public long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public boolean flag(Map<String,Object> row,String key) { return Boolean.TRUE.equals(row.get(key)); }

    @Transactional public Map<String,Object> publish(Authentication auth,Map<String,Object> input) {
        accounts.writable(auth);
        return createPost(accounts.id(auth),input);
    }
    @Transactional public Map<String,Object> createPost(long author,Map<String,Object> input) {
        String title = (String)input.get("title"), content = (String)input.get("content"), type = (String)input.get("type");
        ContentRules.text(title,5,160); ContentRules.text(content,10,10000);
        if (!Set.of("question","discussion","experience").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"帖子类型无效");
        long boardId = ((Number)input.get("boardId")).longValue(); require(mapper.board(boardId));
        Map<String,Object> post = new java.util.HashMap<>();
        post.put("boardId",boardId); post.put("authorId",author); post.put("type",type); post.put("title",title.trim()); post.put("content",content.trim()); post.put("imagePath",ContentRules.image(input.get("imagePath")));
        mapper.insertPost(post);
        long postId = ((Number)post.get("id")).longValue();
        String attachment=ContentRules.attachment(input.get("attachmentPath"));
        if (attachment!=null) repository.attachment(postId, attachment);
        String cover=ContentRules.image(input.get("coverPath"));
        if (cover!=null) repository.cover(postId, cover);
        if (input.get("tagIds") instanceof List<?> tags) for (Object tag : tags) {
            long tagId = ((Number)tag).longValue(); require(mapper.tag(tagId)); mapper.postTag(postId,tagId);
        }
        points.reward(author,"post",2,"post",postId,5);
        repository.revision(postId, author, title.trim(), content.trim());
        notifications.mentions(author,title+" "+content,"post",postId,postId);
        return mapper.post(postId);
    }
    @Transactional public Map<String,Object> reply(Authentication auth,long postId,Map<String,Object> input) {
        accounts.writable(auth);
        Map<String,Object> post = require(mapper.post(postId));
        if (!"published".equals(post.get("status"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"帖子不存在");
        String content = (String)input.get("content"); ContentRules.text(content,2,10000);
        boolean prior = mapper.priorReply(postId,accounts.id(auth)) > 0;
        Map<String,Object> reply = new java.util.HashMap<>();
        reply.put("postId",postId); reply.put("authorId",accounts.id(auth)); reply.put("content",content.trim()); reply.put("imagePath",ContentRules.image(input.get("imagePath")));
        mapper.insertReply(reply);
        long replyId = ((Number)reply.get("id")).longValue();
        if (input.get("quoteReplyId") instanceof Number quoted) {
            Map<String,Object> source=require(mapper.reply(quoted.longValue()));
            if (number(source,"post_id")!=postId || !"published".equals(source.get("status"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"引用回复无效");
            repository.quote(replyId, quoted.longValue());
        }
        repository.replyRevision(replyId, accounts.id(auth), content.trim());
        notifications.mentions(accounts.id(auth),content,"reply",replyId,postId);
        if (!prior) points.reward(accounts.id(auth),"reply",1,"reply",replyId,5);
        if (number(post,"author_id") != accounts.id(auth)) notifications.notifyUser(number(post,"author_id"),"reply","你的帖子收到了新回复",postId);
        return mapper.reply(replyId);
    }
    @Transactional public void accept(Authentication auth,long postId,long replyId) {
        Map<String,Object> post = require(mapper.post(postId)), reply = require(mapper.reply(replyId));
        Post question = new Post(number(post, "author_id"),
                String.valueOf(post.get("type")), String.valueOf(post.get("status")));
        if (!question.acceptsAnswerFrom(accounts.id(auth))
                || number(reply, "post_id") != postId
                || !"published".equals(reply.get("status")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能采纳自己问答帖中的有效回复");
        if (number(reply,"author_id")==accounts.id(auth)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不能采纳自己的回答");
        if (mapper.acceptPost(postId,replyId) == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"此问题已有最佳答案");
        mapper.acceptReply(replyId);
        Map<String,Object> bounty=repository.openBounty(postId);
        if (bounty!=null) {
            int amount=((Number)bounty.get("amount")).intValue();
            repository.awardBounty(postId, number(reply,"author_id"));
            points.reward(number(reply,"author_id"), "bounty_award", amount, "post", postId, 0);
        }
        points.reward(number(reply,"author_id"),"accepted",10,"reply",replyId,0);
        points.changeReputation(number(reply,"author_id"),accounts.id(auth),2,"回答被采纳");
        notifications.notifyUser(number(reply,"author_id"),"accepted","你的回答被采纳为最佳答案",postId);
    }
    @Transactional public void report(Authentication auth,Map<String,Object> input) {
        String type = (String)input.get("targetType"), reason = (String)input.get("reason"), description = (String)input.getOrDefault("description","");
        if (type == null || !Set.of("post","reply","user").contains(type) || reason == null || !Set.of("广告垃圾","人身攻击","抄袭侵权","色情暴力","政治敏感","其他").contains(reason) || description.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"举报内容无效");
        long targetId = ((Number)input.get("targetId")).longValue();
        long reporter = accounts.id(auth);
        if ("post".equals(type)) {
            Map<String,Object> post = require(mapper.post(targetId));
            if (number(post,"author_id") == reporter) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不能举报自己的帖子");
        }
        if ("reply".equals(type)) {
            Map<String,Object> reply = require(mapper.reply(targetId));
            if (number(reply,"author_id") == reporter) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不能举报自己的回复");
        }
        if ("user".equals(type)) {
            require(mapper.user(targetId));
            if (targetId == reporter) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不能举报自己");
        }
        if (mapper.dailyReports(accounts.id(auth)) >= 5) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"今日举报次数已达上限");
        mapper.report(accounts.id(auth),type,targetId,reason,description);
    }
    @Transactional public void feature(Authentication auth,long postId,boolean featured) {
        Map<String,Object> post = require(mapper.post(postId)); accounts.moderate(auth,number(post,"board_id"));
        if (!"published".equals(post.get("status"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"帖子不可加精");
        if (flag(post,"is_featured") == featured) return;
        if (mapper.feature(postId,featured)==0) return;
        if (featured) {
            long author=number(post,"author_id");
            if (mapper.rewardExists(author,"featured","post",postId)==0) { points.reward(author,"featured",20,"post",postId,0); points.changeReputation(author,accounts.id(auth),2,"帖子加精"); }
            notifications.notifyUser(author,"featured","你的帖子被设为精华",postId);
        }
        mapper.adminLog(accounts.id(auth),"feature","post",postId,String.valueOf(featured));
    }
    @Transactional public void handle(Authentication auth,long reportId,Map<String,Object> input) {
        Map<String,Object> report = require(mapper.reportById(reportId));
        String type = (String)report.get("target_type"); long targetId = number(report,"target_id");
        long boardId = -1;
        if ("post".equals(type)) boardId = number(require(mapper.post(targetId)),"board_id");
        if ("reply".equals(type)) boardId = number(require(mapper.post(number(require(mapper.reply(targetId)),"post_id"))),"board_id");
        if (boardId == -1) accounts.admin(auth); else accounts.moderate(auth,boardId);
        String action = (String)input.get("action"), note = (String)input.getOrDefault("note","");
        if (action == null || !Set.of("reject","hide","delete","mute","ban").contains(action) || note.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"处理方式无效");
        if ("user".equals(type) && Set.of("hide","delete").contains(action) || "message".equals(type) && "hide".equals(action)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"处理方式不适用");
        if (Set.of("mute","ban").contains(action)) accounts.admin(auth);
        if (mapper.handleReport(reportId,"reject".equals(action)?"rejected":"resolved",accounts.id(auth),note) == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"举报已处理");
        if (Set.of("hide","delete").contains(action)) {
            if ("post".equals(type)) mapper.postStatus(targetId,"hide".equals(action)?"hidden":"deleted");
            if ("reply".equals(type)) mapper.deleteReply(targetId);
            if ("message".equals(type)) repository.deleteMessage(targetId);
            if ("resource".equals(type)) repository.rejectResource(targetId);
        }
        if (!"reject".equals(action)) {
            long offender = reportedUser(type, targetId);
            if (Set.of("mute", "ban").contains(action)) {
                mapper.userStatus(offender, "mute".equals(action) ? "muted" : "banned");
            }
            points.changeReputation(offender, accounts.id(auth), "ban".equals(action) ? -20 : -10, "举报处理 #" + reportId);
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
        notifications.notifyUser(number(report,"reporter_id"),"report",message.length()>255?message.substring(0,255):message,null);
        if (!"reject".equals(action)) points.reward(number(report,"reporter_id"),"report_valid",2,"report",reportId,2);
        mapper.adminLog(accounts.id(auth),"report_"+action,type,targetId,note);
    }

    public void deleteReply(Authentication auth, long id) {
        Map<String, Object> reply = require(mapper.reply(id));
        if (((Number) reply.get("author_id")).longValue() != accounts.id(auth) || Boolean.TRUE.equals(reply.get("is_accepted")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        mapper.deleteReply(id);
    }

    public void favorite(Authentication auth, long id) {
        if (!"published".equals(require(mapper.post(id)).get("status")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        mapper.favorite(accounts.id(auth), id);
    }

    public void unfavorite(Authentication auth, long id) {
        mapper.unfavorite(accounts.id(auth), id);
    }

    private long reportedUser(String type, long targetId) {
        return switch (type) {
            case "user" -> targetId;
            case "post" -> number(require(mapper.post(targetId)), "author_id");
            case "resource" -> number(require(repository.resourceAuthor(targetId)), "uploader_id");
            case "message" -> number(require(repository.messageAuthor(targetId)), "sender_id");
            default -> number(require(mapper.reply(targetId)), "author_id");
        };
    }
}
