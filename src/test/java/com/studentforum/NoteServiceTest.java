package com.studentforum;

import com.studentforum.repository.NoteRepository;
import com.studentforum.service.KnowledgeService;
import com.studentforum.service.NoteService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NoteServiceTest {
    @Test void missingLockedNoteCannotBeEdited() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        NoteRepository repository = mock(NoteRepository.class);
        var auth = new UsernamePasswordAuthenticationToken("7", null);
        when(knowledge.note(auth, 42L)).thenReturn(Map.of("id", 42L, "user_id", 7L));
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> new NoteService(knowledge, repository, mock(com.studentforum.service.AccountAccessService.class)).edit(auth, 42L, Map.of("title", "变化")))
                .getStatusCode().value());
        verify(repository).lock(42L);
        verify(repository, never()).update(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
