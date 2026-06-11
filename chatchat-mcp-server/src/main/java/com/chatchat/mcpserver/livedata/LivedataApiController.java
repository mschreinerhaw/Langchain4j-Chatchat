package com.chatchat.mcpserver.livedata;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/livedata-apis")
public class LivedataApiController {

    private final LivedataApiRegistrationService registrationService;

    /**
     * Lists the list.
     *
     * @return the list list
     */
    @GetMapping
    public ApiResponse<List<LivedataApiRegistrationService.LivedataApiCandidate>> list() {
        return ApiResponse.success(registrationService.listCandidates());
    }

    /**
     * Registers the register.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/register")
    public ApiResponse<LivedataApiRegistrationService.LivedataRegistrationResult> register(
        @RequestBody LivedataRegisterRequest request) {
        LivedataApiRegistrationService.LivedataRegistrationResult result =
            registrationService.register(request.ids(), request.overwriteExisting());
        return ApiResponse.success(result, "LiveData API manual registration completed");
    }

    public record LivedataRegisterRequest(List<String> ids, Boolean overwriteExisting) {
    }
}
