package com.studentforum.service;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.dto.AdminPostStatusRequest;
import com.studentforum.model.ModerationScope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {
    private final ForumMapper mapper;
    private final ForumService service;
    private final AccountAccessService accounts;
    public AdminService(ForumMapper mapper,ForumService service,AccountAccessService accounts) { this.mapper=mapper; this.service=service; this.accounts=accounts; }
    public Map<String,Object> dashboard(Authentication auth) { accounts.staff(auth); return mapper.dashboard(); }
    private String keyword(String keyword) {
        String value=keyword==null?"":keyword.trim();
        if (value.length()>60) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"搜索关键词不能超过 60 个字");
        return value.isEmpty()?null:value;
    }
    private int offset(int page) {
        if (page<1 || page>10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"页码无效");
        return (page-1)*PAGE_SIZE;
    }
    private Map<String,Object> paged(List<Map<String,Object>> rows) {
        boolean hasNext=rows.size()>PAGE_SIZE;
        return Map.of("items",hasNext?rows.subList(0,PAGE_SIZE):rows,"hasNext",hasNext);
    }
    private static final int PAGE_SIZE=20;
    public Map<String,Object> users(Authentication auth,String keyword,int page) {
        accounts.admin(auth); return paged(mapper.users(keyword(keyword),PAGE_SIZE+1,offset(page)));
    }
    private ModerationScope scope(Map<String,Object> user) {
        Long boardId=user.get("moderator_board_id")==null?null:service.number(user,"moderator_board_id");
        return new ModerationScope(String.valueOf(user.get("role")),boardId);
    }

    public Map<String,Object> posts(Authentication auth,String keyword,int page) {
        accounts.staff(auth);
        Map<String,Object> user=accounts.current(auth);
        List<Long> boards=scope(user).postBoards();
        return paged(mapper.adminPosts(keyword(keyword),boards,PAGE_SIZE+1,offset(page)));
    }
    public Map<String,Object> reports(Authentication auth,String keyword,int page) {
        accounts.staff(auth);
        Map<String,Object> user=accounts.current(auth);
        Long boardId=scope(user).reportBoard();
        return paged(mapper.reports(keyword(keyword),boardId,PAGE_SIZE+1,offset(page)));
    }
    public void feature(Authentication auth,long id,Map<String,Boolean> input) { service.feature(auth,id,Boolean.TRUE.equals(input.get("featured"))); }
    public void top(Authentication auth,long id,Map<String,Boolean> input) {
        Map<String,Object> post=service.require(mapper.post(id)); accounts.moderate(auth,service.number(post,"board_id")); mapper.top(id,Boolean.TRUE.equals(input.get("top"))); mapper.adminLog(accounts.id(auth),"top","post",id,String.valueOf(input.get("top")));
    }
    public void status(Authentication auth,long id,AdminPostStatusRequest input) {
        Map<String,Object> post=service.require(mapper.post(id)); accounts.moderate(auth,service.number(post,"board_id"));
        String status=input.status(); if (!Set.of("published","hidden","deleted").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        mapper.postStatus(id,status); mapper.adminLog(accounts.id(auth),"status","post",id,status);
    }
    public void handle(Authentication auth,long id,Map<String,Object> input) { service.handle(auth,id,input); }
    public void userStatus(Authentication auth,long id,Map<String,String> input) {
        accounts.admin(auth); String status=input.get("status"); if (!Set.of("active","muted","banned").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        service.require(mapper.user(id)); mapper.userStatus(id,status); mapper.adminLog(accounts.id(auth),"user_status","user",id,status);
    }
    public void role(Authentication auth,long id,Map<String,Object> input) {
        accounts.admin(auth); String role=String.valueOf(input.get("role"));
        if (!Set.of("student","moderator","admin").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Long board=input.get("boardId")==null?null:((Number)input.get("boardId")).longValue();
        String boardName="";
        if ("moderator".equals(role)) {
            if (board==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择版主负责的板块");
            boardName=String.valueOf(service.require(mapper.board(board)).get("name"));
        }
        Map<String,Object> user=service.require(mapper.user(id));
        if (accounts.id(auth)==id && !"admin".equals(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不能变更自己的管理员角色");
        if ("admin".equals(user.get("role")) && !"admin".equals(role) && mapper.adminCount()<=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"至少需要保留一位管理员");
        mapper.userRole(id,role,"moderator".equals(role)?board:null);
        mapper.adminLog(accounts.id(auth),"role","user",id,"moderator".equals(role)?role+" · "+boardName:role);
    }
    @Transactional public void adjust(Authentication auth,long id,Map<String,Object> input) {
        accounts.admin(auth); service.require(mapper.user(id)); int amount=((Number)input.get("amount")).intValue();
        if (amount==0 || Math.abs(amount)>1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"积分调整范围为 -1000 到 1000，且不能为 0");
        int before=mapper.points(id); mapper.addPoints(id,amount); int after=mapper.points(id);
        mapper.pointLog(id,"adjust",after-before,after,"user",id); mapper.adminLog(accounts.id(auth),"points","user",id,String.valueOf(amount));
    }
    public Map<String,Object> pointLogs(Authentication auth,String keyword,int page) {
        accounts.admin(auth); return paged(mapper.adminLogs(keyword(keyword),PAGE_SIZE+1,offset(page)));
    }
    public Map<String,Object> boardList(Authentication auth,String keyword,int page) {
        accounts.admin(auth); return paged(mapper.adminBoards(keyword(keyword),PAGE_SIZE+1,offset(page)));
    }
    public Map<String,Object> tagList(Authentication auth,String keyword,int page) {
        accounts.admin(auth); return paged(mapper.adminTags(keyword(keyword),PAGE_SIZE+1,offset(page)));
    }
    private String boardName(Map<String,Object> input) {
        String name=String.valueOf(input.getOrDefault("name","")).trim();
        if (name.length()<2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"板块名称至少需要 2 个字");
        if (name.length()>50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"板块名称不能超过 50 个字");
        return name;
    }
    private String boardSlug(Map<String,Object> input) {
        String slug=String.valueOf(input.getOrDefault("slug","")).trim();
        if (!slug.matches("[a-z0-9-]{2,80}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"板块标识只能使用 2-80 位小写字母、数字或短横线");
        return slug;
    }
    private String boardDescription(Map<String,Object> input) {
        String description=String.valueOf(input.getOrDefault("description","")).trim();
        if (description.length()>255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"板块简介不能超过 255 个字");
        return description;
    }
    private int sortOrder(Map<String,Object> input) {
        int order=input.get("sortOrder") instanceof Number value?value.intValue():0;
        if (order<0 || order>9999) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"排序值范围为 0 到 9999");
        return order;
    }
    private String tagName(Map<String,String> input) {
        String name=input.getOrDefault("name","").trim();
        if (name.length()<2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"标签名称至少需要 2 个字");
        if (name.length()>40) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"标签名称不能超过 40 个字");
        return name;
    }
    public void board(Authentication auth,Map<String,Object> input) {
        accounts.admin(auth); String name=boardName(input),slug=boardSlug(input);
        if (mapper.boardSlugTaken(slug,0)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块标识已被使用");
        mapper.addBoard(name,slug,boardDescription(input),sortOrder(input)); mapper.adminLog(accounts.id(auth),"create","board",0,name);
    }
    public void editBoard(Authentication auth,long id,Map<String,Object> input) {
        accounts.admin(auth); Map<String,Object> board=service.require(mapper.adminBoard(id));
        String name=boardName(input),slug=boardSlug(input);
        if (mapper.boardSlugTaken(slug,id)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块标识已被使用");
        mapper.editBoard(id,name,slug,boardDescription(input),sortOrder(input));
        mapper.adminLog(accounts.id(auth),"edit","board",id,String.valueOf(board.get("name"))+" → "+name);
    }
    public void boardStatus(Authentication auth,long id,Map<String,String> input) {
        accounts.admin(auth); Map<String,Object> board=service.require(mapper.adminBoard(id));
        String status=input.getOrDefault("status","");
        if (!Set.of("enabled","disabled").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"板块状态无效");
        if (status.equals(board.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块已处于该状态");
        if (("enabled".equals(status)?mapper.enableBoard(id):mapper.disableBoard(id))==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块状态已发生变化");
        mapper.adminLog(accounts.id(auth),"enabled".equals(status)?"enable":"disable","board",id,String.valueOf(board.get("name")));
    }
    public void deleteBoard(Authentication auth,long id) {
        accounts.admin(auth); Map<String,Object> board=service.require(mapper.adminBoard(id));
        if (!"enabled".equals(board.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块已经停用");
        if (mapper.disableBoard(id)==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块状态已发生变化");
        mapper.adminLog(accounts.id(auth),"disable","board",id,String.valueOf(board.get("name")));
    }
    public void tag(Authentication auth,Map<String,String> input) {
        accounts.admin(auth); String name=tagName(input);
        if (mapper.tagNameTaken(name,0)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签名称已存在");
        mapper.addTag(name); mapper.adminLog(accounts.id(auth),"create","tag",0,name);
    }
    public void editTag(Authentication auth,long id,Map<String,String> input) {
        accounts.admin(auth); Map<String,Object> tag=service.require(mapper.adminTag(id)); String name=tagName(input);
        if (mapper.tagNameTaken(name,id)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签名称已存在");
        mapper.editTag(id,name); mapper.adminLog(accounts.id(auth),"edit","tag",id,String.valueOf(tag.get("name"))+" → "+name);
    }
    public void tagStatus(Authentication auth,long id,Map<String,String> input) {
        accounts.admin(auth); Map<String,Object> tag=service.require(mapper.adminTag(id));
        String status=input.getOrDefault("status","");
        if (!Set.of("enabled","disabled").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"标签状态无效");
        if (status.equals(tag.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签已处于该状态");
        if (("enabled".equals(status)?mapper.enableTag(id):mapper.disableTag(id))==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签状态已发生变化");
        mapper.adminLog(accounts.id(auth),"enabled".equals(status)?"enable":"disable","tag",id,String.valueOf(tag.get("name")));
    }
    public void deleteTag(Authentication auth,long id) {
        accounts.admin(auth); Map<String,Object> tag=service.require(mapper.adminTag(id));
        if (!"enabled".equals(tag.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签已经停用");
        if (mapper.disableTag(id)==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签状态已发生变化");
        mapper.adminLog(accounts.id(auth),"disable","tag",id,String.valueOf(tag.get("name")));
    }
    private String announcementMessage(Map<String,String> input) {
        String message=input.getOrDefault("message","").trim();
        if (message.length()<5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"公告内容至少需要 5 个字");
        if (message.length()>255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"公告内容不能超过 255 个字");
        return message;
    }
    public Map<String,Object> announcements(Authentication auth,int page) {
        accounts.admin(auth); return paged(mapper.adminAnnouncements(PAGE_SIZE+1,offset(page)));
    }
    @Transactional public void announcement(Authentication auth,Map<String,String> input) {
        accounts.admin(auth); long actor=accounts.id(auth); String message=announcementMessage(input);
        Map<String,Object> announcement=new HashMap<>();
        announcement.put("adminId",actor); announcement.put("message",message);
        mapper.insertAnnouncement(announcement);
        long id=((Number)announcement.get("id")).longValue();
        for (long userId:mapper.allUserIds()) mapper.notifyAnnouncement(userId,message,id);
        // 自己发的公告直接算已读：管理员不该被自己的公告弹一次。
        mapper.readOwnAnnouncement(id,actor);
        mapper.adminLog(actor,"announcement","announcement",id,message);
    }
    @Transactional public void editAnnouncement(Authentication auth,long id,Map<String,String> input) {
        accounts.admin(auth); service.require(mapper.announcementById(id));
        String message=announcementMessage(input);
        mapper.editAnnouncement(id,message); mapper.editAnnouncementMessages(id,message); mapper.readOwnAnnouncement(id,accounts.id(auth));
        mapper.adminLog(accounts.id(auth),"edit","announcement",id,message);
    }
    public void deleteAnnouncement(Authentication auth,long id) {
        accounts.admin(auth); Map<String,Object> announcement=service.require(mapper.announcementById(id));
        mapper.deleteAnnouncement(id);
        mapper.adminLog(accounts.id(auth),"delete","announcement",id,String.valueOf(announcement.get("message")));
    }
}
