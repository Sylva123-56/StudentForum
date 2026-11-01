package com.studentforum.controller;

import com.studentforum.service.CheckinService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/checkin")
public class CheckinController {
    private final CheckinService checkinService;

    public CheckinController(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return checkinService.settings();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(Authentication auth) {
        return checkinService.stats(auth);
    }

    @GetMapping("/calendar")
    public Map<String, Object> calendar(Authentication auth,
                                @RequestParam(required = false) Integer year,
                                @RequestParam(required = false) Integer month) {
        return checkinService.calendar(auth,year,month);
    }

    @GetMapping("/goals")
    public List<Map<String, Object>> goals(Authentication auth, @RequestParam(defaultValue = "active") String status) {
        return checkinService.goals(auth,status);
    }

    @GetMapping("/public")
    public List<Map<String, Object>> publicGoals(Authentication auth, @RequestParam(defaultValue = "") String subject) {
        return checkinService.publicGoals(auth,subject);
    }

    @GetMapping("/records")
    public Map<String, Object> records(Authentication auth,
                               @RequestParam(required = false) Long goalId,
                               @RequestParam(defaultValue = "1") int page) {
        return checkinService.records(auth,goalId,page);
    }

    @PostMapping("/goals")
    public Map<String, Object> createGoal(Authentication auth, @RequestBody Map<String, Object> input) {
        return checkinService.createGoal(auth,input);
    }

    @PatchMapping("/goals/{goalId}")
    public Map<String, Object> updateGoal(Authentication auth, @PathVariable long goalId, @RequestBody Map<String, Object> input) {
        return checkinService.updateGoal(auth,goalId,input);
    }

    @PatchMapping("/goals/{goalId}/archive")
    public Map<String, Object> archiveGoal(Authentication auth, @PathVariable long goalId, @RequestBody(required = false) Map<String, Object> input) {
        return checkinService.archiveGoal(auth,goalId,input);
    }

    @DeleteMapping("/goals/{goalId}")
    public void deleteGoal(Authentication auth, @PathVariable long goalId) {
        checkinService.deleteGoal(auth,goalId);
    }

    @PostMapping
    public Map<String, Object> checkin(Authentication auth, @RequestBody Map<String, Object> input) {
        return checkinService.checkin(auth,input);
    }

    @GetMapping("/badges")
    public Map<String, Object> badges(Authentication auth) {
        return checkinService.badges(auth);
    }

    @GetMapping("/goals/{goalId}")
    public Map<String, Object> goalDetail(Authentication auth, @PathVariable long goalId) {
        return checkinService.goalDetail(auth,goalId);
    }

    @GetMapping("/goals/{goalId}/records")
    public Map<String, Object> goalRecords(Authentication auth, @PathVariable long goalId, @RequestParam(defaultValue = "30") int days) {
        return checkinService.goalRecords(auth,goalId,days);
    }
}
