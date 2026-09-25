package com.studentforum;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class ForumServiceTest {
    private ForumMapper mapper;
    private ForumService service;
    private Authentication author;

    @BeforeEach void setup() {
        mapper=mock(ForumMapper.class);
        service=new ForumService(mapper);
        author=new UsernamePasswordAuthenticationToken("1",null,List.of());
        when(mapper.user(1)).thenReturn(Map.of("id",1L,"status","active","role","student"));
    }

    @Test void publishAwardsOnceWithinDailyLimit() {
        when(mapper.board(2)).thenReturn(Map.of("id",2L));
        when(mapper.tag(3)).thenReturn(Map.of("id",3L));
        doAnswer(invocation -> { invocation.<Map<String,Object>>getArgument(0).put("id",8L); return null; }).when(mapper).insertPost(anyMap());
        when(mapper.post(8)).thenReturn(Map.of("id",8L));
        when(mapper.dailyPoints(1,"post")).thenReturn(5);
        service.publish(author,Map.of("boardId",2,"type","question","title","一个具体问题","content","我尝试了几种不同的方法来解决问题","tagIds",List.of(3)));
        verify(mapper).postTag(8,3);
        verify(mapper,never()).addPoints(anyLong(),anyInt());
    }

    @Test void answerCannotBeAcceptedTwice() {
        when(mapper.post(5)).thenReturn(new HashMap<>(Map.of("id",5L,"author_id",1L,"type","question","status","published")));
        when(mapper.reply(7)).thenReturn(Map.of("id",7L,"post_id",5L,"author_id",2L,"status","published"));
        when(mapper.acceptPost(5,7)).thenReturn(0);
        assertThrows(ResponseStatusException.class,() -> service.accept(author,5,7));
        verify(mapper,never()).acceptReply(7);
        verify(mapper,never()).addPoints(anyLong(),anyInt());
    }

    @Test void moderatorCannotFeatureOtherBoard() {
        when(mapper.user(1)).thenReturn(Map.of("id",1L,"role","moderator","status","active","moderator_board_id",2L));
        when(mapper.post(5)).thenReturn(Map.of("id",5L,"board_id",3L,"status","published","is_featured",false));
        assertThrows(ResponseStatusException.class,() -> service.feature(author,5,true));
        verify(mapper,never()).feature(anyLong(),anyBoolean());
    }

    @Test void repeatedFeatureDoesNotRewardAgain() {
        when(mapper.user(1)).thenReturn(Map.of("id",1L,"role","admin","status","active"));
        when(mapper.post(5)).thenReturn(Map.of("id",5L,"board_id",3L,"author_id",2L,"status","published","is_featured",false));
        when(mapper.feature(5,true)).thenReturn(1);
        when(mapper.rewardExists(2,"featured","post",5)).thenReturn(1);
        service.feature(author,5,true);
        verify(mapper,never()).addPoints(anyLong(),anyInt());
    }
}
