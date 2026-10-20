package com.securebank.transaction.workflow;

import com.securebank.transaction.client.AccountOperationRequest;
import com.securebank.transaction.client.AccountOperationType;
import com.securebank.transaction.client.AccountServiceClient;
import com.securebank.transaction.client.LedgerEntryLine;
import com.securebank.transaction.client.LedgerPostRequest;
import com.securebank.transaction.client.LedgerReversalRequest;
import com.securebank.transaction.client.LedgerServiceClient;
import com.securebank.transaction.client.RemoteResult;
import com.securebank.transaction.domain.OperationType;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferOperation;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Translates a persisted workflow step into the corresponding remote call. */
@Component
public class RemoteOperations {

    private final AccountServiceClient accounts;
    private final LedgerServiceClient ledger;

    public RemoteOperations(AccountServiceClient accounts, LedgerServiceClient ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    public static String businessReference(Transfer t) {
        return "TRANSFER-" + t.getId();
    }

    public static String reversalReference(Transfer t) {
        return businessReference(t) + "-REVERSAL";
    }

    public RemoteResult send(Transfer t, TransferOperation op, Map<OperationType, TransferOperation> all) {
        switch (op.getOperationType()) {
            case DEBIT_SOURCE:
                return accounts.applyOperation(new AccountOperationRequest(t.getSourceAccountId(),
                        op.getOperationId(), AccountOperationType.DEBIT, t.getAmount(), t.getCurrency(),
                        t.getId(), null));
            case CREDIT_DESTINATION:
                return accounts.applyOperation(new AccountOperationRequest(t.getDestinationAccountId(),
                        op.getOperationId(), AccountOperationType.CREDIT, t.getAmount(), t.getCurrency(),
                        t.getId(), null));
            case REVERSE_DEBIT:
                return accounts.applyOperation(new AccountOperationRequest(t.getSourceAccountId(),
                        op.getOperationId(), AccountOperationType.REVERSE_DEBIT, t.getAmount(), t.getCurrency(),
                        t.getId(), all.get(OperationType.DEBIT_SOURCE).getOperationId()));
            case LEDGER_POST:
                return ledger.post(new LedgerPostRequest(businessReference(t), op.getOperationId(),
                        "SecureBank transfer " + t.getId(), t.getCurrency(), List.of(
                        new LedgerEntryLine(t.getSourceAccountId(), "DEBIT", t.getAmount()),
                        new LedgerEntryLine(t.getDestinationAccountId(), "CREDIT", t.getAmount()))));
            case LEDGER_REVERSAL:
                return ledger.reverse(new LedgerReversalRequest(businessReference(t), reversalReference(t),
                        op.getOperationId(), "TRANSFER_COMPENSATION"));
            default:
                throw new IllegalStateException("Unsupported operation type " + op.getOperationType());
        }
    }

    public RemoteResult lookup(Transfer t, TransferOperation op) {
        switch (op.getOperationType()) {
            case DEBIT_SOURCE:
            case REVERSE_DEBIT:
                return accounts.getOperation(t.getSourceAccountId(), op.getOperationId());
            case CREDIT_DESTINATION:
                return accounts.getOperation(t.getDestinationAccountId(), op.getOperationId());
            case LEDGER_POST:
                return ledger.getPosting(businessReference(t));
            case LEDGER_REVERSAL:
                return ledger.getPosting(reversalReference(t));
            default:
                throw new IllegalStateException("Unsupported operation type " + op.getOperationType());
        }
    }
}
