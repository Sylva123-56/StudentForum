package com.studentforum.service;

import com.studentforum.repository.GroupRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupMembershipServiceTest {
    private final GroupRepository groups = mock(GroupRepository.class);
    private final AccountAccessService accounts = mock(AccountAccessService.class);
    private final GroupMembershipService membership = new GroupMembershipService(groups, accounts, new GroupAccessService(groups));
    private final Authentication auth = new UsernamePasswordAuthenticationToken("7", "", List.of());

    @BeforeEach void setup() {
        when(accounts.id(auth)).thenReturn(7L);
        when(groups.findGroup(3)).thenReturn(List.of(Map.of("id", 3L, "name", "Study", "owner_id", 7L, "visibility", "public", "join_mode", "free", "status", "active")));
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "member")));
    }

    @Test void ordinaryMemberCannotPromoteThemselfOrOthers() {
        assertStatus(403, () -> membership.memberRole(auth, 3, 7, Map.of("role", "admin")));
        assertStatus(403, () -> membership.memberRole(auth, 3, 8, Map.of("role", "admin")));
        verify(groups, never()).setMemberRole(anyString(), anyLong(), anyLong());
        verify(groups, never()).log(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test void groupAdminCannotDemoteTheOwner() {
        when(groups.findGroup(3)).thenReturn(List.of(Map.of("id", 3L, "owner_id", 8L)));
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "admin")));
        when(groups.membership(3, 8)).thenReturn(List.of(Map.of("role", "owner")));
        assertStatus(400, () -> membership.memberRole(auth, 3, 8, Map.of("role", "member")));
        verify(groups, never()).setMemberRole(anyString(), anyLong(), anyLong());
    }

    @Test void ownershipCannotBeTransferredToANonMember() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        assertStatus(403, () -> membership.memberRole(auth, 3, 8, Map.of("role", "owner")));
        verify(groups, never()).demoteOwner(anyLong());
        verify(groups, never()).transferOwner(anyLong(), anyLong());
        verify(groups, never()).setMemberRole(anyString(), anyLong(), anyLong());
    }

    @Test void freeJoinAddsMemberRefreshesCountAndKeepsTheResponse() {
        var member = Map.<String, Object>of("role", "member", "user_id", 7L);
        when(groups.membership(3, 7)).thenReturn(List.of(), List.of(member));
        assertSame(member, membership.join(auth, 3));
        var order = inOrder(groups);
        order.verify(groups).addMember(3, 7, "member");
        order.verify(groups).refreshMemberCount(3);
        order.verify(groups).log(3, 7, "join", "member", 7, "自由加入");
    }

    @Test void existingMemberJoinIsIdempotent() {
        assertEquals("member", membership.join(auth, 3).get("role"));
        verify(groups, never()).addMember(anyLong(), anyLong(), anyString());
        verify(groups, never()).refreshMemberCount(anyLong());
        verify(groups, never()).log(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test void disabledGroupCannotBeJoinedEvenByAnExistingMember() {
        when(groups.findGroup(3)).thenReturn(List.of(Map.of("status", "disabled")));
        assertStatus(400, () -> membership.join(auth, 3));
        verify(groups, never()).addMember(anyLong(), anyLong(), anyString());
    }

    @Test void privateApplyAndInviteGroupsCannotBeFreelyJoined() {
        when(groups.membership(3, 7)).thenReturn(List.of());
        for (var group : List.of(Map.of("visibility", "private", "join_mode", "free", "status", "active"), Map.of("visibility", "public", "join_mode", "apply", "status", "active"), Map.of("visibility", "public", "join_mode", "invite", "status", "active"))) {
            when(groups.findGroup(3)).thenReturn(List.of(Map.copyOf(group)));
            assertStatus(403, () -> membership.join(auth, 3));
        }
        verify(groups, never()).addMember(anyLong(), anyLong(), anyString());
    }

    @Test void missingGroupRetainsNotFoundBeforeJoinWrites() {
        when(groups.findGroup(3)).thenReturn(List.of());
        assertStatus(404, () -> membership.join(auth, 3));
        verify(groups, never()).addMember(anyLong(), anyLong(), anyString());
    }

    @Test void newApplicationTrimsMessageNotifiesManagersAndAudits() {
        when(groups.membership(3, 7)).thenReturn(List.of());
        assertEquals(Map.of("status", "pending"), membership.apply(auth, 3, Map.of("message", "  please join  ")));
        verify(groups).createRequest(3, 7, "please join");
        verify(groups).notifyManagers("有新的小组加入申请，请及时审核", 3);
        verify(groups).log(3, 7, "apply", "member", 7, "please join");
    }

    @Test void reapplicationReusesTheLatestRowAndAcceptsAnOptionalBody() {
        when(groups.membership(3, 7)).thenReturn(List.of());
        when(groups.latestRequest(3, 7)).thenReturn(List.of(Map.of("id", 42L)));
        membership.apply(auth, 3, null);
        var order = inOrder(groups);
        order.verify(groups).removeOlderRequests(3, 7, 42);
        order.verify(groups).reopenRequest("", 42);
        verify(groups, never()).createRequest(anyLong(), anyLong(), anyString());
    }

    @Test void membersInviteOnlyAndPendingApplicationsAreRejectedWithoutWrites() {
        assertStatus(400, () -> membership.apply(auth, 3, null));
        when(groups.membership(3, 7)).thenReturn(List.of());
        when(groups.findGroup(3)).thenReturn(List.of(Map.of("join_mode", "invite")));
        assertStatus(403, () -> membership.apply(auth, 3, null));
        when(groups.findGroup(3)).thenReturn(List.of(Map.of("join_mode", "apply")));
        when(groups.pendingRequest(3, 7)).thenReturn(List.of(Map.of("id", 42L)));
        assertStatus(400, () -> membership.apply(auth, 3, null));
        verify(groups, never()).createRequest(anyLong(), anyLong(), anyString());
        verify(groups, never()).reopenRequest(anyString(), anyLong());
        verify(groups, never()).notifyManagers(anyString(), anyLong());
    }

    @Test void managerInvitationKeepsMemberCountsNotificationAndAudit() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "admin")));
        membership.invite(auth, 3, Map.of("userId", "8"));
        verify(groups).addMember(3, 8, "member");
        verify(groups).refreshMemberCount(3);
        verify(groups).notifyUser(8, "group", "你已被邀请加入小组：Study");
        verify(groups).log(3, 7, "invite", "member", 8, "邀请成员");
    }

    @Test void invalidInvitationsDoNotAddMembers() {
        assertStatus(403, () -> membership.invite(auth, 3, Map.of("userId", 8)));
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        assertStatus(400, () -> membership.invite(auth, 3, Map.of()));
        when(groups.membership(3, 8)).thenReturn(List.of(Map.of("role", "member")));
        assertStatus(400, () -> membership.invite(auth, 3, Map.of("userId", 8)));
        verify(groups, never()).addMember(anyLong(), anyLong(), anyString());
    }

    @Test void leavingRemovesOnlyTheCurrentMemberAndKeepsTheCountAndAudit() {
        membership.leave(auth, 3);
        verify(groups).removeMember(3, 7);
        verify(groups).decrementMemberCount(3);
        verify(groups).log(3, 7, "leave", "member", 7, "退出小组");
    }

    @Test void absentMembersAndOwnersCannotLeave() {
        when(groups.membership(3, 7)).thenReturn(List.of());
        assertStatus(400, () -> membership.leave(auth, 3));
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        assertStatus(400, () -> membership.leave(auth, 3));
        verify(groups, never()).removeMember(anyLong(), anyLong());
        verify(groups, never()).decrementMemberCount(anyLong());
    }

    @Test void memberListKeepsPublicAccessAndPrivateMembershipChecks() {
        var rows = List.<Map<String, Object>>of(Map.of("user_id", 7L, "role", "member"));
        when(groups.members(3)).thenReturn(rows);
        assertSame(rows, membership.memberList(3, null));
        when(groups.findGroup(3)).thenReturn(List.of(Map.of("id", 3L, "visibility", "private")));
        assertSame(rows, membership.memberList(3, auth));
        when(groups.membership(3, 7)).thenReturn(List.of());
        assertStatus(403, () -> membership.memberList(3, auth));
        assertStatus(403, () -> membership.memberList(3, null));
        verify(groups, times(2)).members(3);
    }

    @Test void managersMayChangeAnExistingNonOwnerRole() {
        when(groups.membership(3, 8)).thenReturn(List.of(Map.of("role", "member")));
        for (String actorRole : List.of("owner", "admin")) {
            when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", actorRole)));
            membership.memberRole(auth, 3, 8, Map.of("role", " admin "));
        }
        verify(groups, times(2)).setMemberRole("admin", 3, 8);
        verify(groups, times(2)).log(3, 7, "role", "member", 8, "设置角色为admin");
        verify(groups, never()).demoteOwner(anyLong());
    }

    @Test void onlyTheOwnerCanTransferOwnershipAndNotDemoteThemself() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "admin")));
        assertStatus(403, () -> membership.memberRole(auth, 3, 8, Map.of("role", "owner")));
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        assertStatus(400, () -> membership.memberRole(auth, 3, 7, Map.of("role", "admin")));
        verify(groups, never()).setMemberRole(anyString(), anyLong(), anyLong());
    }

    @Test void successfulTransferKeepsFormerOwnerDemotionAndAuditOrdering() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        when(groups.membership(3, 8)).thenReturn(List.of(Map.of("role", "member")));
        membership.memberRole(auth, 3, 8, Map.of("role", "owner"));
        var order = inOrder(groups);
        order.verify(groups).demoteOwner(3);
        order.verify(groups).transferOwner(8, 3);
        order.verify(groups).setMemberRole("owner", 3, 8);
        order.verify(groups).log(3, 7, "role", "member", 8, "设置角色为owner");
    }

    @Test void invalidRolesAndNonMemberTargetsNeverWrite() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        assertStatus(400, () -> membership.memberRole(auth, 3, 8, Map.of("role", "invalid")));
        assertStatus(403, () -> membership.memberRole(auth, 3, 8, Map.of("role", "admin")));
        verify(groups, never()).setMemberRole(anyString(), anyLong(), anyLong());
    }

    @Test void managerRemovalKeepsTheScopedMemberCountAndAudit() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "admin")));
        when(groups.membership(3, 8)).thenReturn(List.of(Map.of("role", "member")));
        membership.removeMember(auth, 3, 8);
        verify(groups).removeMember(3, 8);
        verify(groups).decrementMemberCount(3);
        verify(groups).log(3, 7, "remove", "member", 8, "移除成员");
    }

    @Test void removalRejectsNonManagersAbsentTargetsAndOwners() {
        assertStatus(403, () -> membership.removeMember(auth, 3, 8));
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "admin")));
        assertStatus(403, () -> membership.removeMember(auth, 3, 8));
        when(groups.membership(3, 8)).thenReturn(List.of(Map.of("role", "owner")));
        assertStatus(400, () -> membership.removeMember(auth, 3, 8));
        verify(groups, never()).removeMember(anyLong(), anyLong());
        verify(groups, never()).decrementMemberCount(anyLong());
    }

    @Test void onlyManagersCanReadAndReviewApplications() {
        assertStatus(403, () -> membership.requests(auth, 3));
        assertStatus(403, () -> membership.reviewRequest(auth, 3, 42, Map.of("status", "approved")));
        verify(groups, never()).joinRequests(anyLong());
        verify(groups, never()).reviewableRequest(anyLong(), anyLong());
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "admin")));
        var rows = List.<Map<String, Object>>of(Map.of("id", 42L));
        when(groups.joinRequests(3)).thenReturn(rows);
        assertSame(rows, membership.requests(auth, 3));
    }

    @Test void reviewRejectsInvalidStatusesAndNonPendingOrForeignRequests() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        assertStatus(400, () -> membership.reviewRequest(auth, 3, 42, Map.of("status", "pending")));
        verify(groups, never()).reviewableRequest(anyLong(), anyLong());
        assertStatus(400, () -> membership.reviewRequest(auth, 3, 42, Map.of("status", "approved")));
        verify(groups).reviewableRequest(42, 3);
        verify(groups, never()).reviewRequest(anyString(), anyLong(), anyLong());
        verify(groups, never()).removeOlderRequests(anyLong(), anyLong(), anyLong());
    }

    @Test void approvalKeepsHistoryCleanupMembershipCountsNotificationAndAudit() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        when(groups.reviewableRequest(42, 3)).thenReturn(List.of(Map.of("user_id", 8L)));
        membership.reviewRequest(auth, 3, 42, Map.of("status", " approved "));
        var order = inOrder(groups);
        order.verify(groups).removeOlderRequests(3, 8, 42);
        order.verify(groups).reviewRequest("approved", 7, 42);
        order.verify(groups).addMember(3, 8, "member");
        order.verify(groups).refreshMemberCount(3);
        order.verify(groups).notifyUser(8, "group", "小组加入申请已通过");
        order.verify(groups).log(3, 7, "review", "join_request", 42, "approved");
    }

    @Test void approvalDoesNotDuplicateAnExistingMembership() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        when(groups.reviewableRequest(42, 3)).thenReturn(List.of(Map.of("user_id", 8L)));
        when(groups.membership(3, 8)).thenReturn(List.of(Map.of("role", "member")));
        membership.reviewRequest(auth, 3, 42, Map.of("status", "approved"));
        verify(groups, never()).addMember(anyLong(), anyLong(), anyString());
        verify(groups, never()).refreshMemberCount(anyLong());
        verify(groups).notifyUser(8, "group", "小组加入申请已通过");
    }

    @Test void rejectionDoesNotAddOrRecountMembers() {
        when(groups.membership(3, 7)).thenReturn(List.of(Map.of("role", "owner")));
        when(groups.reviewableRequest(42, 3)).thenReturn(List.of(Map.of("user_id", 8L)));
        membership.reviewRequest(auth, 3, 42, Map.of("status", "rejected"));
        verify(groups).removeOlderRequests(3, 8, 42);
        verify(groups).reviewRequest("rejected", 7, 42);
        verify(groups).notifyUser(8, "group", "小组加入申请已拒绝");
        verify(groups).log(3, 7, "review", "join_request", 42, "rejected");
        verify(groups, never()).addMember(anyLong(), anyLong(), anyString());
        verify(groups, never()).refreshMemberCount(anyLong());
    }

    private void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }
}
