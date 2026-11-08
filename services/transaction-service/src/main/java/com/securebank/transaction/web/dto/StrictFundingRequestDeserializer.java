package com.securebank.transaction.web.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * Deserializes {@link FundingRequest} and rejects any property outside the funding contract, regardless of the
 * ObjectMapper's global FAIL_ON_UNKNOWN_PROPERTIES setting. The error never names or echoes the offending field or
 * value, so a smuggled value is neither logged nor returned. Only used for FundingRequest (no global change).
 */
public class StrictFundingRequestDeserializer extends StdDeserializer<FundingRequest> {

    private static final long serialVersionUID = 1L;

    static final Set<String> ALLOWED = Set.of("targetAccountId", "amount", "currency", "fundingReference", "reason");

    public StrictFundingRequestDeserializer() {
        super(FundingRequest.class);
    }

    @Override
    public FundingRequest deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        JsonNode node = ctxt.readTree(parser);
        if (node == null || !node.isObject()) {
            throw MismatchedInputException.from(parser, FundingRequest.class, "Funding request must be a JSON object");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!ALLOWED.contains(names.next())) {
                throw MismatchedInputException.from(parser, FundingRequest.class,
                        "Funding request contains an unsupported field");
            }
        }
        return new FundingRequest(
                read(ctxt, node, "targetAccountId", UUID.class),
                read(ctxt, node, "amount", BigDecimal.class),
                read(ctxt, node, "currency", String.class),
                read(ctxt, node, "fundingReference", String.class),
                read(ctxt, node, "reason", String.class));
    }

    private static <T> T read(DeserializationContext ctxt, JsonNode node, String name, Class<T> type)
            throws IOException {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;   // bean validation reports the missing field
        }
        return ctxt.readTreeAsValue(value, type);
    }
}
