package com.kartaguez.pocoma.runtime.consumption.v3;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Diagnostic HTTP endpoint exposing asynchronous stage handoff timings.
 */
@Profile({"v3-http-admission", "v3-http-read", "v3-read-worker"})
@RestController
public final class V3StageHandoffController {

    @GetMapping({"/actuator/stage-handoffs", "/api/v3/diagnostics/stage-handoffs"})
    public Map<String, Object> stageHandoffs(
            @RequestParam(name = "limit", required = false, defaultValue = "5000") int limit,
            @RequestParam(name = "sinceEpochMs", required = false) Long sinceEpochMs,
            @RequestParam(name = "clear", required = false, defaultValue = "false") boolean clear) {
        if (clear) {
            StageHandoffTracker.getInstance().clear();
        }
        return StageHandoffTracker.getInstance().snapshot(limit, sinceEpochMs);
    }
}
