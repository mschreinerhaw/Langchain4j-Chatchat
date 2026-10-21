package com.chatchat.chat.interaction.service.handler;

import com.chatchat.agents.runtime.tool.*;
import com.chatchat.chat.interaction.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ToolDirectReportModeTest {
    @Test void reportPreferenceDoesNotChangeControlledToolParametersOrDispatchAnotherTool(){
        for(String mode:List.of("off","visual")){
            var runtime=mock(ToolRuntimeService.class);
            when(runtime.execute(any())).thenAnswer(call->{ToolRuntimeRequest request=call.getArgument(0);
                assertThat(request.getToolName()).isEqualTo("fixed_template");
                assertThat(request.getToolInput().getParameters()).isEqualTo(Map.of("period","2026-01"));
                assertThat(request.getAttributes()).doesNotContainKey("reportMode");
                throw new IllegalStateException("controlled invocation captured");});
            var handler=new ToolDirectModeHandler(runtime);
            assertThatThrownBy(()->handler.handle(InteractionRequest.builder().query("run fixed template").toolName("fixed_template")
                .toolInput(Map.of("period","2026-01","reportMode",mode)).build(),InteractionContext.builder().requestId("request").conversationId("session").build()))
                .hasMessage("controlled invocation captured");
            verify(runtime,times(1)).execute(any());
        }
    }
}
