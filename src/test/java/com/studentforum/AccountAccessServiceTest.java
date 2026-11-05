package com.studentforum;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.service.AccountAccessService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountAccessServiceTest {
    private final ForumMapper mapper = mock(ForumMapper.class);
    private final AccountAccessService accounts = new AccountAccessService(mapper);
    private final Authentication auth = new UsernamePasswordAuthenticationToken("7", null, List.of());

    @Test void anonymousIdentitiesNeverReachAccountStorage() {
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> accounts.id(null)).getStatusCode().value());
        var guest = new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> accounts.id(guest)).getStatusCode().value());
        verifyNoInteractions(mapper);
    }

    @Test void inactiveStaffCannotUseRolePrivileges() {
        when(mapper.user(7)).thenReturn(Map.of("role", "admin", "status", "banned"));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> accounts.admin(auth)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> accounts.staff(auth)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> accounts.moderate(auth, 2)).getStatusCode().value());
    }

    @Test void moderatorsStayWithinTheirAssignedBoard() {
        when(mapper.user(7)).thenReturn(Map.of("role", "moderator", "status", "active", "moderator_board_id", 2L));
        assertDoesNotThrow(() -> accounts.moderate(auth, 2));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> accounts.moderate(auth, 3)).getStatusCode().value());
    }

    @Test void publishingUsesOneAccountSnapshotAndKeepsReputationThreshold() {
        when(mapper.user(7)).thenReturn(Map.of("status", "active", "reputation", 40));
        assertDoesNotThrow(() -> accounts.writable(auth));
        verify(mapper, times(1)).user(7);
        when(mapper.user(7)).thenReturn(Map.of("status", "active", "reputation", 39));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> accounts.writable(auth)).getStatusCode().value());
    }
}
