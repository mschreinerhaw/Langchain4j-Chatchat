package com.chatchat.common.runtime.coding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A bounded source-input adapter for the existing single-script Sandbox contract. Never executes code in Java. */
public final class PythonCodeProject {
    public static final String VERSION="runtime_os.python.code-project.v1";
    public static final String MARKER="# "+VERSION+"\n";
    public static final String TOKEN="__RUNTIME_PROJECT_PAYLOAD__";
    public static final int MAX_BYTES=128_000,MAX_FILES=32;
    private PythonCodeProject() { }
    public record File(String path,String content) { }
    public record Project(String entryPath,List<File> files) { }
    public static String path(String value) {
        if(value==null||value.length()>240||!value.endsWith(".py")||value.startsWith("/")||value.contains("\\")||value.contains(":"))throw new IllegalArgumentException("Invalid Python project path");
        for(String part:value.split("/",-1))if(!part.matches("[\\p{L}\\p{N}_][\\p{L}\\p{N}_.-]{0,100}")||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("Invalid Python project path");
        return value;
    }
    public static boolean diagnosticPath(String value) {
        if(value==null||!value.startsWith("project/"))return false;
        try{path(value.substring("project/".length()));return true;}catch(IllegalArgumentException invalid){return false;}
    }
    public static Project validate(Project project) {
        if(project==null||project.files()==null||project.files().isEmpty()||project.files().size()>MAX_FILES)throw new IllegalArgumentException("Bounded Python project required");
        String entry=path(project.entryPath());var names=new HashSet<String>();int bytes=0;
        for(var file:project.files()) {
            if(file==null||file.content()==null||!names.add(path(file.path()).toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Duplicate or missing Python file");
            bytes+=file.content().getBytes(StandardCharsets.UTF_8).length;
            if(bytes>MAX_BYTES)throw new IllegalArgumentException("Python project exceeds source budget");
        }
        if(project.files().stream().noneMatch(file->file.path().equals(entry)))throw new IllegalArgumentException("Python entry file missing");
        return new Project(entry,project.files().stream().sorted(Comparator.comparing(File::path)).toList());
    }
    public static String template() {
        return resource("python-project-adapter.py");
    }
    private static String resource(String name) {
        try(var input=PythonCodeProject.class.getResourceAsStream("/runtime/coding/"+name)) {
            if(input==null)throw new IllegalStateException("Python project adapter unavailable");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }catch(java.io.IOException e){throw new IllegalStateException("Cannot read project adapter",e);}
    }
    public static Optional<String> decode(String source) {
        if(source==null||!source.startsWith(MARKER))return Optional.empty();
        var match=java.util.regex.Pattern.compile("(?m)^__project_b64__ = \"([A-Za-z0-9+/=]{1,250000})\"$").matcher(source);
        if(!match.find())throw new SecurityException("Invalid project adapter payload");
        String encoded=match.group(1);
        if(!templates().stream().anyMatch(template->template.replace(TOKEN,encoded).equals(source)))throw new SecurityException("Project adapter differs from Runtime template");
        try{return Optional.of(new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8));}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid project payload",e);}
    }
    public static Optional<String> decode(String source,Object frozenContext) {
        fromContext(frozenContext);
        var payload=decode(source);
        if(payload.isPresent()) {
            String encoded=Base64.getEncoder().encodeToString(payload.get().getBytes(StandardCharsets.UTF_8));
            String adapter=(String)((Map<?,?>)frozenContext).get("adapterTemplate");
            if(!adapter.replace(TOKEN,encoded).equals(source))throw new SecurityException("Execution adapter differs from the authorized task snapshot");
        }
        return payload;
    }
    public static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static Map<String,Object> context(Project project,java.util.function.Function<Project,String> encoder) {
            String payload=encoder.apply(validate(project));var chunks=new ArrayList<String>();
            for(int start=0;start<payload.length();start+=8000)chunks.add(payload.substring(start,Math.min(payload.length(),start+8000)));
            return Map.of("schemaVersion",VERSION,"sha256",hash(payload),"payloadChunks",chunks,"adapterTemplate",template());
    }
    public static String fromContext(Object value) {
        if(!(value instanceof Map<?,?> context)||!context.keySet().equals(Set.of("schemaVersion","sha256","payloadChunks","adapterTemplate"))
            ||!VERSION.equals(context.get("schemaVersion"))||!templates().contains(context.get("adapterTemplate"))
            ||!(context.get("payloadChunks") instanceof List<?> chunks)||chunks.isEmpty()||chunks.size()>40)
            throw new IllegalArgumentException("Invalid Python project context");
        var payload=new StringBuilder();for(var chunk:chunks){if(!(chunk instanceof String text)||text.length()>8000)throw new IllegalArgumentException("Invalid project chunk");payload.append(text);}
        if(!hash(payload.toString()).equals(context.get("sha256")))throw new SecurityException("Python project digest mismatch");
        return payload.toString();
    }
    private static List<String> templates(){return List.of(template(),resource("python-project-adapter.legacy-output-v1.py"));}
}
