package com.studentforum;

import com.studentforum.repository.StudyRoomRepository;
import com.studentforum.service.StudyRoomAccessService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudyRoomAccessServiceTest {
    private final StudyRoomRepository repository = mock(StudyRoomRepository.class);
    private final StudyRoomAccessService rooms = new StudyRoomAccessService(repository);

    @Test void privateRoomRequiresOwnerOrMembership() {
        Map<String, Object> privateRoom = Map.of("id", 9L, "owner_id", 7L, "visibility", "private");
        assertDoesNotThrow(() -> rooms.requireRoomView(privateRoom, 7L));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> rooms.requireRoomView(privateRoom, null)).getStatusCode().value());
        when(repository.isMember(9, 8)).thenReturn(true);
        assertDoesNotThrow(() -> rooms.requireRoomView(privateRoom, 8L));
        verify(repository).isMember(9, 8);
    }

    @Test void onlyOwnerCanChangeRoomAndUnknownRoomReturnsNotFound() {
        when(repository.details(99)).thenReturn(null);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> rooms.room(99)).getStatusCode().value());
        Map<String, Object> privateRoom = Map.of("id", 9L, "owner_id", 7L, "visibility", "private");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> rooms.requireRoomOwner(privateRoom, 8)).getStatusCode().value());
    }
}
