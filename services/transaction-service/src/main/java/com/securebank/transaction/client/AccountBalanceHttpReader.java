package com.securebank.transaction.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpMethod;

/** GET /internal/account-balances on Account Service (ROLE_TRANSACTION_SERVICE token from the shared provider). */
public class AccountBalanceHttpReader implements AccountBalanceReader {

    static final String PATH = "/internal/account-balances";

    private final RemoteHttp http;
    private final ObjectMapper mapper;

    public AccountBalanceHttpReader(RemoteHttp http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public AccountBalancePage list(String status, String currency, UUID accountId, int page, int size) {
        StringBuilder template = new StringBuilder(PATH).append("?page={page}&size={size}");
        List<Object> vars = new ArrayList<>(List.of(page, size));
        if (status != null) {
            template.append("&status={status}");
            vars.add(status);
        }
        if (currency != null) {
            template.append("&currency={currency}");
            vars.add(currency);
        }
        if (accountId != null) {
            template.append("&accountId={accountId}");
            vars.add(accountId.toString());
        }
        HttpResult r = http.execute(HttpMethod.GET, template.toString(), vars.toArray(), null, null);
        if (r.kind() != HttpResult.Kind.RESPONSE) {
            throw new RemoteCallException("ACCOUNT_SERVICE_UNREACHABLE");
        }
        if (r.status() < 200 || r.status() >= 300) {
            throw new RemoteCallException("ACCOUNT_SERVICE_HTTP_" + r.status());
        }
        try {
            AccountBalancePage result = mapper.readValue(r.body(), AccountBalancePage.class);
            if (result.content() == null) {
                throw new RemoteCallException("ACCOUNT_SERVICE_BAD_RESPONSE");
            }
            return result;
        } catch (RemoteCallException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteCallException("ACCOUNT_SERVICE_BAD_RESPONSE");
        }
    }
}
