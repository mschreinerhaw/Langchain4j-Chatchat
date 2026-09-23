package com.chatchat.api.model;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/platform/models")
public class PlatformModelController {
    private final PlatformModelCatalogService catalog;

    @GetMapping
    public ApiResponse<List<PlatformModelCatalogService.ModelView>> list() {
        return ApiResponse.success(catalog.list());
    }

    @PutMapping
    public ApiResponse<PlatformModelCatalogService.ModelView> save(
        @RequestBody PlatformModelCatalogService.ModelDraft draft) {
        return ApiResponse.success(catalog.save(draft));
    }

    public record ModelIdentity(String type, String name) { }

    @PostMapping("/publish")
    public ApiResponse<PlatformModelCatalogService.ModelView> publish(@RequestBody ModelIdentity identity) {
        return ApiResponse.success(catalog.publish(identity.type(), identity.name()));
    }

    @PostMapping("/default")
    public ApiResponse<Void> setDefault(@RequestBody ModelIdentity identity) {
        catalog.setDefault(identity.type(), identity.name());
        return ApiResponse.success(null);
    }

    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam("type") String type,
                                    @RequestParam("name") String name) {
        catalog.delete(type, name);
        return ApiResponse.success(null);
    }
}
