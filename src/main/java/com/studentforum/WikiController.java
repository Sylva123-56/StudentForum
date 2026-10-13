package com.studentforum;

import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class WikiController {
    private final KnowledgeService k;
    WikiController(KnowledgeService k){this.k=k;}
    @GetMapping("/wiki/{boardId}")
    Map<String,Object> list(Authentication auth,@PathVariable long boardId,@RequestParam(defaultValue="") String q){var board=k.required("SELECT * FROM boards WHERE id=? AND status='enabled'",boardId);k.check(q.length()<=100,"关键词过长");boolean edit=k.manager(auth,boardId);return Map.of("board",board,"canEdit",edit,"items",k.db.queryForList("SELECT p.*,u.username FROM wiki_pages p JOIN users u ON u.id=p.updated_by WHERE p.board_id=? AND (p.status='published' OR ?) AND (?='' OR LOCATE(?,p.title)>0 OR LOCATE(?,p.content)>0 OR LOCATE(?,p.tags)>0) ORDER BY p.id",boardId,edit,q,q,q,q));}
    @PostMapping("/wiki/{boardId}") @Transactional
    Map<String,Object> create(Authentication auth,@PathVariable long boardId,@RequestBody Map<String,Object> input){k.forum.moderate(auth,boardId);k.required("SELECT id FROM boards WHERE id=? AND status='enabled'",boardId);String title=k.text(input,"title",2,160),content=k.text(input,"content",1,30000),status=k.choice(input,"status","draft","draft","published");k.check(k.row("SELECT id FROM wiki_pages WHERE board_id=? AND title=?",boardId,title)==null,"此板块已有同名 Wiki");String tags=k.text(input,"tags",0,500); Long post=post(input);long id=k.insert("INSERT INTO wiki_pages(board_id,title,content,status,updated_by,tags,post_id) VALUES(?,?,?,?,?,?,?)",boardId,title,content,status,k.user(auth),tags,post);revision(auth,id,title,content,tags,post);return Map.of("id",id);}
    @PatchMapping("/wiki/{pageId}") @Transactional
    void edit(Authentication auth,@PathVariable long pageId,@RequestBody Map<String,Object> input){var p=k.required("SELECT * FROM wiki_pages WHERE id=? FOR UPDATE",pageId);k.forum.moderate(auth,k.n(p,"board_id"));long base=k.number(input,"baseRevisionId",0);var revision=k.required("SELECT id FROM wiki_revisions WHERE page_id=? ORDER BY id DESC LIMIT 1",pageId);if(base!=k.n(revision,"id"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Wiki 已被其他人修改，请刷新后合并内容");String title=k.text(input,"title",2,160),content=k.text(input,"content",1,30000),status=k.choice(input,"status","draft","draft","published");k.check(k.row("SELECT id FROM wiki_pages WHERE board_id=? AND title=? AND id<>?",k.n(p,"board_id"),title,pageId)==null,"此板块已有同名 Wiki");String tags=k.text(input,"tags",0,500);Long post=post(input);k.db.update("UPDATE wiki_pages SET title=?,content=?,status=?,updated_by=?,tags=?,post_id=? WHERE id=?",title,content,status,k.user(auth),tags,post,pageId);revision(auth,pageId,title,content,tags,post);}
    private Long post(Map<String,Object> input){long id=k.number(input,"postId",0);k.check(id>=0,"帖子 ID 无效");if(id>0)k.required("SELECT id FROM posts WHERE id=? AND status='published'",id);return id>0?id:null;}
    private void revision(Authentication auth,long id,String title,String content,String tags,Long post){k.db.update("INSERT INTO wiki_revisions(page_id,editor_id,title,content,tags,post_id) VALUES(?,?,?,?,?,?)",id,k.user(auth),title,content,tags,post);k.audit(auth,"wiki_edit","wiki",id,title);}
    @GetMapping("/wiki/pages/{pageId}/revisions")
    List<Map<String,Object>> history(Authentication auth,@PathVariable long pageId){var p=k.required("SELECT * FROM wiki_pages WHERE id=?",pageId);k.forum.moderate(auth,k.n(p,"board_id"));return k.db.queryForList("SELECT r.*,u.username FROM wiki_revisions r JOIN users u ON u.id=r.editor_id WHERE r.page_id=? ORDER BY r.id DESC LIMIT 100",pageId);}
    @GetMapping("/admin/wiki")
    Map<String,Object> admin(Authentication auth,@RequestParam(defaultValue="1") int page){k.forum.staff(auth);var u=k.forum.current(auth);String where="admin".equals(u.get("role"))?"?=0":"p.board_id=?";long scope="admin".equals(u.get("role"))?0:k.n(u,"moderator_board_id");return k.page(k.db.queryForList("SELECT p.*,b.name AS board_name,u.username FROM wiki_pages p JOIN boards b ON b.id=p.board_id JOIN users u ON u.id=p.updated_by WHERE "+where+" ORDER BY p.status='draft' DESC,p.updated_at DESC LIMIT 21 OFFSET ?",scope,k.offset(page)));}
    @PatchMapping("/admin/wiki/{id}") @Transactional
    void review(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input){var p=k.required("SELECT * FROM wiki_pages WHERE id=? FOR UPDATE",id);k.forum.moderate(auth,k.n(p,"board_id"));String status=k.choice(input,"status","draft","draft","published");String note=k.text(input,"note",1,500);k.db.update("UPDATE wiki_pages SET status=?,updated_by=? WHERE id=?",status,k.user(auth),id);k.audit(auth,"wiki_"+status,"wiki",id,note);}
}
