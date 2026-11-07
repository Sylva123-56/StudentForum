package com.studentforum.service;

import com.studentforum.repository.GroupRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupFileServiceTest {
    @TempDir Path directory;
    private final GroupRepository groups = mock(GroupRepository.class);
    private final AccountAccessService accounts = mock(AccountAccessService.class);
    private final Authentication auth = new UsernamePasswordAuthenticationToken("7", "", List.of());
    private GroupFileService files;

    @BeforeEach void setup() {
        files = new GroupFileService(groups, accounts, new GroupAccessService(groups), directory.toString());
        when(accounts.id(auth)).thenReturn(7L);
        when(groups.findGroup(3)).thenReturn(List.of(Map.of("id", 3L, "visibility", "public")));
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "member")));
    }

    @Test void publicFileListKeepsTheOriginalRowsForGuests() {
        var rows = List.<Map<String, Object>>of(Map.of("id", 11L, "name", "study.txt"));
        when(groups.files(3)).thenReturn(rows);
        assertSame(rows, files.files(3, null));
        verify(groups, never()).membership(anyLong(), anyLong());
    }

    @Test void privateFileListRequiresMembershipBeforeReadingRows() {
        when(groups.findGroup(3)).thenReturn(List.of(Map.of("id", 3L, "visibility", "private")));
        when(groups.membership(3, 7)).thenReturn(List.of());
        assertStatus(403, () -> files.files(3, auth));
        assertStatus(403, () -> files.files(3, null));
        verify(groups, never()).files(anyLong());
    }

    @Test void nonMemberCannotRegisterUploadOrDelete() {
        when(groups.membership(3, 7)).thenReturn(List.of());
        assertStatus(403, () -> files.file(auth, 3, Map.of("name", "study", "path", "/file")));
        assertStatus(403, () -> files.uploadFile(auth, 3, upload("study.txt")));
        assertStatus(403, () -> files.deleteFile(auth, 3, 11));
        verify(groups, never()).insertFile(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean());
        verify(groups, never()).findFile(anyLong(), anyLong());
        verify(accounts, never()).writable(any());
        assertFalse(Files.exists(directory.resolve("groups")));
    }

    @Test void registrationKeepsTrimmedFieldsAndDefaultSizeAndDownloadFlag() {
        files.file(auth, 3, Map.of("name", " study ", "path", " /file "));
        verify(groups).insertFile(3, 7, "study", "/file", 0, true);
        verify(groups).log(3, 7, "file", "file", 0, "study");
    }

    @Test void registrationKeepsExplicitSizeAndDisabledDownloadFlag() {
        files.file(auth, 3, Map.of("name", "study", "path", "/file", "sizeBytes", "12", "allowDownload", false));
        verify(groups).insertFile(3, 7, "study", "/file", 12, false);
    }

    @Test void blankMetadataDoesNotWriteOrLog() {
        assertStatus(400, () -> files.file(auth, 3, Map.of("name", " ", "path", "/file")));
        assertStatus(400, () -> files.file(auth, 3, Map.of("name", "study")));
        verify(groups, never()).insertFile(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean());
        verify(groups, never()).log(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test void uploadsStripClientDirectoriesNormalizeJpegAndPreserveBytes() throws Exception {
        for (String filename : List.of("../../photo.JPEG", "C:\\client\\photo.JPEG")) {
            var result = files.uploadFile(auth, 3, upload(filename));
            assertEquals("photo.JPEG", result.get("name"));
            assertEquals(3L, result.get("sizeBytes"));
            String path = (String) result.get("path");
            assertTrue(path.matches("/uploads/groups/3/[0-9a-f-]{36}\\.jpg"));
            Path stored = directory.resolve(path.substring("/uploads/".length()));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(stored));
        }
        verify(accounts, times(2)).writable(auth);
        verify(groups, never()).insertFile(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test void unsupportedExtensionsCannotCreateStorage() {
        for (String filename : List.of("run.exe", "missing", "trailing.", "x".repeat(161) + ".txt")) {
            assertStatus(400, () -> files.uploadFile(auth, 3, upload(filename)));
        }
        assertFalse(Files.exists(directory.resolve("groups")));
    }

    @Test void nullOrEmptyUploadRetainsBadRequest() {
        assertStatus(400, () -> files.uploadFile(auth, 3, null));
        assertStatus(400, () -> files.uploadFile(auth, 3, new MockMultipartFile("file", "study.txt", "text/plain", new byte[0])));
        assertFalse(Files.exists(directory.resolve("groups")));
    }

    @Test void oversizedUploadIsRejectedBeforeTransfer() throws Exception {
        MultipartFile large = mock(MultipartFile.class);
        when(large.getSize()).thenReturn(25_000_001L);
        assertStatus(400, () -> files.uploadFile(auth, 3, large));
        verify(large, never()).transferTo(any(Path.class));
        assertFalse(Files.exists(directory.resolve("groups")));
    }

    @Test void exactUploadLimitAndUppercasePdfRemainAccepted() throws Exception {
        MultipartFile limit = mock(MultipartFile.class);
        when(limit.getSize()).thenReturn(25_000_000L);
        when(limit.getOriginalFilename()).thenReturn("study.PDF");
        var result = files.uploadFile(auth, 3, limit);
        assertEquals(25_000_000L, result.get("sizeBytes"));
        assertTrue(((String) result.get("path")).endsWith(".pdf"));
        verify(limit).transferTo(any(Path.class));
    }

    @Test void restrictedAccountsCannotTransferFiles() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "denied")).when(accounts).writable(auth);
        assertStatus(403, () -> files.uploadFile(auth, 3, upload("study.txt")));
        assertFalse(Files.exists(directory.resolve("groups")));
    }

    @Test void uploaderCanDeleteTheirFileAndTheOriginalAuditIsKept() throws Exception {
        String path = storedFile("groups/3/");
        fileRow(7, path);
        files.deleteFile(auth, 3, 11);
        assertFalse(Files.exists(storedPath(path)));
        verify(groups).findFile(11, 3);
        verify(groups).deleteFile(11);
        verify(groups).log(3, 7, "file_delete", "file", 11, "study");
    }

    @Test void bothGroupManagerRolesCanDeleteAnotherMembersFile() throws Exception {
        for (String role : List.of("owner", "admin")) {
            String path = storedFile("groups/3/");
            fileRow(8, path);
            when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", role)));
            files.deleteFile(auth, 3, 11);
            assertFalse(Files.exists(storedPath(path)));
        }
        verify(groups, times(2)).deleteFile(11);
    }

    @Test void ordinaryMemberCannotDeleteAnotherMembersFile() throws Exception {
        String path = storedFile("groups/3/");
        fileRow(8, path);
        assertStatus(403, () -> files.deleteFile(auth, 3, 11));
        assertTrue(Files.exists(storedPath(path)));
        verify(groups, never()).deleteFile(anyLong());
        verify(groups, never()).log(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test void missingOrForeignGroupFileRetainsNotFound() {
        assertStatus(404, () -> files.deleteFile(auth, 3, 11));
        verify(groups).findFile(11, 3);
        verify(groups, never()).deleteFile(anyLong());
    }

    @Test void legacyUuidUploadsAreStillRemoved() throws Exception {
        String path = storedFile("");
        fileRow(7, path);
        files.deleteFile(auth, 3, 11);
        assertFalse(Files.exists(storedPath(path)));
    }

    @Test void unrecognizedPathsNeverDeleteFiles() throws Exception {
        Path keep = directory.resolve("keep.txt");
        Files.writeString(keep, "keep");
        for (String path : List.of("/uploads/groups/3/../../keep.txt", "/uploads/keep.txt", keep.toString(), "https://example.test/keep.txt")) {
            fileRow(7, path);
            files.deleteFile(auth, 3, 11);
            assertTrue(Files.exists(keep));
        }
    }

    @Test void missingStorageStillDeletesMetadataAndLogs() {
        fileRow(7, "/uploads/groups/3/" + UUID.randomUUID() + ".txt");
        files.deleteFile(auth, 3, 11);
        verify(groups).deleteFile(11);
        verify(groups).log(3, 7, "file_delete", "file", 11, "study");
    }

    @Test void storageIoFailureRetainsTheExistingBestEffortCleanup() throws Exception {
        String path = "/uploads/groups/3/" + UUID.randomUUID() + ".txt";
        Path nonEmptyDirectory = storedPath(path);
        Files.createDirectories(nonEmptyDirectory);
        Files.writeString(nonEmptyDirectory.resolve("keep.txt"), "keep");
        fileRow(7, path);
        files.deleteFile(auth, 3, 11);
        assertTrue(Files.exists(nonEmptyDirectory));
        verify(groups).deleteFile(11);
        verify(groups).log(3, 7, "file_delete", "file", 11, "study");
    }

    private MockMultipartFile upload(String name) {
        return new MockMultipartFile("file", name, "application/octet-stream", new byte[]{1, 2, 3});
    }

    private String storedFile(String folder) throws IOException {
        String path = "/uploads/" + folder + UUID.randomUUID() + ".txt";
        Path stored = storedPath(path);
        Files.createDirectories(stored.getParent());
        Files.writeString(stored, "study");
        return path;
    }

    private Path storedPath(String path) {
        return directory.resolve(path.substring("/uploads/".length()));
    }

    private void fileRow(long uploader, String path) {
        when(groups.findFile(11, 3)).thenReturn(List.of(Map.of("uploader_id", uploader, "path", path, "name", "study")));
    }

    private void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }
}
