package com.ewallet.notification;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dlq")
class DlqAdminController {
    private final DlqReplayService replayService;

    DlqAdminController(DlqReplayService replayService) {
        this.replayService = replayService;
    }

    @GetMapping
    List<DlqMessage> inspect(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return replayService.inspect(Math.max(1, Math.min(limit, 500)));
    }

    @PostMapping("/replay")
    DlqReplayResult replay(@RequestBody DlqReplayRequest request) {
        return replayService.replay(request);
    }
}
