package com.studentforum;

import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notes")
class NoteController {
    private final KnowledgeService k;
    NoteController(KnowledgeService k){this.k=k;}
    @GetMapping
    Map<String,Object> list(Authentication auth,@RequestParam(defaultValue="false") boolean mine,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String subject,@RequestParam(defaultValue="1") int page){k.check(!mine || k.user(auth)>0,"请先登录");k.check(q.length()<=100,"关键词过长");return k.page(k.db.queryForList("SELECT n.*,u.username FROM notes n JOIN users u ON u.id=n.user_id WHERE "+(mine?"n.user_id=?":"n.status='published' AND ?>=0")+" AND (?='' OR n.subject=?) AND (?='' OR LOCATE(?,n.title)>0 OR LOCATE(?,n.tags)>0 OR LOCATE(?,n.content)>0) ORDER BY n.updated_at DESC,n.id DESC LIMIT 21 OFFSET ?",k.user(auth),subject,subject,q,q,q,q,k.offset(page)));}
    @GetMapping("/{id}") Map<String,Object> detail(Authentication auth,@PathVariable long id){return k.note(auth,id);}
    @PostMapping @Transactional
    Map<String,Object> create(Authentication auth,@RequestBody Map<String,Object> input){k.forum.writable(auth);String title=k.text(input,"title",2,160),content=k.text(input,"content",1,30000),status=k.choice(input,"status","draft","draft","published");long id=k.insert("INSERT INTO notes(user_id,title,content,subject,tags,status) VALUES(?,?,?,?,?,?)",k.user(auth),title,content,k.text(input,"subject",0,40),k.text(input,"tags",0,500),status);revision(auth,id,title,content);return Map.of("id",id);}
    @PatchMapping("/{id}") @Transactional
    void edit(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input){k.owner(auth,k.note(auth,id),"user_id");k.required("SELECT id FROM notes WHERE id=? FOR UPDATE",id);String title=k.text(input,"title",2,160),content=k.text(input,"content",1,30000),status=k.choice(input,"status","draft","draft","published");k.db.update("UPDATE notes SET title=?,content=?,subject=?,tags=?,status=? WHERE id=?",title,content,k.text(input,"subject",0,40),k.text(input,"tags",0,500),status,id);revision(auth,id,title,content);}
    private void revision(Authentication auth,long id,String title,String content){k.db.update("INSERT INTO note_revisions(note_id,user_id,title,content) VALUES(?,?,?,?)",id,k.user(auth),title,content);}
    @GetMapping("/{id}/revisions") List<Map<String,Object>> revisions(Authentication auth,@PathVariable long id){k.owned(auth,k.note(auth,id),"user_id");return k.db.queryForList("SELECT * FROM note_revisions WHERE note_id=? ORDER BY id DESC LIMIT 100",id);}
}
