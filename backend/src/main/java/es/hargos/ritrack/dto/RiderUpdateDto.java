package es.hargos.ritrack.dto;

import lombok.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderUpdateDto {

    @Size(min = 2, max = 100, message = "El nombre debe tener entre 2 y 100 caracteres")
    private String name;

    @Email(message = "Email inválido")
    private String email;

    @Size(min = 10, message = "La contraseña debe tener al menos 10 caracteres")
    @Pattern(regexp = "^(?=.*[A-Z])(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).*$",
            message = "La contraseña debe contener al menos una mayúscula, un número y un símbolo")
    private String password;

    @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Teléfono inválido")
    private String phone;

    private String bankData;

    @Builder.Default  // Para inicializar el Map vacío por defecto
    private Map<String, String> fields = new HashMap<>();
    private Integer reportingTo;
}