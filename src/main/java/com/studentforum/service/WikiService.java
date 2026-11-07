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
    private final KnowledgeService knowledge;
    public WikiService(WikiRepository repository, AccountAccessService accounts, KnowledgeService knowledge){this.repository=repository;this.accounts=accounts;this.knowledge=knowledge;}
    public Map<String,Object> list(Authentication auth,long boardId,String q){var board=knowledge.required(repository.enabledBoard(boardId));knowledge.check(q.length()<=100,"关键词过长");boolean edit=knowledge.manager(auth,boardId);return Map.of("board",board,"canEdit",edit,"items",repository.pages(boardId, edit, q));}
    @Transactional
    public Map<String,Object> create(Authentication auth,long boardId,Map<String,Object> input){accounts.moderate(auth,boardId);knowledge.required(repository.enabledBoardId(boardId));String title=knowledge.text(input,"title",2,160),content=knowledge.text(input,"content",1,30000),status=knowledge.choice(input,"status","draft","draft","published");knowledge.check(repository.sameTitle(boardId, title)==null,"此板块已有同名 Wiki");String tags=knowledge.text(input,"tags",0,500); Long post=post(input);long id=repository.insertPage(boardId, title, content, status, knowledge.user(auth), tags, post);revision(auth,id,title,content,tags,post);return Map.of("id",id);}
    @Transactional
    public void edit(Authentication auth,long pageId,Map<String,Object> input){var p=knowledge.required(repository.lockPage(pageId));accounts.moderate(auth,knowledge.n(p,"board_id"));long base=knowledge.number(input,"baseRevisionId",0);var revision=knowledge.required(repository.latestRevision(pageId));if(base!=knowledge.n(revision,"id"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Wiki 已被其他人修改，请刷新后合并内容");String title=knowledge.text(input,"title",2,160),content=knowledge.text(input,"content",1,30000),status=knowledge.choice(input,"status","draft","draft","published");knowledge.check(repository.otherPageWithTitle(knowledge.n(p,"board_id"), title, pageId)==null,"此板块已有同名 Wiki");String tags=knowledge.text(input,"tags",0,500);Long post=post(input);repository.updatePage(title, content, status, knowledge.user(auth), tags, post, pageId);revision(auth,pageId,title,content,tags,post);}
    private Long post(Map<String,Object> input){long id=knowledge.number(input,"postId",0);knowledge.check(id>=0,"帖子 ID 无效");if(id>0)knowledge.required(repository.publishedPost(id));return id>0?id:null;}
    private void revision(Authentication auth,long id,String title,String content,String tags,Long post){repository.insertRevision(id, knowledge.user(auth), title, content, tags, post);knowledge.audit(auth,"wiki_edit","wiki",id,title);}
    public List<Map<String,Object>> history(Authentication auth,long pageId){var p=knowledge.required(repository.findPage(pageId));accounts.moderate(auth,knowledge.n(p,"board_id"));return repository.history(pageId);}
    public Map<String,Object> admin(Authentication auth,int page){accounts.staff(auth);var u=accounts.current(auth);long scope="admin".equals(u.get("role"))?0:knowledge.n(u,"moderator_board_id");return knowledge.page(repository.adminPages("admin".equals(u.get("role")), scope, knowledge.offset(page)));}
    @Transactional
    public void review(Authentication auth,long id,Map<String,Object> input){var p=knowledge.required(repository.lockPage(id));accounts.moderate(auth,knowledge.n(p,"board_id"));String status=knowledge.choice(input,"status","draft","draft","published");String note=knowledge.text(input,"note",1,500);repository.setStatus(status, knowledge.user(auth), id);knowledge.audit(auth,"wiki_"+status,"wiki",id,note);}
}
