package com.studentforum.controller;

import com.studentforum.service.FocusService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/focus")
public class FocusController {
    private final FocusService focusService;

    public FocusController(FocusService focusService) {
        this.focusService = focusService;
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(Authentication auth, @RequestBody Map<String, Object> input) {
        return focusService.createSession(auth,input);
    }

    @GetMapping("/sessions")
    public Map<String, Object> sessions(Authentication auth,
                                @RequestParam(required = false) String range,
                                @RequestParam(defaultValue = "1") int page) {
        return focusService.sessions(auth,range,page);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(Authentication auth, @RequestParam(defaultValue = "week") String range) {
        return focusService.stats(auth,range);
    }

    @GetMapping("/leaderboard")
    public Map<String, Object> leaderboard(@RequestParam(defaultValue = "week") String range,
                                    @RequestParam(defaultValue = "1") int page,
                                    Authentication auth) {
        return focusService.leaderboard(range,page,auth);
    }

    @GetMapping("/me/rooms")
    public Map<String, Object> myRooms(Authentication auth) {
        return focusService.myRooms(auth);
    }
}
