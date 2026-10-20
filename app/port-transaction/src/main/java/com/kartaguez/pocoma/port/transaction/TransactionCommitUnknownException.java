package com.kartaguez.pocoma.port.transaction;

/** The transaction action completed but transport failed during commit resolution.
 * A framework rollback notification cannot prove that the server did not commit.
 * Callers must reconcile durable state before deciding whether to retry.
 */
public final class TransactionCommitUnknownException extends RuntimeException {
    public TransactionCommitUnknownException(Throwable cause) {
        super("Physical COMMIT acknowledgement unavailable; reconcile durable state", cause);
    }
}
