package com.kartaguez.pocoma.contract.consumption.spi;
import java.util.UUID;

import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation.Page;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation.Walk;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

/** Raw MVCC key navigation. Eligibility, acquisition and durable progress are separate. */
public interface SlotKeyNavigation {




    /** PostgreSQL uuid order: unsigned network bytes, unlike UUID.compareTo. */
    static int compare(UUID left, UUID right) {
        int high =
                Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0
                ? high
                : Long.compareUnsigned(
                        left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    Page readPage(
            ConsumptionId consumption,
            int partition,
            Walk walk,
            int limit,
            RecoveryReadContext context);
}
