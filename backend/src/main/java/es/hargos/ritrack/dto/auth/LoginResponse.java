package es.hargos.ritrack.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private String type = "Bearer";
    private String email;
    private String fullName;
    private String role;
    private String companyName;
    private Long tenantId;
    private String rememberToken; // Solo si remember me está activo
}
