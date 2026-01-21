package com.ureca.snac.common.event;

import com.ureca.snac.common.exception.UnknownAggregateTypeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateTypeTest {
    @Test
    @DisplayName("예외 : 이상한 값, null -> 예외 발생")
    void from_Fail() {
        // given
        String[] invalid = {"APPLE", "META", "", null};

        // when, then
        for (String str : invalid) {
            assertThatThrownBy(() -> AggregateType.from(str))
                    .isInstanceOf(UnknownAggregateTypeException.class);
        }
    }
}