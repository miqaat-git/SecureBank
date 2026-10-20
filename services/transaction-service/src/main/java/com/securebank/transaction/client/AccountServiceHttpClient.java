package com.securebank.transaction.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.transaction.config.RemoteClientProperties;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpMethod;

public class AccountServiceHttpClient implements AccountServiceClient {

    private static final Set<String> SUCCESS = Set.of("SUCCEEDED");

    private final RemoteHttp http;
    private final RemoteClientProperties.Account config;
    private final ObjectMapper mapper;

    public AccountServiceHttpClient(RemoteHttp http, RemoteClientProperties.Account config, ObjectMapper mapper) {
        this.http = http;
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public Optional<AccountView> findAccount(UUID accountId) {
        HttpResult r = http.execute(HttpMethod.GET, config.accountPath(), new Object[]{accountId}, null, null);
        if (r.kind() != HttpResult.Kind.RESPONSE) {
            throw new RemoteCallException("ACCOUNT_SERVICE_UNREACHABLE");
        }
        if (r.status() == 404) {
            return Optional.empty();
        }
        if (r.status() < 200 || r.status() >= 300) {
            throw new RemoteCallException("ACCOUNT_SERVICE_HTTP_" + r.status());
        }
        try {
            return Optional.of(mapper.readValue(r.body(), AccountView.class));
        } catch (Exception e) {
            throw new RemoteCallException("ACCOUNT_SERVICE_BAD_RESPONSE");
        }
    }

    @Override
    public RemoteResult applyOperation(AccountOperationRequest request) {
        HttpResult r = http.execute(HttpMethod.POST, config.operationsPath(), new Object[]{request.accountId()},
                request, request.operationId().toString());
        return RemoteOutcomeMapper.map(r, mapper, SUCCESS, false);
    }

    @Override
    public RemoteResult getOperation(UUID accountId, UUID operationId) {
        HttpResult r = http.execute(HttpMethod.GET, config.operationPath(),
                new Object[]{accountId, operationId}, null, null);
        return RemoteOutcomeMapper.map(r, mapper, SUCCESS, true);
    }
}
