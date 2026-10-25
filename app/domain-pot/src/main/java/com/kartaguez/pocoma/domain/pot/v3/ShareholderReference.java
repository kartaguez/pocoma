package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

/** A missing Header is an authoritative absence, not an omitted lookup. */
public record ShareholderReference(PotId potId, long workingVersion, ShareholderId shareholderId,
                                   long createdAt, Optional<Versioned<ShareholderHeader>> header) {
    public ShareholderReference {
        Objects.requireNonNull(potId); Objects.requireNonNull(shareholderId); Objects.requireNonNull(header);
        if (workingVersion < 1 || (header.isEmpty() ? createdAt != 0 : createdAt < 1 || createdAt > workingVersion))
            throw new InconsistentPotFactsException("Invalid existence witness");
        header.ifPresent(h -> {
            if (!h.value().potId().equals(potId) || !h.value().shareholderId().equals(shareholderId)
                    || h.activeFrom() < createdAt || h.activeFrom() > workingVersion)
                throw new InconsistentPotFactsException("Mixed shareholder reference");
        });
    }
}
