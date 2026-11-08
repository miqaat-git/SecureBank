package com.securebank.transaction.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountBalancePage(List<AccountBalanceRow> content, int page, int size, long totalElements,
                                 int totalPages) {
}
