package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/agent/knowledge")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public class KnowledgeCitationController {
    private final KnowledgeDisclosureGuard guard;
    public KnowledgeCitationController(KnowledgeDisclosureGuard guard){this.guard=guard;}
    @GetMapping("/citations/{id}")
    public ResponseEntity<?> source(@PathVariable("id") String id,HttpServletRequest request) {
        if(!id.matches("[0-9a-f]{64}"))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            var source=guard.resolve((String)request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID),
                (String)request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID),"runtime-evidence:knowledge:"+id);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(source));
        }catch(SecurityException denied){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Source authorization or version has expired");}
    }
}
