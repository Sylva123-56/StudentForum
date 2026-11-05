package com.studentforum.schedule;

import com.studentforum.service.ScheduledDraftService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DraftPublisher {
    private final ScheduledDraftService drafts;

    public DraftPublisher(ScheduledDraftService drafts) {
        this.drafts = drafts;
    }

    @Scheduled(fixedDelay = 60000)
    public void publishDue() {
        drafts.publishDue();
    }
}
