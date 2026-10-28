package com.zer0drv.blog.article.series.dto;

import jakarta.validation.constraints.*;
import java.util.List;

/** A single atomic replacement of metadata and the complete ordered chapter list. */
public record SeriesSaveDTO(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String description,
        @Size(max = 512) String cover,
        @NotBlank @Size(max = 200) String slug,
        @NotNull @Pattern(regexp = "DRAFT|PUBLISHED") String status,
        @NotNull @Size(max = 200) List<@NotNull @Positive Long> articleIds,
        @PositiveOrZero Long revision) { }
