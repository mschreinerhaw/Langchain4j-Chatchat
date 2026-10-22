package com.chatchat.mcpserver.authorization;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
/** Retired surface: authorization writes belong exclusively to the control plane. */
@RestController
public class McpAuthorizationAdminController {
    @RequestMapping({"/api/v1/mcp-authorization", "/api/v1/mcp-authorization/**"})
    public void retired() { throw new ResponseStatusException(HttpStatus.GONE, "Use the unified permission management workspace"); }
}
