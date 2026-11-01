package com.studentforum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentforum.controller.AdminController;
import com.studentforum.dto.AdminPostStatusRequest;
import com.studentforum.service.AdminService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import static org.junit.jupiter.api.Assertions.*;

class AdminContractTest {
    @Test void postStatusRequestBindsThroughHttp() throws Exception {
        AdminService service = mock(AdminService.class);
        MockMvcBuilders.standaloneSetup(new AdminController(service)).build()
                .perform(patch("/api/admin/posts/4/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"hidden\"}"))
                .andExpect(status().isOk());
        verify(service).status(isNull(), eq(4L), argThat(request -> "hidden".equals(request.status())));
    }

    @Test void controllerPathAndServiceBeanArePreserved() throws Exception {
        assertArrayEquals(new String[]{"/api/admin"}, AdminController.class.getAnnotation(RequestMapping.class).value());
        assertEquals("/posts/{id}/status", AdminController.class
                .getMethod("status", org.springframework.security.core.Authentication.class, long.class, AdminPostStatusRequest.class)
                .getAnnotation(PatchMapping.class).value()[0]);
        assertNotNull(AdminService.class.getAnnotation(Service.class));
        assertEquals("hidden", new ObjectMapper()
                .readValue("{\"status\":\"hidden\"}", AdminPostStatusRequest.class).status());
    }
}
