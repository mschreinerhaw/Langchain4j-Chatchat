package com.chatchat.mcpserver.routing;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/execution-targets")
public class ExecutionTargetController {

    private final ExecutionTargetService executionTargetService;

    @GetMapping
    public ApiResponse<List<ExecutionTargetConfig>> list() {
        return ApiResponse.success(executionTargetService.listAll());
    }

    @PostMapping
    public ApiResponse<ExecutionTargetConfig> create(@RequestBody ExecutionTargetConfig request) {
        return ApiResponse.success(executionTargetService.create(request), "Execution target created");
    }

    @PutMapping("/{id}")
    public ApiResponse<ExecutionTargetConfig> update(@PathVariable("id") String id,
                                                     @RequestBody ExecutionTargetConfig request) {
        return ApiResponse.success(executionTargetService.update(id, request), "Execution target updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") String id) {
        executionTargetService.delete(id);
        return ApiResponse.success(null, "Execution target deleted");
    }
}
