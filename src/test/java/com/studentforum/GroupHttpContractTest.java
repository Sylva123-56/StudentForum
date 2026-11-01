package com.studentforum;

import com.studentforum.controller.GroupController;
import com.studentforum.service.GroupService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

class GroupHttpContractTest {
    @Test void rankingRouteKeepsTheJsonListAndQueryParameter() throws Exception {
        GroupService groups = mock(GroupService.class);
        when(groups.rankings("members")).thenReturn(List.of(Map.of("id", 4L)));
        MockMvcBuilders.standaloneSetup(new GroupController(groups)).build()
                .perform(get("/api/groups/rankings").param("type", "members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(4));
        verify(groups).rankings("members");
    }
}
