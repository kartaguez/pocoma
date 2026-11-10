package com.kartaguez.pocoma.contract.writeadmission;

@FunctionalInterface
public interface AdmitIdentityRequest {
    java.util.UUID admit(com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal principal, String clientKey, IdentityAdmissionInput input);
}
