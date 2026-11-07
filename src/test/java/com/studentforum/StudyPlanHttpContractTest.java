package com.studentforum;

import com.studentforum.controller.StudyPlanController;
import com.studentforum.service.StudyPlanService;
import com.studentforum.service.StudyPlanTemplateService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudyPlanHttpContractTest {
    private final StudyPlanService plans = mock(StudyPlanService.class);
    private final StudyPlanTemplateService templates = mock(StudyPlanTemplateService.class);

    @Test void templateListUsesTheTemplateServiceAndKeepsTheJsonShape() throws Exception {
        when(templates.templates(null, "数学")).thenReturn(Map.of("official", List.of(Map.of("id", 2)),
                "mine", List.of(), "shared", List.of(), "subjects", List.of("数学")));
        MockMvcBuilders.standaloneSetup(new StudyPlanController(plans, templates)).build()
                .perform(get("/api/plans/templates").param("subject", "数学"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.official[0].id").value(2))
                .andExpect(jsonPath("$.mine").isArray()).andExpect(jsonPath("$.shared").isArray())
                .andExpect(jsonPath("$.subjects[0]").value("数学"));
        verify(templates).templates(null, "数学");
        verifyNoInteractions(plans);
    }

    @Test void templateWritesKeepRoutesAndOptionalRequestBodies() throws Exception {
        when(templates.createTemplate(null, Map.of("title", "复习模板"))).thenReturn(Map.of("id", 8));
        when(templates.templateFromPlan(null, 4, null)).thenReturn(Map.of("id", 9));
        when(templates.deleteTemplate(null, 8)).thenReturn(Map.of("deleted", true));
        var mvc = MockMvcBuilders.standaloneSetup(new StudyPlanController(plans, templates)).build();
        mvc.perform(post("/api/plans/templates").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"复习模板\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(8));
        mvc.perform(post("/api/plans/4/template")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(9));
        mvc.perform(delete("/api/plans/templates/8")).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));
        verify(templates).createTemplate(null, Map.of("title", "复习模板"));
        verify(templates).templateFromPlan(null, 4, null);
        verify(templates).deleteTemplate(null, 8);
        verifyNoInteractions(plans);
    }

    @Test void templateApplicationStillUsesThePlanCreationTransaction() throws Exception {
        when(plans.applyTemplateEndpoint(null, 8, null)).thenReturn(Map.of("id", 4, "tasks", List.of(Map.of("id", 5))));
        MockMvcBuilders.standaloneSetup(new StudyPlanController(plans, templates)).build()
                .perform(post("/api/plans/templates/8/apply"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.tasks[0].id").value(5));
        verify(plans).applyTemplateEndpoint(null, 8, null);
        verifyNoInteractions(templates);
    }
}
