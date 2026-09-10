package com.rrmadon.flashsale.release;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * NOTE: unauthenticated on purpose only for this learning project. A real
 * deployment must put this behind real admin auth before going live --
 * an unauthenticated kill switch is itself an outage waiting to happen.
 */
@RestController
public class AdminController {

    private final KillSwitch killSwitch;

    public AdminController(KillSwitch killSwitch) {
        this.killSwitch = killSwitch;
    }

    @PostMapping("/admin/halt")
    public Map<String, Object> halt() {
        killSwitch.halt();
        return Map.of("admission", "halted");
    }

    @PostMapping("/admin/resume")
    public Map<String, Object> resume() {
        killSwitch.resume();
        return Map.of("admission", "resumed");
    }
}
