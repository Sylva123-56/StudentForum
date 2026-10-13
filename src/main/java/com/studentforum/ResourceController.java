package com.studentforum;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class ResourceController {
    private final KnowledgeService k;
    private final Path directory;
    ResourceController(KnowledgeService k,@Value("${forum.knowledge-files:knowledge-files}") String path,@Value("${forum.uploads}") String publicUploads) { this.k=k; directory=Path.of(path).toAbsolutePath().normalize(); if(directory.startsWith(Path.of(publicUploads).toAbsolutePath().normalize()))throw new IllegalArgumentException("Knowledge files must not be stored under public uploads"); }
    @GetMapping("/resources")
    Map<String,Object> list(Authentication auth,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String subject,@RequestParam(defaultValue="") String grade,@RequestParam(defaultValue="") String textbook,@RequestParam(defaultValue="") String exam,@RequestParam(defaultValue="false") boolean mine,@RequestParam(defaultValue="false") boolean favorite,@RequestParam(defaultValue="1") int page) {
        k.check(!mine && !favorite || k.user(auth)>0,"请先登录");
        String sql="SELECT r.*,u.username,(SELECT COUNT(*) FROM resource_favorites f WHERE f.resource_id=r.id) AS favorite_count,(SELECT COUNT(*) FROM resource_votes v WHERE v.resource_id=r.id) AS like_count FROM resources r JOIN users u ON u.id=r.uploader_id WHERE "+(mine?"r.uploader_id=?":"r.status='published'");
        List<Object> args=new ArrayList<>(); if(mine)args.add(k.user(auth));
        for(var entry:Map.of("subject",subject,"grade",grade,"textbook",textbook,"exam",exam).entrySet())if(!entry.getValue().isBlank()){ sql+=" AND r."+entry.getKey()+"=?"; args.add(entry.getValue().trim()); }
        k.check(q.length()<=100,"关键词过长"); if(!q.isBlank()){sql+=" AND (LOCATE(?,r.title)>0 OR LOCATE(?,r.description)>0 OR LOCATE(?,r.tags)>0)"; args.add(q.trim());args.add(q.trim());args.add(q.trim());}
        if(favorite){sql+=" AND EXISTS(SELECT 1 FROM resource_favorites f WHERE f.resource_id=r.id AND f.user_id=?)";args.add(k.user(auth));}
        sql+=" ORDER BY r.id DESC LIMIT 21 OFFSET ?";args.add(k.offset(page)); return k.page(k.db.queryForList(sql,args.toArray()));
    }
    @PostMapping("/resources/files") @Transactional
    Map<String,Object> upload(Authentication auth,@RequestParam MultipartFile file) throws IOException {
        k.forum.writable(auth); k.check(!file.isEmpty() && file.getSize()<=25_000_000,"文件大小须为 1 字节至 25MB");
        String name=Objects.toString(file.getOriginalFilename(),"file").replace((char)92,'_').replaceAll("[\\/\r\n]","_"); k.check(name.length()<=255,"文件名过长");
        String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        k.check(Set.of("jpg","jpeg","png","webp","pdf","txt","doc","docx","xls","xlsx","ppt","pptx","zip").contains(ext),"支持图片、PDF、Office 文档、TXT 和 ZIP");
        byte[] bytes=file.getBytes(); validateFile(ext,bytes);
        String stored=UUID.randomUUID()+"."+ext; Files.createDirectories(directory); Path target=directory.resolve(stored); Files.write(target,bytes);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(target);}catch(IOException ignored){} }
        });
        String mime=switch(ext){case "pdf"->"application/pdf";case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";case "webp"->"image/webp";case "txt"->"text/plain";default->"application/octet-stream";};
        long id=k.insert("INSERT INTO resource_uploads(user_id,file_name,stored_name,mime_type,size_bytes) VALUES(?,?,?,?,?)",k.user(auth),name,stored,mime,bytes.length);
        return Map.of("id",id,"name",name,"sizeBytes",bytes.length);
    }
    static void validateFile(String ext,byte[] b) throws IOException {
        boolean zip=b.length>=4 && b[0]=='P' && b[1]=='K' && b[2]==3 && b[3]==4;
        boolean valid=switch(ext){
            case "pdf" -> b.length>4 && b[0]=='%' && b[1]=='P' && b[2]=='D' && b[3]=='F' && b[4]=='-';
            case "jpg","jpeg" -> b.length>3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255;
            case "png" -> b.length>=8 && Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
            case "webp" -> b.length>=12 && new String(b,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF") && new String(b,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
            case "doc","xls","ppt" -> b.length>=8 && Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)208,(byte)207,17,(byte)224,(byte)161,(byte)177,26,(byte)225});
            case "docx","xlsx","pptx" -> zip && officePackage(ext,b);
            case "zip" -> zip || b.length>=4 && b[0]=='P' && b[1]=='K' && b[2]==5 && b[3]==6;
            case "txt" -> { try { java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)); yield !new String(b,java.nio.charset.StandardCharsets.UTF_8).contains("\0"); } catch(java.nio.charset.CharacterCodingException e){ yield false; } }
            default -> false;
        };
        if(!valid)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"文件实际格式与扩展名不符");
    }
    private static boolean officePackage(String ext,byte[] b) throws IOException {
        String prefix=switch(ext){case "docx"->"word/";case "xlsx"->"xl/";default->"ppt/";};
        boolean types=false,document=false; int entries=0; long total=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(b))){ java.util.zip.ZipEntry entry; byte[] buffer=new byte[8192]; while((entry=zip.getNextEntry())!=null){ if(++entries>2000)return false; String name=entry.getName(); if(name.equals("[Content_Types].xml"))types=true; if(name.startsWith(prefix))document=true; int read; while((read=zip.read(buffer))!=-1){total+=read;if(total>100_000_000)return false;} } } catch(java.util.zip.ZipException e){return false;}
        return types && document;
    }
    @PostMapping("/resources") @Transactional
    Map<String,Object> create(Authentication auth,@RequestBody Map<String,Object> input) {
        k.forum.writable(auth); k.check(Boolean.TRUE.equals(input.get("integrityAccepted")),"请确认版权与学术诚信声明");
        if(!(input.get("files") instanceof List<?> files) || files.isEmpty() || files.size()>10)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择 1 至 10 个文件");
        long points=k.number(input,"downloadPoints",0); k.check(points>=0 && points<=100,"下载积分须为 0 至 100");
        long id=k.insert("INSERT INTO resources(uploader_id,title,description,subject,grade,textbook,exam,tags,source,copyright_note,download_points) VALUES(?,?,?,?,?,?,?,?,?,?,?)",k.user(auth),k.text(input,"title",2,160),k.text(input,"description",1,10000),k.text(input,"subject",0,40),k.text(input,"grade",0,40),k.text(input,"textbook",0,80),k.text(input,"exam",0,80),k.text(input,"tags",0,500),k.text(input,"source",1,255),k.text(input,"copyrightNote",1,500),points);
        Set<Long> used=new HashSet<>();
        for(Object value:files){k.check(value instanceof Number || value instanceof String,"文件 ID 无效");long upload=k.number(Map.of("id",value),"id",0); k.check(used.add(upload),"文件重复"); var f=k.required("SELECT * FROM resource_uploads WHERE id=? AND user_id=? AND resource_id IS NULL FOR UPDATE",upload,k.user(auth));
            k.db.update("INSERT INTO resource_files(resource_id,file_name,file_path,mime_type,size_bytes) VALUES(?,?,?,?,?)",id,f.get("file_name"),f.get("stored_name"),f.get("mime_type"),f.get("size_bytes")); k.db.update("UPDATE resource_uploads SET resource_id=? WHERE id=?",id,upload);
        }
        return Map.of("id",id,"status","pending");
    }
    @GetMapping("/resources/{id}")
    Map<String,Object> detail(Authentication auth,@PathVariable long id) {
        var r=k.resource(auth,id); r.put("files",k.db.queryForList("SELECT id,file_name,size_bytes,mime_type FROM resource_files WHERE resource_id=?",id));
        r.put("comments",k.db.queryForList("SELECT c.*,u.username FROM resource_comments c JOIN users u ON u.id=c.user_id WHERE c.resource_id=? ORDER BY c.id DESC LIMIT 100",id));
        r.put("favorited",k.row("SELECT 1 FROM resource_favorites WHERE resource_id=? AND user_id=?",id,k.user(auth))!=null); r.put("liked",k.row("SELECT 1 FROM resource_votes WHERE resource_id=? AND user_id=?",id,k.user(auth))!=null);
        r.put("favorite_count",k.db.queryForObject("SELECT COUNT(*) FROM resource_favorites WHERE resource_id=?",Long.class,id)); r.put("like_count",k.db.queryForObject("SELECT COUNT(*) FROM resource_votes WHERE resource_id=?",Long.class,id));return r;
    }
    @PostMapping("/resources/{id}/download") @Transactional
    Map<String,Object> download(Authentication auth,@PathVariable long id) {
        k.forum.writable(auth); k.resource(auth,id); var r=k.required("SELECT * FROM resources WHERE id=? FOR UPDATE",id); k.check("published".equals(r.get("status")),"仅已发布资料可以下载");
        var files=k.db.queryForList("SELECT id,file_name,size_bytes FROM resource_files WHERE resource_id=?",id); k.check(!files.isEmpty(),"资料没有文件");
        long me=k.user(auth),cost=k.n(r,"download_points");
        // Serialize on the resource row: concurrent retries charge once, later downloads remain free.
        if(me!=k.n(r,"uploader_id") && cost>0 && k.row("SELECT id FROM resource_downloads WHERE resource_id=? AND user_id=? LIMIT 1",id,me)==null){
            if(k.db.update("UPDATE users SET points=points-? WHERE id=? AND points>=?",cost,me,cost)!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"积分不足");
            k.db.update("INSERT INTO point_logs(user_id,action,points,balance_after,ref_type,ref_id) SELECT id,'resource_download',?,points,'resource',? FROM users WHERE id=?",-cost,id,me);
        }
        k.db.update("INSERT INTO resource_downloads(resource_id,user_id) VALUES(?,?)",id,me);k.db.update("UPDATE resources SET download_count=download_count+1 WHERE id=?",id);
        return Map.of("files",files);
    }
    @GetMapping("/resources/{id}/files/{fileId}")
    ResponseEntity<FileSystemResource> file(Authentication auth,@PathVariable long id,@PathVariable long fileId) throws IOException {
        var r=k.resource(auth,id); long me=k.user(auth);
        boolean privileged=me>0 && (me==k.n(r,"uploader_id") || k.manager(auth,-1));
        if(!privileged && (!"published".equals(r.get("status")) || me==0 || k.row("SELECT id FROM resource_downloads WHERE resource_id=? AND user_id=? LIMIT 1",id,me)==null))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请先点击下载资料");
        var f=k.required("SELECT * FROM resource_files WHERE id=? AND resource_id=?",fileId,id);
        Path target=directory.resolve(String.valueOf(f.get("file_path"))).normalize(); if(!target.startsWith(directory) || !Files.isRegularFile(target))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文件不存在");
        return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(String.valueOf(f.get("file_name")),java.nio.charset.StandardCharsets.UTF_8).build().toString()).contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(Files.size(target)).body(new FileSystemResource(target));
    }
    @PostMapping("/resources/{id}/{action:favorite|like}") @Transactional
    void toggle(Authentication auth,@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> input) {
        k.forum.writable(auth);var r=k.resource(auth,id);k.check("published".equals(r.get("status")),"资料尚未发布");String table=action.equals("favorite")?"resource_favorites":"resource_votes";
        if(Boolean.TRUE.equals(input.get("active")))k.db.update("INSERT IGNORE INTO "+table+"(resource_id,user_id) VALUES(?,?)",id,k.user(auth));else k.db.update("DELETE FROM "+table+" WHERE resource_id=? AND user_id=?",id,k.user(auth));
    }
    @PostMapping("/resources/{id}/comments")
    void comment(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input){k.forum.writable(auth);k.check("published".equals(k.resource(auth,id).get("status")),"资料尚未发布");k.db.update("INSERT INTO resource_comments(resource_id,user_id,content) VALUES(?,?,?)",id,k.user(auth),k.text(input,"content",1,1000));}
    @PostMapping("/resources/{id}/report")
    void report(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input){k.forum.writable(auth);k.resource(auth,id);long count=Objects.requireNonNullElse(k.db.queryForObject("SELECT COUNT(*) FROM reports WHERE reporter_id=? AND created_at>=CURRENT_DATE",Long.class,k.user(auth)),0L);k.check(count<5,"今日举报次数已达上限");k.db.update("INSERT INTO reports(reporter_id,target_type,target_id,reason,description) VALUES(?,'resource',?,?,?)",k.user(auth),id,k.text(input,"reason",1,40),k.text(input,"description",0,500));}
    @GetMapping("/resources/downloads")
    List<Map<String,Object>> downloads(Authentication auth){return k.db.queryForList("SELECT d.*,r.title FROM resource_downloads d JOIN resources r ON r.id=d.resource_id WHERE d.user_id=? ORDER BY d.id DESC LIMIT 100",k.forum.id(auth));}
    @GetMapping("/admin/resources")
    Map<String,Object> admin(Authentication auth,@RequestParam(defaultValue="1") int page){k.forum.admin(auth);return k.page(k.db.queryForList("SELECT r.*,u.username FROM resources r JOIN users u ON u.id=r.uploader_id ORDER BY r.status='pending' DESC,r.id DESC LIMIT 21 OFFSET ?",k.offset(page)));}
    @PatchMapping("/admin/resources/{id}") @Transactional
    void moderate(Authentication auth,@PathVariable long id,@RequestBody Map<String,Object> input){k.forum.admin(auth);k.required("SELECT id FROM resources WHERE id=?",id);String status=k.choice(input,"status","pending","pending","published","rejected");String note=k.text(input,"note",1,500);k.db.update("UPDATE resources SET status=? WHERE id=?",status,id);k.audit(auth,"resource_"+status,"resource",id,note);}
}
