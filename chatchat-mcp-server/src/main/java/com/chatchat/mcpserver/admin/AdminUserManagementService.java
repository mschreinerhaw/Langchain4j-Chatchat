package com.chatchat.mcpserver.admin;

import com.chatchat.common.security.InternalCredentialProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class AdminUserManagementService {
    private final AdminUserRepository users;
    private final AdminMenuGrantRepository grants;
    private final AdminPasswordStore passwords;
    private final AdminAuthService auth;
    private final AdminMenuAccessService access;
    private final InternalCredentialProperties internal;
    private final AdminLoginAuditService audits;
    public record UserView(String username,boolean enabled,boolean superAdmin,java.time.Instant createdAt,java.time.Instant updatedAt,Set<String> menuKeys){}
    public record UserPage(List<UserView> items,long total,int page,int pageSize){}
    public record Update(String username,String password,boolean enabled,Set<String> menuKeys){}
    private UserView view(AdminUser user){return new UserView(user.getUsername(),user.isEnabled(),"admin".equalsIgnoreCase(user.getUsername()),user.getCreatedAt(),user.getUpdatedAt(),access.keysFor(user.getUsername()));}
    @Transactional(readOnly=true)
    public UserPage list(HttpServletRequest request,String keyword,String status,int page,int pageSize){
        access.require(request,"adminUsers");
        String query=keyword==null?"":keyword.trim().toLowerCase(Locale.ROOT);
        int current=Math.max(1,page),size=Math.min(100,Math.max(1,pageSize));
        var matches=users.findAll().stream().filter(user->user.getUsername().toLowerCase(Locale.ROOT).contains(query))
            .filter(user->!"enabled".equals(status)||user.isEnabled()).filter(user->!"disabled".equals(status)||!user.isEnabled())
            .sorted(Comparator.comparing(AdminUser::getUsername)).toList();
        return new UserPage(matches.stream().skip((long)(current-1)*size).limit(size).map(this::view).toList(),matches.size(),current,size);
    }
    @Transactional
    public UserView create(HttpServletRequest request,Update update){
        access.require(request,"adminUsers");
        String name=update.username()==null?"":update.username().trim().toLowerCase(Locale.ROOT);
        if(!name.matches("[a-z][a-z0-9_.-]{2,63}"))throw new IllegalArgumentException("用户名需为 3-64 位字母、数字、下划线、点或短横线，以字母开头");
        if(name.equalsIgnoreCase(internal.resolvedUsername()))throw new IllegalArgumentException("该用户名为内部通信账号保留");
        if(users.findByUsernameIgnoreCase(name).isPresent())throw new IllegalArgumentException("用户名已存在");
        validatePassword(update.password());validateKeys(update.menuKeys());
        var user=passwords.newUser(name,update.password());user.setEnabled(update.enabled());users.saveAndFlush(user);
        replaceGrants(name,update.menuKeys(),access.username(request));
        audits.recordManagement(access.username(request),"create-user",name,request);
        return view(user);
    }
    @Transactional
    public UserView update(HttpServletRequest request,String username,Update update){
        access.require(request,"adminUsers");
        var user=users.lockByUsername(username).orElseThrow(()->new IllegalArgumentException("用户不存在"));
        if("admin".equalsIgnoreCase(user.getUsername()))throw new IllegalArgumentException("内置管理员不可停用或修改授权；密码请通过本人修改密码功能维护");
        validateKeys(update.menuKeys());
        boolean reset=update.password()!=null && !update.password().isEmpty();
        if(reset){validatePassword(update.password());passwords.applyPassword(user,update.password());}
        user.setEnabled(update.enabled());user.onUpdate();users.saveAndFlush(user);
        replaceGrants(user.getUsername(),update.menuKeys(),access.username(request));
        if(reset || !user.isEnabled())auth.revokeUser(user.getUsername());
        audits.recordManagement(access.username(request),reset?"reset-user-password":"update-user-access",user.getUsername(),request);
        return view(user);
    }
    private void validatePassword(String password){if(password==null || password.length()<8 || password.length()>128)throw new IllegalArgumentException("密码长度需为 8-128 位");}
    private void validateKeys(Set<String> keys){
        var known=access.menus().stream().map(AdminMenuAccessService.Menu::key).collect(java.util.stream.Collectors.toSet());
        if(keys==null || !known.containsAll(keys))throw new IllegalArgumentException("请选择有效的管理菜单权限");
    }
    private void replaceGrants(String username,Set<String> keys,String actor){
        grants.deleteByUsername(username);grants.flush();
        for(String key:keys){var grant=new AdminMenuGrant();grant.setUsername(username);grant.setMenuKey(key);grant.setGrantedBy(actor);grants.save(grant);}
        grants.flush();
    }
}
