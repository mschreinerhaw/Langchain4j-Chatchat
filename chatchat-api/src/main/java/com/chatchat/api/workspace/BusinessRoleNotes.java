package com.chatchat.api.workspace;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BusinessRoleNotes {
    private static final String READ = "/workspaces/business/role-notes/{panel}";
    private static final String MANAGE = "/enterprise" + READ + "/{roleId}";

    private final WorkspaceService workspaces;
    private final ObjectAccessService access;
    private final JdbcTemplate jdbc;

    public record Note(String roleId, String roleName, String title, String body) {}
    public record RoleOption(String id, String name, String code, String title, String body) {}
    public record Page(List<Note> notes, boolean canPublish, List<RoleOption> roles) {}
    public record Draft(String title, String body) {}

    @GetMapping(READ)
    @Transactional(readOnly = true)
    public ApiResponse<Page> list(HttpServletRequest request, @PathVariable("panel") String panel) {
        requirePanel(request, panel);
        String tenant = access.tenant(request);
        var notes = jdbc.query("""
            select r.id, r.role_name, n.title, n.body
            from business_workspace_role_note n
            join sys_role r on r.id = n.role_id and r.tenant_id = n.tenant_id and r.status = 'enabled'
            where n.tenant_id = ? and n.panel = ? and exists (
                select 1 from sys_user_role ur where ur.tenant_id = n.tenant_id and ur.role_id = n.role_id
                    and ur.user_id = ?
            )
            order by r.role_name, r.id
            """, (row, index) -> new Note(row.getString(1), row.getString(2), row.getString(3), row.getString(4)),
            tenant, panel, access.user(request));
        boolean canPublish = workspaces.permissions(request).contains("system:workspaces:manage");
        List<RoleOption> roles = canPublish ? jdbc.query("""
            select r.id, r.role_name, r.role_code, n.title, n.body
            from sys_role r
            left join business_workspace_role_note n on n.tenant_id = r.tenant_id and n.role_id = r.id and n.panel = ?
            where r.tenant_id = ? and r.status = 'enabled'
            order by r.role_name, r.id
            """, (row, index) -> new RoleOption(row.getString(1), row.getString(2), row.getString(3),
            row.getString(4), row.getString(5)),
            panel, tenant) : List.of();
        return ApiResponse.success(new Page(notes, canPublish, roles));
    }

    @PutMapping(MANAGE)
    @Transactional
    public ApiResponse<Void> publish(HttpServletRequest request, @PathVariable("panel") String panel,
                                     @PathVariable("roleId") String roleId, @RequestBody Draft draft) {
        requireTarget(request, panel, roleId);
        String title = required(draft.title(), 120, "title");
        String body = required(draft.body(), 4000, "body");
        String tenant = access.tenant(request);
        if (jdbc.queryForList("""
            select role_id from business_workspace_role_note where tenant_id = ? and role_id = ? and panel = ?
            for update
            """, String.class, tenant, roleId, panel).isEmpty()) {
            jdbc.update("""
                insert into business_workspace_role_note (tenant_id, role_id, panel, title, body)
                values (?, ?, ?, ?, ?)
                """, tenant, roleId, panel, title, body);
        } else {
            jdbc.update("""
                update business_workspace_role_note set title = ?, body = ?
                where tenant_id = ? and role_id = ? and panel = ?
                """, title, body, tenant, roleId, panel);
        }
        workspaces.audit(request, "publish-role-note", panel + ":" + roleId);
        return ApiResponse.success(null);
    }

    @DeleteMapping(MANAGE)
    @Transactional
    public ApiResponse<Void> withdraw(HttpServletRequest request, @PathVariable("panel") String panel,
                                      @PathVariable("roleId") String roleId) {
        requireTarget(request, panel, roleId);
        if (jdbc.update("delete from business_workspace_role_note where tenant_id = ? and role_id = ? and panel = ?",
            access.tenant(request), roleId, panel) > 0) {
            workspaces.audit(request, "withdraw-role-note", panel + ":" + roleId);
        }
        return ApiResponse.success(null);
    }

    void requirePanel(HttpServletRequest request, String panel) {
        if (!Set.of("guides", "outcomes").contains(panel)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business workspace panel not found");
        }
        if (workspaces.load(request, BusinessWorkspaceMigration.ID).manifest().pages().stream()
            .noneMatch(page -> "businessHome".equals(page.componentKey()))) {
            throw new SecurityException("Business workspace page access denied");
        }
    }

    private void requireTarget(HttpServletRequest request, String panel, String roleId) {
        workspaces.requireManagement(request);
        requirePanel(request, panel);
        // Serialize changes to the same role, including its first publication.
        if (jdbc.queryForList("select id from sys_role where tenant_id = ? and id = ? and status = 'enabled' for update",
            String.class, access.tenant(request), roleId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Enabled role not found");
        }
    }

    private static String required(String value, int maxLength, String field) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.length() > maxLength || text.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid note " + field);
        }
        return text;
    }
}
