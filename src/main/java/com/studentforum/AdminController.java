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
    @GetMapping("/users") List<Map<String,Object>> users(Authentication auth) { service.admin(auth); return mapper.users(); }
    @GetMapping("/posts") List<Map<String,Object>> posts(Authentication auth) { service.staff(auth); return mapper.adminPosts().stream().filter(post -> allowed(auth,service.number(post,"board_id"))).toList(); }
    @GetMapping("/reports") List<Map<String,Object>> reports(Authentication auth) {
        service.staff(auth);
        return mapper.reports().stream().filter(report -> {
            String type=String.valueOf(report.get("target_type")); long target=service.number(report,"target_id");
            if ("user".equals(type) || "message".equals(type)) return "admin".equals(service.current(auth).get("role"));
            Map<String,Object> post="post".equals(type)?mapper.post(target):mapper.post(service.number(mapper.reply(target),"post_id"));
            return post!=null && allowed(auth,service.number(post,"board_id"));
        }).toList();
    }
    private boolean allowed(Authentication auth,long board) {
        Map<String,Object> user=service.current(auth);
        return "admin".equals(user.get("role")) || user.get("moderator_board_id")!=null && service.number(user,"moderator_board_id")==board;
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
        if ("moderator".equals(role)) service.require(mapper.board(board==null?-1:board));
        service.require(mapper.user(id)); mapper.userRole(id,role,"moderator".equals(role)?board:null); mapper.adminLog(service.id(auth),"role","user",id,role);
    }
    @PostMapping("/users/{id}/points") @Transactional void adjust(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input) {
        service.admin(auth); service.require(mapper.user(id)); int amount=((Number)input.get("amount")).intValue();
        if (amount==0 || Math.abs(amount)>1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"积分调整范围为 -1000 到 1000，且不能为 0");
        int before=mapper.points(id); mapper.addPoints(id,amount); int after=mapper.points(id);
        mapper.pointLog(id,"adjust",after-before,after,"user",id); mapper.adminLog(service.id(auth),"points","user",id,String.valueOf(amount));
    }
    @GetMapping("/points") List<Map<String,Object>> pointLogs(Authentication auth) { service.admin(auth); return mapper.adminLogs(); }
    @PostMapping("/boards") void board(Authentication auth,@RequestBody Map<String,Object> input) {
        service.admin(auth); String name=String.valueOf(input.get("name")),slug=String.valueOf(input.get("slug")),description=String.valueOf(input.getOrDefault("description",""));
        if (name.length()<2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"板块名称至少需要 2 个字");
        if (name.length()>50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"板块名称不能超过 50 个字");
        if (!slug.matches("[a-z0-9-]{2,80}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"板块标识只能使用 2-80 位小写字母、数字或短横线");
        if (description.length()>255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"板块简介不能超过 255 个字");
        mapper.addBoard(name,slug,description,((Number)input.getOrDefault("sortOrder",0)).intValue()); mapper.adminLog(service.id(auth),"create","board",0,name);
    }
    @DeleteMapping("/boards/{id}") void deleteBoard(Authentication auth,@PathVariable long id) {
        service.admin(auth); Map<String,Object> board=service.require(mapper.adminBoard(id));
        if (!"enabled".equals(board.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块已经停用");
        if (mapper.disableBoard(id)==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"板块状态已发生变化");
        mapper.adminLog(service.id(auth),"disable","board",id,String.valueOf(board.get("name")));
    }
    @PostMapping("/tags") void tag(Authentication auth,@RequestBody Map<String,String> input) {
        service.admin(auth); String name=input.getOrDefault("name","").trim();
        if (name.length()<2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"标签名称至少需要 2 个字");
        if (name.length()>40) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"标签名称不能超过 40 个字");
        mapper.addTag(name); mapper.adminLog(service.id(auth),"create","tag",0,name);
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
