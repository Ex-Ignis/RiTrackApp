package es.hargos.ritrack.jwt_token;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TokenService {

    @Value("${api.token-url}")
    private String tokenUrl;

    private final JwtGenerator jwtGenerator;
    private final RestTemplate restTemplate = new RestTemplate();

    private final AtomicReference<String> currentToken = new AtomicReference<>();
    private long tokenExpiryTime = 0;

    public TokenService(JwtGenerator jwtGenerator) {
        this.jwtGenerator = jwtGenerator;
    }

    public String getAccessToken() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        // Seteo de expiración a falta de 60 segundos
        if (currentToken.get() == null || now >= tokenExpiryTime - 60) {
            refreshToken();
        }
        return currentToken.get();
    }

    private void refreshToken() throws Exception {
        String clientAssertion = jwtGenerator.generateClientAssertion();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String body = "grant_type=client_credentials" +
                "&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer" +
                "&client_assertion=" + clientAssertion;

        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, entity, Map.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            Map<String, Object> map = response.getBody();
            currentToken.set((String) map.get("access_token"));
            int expiresIn = (Integer) map.get("expires_in");
            tokenExpiryTime = (System.currentTimeMillis() / 1000) + expiresIn;
        } else {
            throw new RuntimeException("Failed to fetch token: " + response.getStatusCode());
        }
    }
}

