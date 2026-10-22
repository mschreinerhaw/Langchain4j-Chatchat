package com.chatchat.common.runtime.capability;

import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityIdTest {
    @Test void parsesStableBusinessCapabilityIdentity() {
        CapabilityId value = CapabilityId.parse("finance.portfolio-attribution.v2");
        assertThat(value.namespace()).isEqualTo("finance");
        assertThat(value.name()).isEqualTo("portfolio-attribution");
        assertThat(value.version()).isEqualTo("v2");
        assertThat(value.value()).isEqualTo("finance.portfolio-attribution.v2");
    }

    @Test void rejectsUnsafeSegments() {
        assertThatThrownBy(() -> CapabilityId.parse("finance.portfolio attribution.v1"))
            .isInstanceOf(IllegalArgumentException.class);
    }




}
