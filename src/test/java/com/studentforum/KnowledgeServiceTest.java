package com.studentforum;

import com.studentforum.controller.ResourceController;
import com.studentforum.controller.WikiController;
import com.studentforum.service.WikiService;
import com.studentforum.mapper.ForumMapper;
import com.studentforum.service.KnowledgeService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class KnowledgeServiceTest {
    JdbcTemplate db; ForumMapper mapper; KnowledgeService k; Authentication auth;
    @BeforeEach void setup(){db=mock(JdbcTemplate.class);mapper=mock(ForumMapper.class);k=new KnowledgeService(new com.studentforum.repository.KnowledgeRepository(db),new com.studentforum.service.AccountAccessService(mapper));auth=new UsernamePasswordAuthenticationToken("7",null,List.of());when(mapper.user(7L)).thenReturn(Map.of("id",7L,"status","active","reputation",100,"role","student"));}
    void row(String match,Map<String,Object> row){when(db.queryForList(argThat(sql->sql!=null&&sql.contains(match)),any(Object[].class))).thenReturn(List.of(row));}
    @Test void anonymousUserIsNotAuthenticated(){assertEquals(0,k.user(null));assertEquals(0,k.user(new UsernamePasswordAuthenticationToken("anonymousUser",null,List.of())));}
    @Test void privateMistakeIsInvisibleToNonOwner(){row("FROM mistakes",Map.of("id",3L,"user_id",8L,"visibility","private"));assertEquals(404,assertThrows(ResponseStatusException.class,()->k.mistake(auth,3)).getStatusCode().value());verify(db,never()).update(anyString(),any(Object[].class));}
    @Test void groupVisibilityDoesNotTreatGroupIdAsAuthorization(){row("FROM mistakes",Map.of("id",3L,"user_id",8L,"visibility","group","group_id",99L));assertEquals(404,assertThrows(ResponseStatusException.class,()->k.mistake(auth,3)).getStatusCode().value());}
    @Test void activeGroupMemberCanReadSharedMistake(){row("FROM mistakes",Map.of("id",3L,"user_id",8L,"visibility","group","group_id",99L));row("FROM study_group_members",Map.of("id",1L));assertEquals(3L,k.mistake(auth,3).get("id"));}
    @Test void publicReadDoesNotGrantEditRights(){row("FROM mistakes",Map.of("id",3L,"user_id",8L,"visibility","public"));var r=k.mistake(auth,3);assertEquals(403,assertThrows(ResponseStatusException.class,()->k.owner(auth,r,"user_id")).getStatusCode().value());}
    @Test void unpublishedResourcesCannotBeViewedByOtherUsers(){row("FROM resources",Map.of("id",4L,"uploader_id",8L,"status","pending"));assertEquals(404,assertThrows(ResponseStatusException.class,()->k.resource(auth,4)).getStatusCode().value());}
    @Test void pendingResourceOwnerCanView(){row("FROM resources",Map.of("id",4L,"uploader_id",7L,"status","pending"));assertEquals("pending",k.resource(auth,4).get("status"));}
    @Test void draftNoteCannotBeCollectedEvenByItsOwner(){row("FROM notes",Map.of("id",4L,"user_id",7L,"status","draft"));assertEquals(400,assertThrows(ResponseStatusException.class,()->k.item(auth,"note",4)).getStatusCode().value());}
    @Test void arbitraryTableNamesAreRejected(){assertThrows(ResponseStatusException.class,()->k.item(auth,"users",1));}
    @Test void validatesNumbersWithoutSilentlyTruncating(){assertEquals(3,k.number(Map.of("id","3"),"id",0));assertThrows(ResponseStatusException.class,()->k.number(Map.of("id",3.2),"id",0));assertThrows(ResponseStatusException.class,()->k.number(Map.of("id","x"),"id",0));}
    @Test void invalidPagesAreRejected(){assertThrows(ResponseStatusException.class,()->k.offset(0));assertThrows(ResponseStatusException.class,()->k.offset(10001));assertEquals(20,k.offset(2));}
    @Test void pageSentinelIsNotReturned(){List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<21;i++)rows.add(Map.of("id",i));var p=k.page(rows);assertEquals(20,((List<?>)p.get("items")).size());assertEquals(true,p.get("hasNext"));}
    @Test void binaryDisguisedAsTextIsRejected(){assertThrows(ResponseStatusException.class,()->ResourceController.validateFile("txt",new byte[]{0,1,2}));assertThrows(ResponseStatusException.class,()->ResourceController.validateFile("pdf","not a pdf".getBytes()));assertThrows(ResponseStatusException.class,()->ResourceController.validateFile("docx",new byte[]{'P','K',3,4}));}
    @Test void acceptsUtf8TextAndPdfMagic(){assertDoesNotThrow(()->ResourceController.validateFile("txt","学习资料".getBytes(java.nio.charset.StandardCharsets.UTF_8)));assertDoesNotThrow(()->ResourceController.validateFile("pdf","%PDF-1.4\n".getBytes()));}
    @Test void wikiConcurrentEditRequiresCurrentRevision(){row("FROM wiki_pages",Map.of("id",5L,"board_id",1L));row("FROM wiki_revisions",Map.of("id",9L));when(mapper.user(7L)).thenReturn(Map.of("role","moderator","moderator_board_id",1L,"status","active"));var c=new WikiController(new WikiService(new com.studentforum.repository.WikiRepository(db),new com.studentforum.service.AccountAccessService(mapper),k));assertEquals(409,assertThrows(ResponseStatusException.class,()->c.edit(auth,5L,Map.of("baseRevisionId",8L))).getStatusCode().value());verify(db,never()).update(anyString(),any(Object[].class));}
    @Test void detailResultsAreDefensiveCopies() {
        var source=Map.<String,Object>of("id",4L,"uploader_id",7L,"status","pending");
        row("FROM resources",source);
        var result=k.resource(auth,4);
        result.put("files",List.of());
        assertFalse(source.containsKey("files"));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->k.required((Map<String,Object>)null)).getStatusCode().value());
    }
    @Test void anonymousMembershipDoesNotReachTheDatabase() {
        assertFalse(k.member(99,0));
        assertFalse(k.manager(null,1));
        verifyNoInteractions(db,mapper);
    }
    @Test void pendingResourceIsVisibleToActiveAdminButNotSuspendedAdmin() {
        row("FROM resources",Map.of("id",4L,"uploader_id",8L,"status","pending"));
        when(mapper.user(7L)).thenReturn(Map.of("role","admin","status","active"));
        assertEquals(4L,k.resource(auth,4).get("id"));
        when(mapper.user(7L)).thenReturn(Map.of("role","admin","status","banned"));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->k.resource(auth,4)).getStatusCode().value());
    }
    @Test void moderatorKnowledgePermissionsRemainScopedToTheirBoard() {
        when(mapper.user(7L)).thenReturn(Map.of("role","moderator","status","active","moderator_board_id",1L));
        assertTrue(k.manager(auth,1));
        assertFalse(k.manager(auth,2));
        assertFalse(k.manager(auth,-1));
    }
    @Test void adminCanViewDisabledCollectionsButNotAnotherAuthorsDisabledColumn() {
        when(mapper.user(7L)).thenReturn(Map.of("role","admin","status","active"));
        row("FROM collections",Map.of("id",4L,"owner_id",8L,"status","disabled"));
        row("FROM columns",Map.of("id",5L,"owner_id",8L,"status","disabled"));
        assertEquals(4L,k.collection(auth,4).get("id"));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->k.column(auth,5)).getStatusCode().value());
    }
    @Test void sharedServiceHasNoJdbcOrForumDependencyOrArbitrarySqlEntrypoint() {
        for(var field:KnowledgeService.class.getDeclaredFields()) {
            assertNotEquals(JdbcTemplate.class,field.getType());
            assertNotEquals(com.studentforum.service.ForumService.class,field.getType());
        }
        for(var method:KnowledgeService.class.getDeclaredMethods()) {
            assertNotEquals("row",method.getName());
            assertNotEquals("insert",method.getName());
            if(method.getName().equals("required")) assertEquals(Map.class,method.getParameterTypes()[0]);
        }
    }
}
