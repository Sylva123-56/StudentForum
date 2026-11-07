package com.studentforum.service;

import com.studentforum.repository.GroupRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupFileService {
    private final GroupRepository groups;
    private final AccountAccessService accounts;
    private final GroupAccessService access;
    private final Path directory;

    public GroupFileService(GroupRepository groups, AccountAccessService accounts, GroupAccessService access,
                            @Value("${forum.uploads}") String uploadPath) {
        this.groups = groups;
        this.accounts = accounts;
        this.access = access;
        this.directory = Path.of(uploadPath).toAbsolutePath().normalize();
    }

    public List<Map<String, Object>> files(long groupId, Authentication auth) {
        access.requireView(access.group(groupId), auth);
        return groups.files(groupId);
    }

    public void file(Authentication auth, long groupId, Map<String, Object> input) {
        long user = accounts.id(auth);
        access.requireMember(groupId, user);
        String name = text(input, "name"), path = text(input, "path");
        if (name.isBlank() || path.isBlank()) bad("文件名和路径不能为空");
        groups.insertFile(groupId, user, name, path, numberOr(input, "sizeBytes", 0), !Boolean.FALSE.equals(input.get("allowDownload")));
        log(groupId, user, "file", "file", 0, name);
    }

    public Map<String, Object> uploadFile(Authentication auth, long groupId, MultipartFile file) throws IOException {
        long user = accounts.id(auth);
        access.requireMember(groupId, user);
        accounts.writable(auth);
        if (file == null || file.isEmpty()) bad("请选择要上传的文件");
        if (file.getSize() > 25_000_000) bad("资料不能超过 25MB");
        String original = originalName(file);
        String extension = extensionOf(original);
        if (extension == null) bad("仅支持 PDF、Word、Excel、PPT、TXT、Markdown、压缩包和图片文件");
        Path folder = directory.resolve("groups").resolve(String.valueOf(groupId));
        Files.createDirectories(folder);
        String stored = UUID.randomUUID() + "." + extension;
        file.transferTo(folder.resolve(stored));
        return Map.of("path", "/uploads/groups/" + groupId + "/" + stored, "name", original, "sizeBytes", file.getSize());
    }

    public void deleteFile(Authentication auth, long groupId, long fileId) {
        long user = accounts.id(auth);
        access.requireMember(groupId, user);
        List<Map<String, Object>> rows = groups.findFile(fileId, groupId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资料不存在");
        Map<String, Object> row = rows.getFirst();
        if (rowNumber(row, "uploader_id") != user) access.requireManager(groupId, user);
        groups.deleteFile(fileId);
        removeStoredFile(String.valueOf(row.get("path")));
        log(groupId, user, "file_delete", "file", fileId, String.valueOf(row.get("name")));
    }

    private String originalName(MultipartFile file) {
        String name = String.valueOf(file.getOriginalFilename());
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = (slash < 0 ? name : name.substring(slash + 1)).trim();
        if (name.isEmpty() || name.length() > 160) name = "小组资料";
        return name;
    }

    private String extensionOf(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return null;
        String extension = lower.substring(dot + 1);
        if ("jpeg".equals(extension)) return "jpg";
        return Set.of("pdf", "txt", "md", "csv", "zip", "rar", "7z", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "jpg", "png", "webp", "gif").contains(extension) ? extension : null;
    }

    private void removeStoredFile(String path) {
        if (path == null || !path.matches("/uploads/(groups/[0-9]+/)?[0-9a-fA-F-]{36}\\.[A-Za-z0-9]{1,5}")) return;
        try {
            Files.deleteIfExists(directory.resolve(path.substring("/uploads/".length())));
        } catch (IOException ignored) {
        }
    }

    private String text(Map<String, Object> input, String key) {
        return input == null || input.get(key) == null ? "" : String.valueOf(input.get(key)).trim();
    }

    private long numberOr(Map<String, Object> input, String key, long fallback) {
        Object v = input.get(key);
        return v == null ? fallback : Long.parseLong(String.valueOf(v));
    }

    private long rowNumber(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private void log(long groupId, long actor, String action, String type, long target, String detail) {
        groups.log(groupId, actor, action, type, target, detail == null ? "" : detail);
    }

    private void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
