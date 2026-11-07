package com.studentforum.service;

import com.studentforum.repository.GroupRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupAccessServiceTest {
    private final GroupRepository groups = mock(GroupRepository.class);
    private final GroupAccessService access = new GroupAccessService(groups);
    private final Authentication auth = new UsernamePasswordAuthenticationToken("7", "", List.of());
    private final Map<String, Object> privateGroup = Map.of("id", 3L, "visibility", "private");

    @Test void missingGroupRetainsNotFound() {
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> access.group(3)).getStatusCode().value());
    }

    @Test void existingGroupIsReturnedUnchanged() {
        when(groups.findGroup(3)).thenReturn(List.of(privateGroup));
        assertSame(privateGroup, access.group(3));
    }

    @Test void publicGroupNeedsNoMembershipLookup() {
        access.requireView(Map.of("id", 3L, "visibility", "public"), null);
        verifyNoInteractions(groups);
    }

    @Test void privateGroupRejectsGuestsWithoutLookingUpMembership() {
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        var unsigned = new UsernamePasswordAuthenticationToken("7", "");
        for (Authentication guest : new Authentication[]{null, anonymous, unsigned}) {
            assertNull(access.userOrNull(guest));
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.requireView(privateGroup, guest)).getStatusCode().value());
        }
        verifyNoInteractions(groups);
    }

    @Test void privateGroupRejectsSignedInOutsiders() {
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.requireView(privateGroup, auth)).getStatusCode().value());
        verify(groups).membership(3, 7);
    }

    @Test void privateGroupAllowsItsOwnMember() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "member")));
        access.requireView(privateGroup, auth);
        assertEquals(7L, access.userOrNull(auth));
    }

    @Test void absentMembershipRetainsForbidden() {
        assertNull(access.member(3, 7));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.requireMember(3, 7)).getStatusCode().value());
    }

    @Test void ownerAndGroupAdminCanManage() {
        for (String role : List.of("owner", "admin")) {
            var member = Map.<String, Object>of("role", role);
            when(groups.membership(3, 7)).thenReturn(List.of(member));
            assertSame(member, access.requireManager(3, 7));
            assertSame(member, access.requireMember(3, 7));
        }
    }

    @Test void regularMemberCannotManage() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "member")));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.requireManager(3, 7)).getStatusCode().value());
    }
}
