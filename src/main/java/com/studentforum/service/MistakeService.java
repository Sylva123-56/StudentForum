package com.studentforum.service;

import com.studentforum.repository.MistakeRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MistakeService {
    private final MistakeRepository repository;
    private final AccountAccessService accounts;
    private final KnowledgeService k;
    public MistakeService(MistakeRepository repository, AccountAccessService accounts, KnowledgeService k){this.repository=repository;this.accounts=accounts;this.k=k;}
    public Map<String,Object> list(Authentication auth,String scope,String subject,String q,boolean due,int page){
        k.check(Set.of("mine","public","group").contains(scope),"范围无效"); long user=k.user(auth); if(!scope.equals("public"))accounts.id(auth);
        k.check(q.length()<=100,"关键词过长");
        return k.page(repository.search(scope, subject, q, due, user, k.offset(page)));
    }
    public Map<String,Object> detail(Authentication auth,long id){var r=k.mistake(auth,id);if(k.n(r,"user_id")==k.user(auth))r.put("reviews",repository.reviews(id));return r;}
    private Map<String,Object> fields(Authentication auth,Map<String,Object> input){
        Map<String,Object> r=new LinkedHashMap<>();for(String field:List.of("question","answer","reason"))r.put(field,k.text(input,field,1,10000));r.put("subject",k.text(input,"subject",1,40));r.put("knowledge_point",k.text(input,"knowledgePoint",0,120));r.put("source",k.text(input,"source",0,255));r.put("tags",k.text(input,"tags",0,500));String image=k.text(input,"imagePath",0,255);if(!image.isEmpty()){k.check(image.matches("/api/mistakes/images/[0-9]+"),"请使用错题专用图片上传");long imageId=Long.parseLong(image.substring(image.lastIndexOf('/')+1));k.required(repository.ownedImage(imageId, k.user(auth)));}r.put("image_path",image.isEmpty()?null:image);
        String visibility=k.choice(input,"visibility","private","public","private","group");long group=k.number(input,"groupId",0),post=k.number(input,"postId",0);
        k.check(group>=0 && post>=0,"关联 ID 无效");k.check(!visibility.equals("group") || k.member(group,k.user(auth)),"共享错题需选择你加入的有效小组");
        if(post>0)k.required(repository.publishedPost(post));
        r.put("visibility",visibility);r.put("group_id",visibility.equals("group")?group:null);r.put("post_id",post>0?post:null);return r;
    }
    @Transactional
    public Map<String,Object> create(Authentication auth,Map<String,Object> input){accounts.writable(auth);var r=fields(auth,input);long id=repository.insertMistake(k.user(auth), r);return Map.of("id",id);}
    @Transactional
    public void edit(Authentication auth,long id,Map<String,Object> input){k.owner(auth,k.mistake(auth,id),"user_id");var r=fields(auth,input);repository.updateMistake(id, r);}
    @Transactional
    public void review(Authentication auth,long id,Map<String,Object> input){k.owner(auth,k.mistake(auth,id),"user_id");String result=k.choice(input,"result","incorrect","correct","incorrect");var r=k.required(repository.lockMistake(id));boolean correct=result.equals("correct");int interval=correct?(int)Math.min(30,1+k.n(r,"correct_count")*3):1;
        repository.insertReview(id, k.user(auth), result);repository.updateReviewTotals(correct?1:0, correct?0:1, LocalDate.now().plusDays(interval), id);}
    public List<Map<String,Object>> groups(Authentication auth){return repository.groups(accounts.id(auth));}
    public Map<String,Object> stats(Authentication auth){return repository.stats(accounts.id(auth));}

    @Transactional
    public Map<String,Object> plan(Authentication auth,Map<String,Object> input){accounts.writable(auth);
        if(!(input.get("ids") instanceof List<?> ids) || ids.isEmpty() || ids.size()>50)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"请选择 1 至 50 道错题");
        List<Map<String,Object>> rows=new ArrayList<>();Set<Long> used=new HashSet<>();for(Object value:ids){k.check(value instanceof Number || value instanceof String,"错题 ID 无效");long id=k.number(Map.of("id",value),"id",0);k.check(used.add(id),"错题重复");var r=k.mistake(auth,id);k.owner(auth,r,"user_id");rows.add(r);}
        long days=k.number(input,"days",7);k.check(days>=1 && days<=90,"复习周期须为 1 至 90 天");LocalDate start=LocalDate.now();
        long plan=repository.insertReviewPlan(k.user(auth), "错题复习计划", rows.getFirst().get("subject"), "复习选中的 "+rows.size()+" 道错题，回到错题本记录掌握情况", start, start.plusDays(days-1));
        for(int i=0;i<rows.size();i++){var r=rows.get(i);String question=String.valueOf(r.get("question"));repository.insertReviewTask(plan, "复习错题 #"+k.n(r,"id")+"："+question.substring(0,Math.min(80,question.length())), start.plusDays(i%days), i);}
        return Map.of("id",plan);
    }
}
