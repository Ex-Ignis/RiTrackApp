package es.hargos.ritrack.exception;

import java.util.List;
import java.util.Map;

/**
 * Excepción lanzada cuando hay múltiples contratos disponibles en Glovo
 * y el usuario debe seleccionar uno específico.
 *
 * Esta NO es un error real, sino una señal para el frontend de que
 * debe mostrar un selector de contratos al usuario.
 */
public class MultipleContractsException extends RuntimeException {

    private final List<Map<String, Object>> contracts;
    private final Integer companyId;

    public MultipleContractsException(List<Map<String, Object>> contracts, Integer companyId) {
        super("Se encontraron " + contracts.size() + " contratos. Debe seleccionar uno.");
        this.contracts = contracts;
        this.companyId = companyId;
    }

    public List<Map<String, Object>> getContracts() {
        return contracts;
    }

    public Integer getCompanyId() {
        return companyId;
    }
}
