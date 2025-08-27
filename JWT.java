import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

public class ClientAssertionGenerator {

    public static void main(String[] args) throws Exception {

        // --- Configuration ---
        // Details for loading your private key and certificate
        String keystoreFile = "keystore.jks";
        String keystorePassword = "mysecretstorepass";
        String alias = "jwt-cert";
        String keyPassword = "mysecretkeypass";

        // The identifier for your client application. This will be the Issuer.
        String clientId = "your-client-id-goes-here";

        // The URL of the API/token endpoint you want to access. This is the Audience.
        String targetEndpointUrl = "https://api.someservice.com/oauth/token";

        // --- 1. Load Keystore to get Private Key and Certificate ---
        InputStream is = new FileInputStream(keystoreFile);
        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        keystore.load(is, keystorePassword.toCharArray());

        PrivateKey privateKey = (PrivateKey) keystore.getKey(alias, keyPassword.toCharArray());
        X509Certificate certificate = (X509Certificate) keystore.getCertificate(alias);
        PublicKey publicKey = certificate.getPublicKey();

        if (privateKey == null || certificate == null) {
            throw new RuntimeException("Could not load key or certificate from keystore.");
        }

        // --- 2. Calculate Certificate Thumbprint (for the header) ---
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] thumbprintBytes = sha256.digest(certificate.getEncoded());
        String thumbprint = Base64.getUrlEncoder().withoutPadding().encodeToString(thumbprintBytes);

        // --- 3. Build the JWT with Specific Claims ---
        Date now = new Date();
        // These tokens are short-lived. 5 minutes is a common expiration time.
        Date expirationDate = new Date(now.getTime() + 300_000); // 5 minutes

        String jwt = Jwts.builder()
                .header()
                    // x5t#S256 is the standard header for SHA-256 Certificate Thumbprint
                    .add("x5t#S256", thumbprint)
                .and()
                // iss (Issuer): Your client application's unique identifier.
                .issuer(clientId)
                // sub (Subject): The subject is also your client_id in this context.
                .subject(clientId)
                // aud (Audience): The token endpoint URL you are calling. THIS IS CRITICAL.
                .audience().add(targetEndpointUrl)
                .and()
                // jti (JWT ID): A unique ID for the token to prevent replay attacks.
                .id(UUID.randomUUID().toString())
                // iat (Issued At): The time the token was created.
                .issuedAt(now)
                // exp (Expiration Time): When the token expires.
                .expiration(expirationDate)
                // Sign with your private key using RS256 algorithm.
                .signWith(privateKey)
                .compact();

        System.out.println("Generated Client Assertion JWT:");
        System.out.println(jwt);

        // --- 4. (Verification Simulation) How the Target Endpoint Would Verify This JWT ---
        System.out.println("\n--- Simulating Verification by the Target Endpoint ---");
        try {
            Jws<Claims> parsedJwt = Jwts.parser()
                    .verifyWith(publicKey) // 1. Verify the signature with the public key
                    .requireAudience(targetEndpointUrl) // 2. CRITICAL: Enforce the audience
                    .requireIssuer(clientId) // 3. (Good practice) Enforce the issuer
                    .build()
                    .parseSignedClaims(jwt);

            System.out.println("Verification SUCCESSFUL!");
            System.out.println("Audience: " + parsedJwt.getPayload().getAudience());
            System.out.println("Issuer: " + parsedJwt.getPayload().getIssuer());
            System.out.println("JWT ID: " + parsedJwt.getPayload().getId());

        } catch (JwtException e) {
            System.err.println("Verification FAILED: " + e.getMessage());
        }
    }
}```

### Key Changes and Explanation:

1.  **Audience (`aud`) Claim**:
    *   `.audience().add(targetEndpointUrl)` is the most important line for your use case.
    *   It explicitly states that this JWT is intended *only* for the API or service located at `targetEndpointUrl`. This prevents an attacker from taking this JWT and using it to authenticate against a different API.

2.  **Issuer (`iss`) and Subject (`sub`) Claims**:
    *   In a client assertion flow, both the issuer and the subject are typically set to your `clientId`.
    *   `issuer(clientId)`: "I (`clientId`) created this token."
    *   `subject(clientId)`: "This token is about (`clientId`)."

3.  **JWT ID (`jti`) Claim**:
    *   `.id(UUID.randomUUID().toString())`
    *   This adds a unique, random identifier to every JWT you generate. The receiving server can (and should) keep a short-term record of `jti` values it has already seen to prevent an attacker from "replaying" a stolen token.

4.  **Short Expiration**:
    *   Notice the expiration is set to 5 minutes (`300_000` milliseconds). Client assertion tokens are meant to be generated just-in-time and used immediately, so they should have a very short lifespan to limit their exposure if they are ever compromised.

### How to Use It

The generated JWT string is what you send to the service provider, usually as a parameter in a `POST` request to their token endpoint (e.g., as `client_assertion`).
