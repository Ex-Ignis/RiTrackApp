package es.hargos.ritrack.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * DTO para respuestas paginadas
 */
@Getter
@Setter
public class PaginatedResponseDto<T> {

    private List<T> content;
    private Integer page;
    private Integer size;
    private Long totalElements;
    private Integer totalPages;
    private Boolean first;
    private Boolean last;
    private Boolean empty;

    public PaginatedResponseDto() {}

    public PaginatedResponseDto(List<T> content, Integer page, Integer size, Long totalElements) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = (int) Math.ceil((double) totalElements / size);
        this.first = page == 0;
        this.last = page >= (totalPages - 1);
        this.empty = content == null || content.isEmpty();
    }

    /**
     * Crea una respuesta paginada a partir de una lista completa
     */
    public static <T> PaginatedResponseDto<T> of(List<T> allItems, Integer page, Integer size) {
        if (allItems == null) {
            return new PaginatedResponseDto<>(List.of(), page, size, 0L);
        }

        int startIndex = page * size;
        int endIndex = Math.min(startIndex + size, allItems.size());

        List<T> pageContent = startIndex < allItems.size() ?
                allItems.subList(startIndex, endIndex) : List.of();

        return new PaginatedResponseDto<>(pageContent, page, size, (long) allItems.size());
    }

    /**
     * Calcula el número de elementos en la página actual
     */
    public Integer getNumberOfElements() {
        return content != null ? content.size() : 0;
    }

    /**
     * Verifica si hay página siguiente
     */
    public Boolean getHasNext() {
        return !last;
    }

    /**
     * Verifica si hay página anterior
     */
    public Boolean getHasPrevious() {
        return !first;
    }

    @Override
    public String toString() {
        return "PaginatedResponseDto{" +
                "page=" + page +
                ", size=" + size +
                ", totalElements=" + totalElements +
                ", totalPages=" + totalPages +
                ", numberOfElements=" + getNumberOfElements() +
                ", first=" + first +
                ", last=" + last +
                ", empty=" + empty +
                '}';
    }
}