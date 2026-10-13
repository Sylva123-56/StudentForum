package com.studentforum;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Shared validation and visibility rules; public lists and detail endpoints use the same policy. */
@Service
class KnowledgeService {
    final JdbcTemplate db;
    final ForumService forum;
    KnowledgeService(JdbcTemplate db, ForumService forum) { this.db=db; this.forum=forum; }
    long user(Authentication auth) { return auth==null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()) ? 0 : forum.id(auth); }
    Map<String,Object> row(String sql,Object... args) { return db.queryForList(sql,args).stream().findFirst().orElse(null); }
    Map<String,Object> required(String sql,Object... args) { Map<String,Object> r=row(sql,args); if(r==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"内容不存在或无权查看"); return new LinkedHashMap<>(r); }
    long n(Map<String,Object> r,String key) { Object v=r.get(key); return v instanceof Number x ? x.longValue() : 0; }
    long number(Map<String,Object> r,String key,long fallback) {
        Object v=r.get(key); if(v==null || "".equals(v)) return fallback;
        try { if(v instanceof Number x && x.doubleValue()!=x.longValue()) throw new NumberFormatException(); return Long.parseLong(v.toString()); }
        catch(RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"数字参数无效："+key); }
    }
    String text(Map<String,Object> r,String key,int min,int max) {
        Object v=r.get(key); check(v==null || v instanceof String,"文本参数无效"); String s=v==null?"":v.toString().trim(); forum.text(s,min,max); return s;
    }
    String choice(Map<String,Object> r,String key,String fallback,String... allowed) { String s=text(r,key,0,30); if(s.isEmpty())s=fallback; check(Set.of(allowed).contains(s),"状态或类型无效"); return s; }
    void check(boolean ok,String message) { if(!ok)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    void owned(Authentication auth,Map<String,Object> r,String column) { if(n(r,column)!=forum.id(auth))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只能访问自己的内容"); }
    void owner(Authentication auth,Map<String,Object> r,String column) { forum.writable(auth); owned(auth,r,column); }
    boolean member(long group,long user) { return user>0 && row("SELECT m.id FROM study_group_members m JOIN study_groups g ON g.id=m.group_id WHERE m.group_id=? AND m.user_id=? AND g.status='active'",group,user)!=null; }
    boolean manager(Authentication auth,long board) {
        if(user(auth)==0)return false; Map<String,Object> u=forum.current(auth);
        return "active".equals(u.get("status")) && ("admin".equals(u.get("role")) || "moderator".equals(u.get("role")) && n(u,"moderator_board_id")==board);
    }
    long insert(String sql,Object... args) {
        org.springframework.jdbc.support.GeneratedKeyHolder key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection->{ var statement=connection.prepareStatement(sql,java.sql.Statement.RETURN_GENERATED_KEYS); for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]); return statement; },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    int offset(int page) { check(page>=1 && page<=10000,"页码无效"); return (page-1)*20; }
    Map<String,Object> page(List<Map<String,Object>> items) { return Map.of("items",items.size()>20?items.subList(0,20):items,"hasNext",items.size()>20); }
    Map<String,Object> resource(Authentication auth,long id) {
        Map<String,Object> r=required("SELECT r.*,u.username FROM resources r JOIN users u ON u.id=r.uploader_id WHERE r.id=?",id);
        if(!"published".equals(r.get("status")) && n(r,"uploader_id")!=user(auth) && !manager(auth,-1))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"资料尚未发布或已下架"); return r;
    }
    Map<String,Object> mistake(Authentication auth,long id) {
        Map<String,Object> r=required("SELECT m.*,u.username FROM mistakes m JOIN users u ON u.id=m.user_id WHERE m.id=?",id);
        if(n(r,"user_id")!=user(auth) && !"public".equals(r.get("visibility")) && !("group".equals(r.get("visibility")) && member(n(r,"group_id"),user(auth))))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"错题不存在或无权查看"); return r;
    }
    Map<String,Object> note(Authentication auth,long id) {
        Map<String,Object> r=required("SELECT n.*,u.username FROM notes n JOIN users u ON u.id=n.user_id WHERE n.id=?",id);
        if(!"published".equals(r.get("status")) && n(r,"user_id")!=user(auth))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"笔记尚未发布"); return r;
    }
    Map<String,Object> collection(Authentication auth,long id) {
        Map<String,Object> r=required("SELECT c.*,u.username FROM collections c JOIN users u ON u.id=c.owner_id WHERE c.id=?",id);
        if(!"active".equals(r.get("status")) && n(r,"owner_id")!=user(auth) && !manager(auth,-1))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"合集已停用"); return r;
    }
    Map<String,Object> column(Authentication auth,long id) {
        Map<String,Object> r=required("SELECT c.*,u.username FROM columns c JOIN users u ON u.id=c.owner_id WHERE c.id=?",id);
        if(!"active".equals(r.get("status")) && n(r,"owner_id")!=user(auth))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"专栏已停用"); return r;
    }
    Map<String,Object> item(Authentication auth,String type,long id) {
        return switch(type) {
            case "resource" -> { var r=resource(auth,id); check("published".equals(r.get("status")),"只能收录已发布资料"); yield r; }
            case "note" -> { var r=note(auth,id); check("published".equals(r.get("status")),"只能收录已发布笔记"); yield r; }
            case "post" -> required("SELECT id,title FROM posts WHERE id=? AND status='published'",id);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"仅支持帖子、资料、笔记");
        };
    }
    void audit(Authentication auth,String action,String type,long id,String detail) { db.update("INSERT INTO admin_logs(actor_id,action,target_type,target_id,detail) VALUES(?,?,?,?,?)",forum.id(auth),action,type,id,detail); }
}
