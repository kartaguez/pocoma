package com.kartaguez.pocoma.contract.readquery;
import com.kartaguez.pocoma.domain.projection.v3.*;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.v3.ExternalUserIdentity;
import java.util.*;
import java.util.function.Function;

public interface BindingRead {
    public interface Query { Answer get(ExternalIdentity eui, long minimum); }
    public enum Status { ABSENT, NOT_READY, AVAILABLE, UNAVAILABLE }
    public record View(long euiVersion,boolean active,String bindingToken) {
        @Override public String toString() { return "BindingView[version="+euiVersion+",active="+active+",token=REDACTED]"; }
    }
    public record Answer(Status status,View value) {}
    Answer get(com.kartaguez.pocoma.domain.useridentity.ExternalIdentity eui, long minimum);
}
