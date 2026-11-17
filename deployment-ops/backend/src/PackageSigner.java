import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;
/** Offline release signing only; private key never enters runtime packages. */
public class PackageSigner {
 public static void main(String[] args)throws Exception{
  if(args.length<1)throw new IllegalArgumentException("keygen <private.der> <public.json> <key-id> | sign <private.der> <checksums.json> <signature.json> <key-id>");
  if(args[0].equals("keygen")){
   if(args.length!=4||Files.exists(Path.of(args[1]))||Files.exists(Path.of(args[2])))throw new IllegalArgumentException("New key paths and key ID required; existing keys are never overwritten");
   var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);var keys=generator.generateKeyPair();
   Files.write(Path.of(args[1]),keys.getPrivate().getEncoded(),StandardOpenOption.CREATE_NEW);
   try{Files.setPosixFilePermissions(Path.of(args[1]),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
   var pub=(RSAPublicKey)keys.getPublic();Files.write(Path.of(args[2]),Main.J.writeValueAsBytes(Map.of("keyId",args[3],"n",pub.getModulus().toString(16),"e",pub.getPublicExponent().toString(16))));
  }else if(args[0].equals("sign")){
   if(args.length!=5)throw new IllegalArgumentException("Signing arguments missing");
   var key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Path.of(args[1]))));
   Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(key);signature.update(Files.readAllBytes(Path.of(args[2])));
   Files.write(Path.of(args[3]),Main.J.writeValueAsBytes(Map.of("keyId",args[4],"signature",Base64.getEncoder().encodeToString(signature.sign()))));
  }else throw new IllegalArgumentException("Unknown signing action");
 }
}
