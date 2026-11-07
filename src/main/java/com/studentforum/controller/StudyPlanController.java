package com.studentforum.controller;

import com.studentforum.service.StudyPlanService;
import com.studentforum.service.StudyPlanTemplateService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/plans")
public class StudyPlanController {
    private final StudyPlanService studyPlanService;

    private final StudyPlanTemplateService templates;

    public StudyPlanController(StudyPlanService studyPlanService, StudyPlanTemplateService templates) {
        this.studyPlanService = studyPlanService;
        this.templates = templates;
    }

    @GetMapping
    public Map<String, Object> plans(Authentication auth,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String subject) {
        return studyPlanService.plans(auth,status,subject);
    }

    @PostMapping
    public Map<String, Object> createPlan(Authentication auth, @RequestBody Map<String, Object> input) {
        return studyPlanService.createPlan(auth,input);
    }

    @GetMapping("/stats")
    public Map<String, Object> planStats(Authentication auth) {
        return studyPlanService.planStats(auth);
    }

    @GetMapping("/{planId}")
    public Map<String, Object> plan(Authentication auth, @PathVariable long planId) {
        return studyPlanService.plan(auth,planId);
    }

    @PatchMapping("/{planId}")
    public Map<String, Object> updatePlan(Authentication auth, @PathVariable long planId, @RequestBody Map<String, Object> input) {
        return studyPlanService.updatePlan(auth,planId,input);
    }

    @PatchMapping("/{planId}/archive")
    public Map<String, Object> archivePlan(Authentication auth, @PathVariable long planId, @RequestBody(required = false) Map<String, Object> input) {
        return studyPlanService.archivePlan(auth,planId,input);
    }

    @DeleteMapping("/{planId}")
    public Map<String, Object> deletePlan(Authentication auth, @PathVariable long planId) {
        return studyPlanService.deletePlan(auth,planId);
    }

    @PostMapping("/{planId}/tasks")
    public Map<String, Object> addTask(Authentication auth, @PathVariable long planId, @RequestBody Map<String, Object> input) {
        return studyPlanService.addTask(auth,planId,input);
    }

    @PatchMapping("/{planId}/tasks/{taskId}")
    public Map<String, Object> updateTask(Authentication auth, @PathVariable long planId, @PathVariable long taskId, @RequestBody Map<String, Object> input) {
        return studyPlanService.updateTask(auth,planId,taskId,input);
    }

    @DeleteMapping("/{planId}/tasks/{taskId}")
    public Map<String, Object> deleteTask(Authentication auth, @PathVariable long planId, @PathVariable long taskId) {
        return studyPlanService.deleteTask(auth,planId,taskId);
    }

    @GetMapping("/templates")
    public Map<String, Object> templates(Authentication auth, @RequestParam(required = false) String subject) {
        return templates.templates(auth,subject);
    }

    @PostMapping("/templates")
    public Map<String, Object> createTemplate(Authentication auth, @RequestBody Map<String, Object> input) {
        return templates.createTemplate(auth,input);
    }

    @PostMapping("/{planId}/template")
    public Map<String, Object> templateFromPlan(Authentication auth, @PathVariable long planId, @RequestBody(required = false) Map<String, Object> input) {
        return templates.templateFromPlan(auth,planId,input);
    }

    @PostMapping("/templates/{templateId}/apply")
    public Map<String, Object> applyTemplateEndpoint(Authentication auth, @PathVariable long templateId, @RequestBody(required = false) Map<String, Object> input) {
        return studyPlanService.applyTemplateEndpoint(auth,templateId,input);
    }

    @DeleteMapping("/templates/{templateId}")
    public Map<String, Object> deleteTemplate(Authentication auth, @PathVariable long templateId) {
        return templates.deleteTemplate(auth,templateId);
    }
}
