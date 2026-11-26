package com.cutline.queue.api;

import com.cutline.common.web.CurrentMember;
import com.cutline.queue.QueueService;
import com.cutline.queue.QueueService.QueueView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueueController {

    private final QueueService service;

    public QueueController(QueueService service) {
        this.service = service;
    }

    /** 줄 서기. 처음이든 다시 누른 것이든 200과 지금 자리를 돌려준다. */
    @PostMapping("/api/drops/{dropId}/queue")
    QueueView join(@CurrentMember Long memberId, @PathVariable Long dropId) {
        return service.join(memberId, dropId);
    }

    @GetMapping("/api/drops/{dropId}/queue/me")
    QueueView me(@CurrentMember Long memberId, @PathVariable Long dropId) {
        return service.status(memberId, dropId);
    }
}
