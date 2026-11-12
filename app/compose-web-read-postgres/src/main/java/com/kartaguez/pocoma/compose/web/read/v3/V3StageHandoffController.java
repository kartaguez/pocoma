package com.kartaguez.pocoma.compose.web.read.v3;

import com.kartaguez.pocoma.infra.observability.micrometer.v3.StageHandoffTracker;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, redacted view of process-local handoff timings. */
@Profile(V3ReadHttpConfiguration.PROFILE)
@RestController
public final class V3StageHandoffController {
    private static final int MAX_RECORDS = 1_000;
    private final StageHandoffTracker tracker;

    public V3StageHandoffController(@Qualifier("webReadHandoffs") StageHandoffTracker tracker) {
        this.tracker = tracker;
    }

    @GetMapping({"/actuator/stage-handoffs", "/api/v3/diagnostics/stage-handoffs"})
    public ResponseEntity<Map<String, Object>> stageHandoffs(
            @RequestParam(name = "limit", defaultValue = "1000") int limit,
            @RequestParam(name = "sinceEpochMs", required = false) Long sinceEpochMs,
            @RequestParam(name = "clear", defaultValue = "false") boolean clear) {
        if (clear || limit < 1 || (sinceEpochMs != null && sinceEpochMs < 0)) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tracker.snapshot(Math.min(limit, MAX_RECORDS), sinceEpochMs, false));
    }
}
