package com.studentforum.service;

import com.studentforum.repository.CollectionRepository;
import com.studentforum.model.ContentRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CollectionService {
    private final CollectionRepository repository;
    private final AccountAccessService accounts;
    private final KnowledgeService k;
    public CollectionService(CollectionRepository repository, AccountAccessService accounts, KnowledgeService k){this.repository=repository;this.accounts=accounts;this.k=k;}
    public Map<String,Object> list(Authentication auth,String kind,String q,boolean mine,boolean featured,boolean favorite,boolean following,int page){k.check(!(mine||favorite||following) || k.user(auth)>0,"请先登录");k.check(q.length()<=100,"关键词过长");return k.page(repository.search(kind,q,mine,featured,favorite,following,k.user(auth),k.offset(page)));}

    @Transactional
    public Map<String,Object> create(Authentication auth,String kind,Map<String,Object> input){accounts.writable(auth);long id=repository.insert(kind,k.user(auth),k.text(input,"title",2,120),k.text(input,"description",0,500),ContentRules.image(input.get("coverUrl")));return Map.of("id",id);}
    @Transactional
    public void edit(Authentication auth,String kind,long id,Map<String,Object> input){var r=kind.equals("collections")?k.collection(auth,id):k.column(auth,id);k.owner(auth,r,"owner_id");k.check("active".equals(r.get("status")),"已停用的内容不能编辑");repository.update(kind,k.text(input,"title",2,120),k.text(input,"description",0,500),ContentRules.image(input.get("coverUrl")),id);}
    public Map<String,Object> detail(Authentication auth,long id){var r=k.collection(auth,id);List<Map<String,Object>> visible=new ArrayList<>();for(var item:repository.items(id)){try{var target=k.item(auth,String.valueOf(item.get("item_type")),k.n(item,"item_id"));var copy=new LinkedHashMap<>(item);copy.put("title",target.get("title"));visible.add(copy);}catch(ResponseStatusException e){if(e.getStatusCode().value()!=404 && e.getStatusCode().value()!=400)throw e;}}
        r.put("items",visible);r.put("item_count",visible.size());r.put("followed",repository.followBy(id,k.user(auth))!=null);r.put("favorited",repository.favoriteBy(id,k.user(auth))!=null);return r;}
    @Transactional
    public void add(Authentication auth,long id,Map<String,Object> input){var collection=k.collection(auth,id);k.owner(auth,collection,"owner_id");k.check("active".equals(collection.get("status")),"合集已停用");k.required(repository.lockCollection(id));String type=k.choice(input,"itemType","post","post","resource","note");long target=k.number(input,"itemId",0);var item=k.item(auth,type,target);long sort=k.number(input,"sortOrder",0);k.check(sort>=0 && sort<=10000,"排序值无效");repository.upsertItem(id,type,target,item.get("title"),sort);repository.recountItems(id);}
    public void reorder(Authentication auth,long id,long itemId,Map<String,Object> input){k.owner(auth,k.collection(auth,id),"owner_id");long sort=k.number(input,"sortOrder",0);k.check(sort>=0 && sort<=10000,"排序值无效");k.required(repository.findItem(itemId,id));repository.reorderItem(sort,itemId,id);}
    @Transactional
    public void remove(Authentication auth,long id,long itemId){k.owner(auth,k.collection(auth,id),"owner_id");k.required(repository.lockCollection(id));repository.removeItem(itemId,id);repository.recountItems(id);}
    @Transactional
    public void toggle(Authentication auth,long id,String action,Map<String,Object> input){accounts.writable(auth);var r=k.collection(auth,id);k.check("active".equals(r.get("status")),"合集已停用");k.required(repository.lockCollection(id));repository.toggleInteraction(id,k.user(auth),action.equals("follow"),Boolean.TRUE.equals(input.get("active")));repository.recountFollowers(id);}
    public Map<String,Object> column(Authentication auth,long id){var r=k.column(auth,id);r.put("episodes",repository.episodes(id,k.n(r,"owner_id"),k.user(auth)));return r;}
    @Transactional
    public Map<String,Object> episode(Authentication auth,long id,Map<String,Object> input){var c=k.column(auth,id);k.owner(auth,c,"owner_id");k.check("active".equals(c.get("status")),"专栏已停用");k.required(repository.lockColumn(id));long no=k.number(input,"episodeNo",1);k.check(no>=1 && no<=10000,"期数无效");k.check(repository.episodeNumber(id,no)==null,"此期数已存在");String status=k.choice(input,"status","published","draft","published");long noteId=k.number(input,"noteId",0);String title,content;if(noteId>0){var note=k.note(auth,noteId);k.owner(auth,note,"user_id");k.check(!status.equals("published") || "published".equals(note.get("status")),"发布连载需使用已发布笔记");title=String.valueOf(note.get("title"));content=String.valueOf(note.get("content"));}else{title=k.text(input,"title",2,160);content=k.text(input,"content",1,30000);}
        long episode=repository.insertEpisode(id,noteId>0?noteId:null,title,content,no,status);return Map.of("id",episode);}
    public void editEpisode(Authentication auth,long id,long episodeId,Map<String,Object> input){k.owner(auth,k.column(auth,id),"owner_id");k.required(repository.findEpisode(episodeId,id));repository.updateEpisode(k.text(input,"title",2,160),k.text(input,"content",1,30000),k.choice(input,"status","published","draft","published"),episodeId,id);}
    public Map<String,Object> admin(Authentication auth,int page){accounts.admin(auth);return k.page(repository.adminCollections(k.offset(page)));}
    @Transactional
    public void recommend(Authentication auth,long id,Map<String,Object> input){accounts.admin(auth);k.required(repository.findCollectionId(id));String status=k.choice(input,"status","active","active","disabled");boolean featured=Boolean.TRUE.equals(input.get("featured")) && status.equals("active");repository.recommend(featured,status,id);k.audit(auth,"collection_recommend","collection",id,status+"/"+featured);}
}
