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
        return of(initiatorCustomerId, source, destination, amount, currency, TransferType.TRANSFER);
    }

    /**
     * The operation type is part of the fingerprint, so a key used for funding can never be replayed as a transfer
     * (or the reverse), even when every other field is identical. TRANSFER keeps the original canonical form, so
     * fingerprints stored before funding existed remain valid.
     */
    public static String of(String initiatorCustomerId, UUID source, UUID destination, BigDecimal amount,
                            String currency, TransferType type) {
        return digest(type, initiatorCustomerId, source, destination, amount.setScale(2).toPlainString(), currency);
    }

    /**
     * Fingerprint of a request that has NOT passed business validation, so it can be compared with an existing
     * transfer's fingerprint before validation could mask a key-reuse conflict. Never throws for non-null input:
     * an amount that cannot be a stored amount (more than 2 decimals, i.e. never valid) gets a canonical plain
     * form that cannot equal any stored fingerprint, so it is simply reported as "different".
     */
    public static String ofUnvalidated(String initiatorCustomerId, UUID source, UUID destination,
                                       BigDecimal amount, String currency, TransferType type) {
        BigDecimal stripped = amount.signum() == 0 ? BigDecimal.ZERO : amount.stripTrailingZeros();
        String canonicalAmount = stripped.scale() <= 2
                ? stripped.setScale(2).toPlainString()
                : stripped.toPlainString();
        return digest(type, initiatorCustomerId, source, destination, canonicalAmount,
                currency.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Funding fingerprint: the admin scope, the target, amount, currency, the unique funding reference and the
     * reason. Any difference under the same Idempotency-Key is a conflict (HTTP 409).
     */
    public static String ofFunding(String adminScope, UUID target, BigDecimal amount, String currency,
                                   String fundingReference, String reason) {
        String canonical = String.join("|", "FUNDING", adminScope, target.toString(),
                amount.setScale(2).toPlainString(), currency.toUpperCase(java.util.Locale.ROOT), fundingReference,
                reason == null ? "" : reason.trim());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String digest(TransferType type, String initiatorCustomerId, UUID source, UUID destination,
                                 String amount, String currency) {
        String canonical = String.join("|", initiatorCustomerId, source.toString(), destination.toString(),
                amount, currency);
        if (type != TransferType.TRANSFER) {
            canonical = type.name() + "|" + canonical;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
