package com.studentforum;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/admin/groups")
public class GroupAdminController {
    private final JdbcTemplate db;
    private final ForumService service;
    public GroupAdminController(JdbcTemplate db, ForumService service) { this.db=db; this.service=service; }
    @GetMapping public List<Map<String,Object>> groups(Authentication auth,@RequestParam(defaultValue="") String keyword) {
        service.admin(auth); String q=keyword.trim();
        return db.queryForList("SELECT g.*,u.username AS owner_username FROM study_groups g JOIN users u ON u.id=g.owner_id WHERE ?='' OR LOCATE(LOWER(?),LOWER(g.name))>0 OR LOCATE(LOWER(?),LOWER(g.description))>0 ORDER BY g.created_at DESC",q,q,q);
    }
    @GetMapping("/reports") public List<Map<String,Object>> reports(Authentication auth,@RequestParam(defaultValue="pending") String status) { service.admin(auth); return db.queryForList("SELECT r.*,g.name AS group_name,u.username AS reporter_username FROM study_group_reports r JOIN study_groups g ON g.id=r.group_id JOIN users u ON u.id=r.reporter_id WHERE r.status=? ORDER BY r.created_at DESC LIMIT 200",status); }
    @PatchMapping("/reports/{reportId}") public void handleReport(Authentication auth,@PathVariable long reportId,@RequestBody Map<String,Object> input) { service.admin(auth); String status=String.valueOf(input.getOrDefault("status","")); if(!java.util.Set.of("resolved","rejected").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"举报状态无效"); db.update("UPDATE study_group_reports SET status=?,handler_id=?,handled_at=NOW() WHERE id=?",status,service.id(auth),reportId); }
    @PatchMapping("/{groupId}") public void update(Authentication auth,@PathVariable long groupId,@RequestBody Map<String,Object> input) {
        service.admin(auth); require(groupId); String status=String.valueOf(input.getOrDefault("status",""));
        if(!java.util.Set.of("active","disabled").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"小组状态无效");
        db.update("UPDATE study_groups SET status=? WHERE id=?",status,groupId);
        log(groupId,service.id(auth),"admin_status","group",groupId,status);
    }
    @GetMapping("/logs") public List<Map<String,Object>> logs(Authentication auth,@RequestParam(required=false) Long groupId) {
        service.admin(auth);
        if(groupId==null) return db.queryForList("SELECT l.*,u.username AS actor_username,g.name AS group_name FROM study_group_logs l LEFT JOIN users u ON u.id=l.actor_id JOIN study_groups g ON g.id=l.group_id ORDER BY l.created_at DESC LIMIT 200");
        return db.queryForList("SELECT l.*,u.username AS actor_username,g.name AS group_name FROM study_group_logs l LEFT JOIN users u ON u.id=l.actor_id JOIN study_groups g ON g.id=l.group_id WHERE l.group_id=? ORDER BY l.created_at DESC",groupId);
    }
    private void require(long id){ if(db.queryForList("SELECT id FROM study_groups WHERE id=?",id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"小组不存在"); }
    private void log(long groupId,long actor,String action,String type,long target,String detail){ db.update("INSERT INTO study_group_logs(group_id,actor_id,action,target_type,target_id,detail) VALUES(?,?,?,?,?,?)",groupId,actor,action,type,target,detail); }
}
