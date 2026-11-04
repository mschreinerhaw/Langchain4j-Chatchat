package com.chatchat.mcpserver.health;

import java.util.Map;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Infrastructure readiness only: no account, configuration or resource data is disclosed. */
@RestController
public final class McpReadinessController {
    private final ApplicationAvailability availability;
    public McpReadinessController(ApplicationAvailability availability) { this.availability=availability; }
    @GetMapping("/readyz")
    public ResponseEntity<Map<String,String>> ready() {
        boolean ready=availability.getReadinessState()==ReadinessState.ACCEPTING_TRAFFIC;
        return ResponseEntity.status(ready?200:503).body(Map.of("status",ready?"READY":"STARTING"));
    }
}
