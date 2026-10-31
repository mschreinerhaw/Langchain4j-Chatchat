package com.chatchat.chat.skills.domain.artifact;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Read-only inspection of an original Agent Skills package. Never rewrites or executes files. */
public final class NativeSkillPackage {
    private NativeSkillPackage() {}
    public record Inspection(String entryPath, String markdown, String name, String description,
                             String version, String license, String grade, List<String> issues,
                             List<String> risks, Map<String, byte[]> files) {
        public boolean loadable() { return !grade.equals("C") && risks.isEmpty()
            && issues.stream().noneMatch(i -> i.startsWith("Missing local reference:") || i.startsWith("Unsafe local reference:")); }
        public Map<String,Object> metadata() {
            return Map.of("entryPath",entryPath,"name",name,"description",description,"version",version,
                "license",license,"grade",grade,"issues",issues,"risks",risks,"loadable",loadable(),
                "files",files.entrySet().stream().map(e -> Map.of("path",e.getKey(),"sha256",sha256(e.getValue()),
                    "size",e.getValue().length)).toList());
        }
        public String root() { int slash=entryPath.lastIndexOf('/'); return slash<0?"":entryPath.substring(0,slash+1); }
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("Skill text must be valid UTF-8",e); }
    }
    public static String safePath(String path) {
        if (path==null || path.length()>512 || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.contains(":") || path.chars().anyMatch(c -> c<32 || c==127)
                || Arrays.stream(path.split("/",-1)).anyMatch(s -> s.equals("..") || s.equals(".") || s.isEmpty()))
            throw new IllegalArgumentException("Unsafe Skill package path");
        return path;
    }
    public static Map<String,byte[]> unzip(byte[] bytes) {
        // Inspect central-directory Unix modes as ZipInputStream hides symlink attributes.
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end=-1;
        for(int p=bytes.length-22;p>=Math.max(0,bytes.length-65557);p--)
            if(b.getInt(p)==0x06054b50 && p+22+Short.toUnsignedInt(b.getShort(p+20))==bytes.length) { end=p; break; }
        if(end<0 || b.getShort(end+4)!=0 || b.getShort(end+6)!=0) throw new IllegalArgumentException("Invalid or multipart ZIP");
        int count=Short.toUnsignedInt(b.getShort(end+10));
        long offset=Integer.toUnsignedLong(b.getInt(end+16));
        if(count>500 || offset>end) throw new IllegalArgumentException("Skill ZIP entry limit exceeded");
        int p=(int)offset;
        var central=new HashSet<String>();
        for(int i=0;i<count;i++) {
            if(p+46>end || b.getInt(p)!=0x02014b50) throw new IllegalArgumentException("Invalid ZIP directory");
            int flags=Short.toUnsignedInt(b.getShort(p+8));
            int mode=(int)(Integer.toUnsignedLong(b.getInt(p+38))>>>16)&0170000;
            if((flags&1)!=0 || mode!=0 && mode!=0100000 && mode!=0040000)
                throw new IllegalArgumentException("Encrypted, symlink or special Skill ZIP entry is forbidden");
            int n=Short.toUnsignedInt(b.getShort(p+28)), extra=Short.toUnsignedInt(b.getShort(p+30)), comment=Short.toUnsignedInt(b.getShort(p+32));
            if(p+46+n+extra+comment>end) throw new IllegalArgumentException("Invalid ZIP directory entry");
            String path=utf8(Arrays.copyOfRange(bytes,p+46,p+46+n));
            safePath(path.endsWith("/")?path.substring(0,path.length()-1):path);
            if(!central.add(path)) throw new IllegalArgumentException("Duplicate Skill ZIP path");
            p+=46+n+extra+comment;
        }
        if(p!=end) throw new IllegalArgumentException("Unsupported ZIP directory trailer");
        var files=new LinkedHashMap<String,byte[]>(); var local=new HashSet<String>(); int total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)) {
            ZipEntry e;
            while((e=zip.getNextEntry())!=null) {
                if(!central.contains(e.getName()) || !local.add(e.getName())) throw new IllegalArgumentException("Inconsistent ZIP paths");
                String path=e.getName(); safePath(e.isDirectory()?path.substring(0,path.length()-1):path);
                var out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int read;
                while((read=zip.read(buffer))!=-1) {
                    total+=read;
                    if(out.size()+read>2*1024*1024 || total>16*1024*1024) throw new IllegalArgumentException("Skill ZIP expanded size limit exceeded");
                    out.write(buffer,0,read);
                }
                if(!e.isDirectory()) files.put(path,out.toByteArray());
            }
        } catch(IOException e) { throw new IllegalArgumentException("Unable to read Skill ZIP",e); }
        if(!local.equals(central)) throw new IllegalArgumentException("Incomplete ZIP directory");
        return Collections.unmodifiableMap(files);
    }
    public static Inspection inspect(byte[] original, boolean zipped) {
        Map<String,byte[]> files=zipped?unzip(original):Map.of("SKILL.md",original.clone());
        var issues=new ArrayList<String>(); var risks=new ArrayList<String>();
        var entries=files.keySet().stream().filter(p -> p.equals("SKILL.md") || p.endsWith("/SKILL.md")).toList();
        if(entries.size()!=1) return new Inspection("","","","","","","C",List.of("Require exactly one SKILL.md; select one skill directory"),List.of(),files);
        String entry=entries.get(0), markdown;
        try { markdown=utf8(files.get(entry)); }
        catch(IllegalArgumentException e) { return new Inspection(entry,"","","","","","C",List.of(e.getMessage()),List.of(),files); }
        Map<?,?> fm=Map.of(); String name="",description="",version="",license=""; boolean invalid=false;
        try {
            String parsed=markdown.startsWith("\uFEFF")?markdown.substring(1):markdown;
            var matcher=Pattern.compile("\\A---\\r?\\n(.*?)\\r?\\n---(?:\\r?\\n|\\z)",Pattern.DOTALL).matcher(parsed);
            if(!matcher.find() || matcher.group(1).length()>65536) throw new IllegalArgumentException("Missing or oversized YAML frontmatter");
            var options=new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(10); options.setCodePointLimit(65536);
            Object value=new Yaml(new SafeConstructor(options)).load(matcher.group(1));
            if(!(value instanceof Map<?,?> map)) throw new IllegalArgumentException("Frontmatter must be a mapping"); fm=map;
            name=scalar(fm,"name"); description=scalar(fm,"description"); license=scalar(fm,"license");
            version=fm.get("metadata") instanceof Map<?,?> meta?scalar(meta,"version"):"";
            if(version.isEmpty()) version=scalar(fm,"version");
            if(name.length()>64 || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new IllegalArgumentException("Invalid skill name");
            if(description.isBlank() || description.length()>1024) throw new IllegalArgumentException("Invalid skill description");
            if(zipped && entry.contains("/") && !entry.substring(0,entry.lastIndexOf('/')).endsWith("/"+name)
                    && !entry.substring(0,entry.lastIndexOf('/')).equals(name)) throw new IllegalArgumentException("Skill name differs from directory");
            if(!scalar(fm,"compatibility").isEmpty() && scalar(fm,"compatibility").length()>500) throw new IllegalArgumentException("Compatibility exceeds 500 characters");
            if(matcher.end()>=parsed.length() || parsed.substring(matcher.end()).isBlank()) throw new IllegalArgumentException("Empty skill instructions");
        } catch(RuntimeException e) { invalid=true; issues.add("Invalid SKILL.md metadata: "+e.getMessage()); }
        String root=entry.contains("/")?entry.substring(0,entry.lastIndexOf('/')+1):"";
        var active=files.entrySet().stream().filter(e -> e.getKey().startsWith(root) && !e.getKey().equals(entry)).toList();
        if(markdown.length()>72000 || active.size()>64 || active.stream().anyMatch(e -> e.getValue().length>24000)
                || active.stream().mapToLong(e -> ((long)e.getValue().length*4+2)/3 + e.getKey().length()+180).sum()+markdown.length()*6L>250000)
            risks.add("TASK_DISCLOSURE_BUDGET_EXCEEDED: original retained; cannot load atomically into the existing task snapshot");
        // Validate local Markdown resource links and plain references/scripts/assets paths without rewriting them.
        var links=Pattern.compile("(?:\\]\\(([^)]+)\\)|(?<![\\w/])((?:references|scripts|assets)/[\\w./-]*[\\w-]))").matcher(markdown);
        while(links.find()) {
            String link=Objects.toString(links.group(1),links.group(2));
            if(link.contains("://") || link.startsWith("#") || link.startsWith("mailto:")) continue;
            link=link.split("[#? ]",2)[0]; if(link.startsWith("./")) link=link.substring(2);
            try { safePath(link); if(!files.containsKey(root+link)) issues.add("Missing local reference: "+link); }
            catch(IllegalArgumentException e) { issues.add("Unsafe local reference: "+link); }
        }
        var licenseFiles=files.keySet().stream().filter(p -> p.substring(p.lastIndexOf('/')+1).matches("(?i)(LICENSE|COPYING)(\\..*)?")).toList();
        if(license.isBlank() && licenseFiles.isEmpty()) risks.add("LICENSE_UNDECLARED");
        if(license.isBlank() && !licenseFiles.isEmpty()) license="Bundled: "+String.join(", ",licenseFiles);
        for(var e:files.entrySet()) {
            String path=e.getKey();
            if(!path.startsWith(root)) continue; // Preserve surrounding license files, but never activate other directories.
            if(path.matches("(?i).*\\.(md|txt|py|sh|ps1|js|ts|json|ya?ml|toml|cfg|ini|requirements)$") || path.endsWith("requirements.txt")) {
                String text;
                try { text=utf8(e.getValue()); }
                catch(IllegalArgumentException ex) { invalid=true; issues.add("Invalid UTF-8: "+path); continue; }
                if(Pattern.compile("(?i)(curl|wget)\\s[^\\n]*|pip\\s+install|npm\\s+install|subprocess\\.|os\\.system|eval\\(|exec\\(|rm\\s+-[rf]+|\\.ssh/|\\.aws/|os\\.environ|read_(?:file|text)\\([^\\n]*(?:/etc|/root)|https?://[^\\s]+\\s*\\|\\s*(?:sh|bash)").matcher(text).find())
                    risks.add("EXECUTION_OR_SENSITIVE_ACCESS_REVIEW: "+path);
                if(Pattern.compile("(?i)\\b(claude_code|claude-code|codex|copilot|mcp__|hooks?|allowed-tools|context:\\s*fork)\\b").matcher(text).find())
                    issues.add("Vendor tool or environment dependency requires adaptation: "+path);
                if(path.endsWith("requirements.txt") || path.endsWith("package.json")) risks.add("DEPENDENCY_REVIEW: "+path);
            }
        }
        if(fm.keySet().stream().anyMatch(k -> Set.of("allowed-tools","hooks","context","agent","disable-model-invocation").contains(k)))
            issues.add("Vendor-specific declarations are retained; do not grant tools or hooks");
        String grade=invalid?"C":issues.isEmpty()?"A":"B";
        return new Inspection(entry,markdown,name,description,version,license,grade,List.copyOf(new LinkedHashSet<>(issues)),
            List.copyOf(new LinkedHashSet<>(risks)),files);
    }
    private static String scalar(Map<?,?> map,String key) {
        Object value=map.get(key); if(value==null) return "";
        if(!(value instanceof String)) throw new IllegalArgumentException(key+" must be text"); return value.toString();
    }
}
