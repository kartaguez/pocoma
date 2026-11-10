package com.kartaguez.pocoma.engine.admit.registration.v3;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.contract.writeadmission.IdentityAdmissionInput;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

import org.junit.jupiter.api.Test;

import java.util.*;

class V3IdentityAdmissionInputTest {
    final ExternalIdentity eui = new ExternalIdentity("issuer", "subject");

    @Test
    void registrationHasNoTokenOrClientEui() {
        var i = IdentityAdmissionInput.decode(Map.of("operation", "Registration"), eui);
        assertEquals(eui, i.target());
        assertNull(i.token());
        assertInstanceOf(IdentityRequest.Registration.class, i.intention());
        assertThrows(
                AdmissionFailure.class,
                () ->
                        IdentityAdmissionInput.decode(
                                Map.of("operation", "Registration", "bindingToken", "x"), eui));
    }

    @Test
    void unlinkNeedsInitiatorTokenOnly() {
        var i =
                IdentityAdmissionInput.decode(
                        Map.of(
                                "operation",
                                "Unlink",
                                "bindingToken",
                                "initiator-only",
                                "target",
                                Map.of("issuer", "other issuer", "subject", "opaque ")),
                        eui);
        assertEquals("opaque ", i.target().subject());
        assertEquals("initiator-only", i.token().value());
        assertThrows(
                AdmissionFailure.class,
                () ->
                        IdentityAdmissionInput.decode(
                                Map.of(
                                        "operation",
                                        "Unlink",
                                        "target",
                                        Map.of("issuer", "i", "subject", "s")),
                                eui));
    }

    @Test
    void linkIsNotPublic() {
        for (String op : List.of("Link", "Invitation", "CreateInvitation"))
            assertEquals(
                    400,
                    assertThrows(
                                    AdmissionFailure.class,
                                    () ->
                                            IdentityAdmissionInput.decode(
                                                    Map.of("operation", op), eui))
                            .status());
    }

    @Test
    void attestationsAreServerOwned() {
        for (String field :
                List.of("attestations", "requesterEui", "userId", "admission", "clientId"))
            assertThrows(
                    AdmissionFailure.class,
                    () ->
                            IdentityAdmissionInput.decode(
                                    Map.of("operation", "Registration", field, "forged"), eui));
    }
}
