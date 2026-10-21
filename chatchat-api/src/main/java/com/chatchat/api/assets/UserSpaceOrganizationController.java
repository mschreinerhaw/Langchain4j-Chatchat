package com.chatchat.api.assets;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/data-science/user-assets/browser")
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
public class UserSpaceOrganizationController {
    private final UserSpaceOrganization organization;private final ObjectAccessService access;private final UserSpaceFileBrowser browser;
    public record Create(String parentId,String name) { }
    public record Move(String folderId,List<UserSpaceOrganization.Entry> entries) { }
    private UserAssetService.Owner owner(HttpServletRequest request){return new UserAssetService.Owner(access.tenant(request),access.user(request));}
    @GetMapping public ApiResponse<?> list(HttpServletRequest http){return ApiResponse.success(organization.list(owner(http)));}
    @GetMapping("/files") public ApiResponse<?> files(HttpServletRequest http,
        @RequestParam(value="folder",defaultValue="all") String folder,@RequestParam(value="q",defaultValue="") String query,
        @RequestParam(value="sort",defaultValue="updated") String sort,@RequestParam(value="page",defaultValue="1") int page,
        @RequestParam(value="pageSize",defaultValue="25") int pageSize){
        return ApiResponse.success(browser.list(owner(http),access.ownerAliases(http),folder,query,sort,page,pageSize));
    }
    @PostMapping("/folders") public ApiResponse<?> create(HttpServletRequest http,@RequestBody Create body){
        if(body==null)throw new IllegalArgumentException("Folder is required");return ApiResponse.success(organization.create(owner(http),body.parentId(),body.name()));}
    @PutMapping("/locations") public ApiResponse<?> move(HttpServletRequest http,@RequestBody Move body){
        if(body==null)throw new IllegalArgumentException("Files are required");organization.move(owner(http),body.folderId(),body.entries(),access.ownerAliases(http));return ApiResponse.success(null);}
    @DeleteMapping("/folders/{id}") public ApiResponse<?> remove(HttpServletRequest http,@PathVariable("id") String id){organization.remove(owner(http),id,access.ownerAliases(http));return ApiResponse.success(null);}
}
