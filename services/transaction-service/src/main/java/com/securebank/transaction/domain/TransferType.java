package com.securebank.transaction.domain;

/** TRANSFER: customer to customer. FUNDING: administrator-only simulated funding of a customer account (D7). */
public enum TransferType {
    TRANSFER,
    FUNDING
}
