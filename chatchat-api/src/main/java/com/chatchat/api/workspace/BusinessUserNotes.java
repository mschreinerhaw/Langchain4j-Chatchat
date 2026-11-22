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
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workspaces/business/notes/{panel}")
public class BusinessUserNotes {
    private static final String ACTIVE_ROLES = """
        select r.id, r.role_name from sys_role r
        join sys_user_role ur on ur.tenant_id = r.tenant_id and ur.role_id = r.id
        where r.tenant_id = ? and ur.user_id = ? and r.status = 'enabled'
        """;
    private final JdbcTemplate jdbc;
    private final ObjectAccessService access;
    private final BusinessRoleNotes panels;

    public record Role(String id, String name) {}
    public record Note(String id, String title, String body, String roleId, long revision, boolean owned, boolean published,
                       String publishedTitle, String publishedBody) {}
    public record Page(List<Note> notes, List<Role> roles, int page, boolean hasMore) {}
    public record Edit(String title, String body, String publishTo, Long revision) {}

    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<Page> list(HttpServletRequest request, @PathVariable("panel") String panel,
                                 @RequestParam(name="page", defaultValue="0") int page) {
        panels.requirePanel(request, panel);
        String tenant = access.tenant(request), owner = access.user(request);
        int index = Math.max(0, page);
        var notes = jdbc.query("""
            select n.id, case when n.owner_id = ? then n.title else n.published_title end,
                case when n.owner_id = ? then n.body else n.published_body end,
                n.role_id, n.revision, n.owner_id, n.published_title, n.published_body
            from business_workspace_user_note n where n.tenant_id = ? and n.panel = ?
              and (n.owner_id = ? or (n.published_title is not null and n.role_id in (
                select active.id from (
            """ + ACTIVE_ROLES + """
                ) active)))
            order by n.updated_at desc, n.id
            limit 21 offset ?
            """, (row, i) -> new Note(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
            row.getLong(5), owner.equals(row.getString(6)), row.getString(7) != null,
            owner.equals(row.getString(6)) ? row.getString(7) : null,
            owner.equals(row.getString(6)) ? row.getString(8) : null),
            owner, owner, tenant, panel, owner, tenant, owner, (long) index * 20);
        return ApiResponse.success(new Page(notes.stream().limit(20).toList(), roles(tenant, owner), index, notes.size() > 20));
    }

    @PostMapping
    @Transactional
    public ApiResponse<String> create(HttpServletRequest request, @PathVariable("panel") String panel, @RequestBody Edit edit) {
        panels.requirePanel(request, panel);
        String tenant = access.tenant(request), owner = access.user(request);
        String title = title(edit), body = body(edit), id = UUID.randomUUID().toString();
        validateRole(edit.publishTo(), tenant, owner);
        jdbc.update("""
            insert into business_workspace_user_note
            (id, tenant_id, owner_id, panel, title, body, published_title, published_body, role_id, revision, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, current_timestamp)
            """, id, tenant, owner, panel, title, body, edit.publishTo() == null ? null : title,
            edit.publishTo() == null ? null : body, edit.publishTo());
        return ApiResponse.success(id);
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<String> save(HttpServletRequest request, @PathVariable("panel") String panel,
                                    @PathVariable("id") String id, @RequestBody Edit edit) {
        panels.requirePanel(request, panel);
        String tenant = access.tenant(request), owner = access.user(request);
        String title = title(edit), body = body(edit);
        validateRole(edit.publishTo(), tenant, owner);
        String update = """
            update business_workspace_user_note set title = ?, body = ?, revision = revision + 1, updated_at = current_timestamp
            """;
        String where = " where id = ? and tenant_id = ? and owner_id = ? and panel = ? and revision = ?";
        int changed = edit.publishTo() == null
            ? jdbc.update(update + where, title, body, id, tenant, owner, panel, edit.revision())
            : jdbc.update(update + ", published_title = ?, published_body = ?, role_id = ?" + where,
                title, body, title, body, edit.publishTo(), id, tenant, owner, panel, edit.revision());
        requireChange(changed);
        return ApiResponse.success(id);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable("panel") String panel,
                                    @PathVariable("id") String id, @RequestParam("revision") long revision) {
        panels.requirePanel(request, panel);
        requireChange(jdbc.update("""
            delete from business_workspace_user_note where id = ? and tenant_id = ? and owner_id = ? and panel = ? and revision = ?
            """, id, access.tenant(request), access.user(request), panel, revision));
        return ApiResponse.success(null);
    }

    private List<Role> roles(String tenant, String owner) {
        return jdbc.query(ACTIVE_ROLES + " order by r.role_name, r.id",
            (row, i) -> new Role(row.getString(1), row.getString(2)), tenant, owner);
    }

    private void validateRole(String roleId, String tenant, String owner) {
        if (roleId != null && roles(tenant, owner).stream().noneMatch(role -> role.id().equals(roleId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能发布到自己所属的启用角色");
        }
    }

    private static String title(Edit edit) {
        String value = edit.title() == null ? "" : edit.title().strip();
        if (value.isEmpty() || value.length() > 120 || value.indexOf('\0') >= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1 至 120 字的标题");
        return value;
    }

    private static String body(Edit edit) {
        String value = edit.body() == null ? "" : edit.body();
        if (value.length() > 20000 || value.indexOf('\0') >= 0 || (edit.publishTo() != null && value.isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "笔记内容无效或超过 20,000 字");
        return value;
    }

    private static void requireChange(int changed) {
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "笔记已更新、删除或当前账号没有编辑权限，请刷新列表");
    }
}
