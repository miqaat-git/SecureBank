package com.securebank.ledger.service;

import com.securebank.ledger.config.LedgerConstants;
import com.securebank.ledger.exception.InvalidQueryException;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Validation of query/path parameters shared by the read services. */
public final class QuerySupport {

    private static final Pattern ACCOUNT_REFERENCE = Pattern.compile(LedgerConstants.ACCOUNT_REFERENCE_PATTERN);
    private static final Pattern BUSINESS_REFERENCE = Pattern.compile(LedgerConstants.BUSINESS_REFERENCE_PATTERN);
    private static final Pattern CURRENCY = Pattern.compile(LedgerConstants.CURRENCY_PATTERN);

    private QuerySupport() {
    }

    public static Pageable pageable(int page, int size, Sort sort) {
        if (page < 0) {
            throw new InvalidQueryException("page must be 0 or greater");
        }
        if (size < 1 || size > LedgerConstants.MAX_PAGE_SIZE) {
            throw new InvalidQueryException("size must be between 1 and " + LedgerConstants.MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, sort);
    }

    /** The range is [from, to): {@code to} is exclusive. Either bound may be null. */
    public static void requireValidRange(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidQueryException("'from' must be earlier than 'to'");
        }
    }

    public static String requireAccountReference(String accountReference) {
        if (accountReference == null || !ACCOUNT_REFERENCE.matcher(accountReference).matches()) {
            throw new InvalidQueryException("accountReference has an invalid format");
        }
        return accountReference;
    }

    public static String requireBusinessReference(String businessReference) {
        if (businessReference == null || !BUSINESS_REFERENCE.matcher(businessReference).matches()) {
            throw new InvalidQueryException("businessReference has an invalid format");
        }
        return businessReference;
    }

    public static String requireCurrency(String currency) {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new InvalidQueryException("currency must be a 3-letter upper-case code");
        }
        return currency;
    }
}
