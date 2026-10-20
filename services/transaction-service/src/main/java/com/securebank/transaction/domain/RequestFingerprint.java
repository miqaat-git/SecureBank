package com.securebank.transaction.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Canonical SHA-256 digest of the material fields of a transfer request, used to detect key reuse. */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(String initiatorCustomerId, UUID source, UUID destination, BigDecimal amount,
                            String currency) {
        String canonical = String.join("|", initiatorCustomerId, source.toString(), destination.toString(),
                amount.setScale(2).toPlainString(), currency);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
