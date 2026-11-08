package com.securebank.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Mapping of JWT claims to SecureBank identity (prefix securebank.security). */
@ConfigurationProperties(prefix = "securebank.security")
public record SecurityProperties(@DefaultValue("customer_id") String customerIdClaim) {
}
