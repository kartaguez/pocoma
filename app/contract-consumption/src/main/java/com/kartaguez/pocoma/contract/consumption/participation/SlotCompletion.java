package com.kartaguez.pocoma.contract.consumption.participation;
import com.kartaguez.pocoma.contract.consumption.participation.*;
import com.kartaguez.pocoma.contract.consumption.spi.*;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;

/** Complete this attempt's protected canonical slot before closing publication. */
@FunctionalInterface
public interface SlotCompletion {
    void complete();
}
