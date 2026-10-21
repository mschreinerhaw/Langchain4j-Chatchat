package com.chatchat.api.assets;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/v1/data-science/user-assets","/api/v1/interactions/user-assets"})
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
public class UserAssetController {
    private final UserAssetService assets;
    private final ObjectAccessService access;
    private final AssetStorageProperties limits;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.chatchat.api.datascience.python.PythonDataScienceService> python;
    private UserAssetService.Owner owner(HttpServletRequest request){return new UserAssetService.Owner(access.tenant(request),access.user(request));}
    @GetMapping("/space") public ApiResponse<?> space(HttpServletRequest request){return ApiResponse.success(assets.space(owner(request)));}
    @GetMapping public ApiResponse<?> list(HttpServletRequest request,
        @RequestParam(value="kind",required=false) UserAssetService.Kind kind,@RequestParam(value="taskId",required=false) String taskId,
        @RequestParam(value="limit",defaultValue="50") int limit,@RequestParam(value="offset",defaultValue="0") int offset,
        @RequestParam(value="personalOnly",defaultValue="true") boolean personalOnly){return ApiResponse.success(assets.listPage(owner(request),kind,taskId,limit,offset,personalOnly));}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(HttpServletRequest request,@RequestParam("file") MultipartFile file)throws java.io.IOException {
        var owner=owner(request);
        if(file.getSize()>limits.getMaxFileBytes())throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Asset exceeds file limit");
        byte[] data;
        try(var in=file.getInputStream()){data=in.readNBytes(Math.toIntExact(limits.getMaxFileBytes()+1));}
        return ApiResponse.success(assets.upload(owner,file.getOriginalFilename(),data));
    }
    @GetMapping("/{id}") public ApiResponse<?> info(HttpServletRequest request,@PathVariable("id") String id){return ApiResponse.success(assets.get(owner(request),id));}
    @GetMapping("/{id}/content") public ResponseEntity<byte[]> download(HttpServletRequest request,@PathVariable("id") String id){
        var owner=owner(request);var file=assets.get(owner,id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.fileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .body(assets.read(owner,id));
    }
    @DeleteMapping("/{id}") public ApiResponse<?> delete(HttpServletRequest request,@PathVariable("id") String id){
        var owner=owner(request);var file=assets.get(owner,id);
        if(file.taskId()==null || file.taskId().isBlank()){
            var service=python==null?null:python.getIfAvailable();if(service!=null)service.removeUserSpaceData(owner.tenant(),access.legacyUser(request),id);
        }
        assets.delete(owner,id);return ApiResponse.success(null);
    }
}
