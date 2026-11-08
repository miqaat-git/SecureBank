package com.securebank.transaction.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FundingPropertiesTest {

    @Test
    void disabledByDefaultAndNothingIsReserved() {
        TransferProperties.Funding off = new TransferProperties.Funding(false, "", "");

        assertThat(off.configured()).isFalse();
        assertThat(off.clearingAccount()).isNull();
        assertThat(off.isReserved(UUID.randomUUID())).isFalse();
    }

    @Test
    void enabledRequiresAValidAccountIdAndAClearingCustomer() {
        String id = UUID.randomUUID().toString();

        assertThatThrownBy(() -> new TransferProperties.Funding(true, "", "BANK")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransferProperties.Funding(true, "not-a-uuid", "BANK"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransferProperties.Funding(true, id, " ")).isInstanceOf(IllegalStateException.class);

        TransferProperties.Funding on = new TransferProperties.Funding(true, " " + id + " ", "BANK");
        assertThat(on.clearingAccount()).isEqualTo(UUID.fromString(id));
        assertThat(on.isReserved(UUID.fromString(id))).isTrue();
        assertThat(on.isReserved(UUID.randomUUID())).isFalse();
        assertThat(on.isReserved(null)).isFalse();
    }
}
