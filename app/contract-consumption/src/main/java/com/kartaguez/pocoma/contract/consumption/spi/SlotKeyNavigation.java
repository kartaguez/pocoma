package com.kartaguez.pocoma.contract.consumption.spi;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation.*;
import com.kartaguez.pocoma.contract.consumption.participation.*;
import com.kartaguez.pocoma.contract.consumption.spi.*;
import com.kartaguez.pocoma.contract.consumption.spi.SlotKeyNavigation;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.util.*;

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
