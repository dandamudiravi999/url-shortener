package com.urlshortener.service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliasValidatorTest {

    private final AliasValidator validator = new AliasValidator();

    @Test
    void acceptsWellFormedAlias() {
        assertThatCode(() -> validator.validate("launch-2026_v2")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "has space", "slash/inside", "dot.dot", "API", "actuator", "swagger-ui"})
    void rejectsMalformedOrReservedAliases(String alias) {
        assertThatThrownBy(() -> validator.validate(alias)).isInstanceOf(InvalidRequestException.class);
    }
}
