import java.nio.file.*;
import java.security.*;

/** Source-file launcher on CI Java 17; secret material is never written to stdout. */
class CatalogSigner {
    public static void main(String[] args) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        char[] password = System.getenv("ANDROID_KEYSTORE_PASSWORD").toCharArray();
        try (var input = Files.newInputStream(Path.of(System.getenv("ANDROID_KEYSTORE_PATH")))) { store.load(input,password); }
        var cert = store.getCertificate("dpipilot");
        var fingerprint = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        if (!fingerprint.equals(Files.readString(Path.of("signing/certificate-sha256.txt")).trim())) throw new SecurityException("certificate mismatch");
        var signature = Signature.getInstance("SHA256withRSA");
        byte[] payload = Files.readAllBytes(Path.of(args[1]));
        if (args[0].equals("sign")) signature.initSign((PrivateKey)store.getKey("dpipilot",password));
        else if (args[0].equals("verify")) signature.initVerify(cert.getPublicKey());
        else throw new IllegalArgumentException("mode");
        java.util.Arrays.fill(password,'\0');
        signature.update("DPI-PILOT-TV-CATALOG-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        signature.update(payload);
        if (args[0].equals("sign")) {
            byte[] signed = signature.sign();
            signature.initVerify(cert.getPublicKey());
            signature.update("DPI-PILOT-TV-CATALOG-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); signature.update(payload);
            if (!signature.verify(signed)) throw new SecurityException("self verification failed");
            Files.write(Path.of(args[2]),signed);
        } else if (!signature.verify(Files.readAllBytes(Path.of(args[2])))) throw new SecurityException("signature mismatch");
    }
}
