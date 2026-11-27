package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.api.assets.UserAssetService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Inventory of immutable task delivery files; no report generation or business rules. */
public final class NativeHtmlReport {
    private NativeHtmlReport(){}
    public record Resource(String path, String sha256, long size, String content) {
        public static Resource of(String path, byte[] bytes) {
            return new Resource(path, UserAssetService.hash(bytes), bytes.length, Base64.getEncoder().encodeToString(bytes));
        }
    }
    public record Bundle(UserAssetService.File entry, List<Resource> files) {}

    public static Bundle load(UserAssetService assets, UserAssetService.Owner owner, String id) {
        var entry=assets.get(owner,id);
        if(!"ARTIFACT".equals(entry.kind())||!entry.fileName().toLowerCase(Locale.ROOT).endsWith(".html")
            ||entry.taskId()==null||entry.taskId().isBlank()||entry.runId()==null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Not a delivered HTML report");
        var inventory=new ArrayList<UserAssetService.File>();
        for(int offset=0;;offset+=100){
            var page=assets.listPage(owner,null,entry.taskId(),100,offset);
            inventory.addAll(page);
            if(page.size()<100)break;
            if(offset>=10000)throw new IllegalArgumentException("Report inventory exceeds limit");
        }
        var resources=resources(entry.taskId(),entry.runId(),inventory);
        if(resources.stream().noneMatch(file->file.id().equals(id)))throw new SecurityException("Entry outside task delivery");
        var files=new ArrayList<Resource>();
        for(var file:resources){
            var resource=Resource.of(path(file),assets.read(owner,file.id()));
            if(resource.size()!=file.size()||!resource.sha256().equals(file.sha256()))
                throw new IllegalArgumentException("Report resource integrity differs");
            files.add(resource);
        }
        return new Bundle(entry,List.copyOf(files));
    }
    public static String path(UserAssetService.File file){
        if(file.sourceRef()==null||!file.sourceRef().startsWith("coding-node:"))throw new SecurityException("Not a Native delivery file");
        String[] parts=file.sourceRef().split(":",3);String path=parts.length==3?parts[2]:"";
        if(path.isBlank()||path.startsWith("/")||path.contains("\\")||Arrays.stream(path.split("/",-1)).anyMatch(s->s.isEmpty()||s.equals(".")||s.equals(".."))||path.chars().anyMatch(c->c<32))throw new SecurityException("Invalid report resource path");
        return path;
    }
    public static List<UserAssetService.File> resources(String task,String run,List<UserAssetService.File> files){
        var result=files.stream().filter(f->task.equals(f.taskId())&&run.equals(f.runId())&&Set.of("ARTIFACT","CODE").contains(f.kind())&&f.sourceRef()!=null&&f.sourceRef().startsWith("coding-node:")
            &&f.fileName().toLowerCase(Locale.ROOT).matches(".*\\.(html|js|css|json|csv|txt|png|jpg|jpeg|svg|webp|gif|avif|woff|woff2)$")).toList();
        if(result.size()>64||result.stream().anyMatch(f->f.size()<0||f.size()>8L*1024*1024)||result.stream().mapToLong(UserAssetService.File::size).sum()>24L*1024*1024)throw new IllegalArgumentException("HTML report resource budget exceeded");
        var paths=new HashSet<String>();for(var f:result)if(!paths.add(path(f)))throw new IllegalArgumentException("Duplicate report resource path");
        return result;
    }
    public static Map<String,Object> descriptor(UserAssetService.File entry){
        return Map.of("schemaVersion","runtime_html_report.v1","entryFileId",entry.id(),"entryPath",path(entry),"taskId",entry.taskId(),"runId",entry.runId());
    }
}
