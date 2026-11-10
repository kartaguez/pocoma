package com.kartaguez.pocoma.contract.writeadmission;

@FunctionalInterface
public interface AdmitCommand {
    java.util.UUID admit(com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal principal, String clientKey, CommandAdmissionInput input);
}
