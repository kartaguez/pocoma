package com.kartaguez.pocoma.compose.web.read.v3;
import com.kartaguez.pocoma.infra.observability.micrometer.v3.StageHandoffTracker;

import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Diagnostic HTTP endpoint exposing asynchronous stage handoff timings.
 * Unauthenticated requests strictly exclude business entity identifiers.
 */
@Profile({"v3-http-admission", "v3-http-read", "v3-read-worker"})
@RestController
public final class V3StageHandoffController {
    private final StageHandoffTracker tracker;
    public V3StageHandoffController(@org.springframework.beans.factory.annotation.Qualifier("webReadHandoffs") StageHandoffTracker tracker) {this.tracker=tracker;}

    @GetMapping({"/actuator/stage-handoffs", "/api/v3/diagnostics/stage-handoffs"})
    public Map<String, Object> stageHandoffs(
            Authentication authentication,
            @RequestParam(name = "limit", required = false, defaultValue = "5000") int limit,
            @RequestParam(name = "sinceEpochMs", required = false) Long sinceEpochMs,
            @RequestParam(name = "clear", required = false, defaultValue = "false") boolean clear) {
        if (clear) {
            tracker.clear();
        }
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        return tracker.snapshot(limit, sinceEpochMs, authenticated);
    }
}
