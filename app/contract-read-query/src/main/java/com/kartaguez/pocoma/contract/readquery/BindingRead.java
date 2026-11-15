package com.kartaguez.pocoma.contract.readquery;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;

public interface BindingRead {
    public interface Query { Answer get(ExternalIdentity eui, long minimum); }
    public enum Status { ABSENT, NOT_READY, AVAILABLE, UNAVAILABLE }
    public record View(long euiVersion,boolean active,String bindingToken) {
        @Override public String toString() { return "BindingView[version="+euiVersion+",active="+active+",token=REDACTED]"; }
    }
    public record Answer(Status status,View value) {}
    Answer get(com.kartaguez.pocoma.domain.identity.ExternalIdentity eui, long minimum);
}
