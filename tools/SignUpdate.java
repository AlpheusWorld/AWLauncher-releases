import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Build-time signing only. The private key is never included in the launcher. */
public final class SignUpdate {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("keygen")) {
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            // The PowerShell key setup captures this output and immediately protects it with DPAPI.
            System.out.println("{\"publicKey\":\"" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) +
                "\",\"privateKey\":\"" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\"}");
            return;
        }
        if (args.length != 3 || !(args[0].equals("sign") || args[0].equals("verify"))) {
            throw new IllegalArgumentException("Usage: SignUpdate sign update.json update.json.sig | verify update.json public-key.txt");
        }
        var document = Files.readAllBytes(Path.of(args[1]));
        if (document.length == 0 || document.length > 65536) throw new IllegalArgumentException("Invalid manifest size");
        var algorithm = Signature.getInstance("Ed25519");
        var factory = KeyFactory.getInstance("Ed25519");
        if (args[0].equals("sign")) {
            var encoded = System.getenv("AW_UPDATE_SIGNING_PRIVATE_KEY");
            if (encoded == null || encoded.isBlank()) throw new IllegalStateException("The release signing key is not configured");
            var key = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())));
            algorithm.initSign(key);
            algorithm.update(document);
            Files.writeString(Path.of(args[2]), Base64.getEncoder().encodeToString(algorithm.sign()) + "\n");
            System.out.println("Update manifest signed");
        } else {
            var encoded = Files.readString(Path.of(args[2])).trim();
            var key = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            algorithm.initVerify(key);
            algorithm.update(document);
            var signed = Files.readString(Path.of(args[1] + ".sig")).trim();
            if (!algorithm.verify(Base64.getDecoder().decode(signed))) throw new SecurityException("Invalid update signature");
            System.out.println("Update manifest signature verified");
        }
    }
}
