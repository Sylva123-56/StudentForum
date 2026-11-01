package com.studentforum.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Images are private storage, not static /uploads URLs; a shared image follows its mistake ACL. */
@Service
public class MistakeImageService {
    private final KnowledgeService k;
    private final Path directory;
    public MistakeImageService(KnowledgeService k,@Value("${forum.knowledge-files:knowledge-files}") String path){this.k=k;directory=Path.of(path).toAbsolutePath().normalize().resolve("mistakes");}
    @Transactional
    public Map<String,Object> upload(Authentication auth,MultipartFile file) throws IOException {
        k.forum.writable(auth);k.check(!file.isEmpty() && file.getSize()<=5_000_000,"图片须小于 5MB");byte[] bytes=file.getBytes();String ext=null;
        for(String candidate:List.of("png","jpg","webp")){try{ResourceService.validateFile(candidate,bytes);ext=candidate;break;}catch(ResponseStatusException ignored){}}
        k.check(ext!=null,"仅支持 JPG、PNG、WebP 图片");String stored=UUID.randomUUID()+"."+ext;String mime="image/"+("jpg".equals(ext)?"jpeg":ext);Files.createDirectories(directory);Path target=directory.resolve(stored);Files.write(target,bytes);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(target);}catch(IOException ignored){}}});
        long id=k.insert("INSERT INTO mistake_images(user_id,stored_name,mime_type) VALUES(?,?,?)",k.user(auth),stored,mime);return Map.of("path","/api/mistakes/images/"+id);
    }
    public ResponseEntity<FileSystemResource> preview(Authentication auth,long imageId) throws IOException {
        var image=k.required("SELECT * FROM mistake_images WHERE id=? AND user_id=?",imageId,k.forum.id(auth));return bytes(image);
    }
    public ResponseEntity<FileSystemResource> image(Authentication auth,long id) throws IOException {
        var mistake=k.mistake(auth,id);String path=Objects.toString(mistake.get("image_path"),"");if(!path.matches("/api/mistakes/images/[0-9]+"))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"图片不存在");long image=Long.parseLong(path.substring(path.lastIndexOf('/')+1));return bytes(k.required("SELECT * FROM mistake_images WHERE id=? AND user_id=?",image,k.n(mistake,"user_id")));
    }
    private ResponseEntity<FileSystemResource> bytes(Map<String,Object> image) throws IOException {
        Path target=directory.resolve(String.valueOf(image.get("stored_name"))).normalize();if(!target.startsWith(directory) || !Files.isRegularFile(target))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"图片不存在");
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(String.valueOf(image.get("mime_type")))).contentLength(Files.size(target)).body(new FileSystemResource(target));
    }
}
