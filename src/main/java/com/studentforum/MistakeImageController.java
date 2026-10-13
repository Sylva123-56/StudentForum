package com.studentforum;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Images are private storage, not static /uploads URLs; a shared image follows its mistake ACL. */
@RestController
@RequestMapping("/api/mistakes")
class MistakeImageController {
    private final KnowledgeService k;
    private final Path directory;
    MistakeImageController(KnowledgeService k,@Value("${forum.knowledge-files:knowledge-files}") String path){this.k=k;directory=Path.of(path).toAbsolutePath().normalize().resolve("mistakes");}
    @PostMapping("/images") @Transactional
    Map<String,Object> upload(Authentication auth,@RequestParam MultipartFile file) throws IOException {
        k.forum.writable(auth);k.check(!file.isEmpty() && file.getSize()<=5_000_000,"图片须小于 5MB");byte[] bytes=file.getBytes();String ext=null;
        for(String candidate:List.of("png","jpg","webp")){try{ResourceController.validateFile(candidate,bytes);ext=candidate;break;}catch(ResponseStatusException ignored){}}
        k.check(ext!=null,"仅支持 JPG、PNG、WebP 图片");String stored=UUID.randomUUID()+"."+ext;String mime="image/"+("jpg".equals(ext)?"jpeg":ext);Files.createDirectories(directory);Path target=directory.resolve(stored);Files.write(target,bytes);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(target);}catch(IOException ignored){}}});
        long id=k.insert("INSERT INTO mistake_images(user_id,stored_name,mime_type) VALUES(?,?,?)",k.user(auth),stored,mime);return Map.of("path","/api/mistakes/images/"+id);
    }
    @GetMapping("/images/{imageId}")
    ResponseEntity<FileSystemResource> preview(Authentication auth,@PathVariable long imageId) throws IOException {
        var image=k.required("SELECT * FROM mistake_images WHERE id=? AND user_id=?",imageId,k.forum.id(auth));return bytes(image);
    }
    @GetMapping("/{id}/image")
    ResponseEntity<FileSystemResource> image(Authentication auth,@PathVariable long id) throws IOException {
        var mistake=k.mistake(auth,id);String path=Objects.toString(mistake.get("image_path"),"");if(!path.matches("/api/mistakes/images/[0-9]+"))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"图片不存在");long image=Long.parseLong(path.substring(path.lastIndexOf('/')+1));return bytes(k.required("SELECT * FROM mistake_images WHERE id=? AND user_id=?",image,k.n(mistake,"user_id")));
    }
    private ResponseEntity<FileSystemResource> bytes(Map<String,Object> image) throws IOException {
        Path target=directory.resolve(String.valueOf(image.get("stored_name"))).normalize();if(!target.startsWith(directory) || !Files.isRegularFile(target))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"图片不存在");
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(String.valueOf(image.get("mime_type")))).contentLength(Files.size(target)).body(new FileSystemResource(target));
    }
}
