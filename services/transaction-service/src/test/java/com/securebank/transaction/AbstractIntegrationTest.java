package com.securebank.transaction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.securebank.transaction.client.AccountOperationRequest;
import com.securebank.transaction.client.AccountOperationType;
import com.securebank.transaction.client.AccountServiceClient;
import com.securebank.transaction.client.AccountView;
import com.securebank.transaction.client.LedgerServiceClient;
import com.securebank.transaction.client.RemoteResult;
import com.securebank.transaction.domain.TransferHistory;
import com.securebank.transaction.domain.TransferStatus;
import com.securebank.transaction.repository.TransferHistoryRepository;
import com.securebank.transaction.repository.TransferOperationRepository;
import com.securebank.transaction.repository.TransferRepository;
import com.securebank.transaction.security.Caller;
import com.securebank.transaction.service.OperationStore;
import com.securebank.transaction.service.TransferCommand;
import com.securebank.transaction.service.TransferService;
import com.securebank.transaction.service.TransferStore;
import com.securebank.transaction.workflow.ReconciliationService;
import com.securebank.transaction.workflow.RecoveryService;
import com.securebank.transaction.workflow.TransferProcessor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Full application context on in-memory H2 (Flyway migrations applied); remote services are mocked. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    protected static final UUID SRC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    protected static final UUID DST = UUID.fromString("22222222-2222-2222-2222-222222222222");
    protected static final String CUSTOMER = "CUST-1";

    @MockitoBean protected AccountServiceClient accounts;
    @MockitoBean protected LedgerServiceClient ledger;
    @MockitoBean protected JwtDecoder jwtDecoder;

    @Autowired protected TransferRepository transferRepo;
    @Autowired protected TransferOperationRepository operationRepo;
    @Autowired protected TransferHistoryRepository historyRepo;
    @Autowired protected TransferService transferService;
    @Autowired protected TransferProcessor processor;
    @Autowired protected ReconciliationService reconciliation;
    @Autowired protected RecoveryService recovery;
    @Autowired protected TransferStore store;
    @Autowired protected OperationStore operationStore;
    @Autowired protected MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        historyRepo.deleteAll();
        operationRepo.deleteAll();
        transferRepo.deleteAll();
    }

    protected static Caller customer() {
        return new Caller("user-1", CUSTOMER, false);
    }

    protected static TransferCommand cmd(String amount) {
        return new TransferCommand(SRC, DST, new BigDecimal(amount), "INR");
    }

    protected static ArgumentMatcher<AccountOperationRequest> isType(AccountOperationType type) {
        return r -> r.type() == type;
    }

    protected void stubActiveAccounts() {
        when(accounts.findAccount(SRC)).thenReturn(Optional.of(new AccountView(SRC, CUSTOMER, "ACTIVE", "INR")));
        when(accounts.findAccount(DST)).thenReturn(Optional.of(new AccountView(DST, "CUST-2", "ACTIVE", "INR")));
    }

    protected void stubEverythingSucceeds() {
        stubActiveAccounts();
        when(accounts.applyOperation(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.post(any())).thenReturn(RemoteResult.succeeded());
        when(ledger.reverse(any())).thenReturn(RemoteResult.succeeded());
    }

    protected List<TransferStatus> historyOf(UUID transferId) {
        return historyRepo.findByTransferIdOrderByIdAsc(transferId).stream()
                .map(TransferHistory::getNewStatus).toList();
    }
}
