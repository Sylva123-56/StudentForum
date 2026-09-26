package com.studentforum;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final ForumMapper mapper;
    private final ForumService service;
    AdminController(ForumMapper mapper,ForumService service) { this.mapper=mapper; this.service=service; }
    @GetMapping("/dashboard") Map<String,Object> dashboard(Authentication auth) { service.staff(auth); return mapper.dashboard(); }
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
    @GetMapping("/users") Map<String,Object> users(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        service.admin(auth); return paged(mapper.users(keyword(keyword),PAGE_SIZE+1,offset(page)));
    }
    @GetMapping("/posts") Map<String,Object> posts(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        service.staff(auth);
        Map<String,Object> user=service.current(auth);
        List<Long> boards="admin".equals(user.get("role"))?null:List.of(user.get("moderator_board_id")==null?-1L:service.number(user,"moderator_board_id"));
        return paged(mapper.adminPosts(keyword(keyword),boards,PAGE_SIZE+1,offset(page)));
    }
    @GetMapping("/reports") Map<String,Object> reports(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        service.staff(auth);
        Map<String,Object> user=service.current(auth);
        Long boardId="admin".equals(user.get("role"))?null:user.get("moderator_board_id")==null?-1L:service.number(user,"moderator_board_id");
        return paged(mapper.reports(keyword(keyword),boardId,PAGE_SIZE+1,offset(page)));
    }
    @PatchMapping("/posts/{id}/feature") void feature(Authentication auth,@PathVariable long id,@RequestBody Map<String,Boolean> input) { service.feature(auth,id,Boolean.TRUE.equals(input.get("featured"))); }
    @PatchMapping("/posts/{id}/top") void top(Authentication auth,@PathVariable long id,@RequestBody Map<String,Boolean> input) {
        Map<String,Object> post=service.require(mapper.post(id)); service.moderate(auth,service.number(post,"board_id")); mapper.top(id,Boolean.TRUE.equals(input.get("top"))); mapper.adminLog(service.id(auth),"top","post",id,String.valueOf(input.get("top")));
    }
    @PatchMapping("/posts/{id}/status") void status(Authentication auth,@PathVariable long id,@RequestBody Map<String,String> input) {
        Map<String,Object> post=service.require(mapper.post(id)); service.moderate(auth,service.number(post,"board_id"));
        String status=input.get("status"); if (!Set.of("published","hidden","deleted").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        mapper.postStatus(id,status); mapper.adminLog(service.id(auth),"status","post",id,status);
    }
    @PatchMapping("/reports/{id}") void handle(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) { service.handle(auth,id,input); }
    @PatchMapping("/users/{id}/status") void userStatus(Authentication auth,@PathVariable long id,@RequestBody Map<String,String> input) {
        service.admin(auth); String status=input.get("status"); if (!Set.of("active","muted","banned").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        service.require(mapper.user(id)); mapper.userStatus(id,status); mapper.adminLog(service.id(auth),"user_status","user",id,status);
    }
    @PatchMapping("/users/{id}/role") void role(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        service.admin(auth); String role=String.valueOf(input.get("role"));
        if (!Set.of("student","moderator","admin").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Long board=input.get("boardId")==null?null:((Number)input.get("boardId")).longValue();
        String boardName="";
        if ("moderator".equals(role)) {
            if (board==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择版主负责的板块");
            boardName=String.valueOf(service.require(mapper.board(board)).get("name"));
        }
        Map<String,Object> user=service.require(mapper.user(id));
        if (service.id(auth)==id && !"admin".equals(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不能变更自己的管理员角色");
        if ("admin".equals(user.get("role")) && !"admin".equals(role) && mapper.adminCount()<=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"至少需要保留一位管理员");
        mapper.userRole(id,role,"moderator".equals(role)?board:null);
        mapper.adminLog(service.id(auth),"role","user",id,"moderator".equals(role)?role+" · "+boardName:role);
    }
    @PostMapping("/users/{id}/points") @Transactional void adjust(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        service.admin(auth); service.require(mapper.user(id)); int amount=((Number)input.get("amount")).intValue();
        if (amount==0 || Math.abs(amount)>1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"积分调整范围为 -1000 到 1000，且不能为 0");
        int before=mapper.points(id); mapper.addPoints(id,amount); int after=mapper.points(id);
        mapper.pointLog(id,"adjust",after-before,after,"user",id); mapper.adminLog(service.id(auth),"points","user",id,String.valueOf(amount));
    }
    @GetMapping("/points") Map<String,Object> pointLogs(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        service.admin(auth); return paged(mapper.adminLogs(keyword(keyword),PAGE_SIZE+1,offset(page)));
    }
    @GetMapping("/boards") Map<String,Object> boardList(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        service.admin(auth); return paged(mapper.adminBoards(keyword(keyword),PAGE_SIZE+1,offset(page)));
    }
    @GetMapping("/tags") Map<String,Object> tagList(Authentication auth,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page) {
        service.admin(auth); return paged(mapper.adminTags(keyword(keyword),PAGE_SIZE+1,offset(page)));
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
    @PostMapping("/boards") void board(Authentication auth,@RequestBody Map<String,Object> input) {
        service.admin(auth); String name=boardName(input),slug=boardSlug(input);
        if (mapper.boardSlugTaken(slug,0)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块标识已被使用");
        mapper.addBoard(name,slug,boardDescription(input),sortOrder(input)); mapper.adminLog(service.id(auth),"create","board",0,name);
    }
    @PatchMapping("/boards/{id}") void editBoard(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        service.admin(auth); Map<String,Object> board=service.require(mapper.adminBoard(id));
        String name=boardName(input),slug=boardSlug(input);
        if (mapper.boardSlugTaken(slug,id)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块标识已被使用");
        mapper.editBoard(id,name,slug,boardDescription(input),sortOrder(input));
        mapper.adminLog(service.id(auth),"edit","board",id,String.valueOf(board.get("name"))+" → "+name);
    }
    @PatchMapping("/boards/{id}/status") void boardStatus(Authentication auth,@PathVariable long id,@RequestBody Map<String,String> input) {
        service.admin(auth); Map<String,Object> board=service.require(mapper.adminBoard(id));
        String status=input.getOrDefault("status","");
        if (!Set.of("enabled","disabled").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"板块状态无效");
        if (status.equals(board.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块已处于该状态");
        if (("enabled".equals(status)?mapper.enableBoard(id):mapper.disableBoard(id))==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块状态已发生变化");
        mapper.adminLog(service.id(auth),"enabled".equals(status)?"enable":"disable","board",id,String.valueOf(board.get("name")));
    }
    @DeleteMapping("/boards/{id}") void deleteBoard(Authentication auth,@PathVariable long id) {
        service.admin(auth); Map<String,Object> board=service.require(mapper.adminBoard(id));
        if (!"enabled".equals(board.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块已经停用");
        if (mapper.disableBoard(id)==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块状态已发生变化");
        mapper.adminLog(service.id(auth),"disable","board",id,String.valueOf(board.get("name")));
    }
    @PostMapping("/tags") void tag(Authentication auth,@RequestBody Map<String,String> input) {
        service.admin(auth); String name=tagName(input);
        if (mapper.tagNameTaken(name,0)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签名称已存在");
        mapper.addTag(name); mapper.adminLog(service.id(auth),"create","tag",0,name);
    }
    @PatchMapping("/tags/{id}") void editTag(Authentication auth,@PathVariable long id,@RequestBody Map<String,String> input) {
        service.admin(auth); Map<String,Object> tag=service.require(mapper.adminTag(id)); String name=tagName(input);
        if (mapper.tagNameTaken(name,id)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签名称已存在");
        mapper.editTag(id,name); mapper.adminLog(service.id(auth),"edit","tag",id,String.valueOf(tag.get("name"))+" → "+name);
    }
    @PatchMapping("/tags/{id}/status") void tagStatus(Authentication auth,@PathVariable long id,@RequestBody Map<String,String> input) {
        service.admin(auth); Map<String,Object> tag=service.require(mapper.adminTag(id));
        String status=input.getOrDefault("status","");
        if (!Set.of("enabled","disabled").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"标签状态无效");
        if (status.equals(tag.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签已处于该状态");
        if (("enabled".equals(status)?mapper.enableTag(id):mapper.disableTag(id))==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签状态已发生变化");
        mapper.adminLog(service.id(auth),"enabled".equals(status)?"enable":"disable","tag",id,String.valueOf(tag.get("name")));
    }
    @DeleteMapping("/tags/{id}") void deleteTag(Authentication auth,@PathVariable long id) {
        service.admin(auth); Map<String,Object> tag=service.require(mapper.adminTag(id));
        if (!"enabled".equals(tag.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签已经停用");
        if (mapper.disableTag(id)==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"标签状态已发生变化");
        mapper.adminLog(service.id(auth),"disable","tag",id,String.valueOf(tag.get("name")));
    }
    @PostMapping("/announcements") void announcement(Authentication auth,@RequestBody Map<String,String> input) {
        service.admin(auth); String message=input.getOrDefault("message","").trim();
        if (message.length()<5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"公告内容至少需要 5 个字");
        if (message.length()>255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"公告内容不能超过 255 个字");
        for (long userId:mapper.allUserIds()) mapper.notifyUser(userId,"system",message,null);
        mapper.adminLog(service.id(auth),"announcement","user",0,message);
    }
}
