package com.chatchat.mcpserver.admin;

import com.chatchat.mcpserver.license.McpAdminMenuCatalog;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class AdminMenuAccessService {
    private final AdminAuthService auth;
    private final AdminUserRepository users;
    private final AdminMenuGrantRepository grants;
    private final McpAdminMenuCatalog catalog;
    public record Menu(String key,String label,String parentKey,String description) {}

    public List<Menu> menus() {
        var result=new ArrayList<Menu>();
        for(var item:catalog.menus())if(item.navigation())
            result.add(new Menu(item.key(),item.label(),item.parentKey(),item.description()));
        result.add(new Menu("license","License 授权",null,"查看和维护本机许可证"));
        result.add(new Menu("adminUsers","用户管理",null,"创建、停用账号、重置密码及配置管理菜单权限"));
        result.add(new Menu("adminLoginAudits","登录审计",null,"查询 MCP 管理端账号登录行为"));
        return List.copyOf(result);
    }
    public static String token(HttpServletRequest request) {
        String value=request.getHeader("Authorization");
        return value!=null && value.regionMatches(true,0,"Bearer ",0,7)?value.substring(7).trim():null;
    }
    public String username(HttpServletRequest request){return auth.username(token(request));}
    public boolean superAdmin(HttpServletRequest request){return auth.internalSession(token(request)) || "admin".equalsIgnoreCase(username(request));}
    public boolean enabled(HttpServletRequest request){
        if(auth.internalSession(token(request)))return true;
        String name=username(request);
        return name!=null && users.findByUsernameIgnoreCase(name).filter(AdminUser::isEnabled).isPresent();
    }
    public Set<String> keysFor(String username) {
        if("admin".equalsIgnoreCase(username))return allKeys();
        var keys=new HashSet<String>();
        for(var grant:grants.findByUsername(username))keys.add(grant.getMenuKey());
        return keys;
    }
    private Set<String> allKeys(){return menus().stream().map(Menu::key).collect(java.util.stream.Collectors.toSet());}
    public Set<String> keys(HttpServletRequest request){return superAdmin(request)?allKeys():keysFor(username(request));}
    public void require(HttpServletRequest request,String key){
        if(!enabled(request) || !keys(request).contains(key))throw new SecurityException("没有此管理功能的操作权限");
    }
    public boolean allows(HttpServletRequest request,String path,String method){
        if(!enabled(request))return false;
        if(superAdmin(request))return true;
        if(path.startsWith("/api/v1/admin/auth/") || path.equals("/api/v1/license/menus"))return true;
        Set<String> keys=keys(request);
        if(prefix(path,"/api/v1/admin/users"))return keys.contains("adminUsers");
        if(prefix(path,"/api/v1/admin/login-audits"))return keys.contains("adminLoginAudits");
        if(prefix(path,"/api/v1/license"))return keys.contains("license");
        var matched=catalog.menuForPath(path);
        if(matched.isEmpty())return false;
        var menu=matched.get();
        if(keys.contains(menu.key()))return true;
        if(menu.parentKey()!=null && keys.contains(menu.parentKey()))return true;
        // Child menus may use the shared parent APIs, without inheriting sibling APIs.
        return menus().stream().anyMatch(child->menu.key().equals(child.parentKey()) && keys.contains(child.key()));
    }
    private boolean prefix(String path,String prefix){return path.equals(prefix)||path.startsWith(prefix+"/");}
}
