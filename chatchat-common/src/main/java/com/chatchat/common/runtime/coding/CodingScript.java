package com.chatchat.common.runtime.coding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/** Runtime-owned immutable script identity; paths are container paths, never host capabilities. */
public record CodingScript(String fileName, String scriptPath, String workingDirectory, int scriptVersion) {
    public static final String LEGACY_FILE = "main.py";
    public CodingScript {
        CodingProtocol.require(safeFileName(fileName) && scriptVersion > 0
            && fileName.endsWith("_v"+scriptVersion+".py"), "Invalid script identity");
        CodingProtocol.require(("/workspace/scripts/" + fileName).equals(scriptPath), "Script path is outside its mount");
        CodingProtocol.require(workingDirectory != null && workingDirectory.matches("tasks/[0-9a-f]{16}/[0-9a-f]{16}/[0-9a-f]{16}/[0-9a-f]{24}"), "Invalid script workspace");
    }
    public static boolean safeFileName(String name) {
        return name != null && name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}_.-]{0,170}\\.py")
            && name.getBytes(StandardCharsets.UTF_8).length <= 200 && !name.contains("..");
    }
    public static CodingScript allocate(CodingTaskContract contract, Instant at, int version) {
        String goal=contract.runtimeContext().get("scriptPurpose") instanceof String purpose?purpose:contract.objective().instruction();
        int start=goal.indexOf("User objective:\n");
        if(start>=0)goal=goal.substring(start+"User objective:\n".length());
        goal=goal.split("\\R",2)[0];
        StringBuilder semantic=new StringBuilder();
        goal.codePoints().limit(96).forEach(cp->{
            if(semantic.codePointCount(0,semantic.length())<24) {
                // New generated names are ASCII only; safeFileName still reads historical Unicode versions.
                if(cp<128 && Character.isLetterOrDigit(cp))semantic.appendCodePoint(Character.toLowerCase(cp));
                else if(!semantic.isEmpty() && semantic.charAt(semantic.length()-1)!='-')semantic.append('-');
            }
        });
        String slug=semantic.toString().replaceAll("-+$", "");
        if(slug.isBlank())slug="analysis";
        String stamp=DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC).format(at);
        String name=slug+"_"+stamp+"_"+digest(contract.taskId(),12)+"_v"+version+".py";
        var scope=contract.scope();
        String directory=workspace(scope.tenantId(),scope.userId(),scope.runId(),contract.taskId());
        return new CodingScript(name,"/workspace/scripts/"+name,directory,version);
    }
    public static String workspace(String tenant,String owner,String run,String task) {
        return "tasks/"+digest(tenant,16)+"/"+digest(owner,16)+"/"+digest(run,16)+"/"+digest(task,24);
    }
    private static String digest(String text,int length) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0,length);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
