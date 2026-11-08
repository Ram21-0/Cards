package com.cards.service.controller.dto;

import com.cards.common.StatDefinition;

public record StatDefinitionResponse(String key, String label, String unit, String valueType,
                                     String comparisonStrategy, int displayOrder) {

    public static StatDefinitionResponse from(StatDefinition s) {
        return new StatDefinitionResponse(s.key(), s.label(), s.unit(), s.valueType().name(),
                s.comparisonStrategy().name(), s.displayOrder());
    }
}
