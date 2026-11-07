package com.studentforum.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import com.studentforum.repository.KnowledgeRepository;
import com.studentforum.model.ContentRules;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Shared validation and visibility rules; public lists and detail endpoints use the same policy. */
@Service
public class KnowledgeService {
    private final KnowledgeRepository repository;
    private final AccountAccessService accounts;

    public KnowledgeService(KnowledgeRepository repository, AccountAccessService accounts) {
        this.repository=repository;
        this.accounts=accounts;
    }

    public long user(Authentication auth) {
        return auth==null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()) ? 0 : accounts.id(auth);
    }
    public Map<String,Object> required(Map<String,Object> row) {
        if(row==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"内容不存在或无权查看");
        return new LinkedHashMap<>(row);
    }
    public long n(Map<String,Object> row,String key) {
        Object value=row.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }
    public long number(Map<String,Object> r,String key,long fallback) {
        Object v=r.get(key);
        if(v==null || "".equals(v)) return fallback;
        try {
            if(v instanceof Number x && x.doubleValue()!=x.longValue()) throw new NumberFormatException();
            return Long.parseLong(v.toString());
        }
        catch(RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"数字参数无效："+key); }
    }
    public String text(Map<String,Object> r,String key,int min,int max) {
        Object v=r.get(key);
        check(v==null || v instanceof String,"文本参数无效");
        String s=v==null?"":v.toString().trim();
        ContentRules.text(s,min,max);
        return s;
    }
    public String choice(Map<String,Object> r,String key,String fallback,String... allowed) {
        String value=text(r,key,0,30);
        if(value.isEmpty()) value=fallback;
        check(Set.of(allowed).contains(value),"状态或类型无效");
        return value;
    }
    public void check(boolean ok,String message) {
        if(!ok) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    public void owned(Authentication auth,Map<String,Object> row,String column) {
        if(n(row,column)!=accounts.id(auth))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只能访问自己的内容");
    }
    public void owner(Authentication auth,Map<String,Object> row,String column) {
        accounts.writable(auth);
        owned(auth,row,column);
    }
    public boolean member(long group,long user) {
        return user>0 && repository.activeMembership(group,user)!=null;
    }
    public boolean manager(Authentication auth,long board) {
        if(user(auth)==0) return false;
        Map<String,Object> u=accounts.current(auth);
        return "active".equals(u.get("status")) && ("admin".equals(u.get("role")) || "moderator".equals(u.get("role")) && n(u,"moderator_board_id")==board);
    }
    public int offset(int page) { check(page>=1 && page<=10000,"页码无效"); return (page-1)*20; }
    public Map<String,Object> page(List<Map<String,Object>> items) { return Map.of("items",items.size()>20?items.subList(0,20):items,"hasNext",items.size()>20); }
    public Map<String,Object> resource(Authentication auth,long id) {
        Map<String,Object> r=required(repository.resource(id));
        if(!"published".equals(r.get("status")) && n(r,"uploader_id")!=user(auth) && !manager(auth,-1))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"资料尚未发布或已下架");
        return r;
    }
    public Map<String,Object> mistake(Authentication auth,long id) {
        Map<String,Object> r=required(repository.mistake(id));
        if(n(r,"user_id")!=user(auth) && !"public".equals(r.get("visibility")) && !("group".equals(r.get("visibility")) && member(n(r,"group_id"),user(auth))))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"错题不存在或无权查看");
        return r;
    }
    public Map<String,Object> note(Authentication auth,long id) {
        Map<String,Object> r=required(repository.note(id));
        if(!"published".equals(r.get("status")) && n(r,"user_id")!=user(auth))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"笔记尚未发布");
        return r;
    }
    public Map<String,Object> collection(Authentication auth,long id) {
        Map<String,Object> r=required(repository.collection(id));
        if(!"active".equals(r.get("status")) && n(r,"owner_id")!=user(auth) && !manager(auth,-1))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"合集已停用");
        return r;
    }
    public Map<String,Object> column(Authentication auth,long id) {
        Map<String,Object> r=required(repository.column(id));
        if(!"active".equals(r.get("status")) && n(r,"owner_id")!=user(auth))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"专栏已停用");
        return r;
    }
    public Map<String,Object> item(Authentication auth,String type,long id) {
        return switch(type) {
            case "resource" -> { var r=resource(auth,id); check("published".equals(r.get("status")),"只能收录已发布资料"); yield r; }
            case "note" -> { var r=note(auth,id); check("published".equals(r.get("status")),"只能收录已发布笔记"); yield r; }
            case "post" -> required(repository.publishedPost(id));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"仅支持帖子、资料、笔记");
        };
    }
    public void audit(Authentication auth,String action,String type,long id,String detail) {
        repository.audit(accounts.id(auth),action,type,id,detail);
    }
}
