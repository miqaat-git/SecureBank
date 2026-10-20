package com.securebank.transaction.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.transaction.config.RemoteClientProperties;
import java.util.Set;
import org.springframework.http.HttpMethod;

public class LedgerServiceHttpClient implements LedgerServiceClient {

    private static final Set<String> SUCCESS = Set.of("POSTED", "REVERSED");

    private final RemoteHttp http;
    private final RemoteClientProperties.Ledger config;
    private final ObjectMapper mapper;

    public LedgerServiceHttpClient(RemoteHttp http, RemoteClientProperties.Ledger config, ObjectMapper mapper) {
        this.http = http;
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public RemoteResult post(LedgerPostRequest request) {
        HttpResult r = http.execute(HttpMethod.POST, config.postPath(), new Object[0], request,
                request.idempotencyKey().toString());
        return RemoteOutcomeMapper.map(r, mapper, SUCCESS, false);
    }

    @Override
    public RemoteResult reverse(LedgerReversalRequest request) {
        HttpResult r = http.execute(HttpMethod.POST, config.reversalPath(),
                new Object[]{request.originalBusinessReference()}, request, request.idempotencyKey().toString());
        return RemoteOutcomeMapper.map(r, mapper, SUCCESS, false);
    }

    @Override
    public RemoteResult getPosting(String businessReference) {
        HttpResult r = http.execute(HttpMethod.GET, config.lookupPath(), new Object[]{businessReference}, null, null);
        return RemoteOutcomeMapper.map(r, mapper, SUCCESS, true);
    }
}
