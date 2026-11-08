package com.securebank.transaction.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpMethod;

/** GET /internal/ledger-balances on Ledger Service (any ledger machine role; the existing token is reused). */
public class LedgerBalanceHttpReader implements LedgerBalanceReader {

    static final String PATH = "/internal/ledger-balances?accountIds={ids}";

    private final RemoteHttp http;
    private final ObjectMapper mapper;

    public LedgerBalanceHttpReader(RemoteHttp http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public LedgerBalances balances(Collection<UUID> accountIds) {
        String ids = accountIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        HttpResult r = http.execute(HttpMethod.GET, PATH, new Object[]{ids}, null, null);
        if (r.kind() != HttpResult.Kind.RESPONSE) {
            throw new RemoteCallException("LEDGER_SERVICE_UNREACHABLE");
        }
        if (r.status() < 200 || r.status() >= 300) {
            throw new RemoteCallException("LEDGER_SERVICE_HTTP_" + r.status());
        }
        try {
            LedgerBalances result = mapper.readValue(r.body(), LedgerBalances.class);
            if (result.balances() == null) {
                throw new RemoteCallException("LEDGER_SERVICE_BAD_RESPONSE");
            }
            return result;
        } catch (RemoteCallException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteCallException("LEDGER_SERVICE_BAD_RESPONSE");
        }
    }
}
