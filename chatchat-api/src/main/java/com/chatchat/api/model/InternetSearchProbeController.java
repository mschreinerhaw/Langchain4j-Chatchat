package com.chatchat.api.model;

import com.chatchat.api.runtime.coding.knowledge.CodingInternetAccess;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Authenticated, single-query readiness check using existing model/Agent/provider governance. */
@RestController
@RequestMapping("/api/v1/platform/models/search-probe")
public class InternetSearchProbeController {
    private final CodingInternetAccess internet;private final ObjectAccessService access;
    public InternetSearchProbeController(CodingInternetAccess internet,ObjectAccessService access){this.internet=internet;this.access=access;}
    public record Request(String modelName,String agentId,String providerId) { }
    @PostMapping public ApiResponse<?> probe(HttpServletRequest http,@RequestBody Request body){
        if(body==null||body.modelName()==null||!body.modelName().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
            ||body.agentId()==null||body.agentId().isBlank()||body.agentId().length()>256
            ||body.providerId()!=null&&!body.providerId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Exact model, Agent and optional provider identity required");
        var owner=new com.chatchat.api.assets.UserAssetService.Owner(access.tenant(http),access.user(http));
        var scope=new KernelDataScope(owner.tenant(),owner.user(),"search-probe",null,"search-probe",null,Map.of());
        return ApiResponse.success(internet.probe(scope,body.agentId(),body.modelName(),body.providerId()));
    }
}
