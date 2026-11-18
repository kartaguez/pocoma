package com.kartaguez.pocoma.domain.identity;

import static com.kartaguez.pocoma.domain.identity.ExternalUserIdentity.compareKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IdentityValueContractsTest {
    private static final ExternalUserIdentityId ID = new ExternalUserIdentityId(new UUID(0, 1));
    private static final PocomaUserId USER = new PocomaUserId(new UUID(1, 1));
    private static final ExternalIdentity KEY = key("issuer", "subject");
    private static final BindingToken TOKEN = new BindingToken("secret-token");

    @Test
    void idsAndTokensHaveExactOpaqueValueSemanticsWithoutGenerationOrOrdering() {
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(ID, new ExternalUserIdentityId(new UUID(0, 1))),
                () -> assertEquals(ID.hashCode(), new ExternalUserIdentityId(new UUID(0, 1)).hashCode()),
                () -> assertNotEquals(ID, new ExternalUserIdentityId(new UUID(0, 2))),
                () -> assertFalse(Comparable.class.isAssignableFrom(ExternalUserIdentityId.class)),
                () -> assertThrows(NullPointerException.class, () -> new ExternalUserIdentityId(null)),
                () -> assertEquals(TOKEN, new BindingToken("secret-token")),
                () -> assertEquals(TOKEN.hashCode(), new BindingToken("secret-token").hashCode()),
                () -> assertNotEquals(TOKEN, new BindingToken("Secret-token")),
                () -> assertNotEquals(TOKEN, new BindingToken(" secret-token ")),
                () -> assertEquals(" secret-token ", new BindingToken(" secret-token ").value()),
                () -> assertFalse(Comparable.class.isAssignableFrom(BindingToken.class)),
                () -> assertThrows(NullPointerException.class, () -> new BindingToken(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new BindingToken("")),
                () -> assertThrows(IllegalArgumentException.class, () -> new BindingToken(" \t\n")));
    }

    @Test
    void statesRequirePositiveVersionAndUserTokenTogether() {
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(1, state(1, USER, TOKEN).version()),
                () -> assertTrue(state(Long.MAX_VALUE, USER, TOKEN).isBound()),
                () -> assertFalse(state(3, null, null).isBound()),
                () -> assertThrows(IllegalArgumentException.class, () -> state(0, null, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> state(-1, USER, TOKEN)),
                () -> assertThrows(IllegalArgumentException.class, () -> state(1, USER, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> state(1, null, TOKEN)),
                () -> assertThrows(NullPointerException.class, () -> new ExternalUserIdentity(null, KEY, 1, null, null)),
                () -> assertThrows(NullPointerException.class, () -> new ExternalUserIdentity(ID, null, 1, null, null)),
                () -> assertEquals(state(2, USER, TOKEN), state(2, USER, new BindingToken("secret-token"))),
                () -> assertNotEquals(state(1, USER, TOKEN), state(2, USER, TOKEN)));
    }

    @Test
    void changedIsNonemptyAndCanOnlyCreateTheUserOfItsNewBoundState() {
        var changed = new IdentityTransitionResult.Changed(Optional.empty(), state(1, null, null));
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertNotNull(changed.identity()),
                () -> assertThrows(NullPointerException.class, () -> new IdentityTransitionResult.Changed(Optional.empty(), null)),
                () -> assertThrows(NullPointerException.class, () -> new IdentityTransitionResult.Changed(null, state(1, null, null))),
                () -> assertThrows(IllegalArgumentException.class, () -> new IdentityTransitionResult.Changed(
                        Optional.of(new User(USER)), state(1, null, null))),
                () -> assertThrows(IllegalArgumentException.class, () -> new IdentityTransitionResult.Changed(
                        Optional.of(new User(new PocomaUserId(new UUID(1, 2)))), state(1, USER, TOKEN))),
                () -> assertThrows(NullPointerException.class, () -> new IdentityTransitionResult.Rejected(null)));
    }

    @Test
    void tokensNeverAppearInDefaultDiagnosticRepresentations() {
        assertFalse(TOKEN.toString().contains(TOKEN.value()));
        assertFalse(state(1, USER, TOKEN).toString().contains(TOKEN.value()));
        assertFalse(new IdentityTransitionResult.Changed(Optional.of(new User(USER)), state(1, USER, TOKEN))
                .toString().contains(TOKEN.value()));
    }

    @Test
    void externalKeysPreserveOpaqueTextWithoutCaseUrlOrUnicodeNormalization() {
        for (var different : List.of(key("https://ISSUER/", "Subject"), key("https://issuer", "Subject"),
                key(" https://issuer/", "Subject"), key("https://issuer/", "subject"),
                key("https://issuer/", " Subject "))) {
            var original = key("https://issuer/", "Subject");
            assertNotEquals(original, different);
            assertNotEquals(0, compareKeys(original, different));
        }
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertNotEquals(key("é", "x"), key("e\u0301", "x")),
                () -> assertNotEquals(0, compareKeys(key("é", "x"), key("e\u0301", "x"))),
                () -> assertEquals("e\u0301", key("e\u0301", "x").issuer()),
                () -> assertEquals(0, compareKeys(KEY, key("issuer", "subject"))));
    }

    @Test
    void independentUtf8VectorsOrderIssuerThenSubjectWithPrefixesAndUnsignedBytes() {
        // Hand-written expected UTF-8 byte order, not derived using the comparator under test.
        var strings = List.of("A", "AA", "e\u0301", "z", "\u007f", "\u0080", "é", "\ue000", "\ud800\udc00");
        assertOrderedPairs(strings.stream().map(s -> key(s, "same-subject")).toList());
        assertOrderedPairs(strings.stream().map(s -> key("same-issuer", s)).toList());
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertTrue(compareKeys(key("a", "z"), key("aa", "a")) < 0),
                () -> assertTrue(compareKeys(key("a", "z"), key("b", "a")) < 0),
                // Tuple order, deliberately unlike concatenation with ':' (prefix a / aa).
                () -> assertTrue(compareKeys(key("a", "zz"), key("a:", "a")) < 0),
                () -> assertTrue(compareKeys(key("\ue000", "s"), key("\ud800\udc00", "s")) < 0));
    }

    @Test
    void allPermutationsOfCanonicalVectorGroupsSortAndDeduplicateExactKeys() {
        var unicode = List.of(key("issuer", "\u007f"), key("issuer", "\u0080"),
                key("issuer", "\ue000"), key("issuer", "\ud800\udc00"));
        var tuples = List.of(key("a", "a"), key("a", "aa"), key("a", "e\u0301"),
                key("a", "é"), key("aa", "a"), key("b", "a"));
        for (var expected : List.of(unicode, tuples)) {
            assertAllPermutations(new ArrayList<>(expected), 0, expected);
            var withDuplicate = new ArrayList<>(expected);
            withDuplicate.add(expected.getFirst());
            assertAllPermutations(withDuplicate, 0, expected);
        }
    }

    @Test
    void nulAndIsolatedSurrogatesAreRejectedInEitherFieldAndEitherOperand() {
        for (String bad : List.of("a\0b", "a\ud800b", "a\udc00b", "\ud800", "\udc00")) {
            for (ExternalIdentity invalid : List.of(key(bad, "valid"), key("valid", bad))) {
                org.junit.jupiter.api.Assertions.assertAll(
                        () -> assertThrows(IllegalArgumentException.class, () -> compareKeys(invalid, KEY)),
                        () -> assertThrows(IllegalArgumentException.class, () -> compareKeys(KEY, invalid)),
                        () -> assertThrows(IllegalArgumentException.class, () -> compareKeys(invalid, invalid)),
                        () -> assertThrows(IllegalArgumentException.class,
                                () -> new ExternalUserIdentity(ID, invalid, 1, null, null)));
            }
        }
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThrows(NullPointerException.class, () -> compareKeys(null, KEY)),
                () -> assertThrows(NullPointerException.class, () -> compareKeys(KEY, null)),
                // A literal replacement character is valid and distinct from malformed UTF-16 input.
                () -> assertEquals(0, compareKeys(key("issuer", "\ufffd"), key("issuer", "\ufffd"))),
                () -> assertEquals(0, compareKeys(key("issuer", "😀"), key("issuer", "😀"))));
    }

    private static void assertOrderedPairs(List<ExternalIdentity> expected) {
        for (int i = 0; i < expected.size(); i++) {
            for (int j = 0; j < expected.size(); j++)
                assertEquals(Integer.signum(i - j), Integer.signum(compareKeys(expected.get(i), expected.get(j))));
        }
    }

    private static void assertAllPermutations(ArrayList<ExternalIdentity> input, int start,
                                               List<ExternalIdentity> expected) {
        if (start == input.size()) {
            assertEquals(expected, input.stream().distinct().sorted(ExternalUserIdentity::compareKeys).toList());
            return;
        }
        for (int i = start; i < input.size(); i++) {
            Collections.swap(input, start, i);
            assertAllPermutations(input, start + 1, expected);
            Collections.swap(input, start, i);
        }
    }

    private static ExternalIdentity key(String issuer, String subject) { return new ExternalIdentity(issuer, subject); }
    private static ExternalUserIdentity state(long version, PocomaUserId user, BindingToken token) {
        return new ExternalUserIdentity(ID, KEY, version, user, token);
    }
}
