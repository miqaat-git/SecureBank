package com.securebank.transaction.web.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The strictness must hold even when the ObjectMapper ignores unknown properties (Spring Boot's default). */
class StrictFundingRequestDeserializerTest {

    private static final String TARGET = "11111111-1111-1111-1111-111111111111";
    private static final String VALID = "{\"targetAccountId\":\"" + TARGET + "\",\"amount\":10.50,"
            + "\"currency\":\"INR\",\"fundingReference\":\"REF-0000-0001\",\"reason\":\"why\"}";

    private final ObjectMapper lenient = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void validJsonIsDeserialized() throws Exception {
        FundingRequest r = lenient.readValue(VALID, FundingRequest.class);

        assertThat(r.targetAccountId()).isEqualTo(UUID.fromString(TARGET));
        assertThat(r.amount()).isEqualByComparingTo(new BigDecimal("10.50"));
        assertThat(r.currency()).isEqualTo("INR");
        assertThat(r.fundingReference()).isEqualTo("REF-0000-0001");
        assertThat(r.reason()).isEqualTo("why");
    }

    @Test
    void unknownPropertiesAreRejectedWithoutEchoingThem() {
        for (String extra : new String[] {"\"sourceAccountId\":\"" + TARGET + "\"",
                "\"businessReference\":\"SECRET-VALUE\"", "\"x\":null"}) {
            String json = VALID.replace("}", "," + extra + "}");
            assertThatThrownBy(() -> lenient.readValue(json, FundingRequest.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageNotContaining("SECRET-VALUE").hasMessageNotContaining("sourceAccountId")
                    .hasMessageNotContaining("businessReference");
        }
    }

    @Test
    void missingFieldsDeserializeAsNullSoBeanValidationReportsThem() throws Exception {
        FundingRequest r = lenient.readValue("{}", FundingRequest.class);

        assertThat(r.targetAccountId()).isNull();
        assertThat(r.amount()).isNull();
        assertThat(r.reason()).isNull();
    }

    @Test
    void theStrictnessIsScopedToFundingRequestOnly() throws Exception {
        assertThat(lenient.readValue("{\"a\":\"x\",\"extra\":1}", Map.class)).containsKey("extra");
    }
}
