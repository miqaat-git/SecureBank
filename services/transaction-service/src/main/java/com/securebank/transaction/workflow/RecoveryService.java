package com.securebank.transaction.workflow;

import com.securebank.transaction.config.TransferProperties;
import com.securebank.transaction.service.TransferStore;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds transfers left in non-terminal, auto-resumable states (after a crash, restart or scheduled retry) and
 * resumes them. Transfers owned by a live lease are skipped; leases of crashed workers simply expire.
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final TransferStore store;
    private final TransferProcessor processor;
    private final TransferProperties props;

    public RecoveryService(TransferStore store, TransferProcessor processor, TransferProperties props) {
        this.store = store;
        this.processor = processor;
        this.props = props;
    }

    /** @return number of transfers picked up in this batch */
    public int recoverBatch() {
        List<UUID> ids = store.findRecoverable(props.recovery().batchSize());
        for (UUID id : ids) {
            try {
                processor.process(id);
            } catch (RuntimeException e) {
                log.warn("recovery of transfer {} failed: {}", id, e.getClass().getSimpleName());
            }
        }
        return ids.size();
    }
}
