package com.chatchat.api.application.interaction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Source reference returned by retrieval-style interactions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InteractionSource {
    private Integer rank;
    private String source;
    private String snippet;
}

