package com.securebank.transaction.service;

import com.securebank.transaction.domain.Transfer;

/** @param replay true if the idempotency key already existed (no new workflow was started) */
public record InitiationResult(Transfer transfer, boolean replay) {
}
