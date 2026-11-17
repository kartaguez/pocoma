package com.kartaguez.pocoma.contract.writeadmission;
import java.util.Optional;
/** Key-free admission capability. Its key-bearing implementation is selected by Compose. */
public interface AdmissionProtection {
    String lookupId();
    String currentId();
    byte[] scope(String issuer, String subject, Optional<String> attestedClient,
                 String category, String operation, String clientKey);
    byte[] intent(String keyId, int version, byte[] canonical);
    boolean matches(AdmissionAssociation association, byte[] canonical);
    static byte[] frame(String... values) { return AdmissionFields.frame(values); }
}
