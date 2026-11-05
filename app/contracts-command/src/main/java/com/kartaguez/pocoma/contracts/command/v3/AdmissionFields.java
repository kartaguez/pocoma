package com.kartaguez.pocoma.contracts.command.v3;

import static com.kartaguez.pocoma.contracts.command.v3.AdmissionFailure.require;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Closed input shapes and unambiguous canonical framing. No business authority or IO. */
public final class AdmissionFields {
    private AdmissionFields() {}

    public static Map<String, Object> object(Object value, String... fields) {
        require(value instanceof Map<?, ?>);
        var copy = new TreeMap<String, Object>();
        ((Map<?, ?>) value)
                .forEach(
                        (k, v) -> {
                            require(k instanceof String);
                            copy.put((String) k, v);
                        });
        require(copy.keySet().equals(Set.of(fields)));
        return copy;
    }

    public static String text(Map<String, Object> m, String key) {
        require(m.get(key) instanceof String);
        return (String) m.get(key);
    }

    public static String nonblank(Map<String, Object> m, String key) {
        var s = text(m, key);
        require(!s.isBlank());
        return s;
    }

    public static UUID uuid(Map<String, Object> m, String key) {
        String s = text(m, key);
        require(
                s.matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
        UUID id = UUID.fromString(s);
        m.put(key, id.toString());
        return id;
    }

    public static List<?> list(Map<String, Object> m, String key) {
        require(m.get(key) instanceof List<?>);
        return (List<?>) m.get(key);
    }

    /** Reject malformed UTF-16 instead of UTF-8's lossy replacement with '?'. */
    static byte[] utf8(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c))
                require(++i < text.length() && Character.isLowSurrogate(text.charAt(i)));
            else require(!Character.isLowSurrogate(c));
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] canonical(Object value) {
        var bytes = new ByteArrayOutputStream();
        try {
            encode(new DataOutputStream(bytes), value);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return bytes.toByteArray();
    }

    private static void encode(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.writeByte(0);
            return;
        }
        if (value instanceof String text) {
            out.writeByte(1);
            byte[] b = utf8(text);
            out.writeInt(b.length);
            out.write(b);
            return;
        }
        if (value instanceof Long || value instanceof Integer) {
            out.writeByte(2);
            out.writeLong(((Number) value).longValue());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.writeByte(3);
            out.writeInt(map.size());
            var sorted = new TreeMap<String, Object>();
            map.forEach((k, v) -> sorted.put((String) k, v));
            for (var e : sorted.entrySet()) {
                encode(out, e.getKey());
                encode(out, e.getValue());
            }
            return;
        }
        if (value instanceof List<?> list) {
            out.writeByte(4);
            out.writeInt(list.size());
            for (var item : list) encode(out, item);
            return;
        }
        throw new AdmissionFailure(400);
    }

    public static void textsWithin(Object value, int bytes) {
        if (value instanceof String s) require(utf8(s).length <= bytes);
        else if (value instanceof Map<?, ?> m) m.values().forEach(v -> textsWithin(v, bytes));
        else if (value instanceof List<?> l) l.forEach(v -> textsWithin(v, bytes));
    }
}
