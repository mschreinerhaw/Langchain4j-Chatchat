package com.chatchat.mcpserver.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import static org.assertj.core.api.Assertions.assertThat;

class McpReadinessControllerTest {
    @Test void openingHttpDoesNotImplyReadinessAndShutdownRejectsNewTraffic() {
        var availability=new ApplicationAvailabilityBean();var controller=new McpReadinessController(availability);
        assertThat(controller.ready().getStatusCode().value()).isEqualTo(503);
        availability.onApplicationEvent(new AvailabilityChangeEvent<>(this,ReadinessState.ACCEPTING_TRAFFIC));
        assertThat(controller.ready().getStatusCode().value()).isEqualTo(200);
        assertThat(controller.ready().getBody()).containsEntry("status","READY");
        availability.onApplicationEvent(new AvailabilityChangeEvent<>(this,ReadinessState.REFUSING_TRAFFIC));
        assertThat(controller.ready().getStatusCode().value()).isEqualTo(503);
    }
}
