package com.chatchat.mcpserver.python;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.NativeCodeWorkspace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Optional native-code view over an already authorized immutable input. No host command execution. */
final class NativeProjectSnapshot {
    static final String PARAMETER="_runtime_native_project";
    static final String DIRECTORY="native-project";
    private NativeProjectSnapshot(){}
    static void materialize(Path root,PythonOperationWorkspace.Snapshot snapshot,Map<String,Object> parameters,
            String tenant,String owner,String run,String task,ObjectMapper json)throws IOException {
        if(!parameters.containsKey(PARAMETER))return;
        if(run==null||task==null)throw new SecurityException("Native projects require a managed task identity");
        var tree=tree(root,snapshot,parameters,json);
        var files=files(tree,json);var manifest=map(tree.get("manifest"),json);
        NativeCodeWorkspace.verify(new KernelDataScope(tenant,owner,null,null,run,null,Map.of()),task,manifest,files);
        Path directory=Files.createDirectory(root.resolve("scripts").resolve(DIRECTORY));
        for(var entry:files.entrySet()){
            Path target=directory.resolve(NativeCodeWorkspace.path(entry.getKey()));
            Files.createDirectories(target.getParent());Files.writeString(target,entry.getValue(),StandardOpenOption.CREATE_NEW);
        }
        verify(root,snapshot,parameters,json);
    }
    static void verify(Path root,PythonOperationWorkspace.Snapshot snapshot,Map<String,Object> parameters,ObjectMapper json)throws IOException {
        Path directory=root.resolve("scripts").resolve(DIRECTORY);
        if(!parameters.containsKey(PARAMETER)){
            if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("Unbound native project mount");
            return;
        }
        var tree=tree(root,snapshot,parameters,json);var expected=files(tree,json);
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory))throw new SecurityException("Invalid native code mount");
        var actual=new TreeMap<String,String>();
        try(var walk=Files.walk(directory)){
            for(Path path:walk.toList()){
                if(Files.isSymbolicLink(path))throw new SecurityException("Native code link denied");
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("Native code file type denied");
                if(Files.getFileStore(path).supportsFileAttributeView("unix")&&((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()>1)
                    throw new SecurityException("Native code hard link denied");
                actual.put(NativeCodeWorkspace.path(directory.relativize(path).toString().replace('\\','/')),Files.readString(path));
            }
        }
        if(!actual.equals(expected))throw new SecurityException("Native code mount checksum mismatch");
    }
    private static Map<String,Object> tree(Path root,PythonOperationWorkspace.Snapshot snapshot,Map<String,Object> parameters,ObjectMapper json)throws IOException {
        var binding=map(parameters.get(PARAMETER),json);
        if(!binding.keySet().equals(Set.of("fileId","sha256","manifestHash")))throw new SecurityException("Invalid native project descriptor");
        var input=snapshot.inputs().stream().filter(f->f.fileId().equals(binding.get("fileId"))&&f.sha256().equals(binding.get("sha256")))
            .findFirst().orElseThrow(()->new SecurityException("Native project input is not authorized"));
        Path source=root.resolve("input").resolve(input.fileId()).resolve(input.fileName());
        if(Files.isSymbolicLink(source)||Files.isSymbolicLink(source.getParent())||Files.size(source)>2097152)throw new SecurityException("Invalid native project input");
        byte[] bytes=Files.readAllBytes(source);
        if(!NativeCodeWorkspace.sha(bytes).equals(input.sha256()))throw new SecurityException("Native project input checksum mismatch");
        var tree=json.readValue(bytes,new TypeReference<LinkedHashMap<String,Object>>(){});
        if(!Objects.equals(map(tree.get("manifest"),json).get("sha256"),binding.get("manifestHash")))throw new SecurityException("Native manifest binding differs");
        return tree;
    }
    private static Map<String,Object> map(Object value,ObjectMapper json){
        if(!(value instanceof Map<?,?>))throw new SecurityException("Native project object required");
        return json.convertValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});
    }
    private static Map<String,String> files(Map<String,Object> tree,ObjectMapper json){
        return NativeCodeWorkspace.files(json.convertValue(tree.get("files"),new TypeReference<LinkedHashMap<String,String>>(){}));
    }
}
