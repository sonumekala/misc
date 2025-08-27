import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class JwtWithThumbprintGenerator {

    public static void main(String[] args) throws Exception {

        // --- Keystore Details ---
        String keystoreFile = "keystore.jks";
        String keystorePassword = "mysecretstorepass";
        String alias = "jwt-cert";
        String keyPassword = "mysecretkeypass";

        // 1. Load the Keystore and retrieve the private key and certificate
        InputStream is = new FileInputStream(keystoreFile);
        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        keystore.load(is, keystorePassword.toCharArray());

        PrivateKey privateKey = (PrivateKey) keystore.getKey(alias, keyPassword.toCharArray());
        X509Certificate certificate = (X509Certificate) keystore.getCertificate(alias);
        PublicKey publicKey = certificate.getPublicKey();

        if (privateKey == null || certificate == null) {
            throw new RuntimeException("Could not load key or certificate from keystore.");
        }

        // 2. Calculate the X.509 Certificate SHA-256 Thumbprint (x5t#S256)
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] thumbprintBytes = sha256.digest(certificate.getEncoded());
        String thumbprint = Base64.getUrlEncoder().withoutPadding().encodeToString(thumbprintBytes);

        System.out.println("Certificate Thumbprint (x5t#S256): " + thumbprint);

        // 3. Define JWT claims
        String subject = "user-abc-123";
        Date now = new Date();
        Date expirationDate = new Date(now.getTime() + 3600000); // 1 hour

        // Custom claims
        Map<String, Object> customClaims = new HashMap<>();
        customClaims.put("role", "user");
        customClaims.put("tenant", "my-tenant");

        // 4. Build the JWT
        String jwt = Jwts.builder()
                .header()
                    // Add the thumbprint to the header. "x5t#S256" is the standard header name.
                    .add("x5t#S256", thumbprint)
                .and()
                .subject(subject)
                .issuedAt(now)
                .expiration(expirationDate)
                .claims(customClaims)
                .signWith(privateKey) // Sign with the private key
                .compact();

        System.out.println("\nGenerated JWT: " + jwt);

        // 5. (Optional) Parse and verify the JWT to confirm it's correct
        try {
            Jws<Claims> parsedJwt = Jwts.parser()
                    .verifyWith(publicKey) // Verify using the public key
                    .build()
                    .parseSignedClaims(jwt);

            Claims claims = parsedJwt.getPayload();
            String headerThumbprint = parsedJwt.getHeader().get("x5t#S256", String.class);

            System.out.println("\n--- JWT Verification Successful ---");
            System.out.println("Header Thumbprint: " + headerThumbprint);
            System.out.println("Subject: " + claims.getSubject());
            System.out.println("Expiration: " + claims.getExpiration());
            System.out.println("Role: " + claims.get("role", String.class));

            // Extra check: ensure thumbprints match
            if (!thumbprint.equals(headerThumbprint)) {
                System.err.println("Thumbprint in header does not match calculated thumbprint!");
            }

        } catch (Exception e) {
            System.err.println("\nError parsing JWT: " + e.getMessage());
        }
    }
}
