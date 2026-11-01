package com.studentforum;

import com.studentforum.mapper.ForumMapper;
import com.studentforum.service.AccountService;
import com.studentforum.service.ForumService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountServiceTest {
    private final ForumMapper mapper = mock(ForumMapper.class);
    private final ForumService forum = mock(ForumService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AccountService accounts = new AccountService(mapper, forum, encoder);

    @Test void rejectsInvalidRegistrationBeforeWriting() {
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> accounts.register(Map.of("email", "wrong", "username", "小王", "password", "12345678"))).getStatusCode().value());
        verifyNoInteractions(mapper, forum, encoder);
    }

    @Test void loginKeepsTheRoleFromCredentials() {
        Map<String, Object> credentials = Map.of("id", 7L, "role", "moderator", "password_hash", "hash");
        Map<String, Object> profile = Map.of("id", 7L, "username", "user");
        when(mapper.credentials("a@example.test")).thenReturn(credentials);
        when(encoder.matches("secret", "hash")).thenReturn(true);
        when(forum.number(credentials, "id")).thenReturn(7L);
        when(mapper.user(7L)).thenReturn(profile);
        var account = accounts.login(Map.of("email", " A@EXAMPLE.TEST ", "password", "secret"));
        assertEquals(7L, account.id());
        assertEquals("moderator", account.role());
        assertSame(profile, account.profile());
        verify(mapper).login(7L);
    }

    @Test void registrationAndLoginRemainTransactional() throws Exception {
        assertNotNull(AccountService.class.getMethod("register", Map.class).getAnnotation(Transactional.class));
        assertNotNull(AccountService.class.getMethod("login", Map.class).getAnnotation(Transactional.class));
    }
}
