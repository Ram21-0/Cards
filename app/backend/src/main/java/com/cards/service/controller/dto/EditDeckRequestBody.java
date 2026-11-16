package com.cards.service.controller.dto;

import java.util.List;

/** The admin's correction of a pending request; a field left out stays as it is. */
public record EditDeckRequestBody(String category, String description, List<String> names) {
}
