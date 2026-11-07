package com.studentforum.service;

import com.studentforum.repository.ResourceRepository;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResourceService {
    private final ResourceRepository repository;
    private final AccountAccessService accounts;
    private final KnowledgeService knowledge;
    private final Path directory;
    public ResourceService(ResourceRepository repository, AccountAccessService accounts, KnowledgeService knowledge,@Value("${forum.knowledge-files:knowledge-files}") String path,@Value("${forum.uploads}") String publicUploads) { this.repository=repository; this.accounts=accounts; this.knowledge=knowledge; directory=Path.of(path).toAbsolutePath().normalize(); if(directory.startsWith(Path.of(publicUploads).toAbsolutePath().normalize()))throw new IllegalArgumentException("Knowledge files must not be stored under public uploads"); }
    public Map<String,Object> list(Authentication auth,String q,String subject,String grade,String textbook,String exam,boolean mine,boolean favorite,int page) {
        knowledge.check(!mine && !favorite || knowledge.user(auth)>0,"请先登录");
        knowledge.check(q.length()<=100,"关键词过长");
        return knowledge.page(repository.search(q,subject,grade,textbook,exam,mine,favorite,knowledge.user(auth),knowledge.offset(page)));
    }
    @Transactional
    public Map<String,Object> upload(Authentication auth,MultipartFile file) throws IOException {
        accounts.writable(auth); knowledge.check(!file.isEmpty() && file.getSize()<=25_000_000,"文件大小须为 1 字节至 25MB");
        String name=Objects.toString(file.getOriginalFilename(),"file").replace((char)92,'_').replaceAll("[\\/\r\n]","_"); knowledge.check(name.length()<=255,"文件名过长");
        String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        knowledge.check(Set.of("jpg","jpeg","png","webp","pdf","txt","doc","docx","xls","xlsx","ppt","pptx","zip").contains(ext),"支持图片、PDF、Office 文档、TXT 和 ZIP");
        byte[] bytes=file.getBytes(); validateFile(ext,bytes);
        String stored=UUID.randomUUID()+"."+ext; Files.createDirectories(directory); Path target=directory.resolve(stored); Files.write(target,bytes);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(target);}catch(IOException ignored){} }
        });
        String mime=switch(ext){case "pdf"->"application/pdf";case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";case "webp"->"image/webp";case "txt"->"text/plain";default->"application/octet-stream";};
        long id=repository.insertUpload(knowledge.user(auth),name,stored,mime,bytes.length);
        return Map.of("id",id,"name",name,"sizeBytes",bytes.length);
    }
    public static void validateFile(String ext,byte[] b) throws IOException {
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
    @Transactional
    public Map<String,Object> create(Authentication auth,Map<String,Object> input) {
        accounts.writable(auth); knowledge.check(Boolean.TRUE.equals(input.get("integrityAccepted")),"请确认版权与学术诚信声明");
        if(!(input.get("files") instanceof List<?> files) || files.isEmpty() || files.size()>10)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择 1 至 10 个文件");
        long points=knowledge.number(input,"downloadPoints",0); knowledge.check(points>=0 && points<=100,"下载积分须为 0 至 100");
        long id=repository.insertResource(knowledge.user(auth),knowledge.text(input,"title",2,160),knowledge.text(input,"description",1,10000),knowledge.text(input,"subject",0,40),knowledge.text(input,"grade",0,40),knowledge.text(input,"textbook",0,80),knowledge.text(input,"exam",0,80),knowledge.text(input,"tags",0,500),knowledge.text(input,"source",1,255),knowledge.text(input,"copyrightNote",1,500),points);
        Set<Long> used=new HashSet<>();
        for(Object value:files){knowledge.check(value instanceof Number || value instanceof String,"文件 ID 无效");long upload=knowledge.number(Map.of("id",value),"id",0); knowledge.check(used.add(upload),"文件重复"); var f=knowledge.required(repository.lockUnusedUpload(upload,knowledge.user(auth)));
            repository.attachFile(id,f); repository.claimUpload(id,upload);
        }
        return Map.of("id",id,"status","pending");
    }
    public Map<String,Object> detail(Authentication auth,long id) {
        var r=knowledge.resource(auth,id); r.put("files",repository.files(id));
        r.put("comments",repository.comments(id));
        r.put("favorited",repository.favoriteBy(id,knowledge.user(auth))!=null); r.put("liked",repository.likeBy(id,knowledge.user(auth))!=null);
        r.put("favorite_count",repository.favoriteCount(id)); r.put("like_count",repository.likeCount(id));return r;
    }
    @Transactional
    public Map<String,Object> download(Authentication auth,long id) {
        accounts.writable(auth); knowledge.resource(auth,id); var r=knowledge.required(repository.lockResource(id)); knowledge.check("published".equals(r.get("status")),"仅已发布资料可以下载");
        var files=repository.downloadFiles(id); knowledge.check(!files.isEmpty(),"资料没有文件");
        long me=knowledge.user(auth),cost=knowledge.n(r,"download_points");
        // Serialize on the resource row: concurrent retries charge once, later downloads remain free.
        if(me!=knowledge.n(r,"uploader_id") && cost>0 && repository.downloadBy(id,me)==null){
            if(repository.chargeDownload(cost,me)!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"积分不足");
            repository.recordDownloadCharge(cost,id,me);
        }
        repository.recordDownload(id,me);repository.incrementDownloads(id);
        return Map.of("files",files);
    }
    public ResponseEntity<FileSystemResource> file(Authentication auth,long id,long fileId) throws IOException {
        var r=knowledge.resource(auth,id); long me=knowledge.user(auth);
        boolean privileged=me>0 && (me==knowledge.n(r,"uploader_id") || knowledge.manager(auth,-1));
        if(!privileged && (!"published".equals(r.get("status")) || me==0 || repository.downloadBy(id,me)==null))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请先点击下载资料");
        var f=knowledge.required(repository.file(fileId,id));
        Path target=directory.resolve(String.valueOf(f.get("file_path"))).normalize(); if(!target.startsWith(directory) || !Files.isRegularFile(target))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文件不存在");
        return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(String.valueOf(f.get("file_name")),java.nio.charset.StandardCharsets.UTF_8).build().toString()).contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(Files.size(target)).body(new FileSystemResource(target));
    }
    @Transactional
    public void toggle(Authentication auth,long id,String action,Map<String,Object> input) {
        accounts.writable(auth);var r=knowledge.resource(auth,id);knowledge.check("published".equals(r.get("status")),"资料尚未发布");repository.toggleInteraction(id,knowledge.user(auth),action.equals("favorite"),Boolean.TRUE.equals(input.get("active")));
    }
    public void comment(Authentication auth,long id,Map<String,Object> input){accounts.writable(auth);knowledge.check("published".equals(knowledge.resource(auth,id).get("status")),"资料尚未发布");repository.addComment(id,knowledge.user(auth),knowledge.text(input,"content",1,1000));}
    public void report(Authentication auth,long id,Map<String,Object> input){accounts.writable(auth);knowledge.resource(auth,id);long count=Objects.requireNonNullElse(repository.dailyReportCount(knowledge.user(auth)),0L);knowledge.check(count<5,"今日举报次数已达上限");repository.report(knowledge.user(auth),id,knowledge.text(input,"reason",1,40),knowledge.text(input,"description",0,500));}
    public List<Map<String,Object>> downloads(Authentication auth){return repository.downloads(accounts.id(auth));}
    public Map<String,Object> admin(Authentication auth,int page){accounts.admin(auth);return knowledge.page(repository.adminResources(knowledge.offset(page)));}
    @Transactional
    public void moderate(Authentication auth,long id,Map<String,Object> input){accounts.admin(auth);knowledge.required(repository.findId(id));String status=knowledge.choice(input,"status","pending","pending","published","rejected");String note=knowledge.text(input,"note",1,500);repository.setStatus(status,id);knowledge.audit(auth,"resource_"+status,"resource",id,note);}
}
