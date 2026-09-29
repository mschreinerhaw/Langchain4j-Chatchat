package com.chatchat.runtime.skill.port.outbound;

import com.chatchat.runtime.skill.api.execution.SkillCompositionRequest;
import com.chatchat.runtime.skill.api.skill.SkillDescriptor;
import java.util.List;

/** The model proposes intent, never permissions, tool bindings, or executable code. */
public interface SkillIntentPlanner {
    Intent understand(SkillCompositionRequest request, List<SkillDescriptor> authorizedCandidates);

    record Task(String objective, List<String> capabilities) {
        public Task { capabilities = List.copyOf(capabilities); }
    }
    record Intent(String domain, String objective, List<Task> tasks, String mode) {
        public Intent { tasks = List.copyOf(tasks); }
        public List<String> capabilities() {
            return tasks.stream().flatMap(task -> task.capabilities().stream()).distinct().toList();
        }
    }
}
