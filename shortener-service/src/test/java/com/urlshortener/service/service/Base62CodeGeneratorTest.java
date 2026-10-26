package com.urlshortener.service.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62CodeGeneratorTest {

    private final Base62CodeGenerator generator = new Base62CodeGenerator();

    @Test
    void generatesCodesOfRequestedLengthFromBase62Alphabet() {
        String code = generator.generate(7);
        assertThat(code).hasSize(7).matches("[0-9A-Za-z]{7}");
    }

    @Test
    void codesAreEffectivelyUnique() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.generate(7));
        }
        assertThat(codes).hasSize(10_000);
    }

    @Test
    void rejectsNonPositiveLength() {
        assertThatThrownBy(() -> generator.generate(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
