package com.studentforum;

import com.studentforum.repository.GroupAdminRepository;
import com.studentforum.service.AccountAccessService;
import com.studentforum.service.GroupAdminService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupAdminServiceTest {
    @Test void invalidStatusDoesNotUpdateReports() {
        GroupAdminRepository repository = mock(GroupAdminRepository.class);
        AccountAccessService accounts = mock(AccountAccessService.class);
        var auth = new UsernamePasswordAuthenticationToken("1", null);
        var admin = new GroupAdminService(repository, accounts);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> admin.handleReport(auth, 9L, Map.of("status", "other"))).getStatusCode().value());
        verifyNoInteractions(repository);
    }

    @Test void missingGroupCannotBeChanged() {
        GroupAdminRepository repository = mock(GroupAdminRepository.class);
        AccountAccessService accounts = mock(AccountAccessService.class);
        var auth = new UsernamePasswordAuthenticationToken("1", null);
        var admin = new GroupAdminService(repository, accounts);
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> admin.update(auth, 4L, Map.of("status", "disabled"))).getStatusCode().value());
        verify(repository).groupExists(4L);
        verify(repository, never()).updateStatus(4L, "disabled");
    }
    @Test void deniedAccountNeverReachesGroupPersistence() {
        var repository=mock(GroupAdminRepository.class);
        var accounts=mock(AccountAccessService.class);
        var auth=new UsernamePasswordAuthenticationToken("7",null);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(accounts).admin(auth);
        assertEquals(403,assertThrows(ResponseStatusException.class,
                ()->new GroupAdminService(repository,accounts).groups(auth,"学习")).getStatusCode().value());
        verifyNoInteractions(repository);
    }
}
