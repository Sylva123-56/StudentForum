package com.studentforum;

import java.time.LocalDate;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mistakes")
class MistakeController {
    private final KnowledgeService k;
    MistakeController(KnowledgeService k){this.k=k;}
    @GetMapping
    Map<String,Object> list(Authentication auth,@RequestParam(defaultValue="mine") String scope,@RequestParam(defaultValue="") String subject,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="false") boolean due,@RequestParam(defaultValue="1") int page){
        k.check(Set.of("mine","public","group").contains(scope),"范围无效"); long user=k.user(auth); if(!scope.equals("public"))k.forum.id(auth);
        String sql="SELECT m.*,u.username FROM mistakes m JOIN users u ON u.id=m.user_id WHERE "+switch(scope){case "mine"->"m.user_id=?";case "public"->"m.visibility='public'";default->"m.visibility='group' AND EXISTS(SELECT 1 FROM study_group_members gm JOIN study_groups g ON g.id=gm.group_id WHERE gm.group_id=m.group_id AND gm.user_id=? AND g.status='active')";};
        List<Object> args=new ArrayList<>();if(!scope.equals("public"))args.add(user);
        if(!subject.isBlank()){sql+=" AND m.subject=?";args.add(subject.trim());}k.check(q.length()<=100,"关键词过长");if(!q.isBlank()){sql+=" AND (LOCATE(?,m.question)>0 OR LOCATE(?,m.tags)>0 OR LOCATE(?,m.knowledge_point)>0)";args.add(q);args.add(q);args.add(q);}
        if(due)sql+=" AND (m.next_review_date IS NULL OR m.next_review_date<=CURRENT_DATE)";
        sql+=" ORDER BY m.id DESC LIMIT 21 OFFSET ?";args.add(k.offset(page));return k.page(k.db.queryForList(sql,args.toArray()));
    }
    @GetMapping("/{id}") Map<String,Object> detail(Authentication auth,@PathVariable long id){var r=k.mistake(auth,id);if(k.n(r,"user_id")==k.user(auth))r.put("reviews",k.db.queryForList("SELECT * FROM mistake_reviews WHERE mistake_id=? ORDER BY id DESC LIMIT 100",id));return r;}
    private Map<String,Object> fields(Authentication auth,Map<String,Object> input){
        Map<String,Object> r=new LinkedHashMap<>();for(String field:List.of("question","answer","reason"))r.put(field,k.text(input,field,1,10000));r.put("subject",k.text(input,"subject",1,40));r.put("knowledge_point",k.text(input,"knowledgePoint",0,120));r.put("source",k.text(input,"source",0,255));r.put("tags",k.text(input,"tags",0,500));String image=k.text(input,"imagePath",0,255);if(!image.isEmpty()){k.check(image.matches("/api/mistakes/images/[0-9]+"),"请使用错题专用图片上传");long imageId=Long.parseLong(image.substring(image.lastIndexOf('/')+1));k.required("SELECT id FROM mistake_images WHERE id=? AND user_id=?",imageId,k.user(auth));}r.put("image_path",image.isEmpty()?null:image);
        String visibility=k.choice(input,"visibility","private","public","private","group");long group=k.number(input,"groupId",0),post=k.number(input,"postId",0);
        k.check(group>=0 && post>=0,"关联 ID 无效");k.check(!visibility.equals("group") || k.member(group,k.user(auth)),"共享错题需选择你加入的有效小组");
        if(post>0)k.required("SELECT id FROM posts WHERE id=? AND status='published'",post);
        r.put("visibility",visibility);r.put("group_id",visibility.equals("group")?group:null);r.put("post_id",post>0?post:null);return r;
    }
    @PostMapping @Transactional
    Map<String,Object> create(Authentication auth,@RequestBody Map<String,Object> input){k.forum.writable(auth);var r=fields(auth,input);long id=k.insert("INSERT INTO mistakes(user_id,subject,question,answer,reason,knowledge_point,source,tags,image_path,visibility,group_id,post_id,next_review_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_DATE)",k.user(auth),r.get("subject"),r.get("question"),r.get("answer"),r.get("reason"),r.get("knowledge_point"),r.get("source"),r.get("tags"),r.get("image_path"),r.get("visibility"),r.get("group_id"),r.get("post_id"));return Map.of("id",id);}
    @PatchMapping("/{id}") @Transactional
    void edit(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input){k.owner(auth,k.mistake(auth,id),"user_id");var r=fields(auth,input);k.db.update("UPDATE mistakes SET subject=?,question=?,answer=?,reason=?,knowledge_point=?,source=?,tags=?,image_path=?,visibility=?,group_id=?,post_id=? WHERE id=?",r.get("subject"),r.get("question"),r.get("answer"),r.get("reason"),r.get("knowledge_point"),r.get("source"),r.get("tags"),r.get("image_path"),r.get("visibility"),r.get("group_id"),r.get("post_id"),id);}
    @PostMapping("/{id}/review") @Transactional
    void review(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input){k.owner(auth,k.mistake(auth,id),"user_id");String result=k.choice(input,"result","incorrect","correct","incorrect");var r=k.required("SELECT * FROM mistakes WHERE id=? FOR UPDATE",id);boolean correct=result.equals("correct");int interval=correct?(int)Math.min(30,1+k.n(r,"correct_count")*3):1;
        k.db.update("INSERT INTO mistake_reviews(mistake_id,user_id,result) VALUES(?,?,?)",id,k.user(auth),result);k.db.update("UPDATE mistakes SET correct_count=correct_count+?,error_count=error_count+?,next_review_date=? WHERE id=?",correct?1:0,correct?0:1,LocalDate.now().plusDays(interval),id);}
    @GetMapping("/groups")
    List<Map<String,Object>> groups(Authentication auth){return k.db.queryForList("SELECT g.id,g.name FROM study_groups g JOIN study_group_members m ON m.group_id=g.id WHERE m.user_id=? AND g.status='active' ORDER BY g.name",k.forum.id(auth));}
    @GetMapping("/stats")
    Map<String,Object> stats(Authentication auth){long user=k.forum.id(auth);String stats="COUNT(*) AS total,SUM(error_count) AS errors,SUM(correct_count) AS corrects,ROUND(100.0*SUM(error_count)/GREATEST(1,SUM(error_count+correct_count)),1) AS error_rate";
        return Map.of("subjects",k.db.queryForList("SELECT subject,"+stats+" FROM mistakes WHERE user_id=? GROUP BY subject ORDER BY total DESC",user),"knowledgePoints",k.db.queryForList("SELECT subject,knowledge_point,"+stats+" FROM mistakes WHERE user_id=? GROUP BY subject,knowledge_point ORDER BY errors DESC",user));}
    @PostMapping("/review-plan") @Transactional
    Map<String,Object> plan(Authentication auth,@RequestBody Map<String,Object> input){k.forum.writable(auth);
        if(!(input.get("ids") instanceof List<?> ids) || ids.isEmpty() || ids.size()>50)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"请选择 1 至 50 道错题");
        List<Map<String,Object>> rows=new ArrayList<>();Set<Long> used=new HashSet<>();for(Object value:ids){k.check(value instanceof Number || value instanceof String,"错题 ID 无效");long id=k.number(Map.of("id",value),"id",0);k.check(used.add(id),"错题重复");var r=k.mistake(auth,id);k.owner(auth,r,"user_id");rows.add(r);}
        long days=k.number(input,"days",7);k.check(days>=1 && days<=90,"复习周期须为 1 至 90 天");LocalDate start=LocalDate.now();
        long plan=k.insert("INSERT INTO study_plans(user_id,title,subject,goal,start_date,end_date) VALUES(?,?,?,?,?,?)",k.user(auth),"错题复习计划",rows.getFirst().get("subject"),"复习选中的 "+rows.size()+" 道错题，回到错题本记录掌握情况",start,start.plusDays(days-1));
        for(int i=0;i<rows.size();i++){var r=rows.get(i);String question=String.valueOf(r.get("question"));k.db.update("INSERT INTO study_tasks(plan_id,title,due_date,sort_order) VALUES(?,?,?,?)",plan,"复习错题 #"+k.n(r,"id")+"："+question.substring(0,Math.min(80,question.length())),start.plusDays(i%days),i);}
        return Map.of("id",plan);
    }
}
