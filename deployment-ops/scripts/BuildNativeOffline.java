import javax.tools.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Run from the release root: java scripts/BuildNativeOffline.java --runtime ... --output ... */
public class BuildNativeOffline {
    static void copy(Path source,Path target)throws IOException{
        if(!Files.exists(source))return;try(var paths=Files.walk(source)){for(Path file:paths.toList()){if(Files.isSymbolicLink(file))throw new IOException("Source symlinks are forbidden");Path out=target.resolve(source.relativize(file));if(Files.isDirectory(file))Files.createDirectories(out);else{Files.createDirectories(out.getParent());Files.copy(file,out,StandardCopyOption.REPLACE_EXISTING);}}}
    }
    static void add(JarOutputStream jar,String name,byte[] bytes,boolean stored,Set<String> written)throws IOException{
        if(!written.add(name))return;ZipEntry entry=new ZipEntry(name);entry.setTime(0);if(stored){CRC32 crc=new CRC32();crc.update(bytes);entry.setMethod(ZipEntry.STORED);entry.setSize(bytes.length);entry.setCompressedSize(bytes.length);entry.setCrc(crc.getValue());}jar.putNextEntry(entry);jar.write(bytes);jar.closeEntry();
    }
    public static void main(String[] args)throws Exception{
        Map<String,String> options=new HashMap<>();for(int i=0;i<args.length;i+=2){if(i+1>=args.length||!Set.of("--runtime","--output","--version","--source-commit").contains(args[i]))throw new IllegalArgumentException("--runtime and --output required; optional --version");options.put(args[i],args[i+1]);}
        Path runtime=Path.of(Objects.requireNonNull(options.get("--runtime"))),output=Path.of(Objects.requireNonNull(options.get("--output"))).toAbsolutePath(),root=Path.of("").toAbsolutePath();
        Path mount=Path.of("/mnt/disk01");if(!mount.toRealPath().equals(mount)||Files.getAttribute(mount,"unix:dev").equals(Files.getAttribute(Path.of("/"),"unix:dev"))||Files.readAllLines(Path.of("/proc/self/mountinfo")).stream().noneMatch(line->{String[] fields=line.split(" ");return fields.length>6&&fields[4].equals(mount.toString());}))throw new IOException("Required real /mnt/disk01 mount unavailable; no system-disk fallback");
        if(!root.toRealPath().startsWith(mount)||!output.normalize().startsWith(mount))throw new IOException("Source and output must remain on the data mount");Path ancestor=output.getParent();while(!Files.exists(ancestor))ancestor=ancestor.getParent();if(!ancestor.toRealPath().startsWith(mount))throw new IOException("Output escapes the data mount");
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IllegalStateException("JDK 17+, including javac, required");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD","");factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema","");Document pom=factory.newDocumentBuilder().parse(root.resolve("pom.xml").toFile());String version=options.get("--version");if(version==null){NodeList children=pom.getDocumentElement().getChildNodes();for(int i=0;i<children.getLength();i++)if("version".equals(children.item(i).getLocalName()))version=children.item(i).getTextContent().trim();}if(version==null||!version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}"))throw new IllegalArgumentException("Native module version required");
        Path temporaryBase=mount.resolve("deployment-ops-build-tmp");Files.createDirectories(temporaryBase);if(!temporaryBase.toRealPath().equals(temporaryBase))throw new IOException("Build temporary directory cannot be a symlink");Path temporary=Files.createTempDirectory(temporaryBase,"javac-");
        try{
            Path libraries=temporary.resolve("lib"),classes=temporary.resolve("classes");Files.createDirectories(libraries);Files.createDirectories(classes);
            try(ZipFile seed=new ZipFile(runtime.toFile())){for(ZipEntry entry:Collections.list(seed.entries()))if(entry.getName().startsWith("BOOT-INF/lib/")&&entry.getName().endsWith(".jar"))try(InputStream in=seed.getInputStream(entry)){Files.copy(in,libraries.resolve(Path.of(entry.getName()).getFileName()),StandardCopyOption.REPLACE_EXISTING);}}
            List<Path> sources;try(var paths=Files.walk(root.resolve("src/main/java"))){sources=new ArrayList<>(paths.filter(p->p.toString().endsWith(".java")).toList());}sources.add(root.resolve("backend/src/Main.java"));sources.add(root.resolve("backend/src/ManagerApi.java"));String classpath;try(var paths=Files.list(libraries)){classpath=String.join(File.pathSeparator,paths.map(Path::toString).toList());}
            try(StandardJavaFileManager manager=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8)){boolean ok=compiler.getTask(null,manager,null,List.of("--release","17","-encoding","UTF-8","-classpath",classpath,"-d",classes.toString()),null,manager.getJavaFileObjectsFromPaths(sources)).call();if(!ok)throw new IllegalStateException("Compilation failed");}
            String commit=options.getOrDefault("--source-commit","unknown");if(!commit.equals("unknown")&&!commit.matches("[a-f0-9]{40}"))throw new IllegalArgumentException("Invalid source commit");copy(root.resolve("src/main/resources"),classes);Files.writeString(classes.resolve("deployment-ops-build.properties"),"version="+version+"\nsourceCommit="+commit+"\n");Files.copy(root.resolve("backend/schema.sql"),classes.resolve("schema.sql"),StandardCopyOption.REPLACE_EXISTING);copy(root.resolve("web-ui/dist"),classes.resolve("static"));copy(root.resolve("frontend"),classes.resolve("static/legacy"));copy(root.resolve("release/trust"),classes.resolve("standalone/trust"));
            Files.createDirectories(output.getParent());Path staged=Files.createTempFile(output.getParent(),"native-",".jar");
            try{
                try(ZipFile seed=new ZipFile(runtime.toFile());JarOutputStream jar=new JarOutputStream(Files.newOutputStream(staged))){Set<String> written=new HashSet<>();
                    for(ZipEntry entry:Collections.list(seed.entries()))if(!entry.getName().startsWith("BOOT-INF/classes/")){byte[] bytes;try(InputStream in=seed.getInputStream(entry)){bytes=in.readAllBytes();}if(entry.getName().equals("META-INF/MANIFEST.MF")){Manifest manifest=new Manifest(new ByteArrayInputStream(bytes));manifest.getMainAttributes().putValue("Implementation-Title","Deployment Ops");manifest.getMainAttributes().putValue("Implementation-Version",version);ByteArrayOutputStream out=new ByteArrayOutputStream();manifest.write(out);bytes=out.toByteArray();}else if(entry.getName().equals("META-INF/maven/com.chatchat/deployment-ops/pom.xml"))bytes=Files.readAllBytes(root.resolve("pom.xml"));else if(entry.getName().endsWith("com.chatchat/deployment-ops/pom.properties"))bytes=("groupId=com.chatchat\nartifactId=deployment-ops\nversion="+version+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);add(jar,entry.getName(),bytes,entry.getName().startsWith("BOOT-INF/lib/"),written);}
                    add(jar,"BOOT-INF/classes/",new byte[0],false,written);try(var paths=Files.walk(classes)){for(Path path:paths.sorted().toList()){if(path.equals(classes))continue;String name="BOOT-INF/classes/"+classes.relativize(path).toString().replace('\\','/');if(Files.isDirectory(path))add(jar,name+"/",new byte[0],false,written);else add(jar,name,Files.readAllBytes(path),false,written);}}
                }Files.move(staged,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }finally{Files.deleteIfExists(staged);}
            MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(output)){byte[] block=new byte[65536];for(int n;(n=in.read(block))!=-1;)hash.update(block,0,n);}System.out.println(HexFormat.of().formatHex(hash.digest())+"  "+output);
        }finally{try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
