import java.nio.file.*;import java.nio.charset.*;import java.nio.file.attribute.*;import java.security.*;import javax.crypto.*;import javax.crypto.spec.*;import java.util.*;
public class Bootstrap {
 static final Path ROOT=Path.of("/shared");
 static String env(String k){String value=System.getenv(k);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing bootstrap setting "+k);return value;}
 static void file(String path,String value)throws Exception{Path p=ROOT.resolve(path);Files.createDirectories(p.getParent());Files.writeString(p,value,StandardCharsets.UTF_8);Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));}
 static String encrypted()throws Exception{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(env("CRYPTO_KEY").getBytes(StandardCharsets.UTF_8)),"AES"),new GCMParameterSpec(128,iv));return "ENC("+Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(env("INTERNAL_SECRET").getBytes(StandardCharsets.UTF_8)))+")";}
 public static void main(String[] args)throws Exception{
  Files.createDirectories(ROOT);if(Files.exists(ROOT.resolve("bootstrap.complete")))return;
  file("crypto-key",env("CRYPTO_KEY"));file("host-mac",env("HOST_MAC"));file("coding-secret",env("CODING_SECRET"));file("monitor-secret",env("MONITOR_SECRET"));
  String common="chatchat.internal-credential.crypto-key-file=/opt/runtime-config/crypto-key\nchatchat.internal-credential.encrypted-secret="+encrypted()+"\n";
  for(String role:List.of("api","mcp","news"))file(role+"/application.properties",common);
  Files.writeString(ROOT.resolve("api/application.properties"),"chatchat.enterprise.bootstrap.admin-password="+env("OS_ADMIN_PASSWORD")+"\n",StandardOpenOption.APPEND);
  for(String path:List.of("host-mac","coding-secret","monitor-secret")){Files.setPosixFilePermissions(ROOT.resolve(path),PosixFilePermissions.fromString("r--r--r--"));}
  Files.createDirectories(Path.of("/node-data"));Files.setAttribute(Path.of("/node-data"),"unix:uid",1000);Files.setAttribute(Path.of("/node-data"),"unix:gid",1000);
  byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);byte[] hash=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(env("OS_ADMIN_PASSWORD").toCharArray(),salt,120000,256)).getEncoded();
  file("mcp-initial-user.sql","INSERT INTO mcp_admin_user(username,enabled,password_algorithm,password_iterations,password_salt,password_hash,created_at,updated_at) VALUES('admin',true,'PBKDF2WithHmacSHA256',120000,'"+Base64.getEncoder().encodeToString(salt)+"','"+Base64.getEncoder().encodeToString(hash)+"',now(),now()) ON CONFLICT(username) DO NOTHING;\n");
  Files.setAttribute(ROOT.resolve("mcp-initial-user.sql"),"unix:uid",999);Files.setAttribute(ROOT.resolve("mcp-initial-user.sql"),"unix:gid",999);Files.setPosixFilePermissions(ROOT.resolve("mcp-initial-user.sql"),PosixFilePermissions.fromString("r--------"));
  file("bootstrap.complete","complete\n");System.out.println("Environment-specific internal credentials prepared. Values are not logged.");
 }
}
