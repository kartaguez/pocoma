package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;
import com.kartaguez.pocoma.domain.projection.radix.Radix16Node;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import java.util.*;

public interface PotVersionStore {
    void insertAndAdvance(PotVersion value);

    void requireStored(PotVersion value);

    OptionalLong currentVersion(UUID pot, RecoveryReadContext context);

    Optional<PotVersion> read(UUID pot, long version, RecoveryReadContext context);

    Optional<PotVersion> readLatestBefore(
            UUID pot,
            long beforeVersionExclusive,
            long minVersionInclusive,
            RecoveryReadContext context);

    ReadEvidence evidence(PotVersion expected, RecoveryReadContext context);

    /** Close the existing READ publication after L2 complete, without committing. */
    void closePublication();

    // --- Radix-16 incremental & targeted operations (L7-C2-B1) ---

    Optional<PotVersionManifest> readManifestLatestBefore(
            UUID pot,
            long beforeVersionExclusive,
            long minVersionInclusive,
            RecoveryReadContext context);

    Optional<PotShareholdersView> readShareholders(
            UUID pot, long version, RecoveryReadContext context);

    Optional<PotExpensesView> readExpenses(
            UUID pot, long version, Collection<UUID> expenseIds, RecoveryReadContext context);

    Map<UUID, PotVersion.Shareholder> readShareholdersByIds(
            UUID pot, long version, Collection<UUID> ids, RecoveryReadContext context);

    Map<UUID, PotVersion.Expense> readExpensesByIds(
            UUID pot, long version, Collection<UUID> ids, RecoveryReadContext context);

    Map<ContentHash, Radix16Node> loadNodes(
            Collection<ContentHash> hashes, RecoveryReadContext context);

    record WriteReceipt(boolean manifestInserted, boolean currentAdvanced) {}

    void publishPrepared(PotVersionWritePlan plan);

    default WriteReceipt publishPreparedWithReceipt(PotVersionWritePlan plan) {
        publishPrepared(plan);
        return new WriteReceipt(false, false);
    }

    default void requireCompletionReady(PotVersionManifest expected, WriteReceipt receipt) {
        requireManifestStored(expected);
    }

    default void closePublication(PotVersionManifest manifest, WriteReceipt receipt) {
        closePublication();
    }

    void requireManifestStored(PotVersionManifest expected);
}
