package com.studentforum.service;

import com.studentforum.repository.WikiRepository;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WikiService {
    private final WikiRepository repository;
    private final AccountAccessService accounts;
    private final KnowledgeService k;
    public WikiService(WikiRepository repository, AccountAccessService accounts, KnowledgeService k){this.repository=repository;this.accounts=accounts;this.k=k;}
    public Map<String,Object> list(Authentication auth,long boardId,String q){var board=k.required(repository.enabledBoard(boardId));k.check(q.length()<=100,"关键词过长");boolean edit=k.manager(auth,boardId);return Map.of("board",board,"canEdit",edit,"items",repository.pages(boardId, edit, q));}
    @Transactional
    public Map<String,Object> create(Authentication auth,long boardId,Map<String,Object> input){accounts.moderate(auth,boardId);k.required(repository.enabledBoardId(boardId));String title=k.text(input,"title",2,160),content=k.text(input,"content",1,30000),status=k.choice(input,"status","draft","draft","published");k.check(repository.sameTitle(boardId, title)==null,"此板块已有同名 Wiki");String tags=k.text(input,"tags",0,500); Long post=post(input);long id=repository.insertPage(boardId, title, content, status, k.user(auth), tags, post);revision(auth,id,title,content,tags,post);return Map.of("id",id);}
    @Transactional
    public void edit(Authentication auth,long pageId,Map<String,Object> input){var p=k.required(repository.lockPage(pageId));accounts.moderate(auth,k.n(p,"board_id"));long base=k.number(input,"baseRevisionId",0);var revision=k.required(repository.latestRevision(pageId));if(base!=k.n(revision,"id"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Wiki 已被其他人修改，请刷新后合并内容");String title=k.text(input,"title",2,160),content=k.text(input,"content",1,30000),status=k.choice(input,"status","draft","draft","published");k.check(repository.otherPageWithTitle(k.n(p,"board_id"), title, pageId)==null,"此板块已有同名 Wiki");String tags=k.text(input,"tags",0,500);Long post=post(input);repository.updatePage(title, content, status, k.user(auth), tags, post, pageId);revision(auth,pageId,title,content,tags,post);}
    private Long post(Map<String,Object> input){long id=k.number(input,"postId",0);k.check(id>=0,"帖子 ID 无效");if(id>0)k.required(repository.publishedPost(id));return id>0?id:null;}
    private void revision(Authentication auth,long id,String title,String content,String tags,Long post){repository.insertRevision(id, k.user(auth), title, content, tags, post);k.audit(auth,"wiki_edit","wiki",id,title);}
    public List<Map<String,Object>> history(Authentication auth,long pageId){var p=k.required(repository.findPage(pageId));accounts.moderate(auth,k.n(p,"board_id"));return repository.history(pageId);}
    public Map<String,Object> admin(Authentication auth,int page){accounts.staff(auth);var u=accounts.current(auth);long scope="admin".equals(u.get("role"))?0:k.n(u,"moderator_board_id");return k.page(repository.adminPages("admin".equals(u.get("role")), scope, k.offset(page)));}
    @Transactional
    public void review(Authentication auth,long id,Map<String,Object> input){var p=k.required(repository.lockPage(id));accounts.moderate(auth,k.n(p,"board_id"));String status=k.choice(input,"status","draft","draft","published");String note=k.text(input,"note",1,500);repository.setStatus(status, k.user(auth), id);k.audit(auth,"wiki_"+status,"wiki",id,note);}
}
