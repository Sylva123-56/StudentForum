package com.studentforum;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opt-in real MySQL + real HTTP/CSRF regression. Never uses the application's normal database. */
@EnabledIfEnvironmentVariable(named="KNOWLEDGE_INTEGRATION",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=${KNOWLEDGE_TEST_DB_URL:jdbc:mysql://localhost:3306/student_forum_knowledge_test_20261011?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}",
 "spring.datasource.username=${KNOWLEDGE_TEST_DB_USER:root}","spring.datasource.password=${KNOWLEDGE_TEST_DB_PASSWORD:}",
 "forum.knowledge-files=target/knowledge-test-files","forum.uploads=target/knowledge-test-images","forum.demo-password="})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeIntegrationTest {
 @org.springframework.test.context.DynamicPropertySource
 static void isolatedDatabase(org.springframework.test.context.DynamicPropertyRegistry registry){String url=Objects.requireNonNullElse(System.getenv("KNOWLEDGE_TEST_DB_URL"),"jdbc:mysql://localhost:3306/student_forum_knowledge_test_20261011?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");String database=URI.create(url.substring(5)).getPath();if(database==null || !database.toLowerCase(Locale.ROOT).contains("knowledge_test"))throw new IllegalArgumentException("Refusing to migrate a non-knowledge_test database");registry.add("spring.datasource.url",()->url);}
 @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @LocalServerPort int port;
 Client owner,other,admin,mod; long ownerId,otherId; String suffix;
 class Client {
  final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
  HttpResponse<String> request(String method,String path,Object data) throws Exception{
   var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api"+path));
   if(!method.equals("GET")){String token=json.readTree(http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()).get("token").asText();builder.header("X-XSRF-TOKEN",token);}
   builder.header("Content-Type","application/json");builder.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
  }
  JsonNode ok(String method,String path,Object data) throws Exception{var r=request(method,path,data);assertTrue(r.statusCode()>=200&&r.statusCode()<300,()->method+" "+path+" => "+r.statusCode()+" "+r.body());return r.body().isBlank()?json.nullNode():json.readTree(r.body());}
  long register(String name) throws Exception{return ok("POST","/auth/register",Map.of("email",name+suffix+"@example.test","username",name+suffix,"password","Knowledge-Test-2026")).get("id").asLong();}
  void login(String name) throws Exception{ok("POST","/auth/login",Map.of("email",name+suffix+"@example.test","password","Knowledge-Test-2026"));}
  long upload() throws Exception{
   var csrf=ok("GET","/csrf",null).get("token").asText();String boundary="knowledge-test-boundary";
   String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"study.txt\"\r\nContent-Type: text/plain\r\n\r\nKnowledge material for integration tests.\r\n--"+boundary+"--\r\n";
   var r=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/resources/files")).header("X-XSRF-TOKEN",csrf).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,r.statusCode(),r.body());return json.readTree(r.body()).get("id").asLong();
  }
 }
 @BeforeAll void setup() throws Exception{
  assertTrue(db.queryForObject("SELECT DATABASE()",String.class).contains("knowledge_test"),"Integration tests require a dedicated knowledge_test database");
  suffix=Long.toString(System.currentTimeMillis());owner=new Client();other=new Client();admin=new Client();mod=new Client();ownerId=owner.register("知识作者");otherId=other.register("知识读者");long a=admin.register("知识管理员"),m=mod.register("知识版主");db.update("UPDATE users SET role='admin' WHERE id=?",a);db.update("UPDATE users SET role='moderator',moderator_board_id=1 WHERE id=?",m);admin.login("知识管理员");mod.login("知识版主");
 }
 Map<String,Object> mistake(String visibility,long group){var m=new LinkedHashMap<String,Object>(Map.of("subject","数学","question","求函数的导数","answer","使用链式法则","reason","忘记内层导数","knowledgePoint","导数","visibility",visibility));m.put("groupId",group);return m;}
 @Test void resourceModerationChargingOwnershipAndTakedown() throws Exception{
  long upload=owner.upload();var input=Map.of("title","导数复习资料","description","原创知识资料","source","原创","copyrightNote","允许个人学习使用","integrityAccepted",true,"downloadPoints",3,"files",List.of(upload));
  long id=owner.ok("POST","/resources",input).get("id").asLong();assertEquals(404,other.request("GET","/resources/"+id,null).statusCode());assertEquals(403,mod.request("PATCH","/admin/resources/"+id,Map.of("status","published","note","通过")).statusCode());
  var pending=admin.ok("GET","/resources/"+id,null);long file=pending.get("files").get(0).get("id").asLong();assertEquals(404,other.request("GET","/resources/"+id+"/files/"+file,null).statusCode());
  admin.ok("PATCH","/admin/resources/"+id,Map.of("status","published","note","原创资料通过审核"));assertEquals(403,other.request("GET","/resources/"+id+"/files/"+file,null).statusCode());
  int before=other.ok("GET","/me",null).get("points").asInt();other.ok("POST","/resources/"+id+"/download",null);other.ok("POST","/resources/"+id+"/download",null);assertEquals(before-3,other.ok("GET","/me",null).get("points").asInt());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM point_logs WHERE user_id=? AND action='resource_download' AND ref_id=?",Integer.class,otherId,id));assertEquals(200,other.request("GET","/resources/"+id+"/files/"+file,null).statusCode());
  other.ok("POST","/resources/"+id+"/favorite",Map.of("active",true));other.ok("POST","/resources/"+id+"/like",Map.of("active",true));other.ok("POST","/resources/"+id+"/comments",Map.of("content","感谢分享"));assertTrue(other.ok("GET","/resources/"+id,null).get("favorited").asBoolean());
  other.ok("POST","/resources/"+id+"/report",Map.of("reason","抄袭侵权","description","核实来源"));long report=db.queryForObject("SELECT id FROM reports WHERE target_type='resource' AND target_id=? ORDER BY id DESC LIMIT 1",Long.class,id);admin.ok("PATCH","/admin/reports/"+report,Map.of("action","hide","note","版权复核"));assertEquals(404,other.request("GET","/resources/"+id+"/files/"+file,null).statusCode());
  assertEquals(404,other.request("POST","/resources",Map.of("title","盗用别人的文件","description","描述","source","来源","copyrightNote","授权","integrityAccepted",true,"files",List.of(upload))).statusCode());
  assertTrue(other.ok("GET","/resources/downloads",null).size()>=2);
 }
 @Test void resourceMetadataMigrationRepairsLegacySchema() throws Exception{
  assertTrue(db.queryForObject("SELECT DATABASE()",String.class).toLowerCase(Locale.ROOT).contains("knowledge_test"));
  db.execute("ALTER TABLE resources DROP COLUMN exam");db.execute("ALTER TABLE resources DROP COLUMN textbook");
  String script=Files.readString(Path.of("src/main/resources/db/migration/V13__knowledge_resource_metadata.sql")).replaceAll("(?m)^--.*$","");
  db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{try(var statement=connection.createStatement()){for(String sql:script.split(";")){if(!sql.isBlank())statement.execute(sql.trim());}}return null;});
  var columns=db.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resources' AND column_name IN ('textbook','exam')");assertEquals(2,columns.size());
 }
 @Test void insufficientPointsRollBackDownloadAndCount() throws Exception{
  long id=owner.ok("POST","/resources",Map.of("title","积分边界资料","description","原创描述","source","原创","copyrightNote","仅学习使用","integrityAccepted",true,"downloadPoints",100,"files",List.of(owner.upload()))).get("id").asLong();admin.ok("PATCH","/admin/resources/"+id,Map.of("status","published","note","审核通过"));assertEquals(400,other.request("POST","/resources/"+id+"/download",null).statusCode());assertEquals(0,db.queryForObject("SELECT download_count FROM resources WHERE id=?",Integer.class,id));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM resource_downloads WHERE resource_id=?",Integer.class,id));
 }
 @Test void mistakesVisibilityReviewAndPlan() throws Exception{
  long id=owner.ok("POST","/mistakes",mistake("private",0)).get("id").asLong();assertEquals(404,other.request("GET","/mistakes/"+id,null).statusCode());owner.ok("PATCH","/mistakes/"+id,mistake("public",0));assertEquals(200,other.request("GET","/mistakes/"+id,null).statusCode());assertEquals(403,other.request("PATCH","/mistakes/"+id,mistake("private",0)).statusCode());assertEquals(403,other.request("POST","/mistakes/"+id+"/review",Map.of("result","correct")).statusCode());
  long group=owner.ok("POST","/groups",Map.of("name","复习小组"+suffix,"description","错题分享","visibility","private","joinMode","invite")).get("group").get("id").asLong();owner.ok("PATCH","/mistakes/"+id,mistake("group",group));assertEquals(404,other.request("GET","/mistakes/"+id,null).statusCode());db.update("INSERT INTO study_group_members(group_id,user_id,role) VALUES(?,?,'member')",group,otherId);assertEquals(200,other.request("GET","/mistakes/"+id,null).statusCode());db.update("DELETE FROM study_group_members WHERE group_id=? AND user_id=?",group,otherId);assertEquals(404,other.request("GET","/mistakes/"+id,null).statusCode());
  owner.ok("POST","/mistakes/"+id+"/review",Map.of("result","correct"));owner.ok("POST","/mistakes/"+id+"/review",Map.of("result","incorrect"));var detail=owner.ok("GET","/mistakes/"+id,null);assertEquals(1,detail.get("correct_count").asInt());assertEquals(2,detail.get("error_count").asInt());assertEquals(2,detail.get("reviews").size());long plan=owner.ok("POST","/mistakes/review-plan",Map.of("ids",List.of(id),"days",7)).get("id").asLong();assertEquals(1,owner.ok("GET","/plans/"+plan,null).get("tasks").size());assertTrue(owner.ok("GET","/mistakes/stats",null).get("subjects").size()>0);assertEquals(400,other.request("POST","/mistakes",mistake("group",group)).statusCode());
 }
 @Test void notesCollectionsAndColumnSerialization() throws Exception{
  var input=new LinkedHashMap<String,Object>(Map.of("title","导数学习笔记","content","# 链式法则\n先外后内","status","draft"));long note=owner.ok("POST","/notes",input).get("id").asLong();assertEquals(404,other.request("GET","/notes/"+note,null).statusCode());assertEquals(404,other.request("GET","/notes/"+note+"/revisions",null).statusCode());long collection=owner.ok("POST","/collections",Map.of("title","导数专题","description","从概念到应用")).get("id").asLong();assertEquals(400,owner.request("POST","/collections/"+collection+"/items",Map.of("itemType","note","itemId",note)).statusCode());input.put("status","published");owner.ok("PATCH","/notes/"+note,input);assertEquals(2,owner.ok("GET","/notes/"+note+"/revisions",null).size());assertEquals(403,other.request("GET","/notes/"+note+"/revisions",null).statusCode());owner.ok("POST","/collections/"+collection+"/items",Map.of("itemType","note","itemId",note,"sortOrder",2));owner.ok("POST","/collections/"+collection+"/items",Map.of("itemType","note","itemId",note,"sortOrder",1));assertEquals(1,owner.ok("GET","/collections/"+collection,null).get("items").size());assertEquals(403,other.request("POST","/collections/"+collection+"/items",Map.of("itemType","note","itemId",note)).statusCode());other.ok("POST","/collections/"+collection+"/follow",Map.of("active",true));other.ok("POST","/collections/"+collection+"/favorite",Map.of("active",true));assertTrue(other.ok("GET","/collections?favorite=true",null).get("items").size()>0);admin.ok("PATCH","/admin/collections/"+collection,Map.of("status","active","featured",true));assertTrue(owner.ok("GET","/collections?featured=true",null).get("items").size()>0);
  long column=owner.ok("POST","/columns",Map.of("title","微积分连载","description","每期一个概念")).get("id").asLong();long e=owner.ok("POST","/columns/"+column+"/episodes",Map.of("noteId",note,"episodeNo",1)).get("id").asLong();assertEquals(400,owner.request("POST","/columns/"+column+"/episodes",Map.of("noteId",note,"episodeNo",1)).statusCode());owner.ok("POST","/columns/"+column+"/episodes",Map.of("title","第二期草稿","content","尚在整理","episodeNo",2,"status","draft"));assertEquals(1,other.ok("GET","/columns/"+column,null).get("episodes").size());owner.ok("PATCH","/columns/"+column+"/episodes/"+e,Map.of("title","第一期修订","content","补充案例","status","published"));
  input.put("status","draft");owner.ok("PATCH","/notes/"+note,input);assertEquals(0,other.ok("GET","/collections/"+collection,null).get("items").size());
 }
 @Test void wikiBoardScopeHistoryConcurrencyAndTakedown() throws Exception{
  var input=new LinkedHashMap<String,Object>(Map.of("title","导数常见问题"+suffix,"content","# FAQ\n如何理解导数？","tags","导数,路线","status","draft"));assertEquals(403,owner.request("POST","/wiki/1",input).statusCode());assertEquals(403,mod.request("POST","/wiki/2",input).statusCode());long id=mod.ok("POST","/wiki/1",input).get("id").asLong();assertFalse(other.ok("GET","/wiki/1",null).get("items").toString().contains("导数常见问题"+suffix));long revision=mod.ok("GET","/wiki/pages/"+id+"/revisions",null).get(0).get("id").asLong();input.put("baseRevisionId",revision);input.put("status","published");mod.ok("PATCH","/wiki/"+id,input);assertEquals(409,mod.request("PATCH","/wiki/"+id,input).statusCode());assertEquals(2,mod.ok("GET","/wiki/pages/"+id+"/revisions",null).size());assertTrue(other.ok("GET","/wiki/1?q=路线",null).get("items").size()>0);assertEquals(403,other.request("GET","/wiki/pages/"+id+"/revisions",null).statusCode());admin.ok("PATCH","/admin/wiki/"+id,Map.of("status","draft","note","知识内容复核"));assertFalse(other.ok("GET","/wiki/1",null).get("items").toString().contains("导数常见问题"+suffix));
 }
 @Test void chineseLongFormFitsStorageAndPostDeletionKeepsKnowledge() throws Exception{
  String content="知".repeat(29000);long note=owner.ok("POST","/notes",Map.of("title","中文长文存储回归","content",content,"status","published")).get("id").asLong();assertEquals(content,owner.ok("GET","/notes/"+note,null).get("content").asText());
  long post=owner.ok("POST","/posts",Map.of("boardId",1,"type","question","title","如何理解链式法则？","content","复合函数求导有什么需要注意的步骤？")).get("id").asLong();var input=mistake("private",0);input.put("postId",post);long mistake=owner.ok("POST","/mistakes",input).get("id").asLong();owner.ok("DELETE","/posts/"+post,null);assertTrue(owner.ok("GET","/mistakes/"+mistake,null).get("post_id").isNull());
 }
 @Test void privateImagesFollowVisibilityAndCannotBeReusedByAnotherUser() throws Exception{
  byte[] image=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aTm0AAAAASUVORK5CYII=");String boundary="image-test-boundary";var bytes=new java.io.ByteArrayOutputStream();bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"question.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));bytes.write(image);bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));String csrf=owner.ok("GET","/csrf",null).get("token").asText();var response=owner.http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/mistakes/images")).header("X-XSRF-TOKEN",csrf).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());String path=json.readTree(response.body()).get("path").asText();var input=mistake("private",0);input.put("imagePath",path);long id=owner.ok("POST","/mistakes",input).get("id").asLong();assertEquals(200,owner.request("GET","/mistakes/"+id+"/image",null).statusCode());assertEquals(404,other.request("GET","/mistakes/"+id+"/image",null).statusCode());assertEquals(404,other.request("POST","/mistakes",input).statusCode());input.put("visibility","public");owner.ok("PATCH","/mistakes/"+id,input);assertEquals(200,new Client().request("GET","/mistakes/"+id+"/image",null).statusCode());input.put("visibility","private");owner.ok("PATCH","/mistakes/"+id,input);assertEquals(404,new Client().request("GET","/mistakes/"+id+"/image",null).statusCode());assertEquals(404,other.request("GET",path.substring(4),null).statusCode());
 }
 @Test void publicGetDoesNotOpenMutationOrPrivateFileEndpoints() throws Exception{
  Client guest=new Client();assertEquals(200,guest.request("GET","/resources",null).statusCode());assertEquals(200,guest.request("GET","/notes",null).statusCode());assertEquals(200,guest.request("GET","/collections",null).statusCode());assertEquals(200,guest.request("GET","/wiki/1",null).statusCode());assertEquals(401,guest.request("GET","/mistakes",null).statusCode());assertEquals(401,guest.request("POST","/notes",Map.of("title","未登录写入","content","不应成功")).statusCode());
 }

 @Autowired com.studentforum.service.ForumService forum;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

 @Test void csrfAndSessionRotationAreEnforcedByTheRealFilterChain() throws Exception {
  Client client = new Client();
  long id = client.register("安全边界");
  CookieManager cookies = (CookieManager) client.http.cookieHandler().orElseThrow();
  String oldSession = cookies.getCookieStore().getCookies().stream()
    .filter(cookie -> "JSESSIONID".equals(cookie.getName())).findFirst().orElseThrow().getValue();
  var noToken = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me/profile"))
    .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
  assertEquals(403, client.http.send(noToken, HttpResponse.BodyHandlers.ofString()).statusCode());
  var badToken = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me/profile"))
    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", "invalid")
    .PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
  assertEquals(403, client.http.send(badToken, HttpResponse.BodyHandlers.ofString()).statusCode());
  client.login("安全边界");
  String newSession = cookies.getCookieStore().getCookies().stream()
    .filter(cookie -> "JSESSIONID".equals(cookie.getName())).findFirst().orElseThrow().getValue();
  assertNotEquals(oldSession, newSession);
  assertEquals(id, client.ok("GET", "/me", null).get("id").asLong());
  var staleSession = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me"))
    .header("Cookie", "JSESSIONID=" + oldSession).GET().build();
  assertEquals(401, HttpClient.newHttpClient().send(staleSession, HttpResponse.BodyHandlers.ofString()).statusCode());
  client.ok("POST", "/auth/logout", null);
  assertEquals(401, client.request("GET", "/me", null).statusCode());
 }

 @Test void postAndReplyWritesRollBackWhenReferencedContentIsMissing() throws Exception {
  String title = "事务失败验证" + suffix;
  var input = new LinkedHashMap<String, Object>(Map.of("boardId", 1, "type", "question",
    "title", title, "content", "缺失标签或引用时不能留下已经插入的内容。"));
  input.put("tagIds", List.of(Long.MAX_VALUE));
  assertEquals(404, owner.request("POST", "/posts", input).statusCode());
  assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM posts WHERE author_id=? AND title=?", Integer.class, ownerId, title));
  input.remove("tagIds");
  long post = owner.ok("POST", "/posts", input).get("id").asLong();
  int balance = db.queryForObject("SELECT points FROM users WHERE id=?", Integer.class, ownerId);
  assertEquals(404, owner.request("POST", "/posts/" + post + "/replies",
    Map.of("content", "无效引用不应保留回复", "quoteReplyId", Long.MAX_VALUE)).statusCode());
  assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM replies WHERE post_id=?", Integer.class, post));
  assertEquals(balance, db.queryForObject("SELECT points FROM users WHERE id=?", Integer.class, ownerId));
 }

 @Test void postUploadsAreDeletedOnlyAfterTheOutermostTransactionCommits() throws Exception {
  long post = owner.ok("POST", "/posts", Map.of("boardId", 1, "type", "discussion",
    "title", "删帖文件事务验证", "content", "验证数据库回滚与文件清理顺序，不丢失仍被引用的文件。")).get("id").asLong();
  String filename = UUID.randomUUID() + ".png";
  Path file = Path.of("target/knowledge-test-images", filename);
  Files.createDirectories(file.getParent());
  Files.write(file, new byte[] {1, 2, 3});
  db.update("UPDATE posts SET image_path=? WHERE id=?", "/uploads/" + filename, post);
  var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
  transaction.executeWithoutResult(status -> {
   forum.hardDeletePost(post);
   assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM posts WHERE id=?", Integer.class, post));
   assertTrue(Files.exists(file), "Files must survive until commit");
   status.setRollbackOnly();
  });
  assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM posts WHERE id=?", Integer.class, post));
  assertTrue(Files.exists(file), "Rollback must preserve referenced uploads");
  owner.ok("DELETE", "/posts/" + post, null);
  assertFalse(Files.exists(file), "Committed post deletion must clean the upload");
 }

 @Test void forumReadsAndReplyDeletionKeepCrossUserAndModeratorBoundaries() throws Exception {
  long post = owner.ok("POST", "/posts", Map.of("boardId", 1, "type", "question",
    "title", "论坛访问边界验证", "content", "在真实权限过滤链下验证作者、读者和版主的访问边界。")).get("id").asLong();
  long reply = other.ok("POST", "/posts/" + post + "/replies", Map.of("content", "这是用于校验权限的回复")).get("id").asLong();
  assertEquals(403, owner.request("DELETE", "/replies/" + reply, null).statusCode());
  assertEquals(403, mod.request("DELETE", "/replies/" + reply, null).statusCode());
  assertEquals(403, owner.request("PATCH", "/admin/posts/" + post + "/status", Map.of("status", "hidden")).statusCode());
  other.ok("DELETE", "/replies/" + reply, null);
  assertEquals(0, other.ok("GET", "/posts/" + post + "/replies", null).size());
  admin.ok("PATCH", "/admin/posts/" + post + "/status", Map.of("status", "hidden"));
  assertEquals(404, new Client().request("GET", "/posts/" + post, null).statusCode());
  assertEquals(404, other.request("POST", "/posts/" + post + "/favorite", null).statusCode());
 }

 @Test void studySettingsAndCheckinsKeepRewardsAndOwnerScope() throws Exception {
  JsonNode previous = admin.ok("GET", "/admin/study-tools/checkin", null).get("settings");
  Client student = new Client();
  long user = student.register("学习打卡");
  try {
   assertEquals(403, student.request("PATCH", "/admin/study-tools/checkin/settings", Map.of("checkin_points", 3)).statusCode());
   admin.ok("PATCH", "/admin/study-tools/checkin/settings", Map.of("checkin_points", 3));
   long goal = student.ok("POST", "/checkin/goals", Map.of("title", "每日学习打卡", "subject", "数学",
     "targetMinutes", 25, "visibility", "private")).get("id").asLong();
   var checkin = student.ok("POST", "/checkin", Map.of("goalId", goal, "content", "完成今日习题", "durationMinutes", 25));
   assertEquals(3, checkin.get("pointsAwarded").asInt());
   assertEquals(3, checkin.get("points").asInt());
   assertEquals(checkin.get("id").asLong(), checkin.get("checkin").get("id").asLong());
   assertEquals(400, student.request("POST", "/checkin", Map.of("goalId", goal, "content", "重复打卡", "durationMinutes", 25)).statusCode());
   assertEquals(403, other.request("GET", "/checkin/goals/" + goal, null).statusCode());
   assertEquals(404, other.request("POST", "/checkin", Map.of("goalId", goal, "content", "他人目标", "durationMinutes", 25)).statusCode());
   assertEquals(1, student.ok("GET", "/checkin/records?goalId=" + goal, null).get("items").size());
   assertEquals(3, db.queryForObject("SELECT points FROM point_logs WHERE user_id=? AND action='checkin' AND ref_id=?", Integer.class, user, checkin.get("id").asLong()));
   assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM user_badges WHERE user_id=? AND code='checkin_first'", Integer.class, user));
  } finally {
   admin.ok("PATCH", "/admin/study-tools/checkin/settings", Map.of("checkin_points", previous.get("checkin_points").asInt()));
  }
 }

 @Test void focusSessionAndRoomTotalsStayPrivateAndRecalculateOnAdminDeletion() throws Exception {
  long room = owner.ok("POST", "/study-rooms", Map.of("name", "私密专注验证", "visibility", "private")).get("id").asLong();
  assertEquals(403, other.request("POST", "/study-rooms/" + room + "/join", null).statusCode());
  assertEquals(403, other.request("POST", "/focus/sessions", Map.of("roomId", room, "durationMinutes", 25)).statusCode());
  assertEquals(403, new Client().request("GET", "/study-rooms/" + room, null).statusCode());
  var session = owner.ok("POST", "/focus/sessions", Map.of("roomId", room, "durationMinutes", 25, "subject", "数学", "source", "room"));
  assertEquals(25, session.get("duration_minutes").asInt());
  var details = owner.ok("GET", "/study-rooms/" + room, null);
  assertEquals(25, details.get("focus_minutes").asInt());
  assertEquals(1, details.get("member_count").asInt());
  var history = owner.ok("GET", "/focus/sessions?range=day", null);
  assertTrue(history.get("items").size() > 0);
  assertTrue(history.get("summary").get("minutes").asInt() >= 25);
  assertEquals(200, new Client().request("GET", "/focus/leaderboard", null).statusCode());
  admin.ok("DELETE", "/admin/study-tools/focus/" + session.get("id").asLong(), null);
  assertEquals(0, owner.ok("GET", "/study-rooms/" + room, null).get("focus_minutes").asInt());
 }

 @Test void planTaskRollupPreservesArchiveAndPreventsIdenticalCompletionReward() throws Exception {
  var plan = owner.ok("POST", "/plans", Map.of("title", "学习进度验证", "subject", "数学", "tasks", List.of(Map.of("title", "父任务"))));
  long id = plan.get("id").asLong();
  assertTrue(plan.get("end_date").isNull());
  long parent = plan.get("tasks").get(0).get("id").asLong();
  var withChild = owner.ok("POST", "/plans/" + id + "/tasks", Map.of("title", "子任务", "parentId", parent));
  long child = 0;
  for (JsonNode task : withChild.get("tasks")) if (!task.get("parent_id").isNull()) child = task.get("id").asLong();
  assertTrue(child > 0);
  assertEquals(404, other.request("GET", "/plans/" + id, null).statusCode());
  assertEquals(400, owner.request("PATCH", "/plans/" + id + "/tasks/" + parent, Map.of("status", "done")).statusCode());
  var completed = owner.ok("PATCH", "/plans/" + id + "/tasks/" + child, Map.of("status", "done"));
  assertEquals("completed", completed.get("status").asText());
  assertEquals(1, completed.get("progress").get("total").asInt());
  assertEquals(100, completed.get("progress").get("percent").asInt());
  int awarded = db.queryForObject("SELECT COUNT(*) FROM point_logs WHERE user_id=? AND ref_type='study_task' AND ref_id=?", Integer.class, ownerId, child);
  owner.ok("PATCH", "/plans/" + id + "/tasks/" + child, Map.of("status", "done"));
  assertEquals(awarded, db.queryForObject("SELECT COUNT(*) FROM point_logs WHERE user_id=? AND ref_type='study_task' AND ref_id=?", Integer.class, ownerId, child));
  owner.ok("PATCH", "/plans/" + id + "/archive", Map.of("archived", true));
  var archived = owner.ok("PATCH", "/plans/" + id + "/tasks/" + child, Map.of("status", "pending"));
  assertEquals("archived", archived.get("status").asText());
 }
}
