package es.hargos.ritrack.dto;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import lombok.*;

import java.util.Map;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderCreateDto {
    // Campos requeridos con validación
    @Size(min = 2, max = 100, message = "El nombre debe tener entre 2 y 100 caracteres")
    @Pattern(regexp = "^[a-zA-ZáéíóúÁÉÍÓÚñÑ]+\\s+[a-zA-ZáéíóúÁÉÍÓÚñÑ\\s]+$",
            message = "El nombre debe incluir al menos nombre y apellido")
    private String name;

    @Pattern(regexp = "^[A-Za-z0-9+_.-]+@(.+)$", message = "Email inválido")
    private String email; // Puede ser null, se generará automáticamente

    @NotBlank(message = "La contraseña es requerida")
    @Size(min = 10, message = "La contraseña debe tener al menos 10 caracteres")
    @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).*$",
            message = "La contraseña debe contener al menos una mayúscula, una minúscula, un número y un símbolo")
    private String password;

    @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Teléfono inválido")
    private String phone;

    // Campos opcionales - se asignan null si no vienen
    private String bankData;
    private Integer reportingTo;
    private Integer cityId;

    // Contrato requerido
    @NotNull(message = "La información del contrato es requerida")
    @Valid
    private ContractInfo contract;

    // IDs opcionales - se asignan como listas vacías si no vienen
    private List<Integer> vehicleTypeIds;
    private List<Integer> startingPointIds;

    // Fields adicionales - se asigna como mapa vacío si no viene
    private Map<String, String> fields;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContractInfo {
        // Ya no es requerido - se tomará de application.properties si no viene
        private Integer contractId;

        // Ya no es requerido - se asignará automáticamente si no viene
        private String startAt;

        private String jobTitle; // Por defecto "RIDER"

        @NotNull(message = "La ciudad del contrato es requerida")
        private Integer cityId;

        private String vehicleType;
        private String timeZone;
    }
}