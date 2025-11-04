package es.hargos.ritrack.jwt_token;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtGenerator {

    @Value("${api.client-id}")
    private String clientId;

    @Value("${api.key-id}")
    private String keyId;

    @Value("${api.audience-url}")
    private String audienceUrl;

    @Value("${api.private-key-path}")
    private String privateKeyPath;

    private RSAPrivateKey loadPrivateKey(String privateKeyPath) throws Exception {
        byte[] keyBytes = Files.readAllBytes(Path.of(privateKeyPath));
        String keyPem = new String(keyBytes)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(keyPem);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    public String generateClientAssertion() throws Exception {
        JWSSigner signer = new RSASSASigner(loadPrivateKey(privateKeyPath));

        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .audience(audienceUrl)
                .issuer(clientId)
                .subject(clientId)
                .jwtID(UUID.randomUUID().toString())
                .expirationTime(java.util.Date.from(now.plusSeconds(60)))
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(keyId)
                .type(JOSEObjectType.JWT)
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(signer);

        return signedJWT.serialize();
    }
}