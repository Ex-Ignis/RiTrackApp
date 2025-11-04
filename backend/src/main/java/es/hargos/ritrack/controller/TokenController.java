package es.hargos.ritrack.controller;

import es.hargos.ritrack.jwt_token.TokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TokenController {

    private final TokenService tokenService;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping("/api/v1/token")
    public String getToken() throws Exception {
        return tokenService.getAccessToken();
    }
}


