package world.willfrog.build.methodspec;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class MethodSpecCanonicalizer {

    private static final String DIGEST_PREFIX = "sha256:";
    private static final ObjectMapper CANONICAL_MAPPER;

    static {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        CANONICAL_MAPPER = mapper;
    }

    private MethodSpecCanonicalizer() {
    }

    public static String canonicalJsonWithDigest(Map<String, Object> spec) {
        Map<String, Object> withoutDigest = new LinkedHashMap<>(spec);
        withoutDigest.remove("specDigest");
        @SuppressWarnings("unchecked")
        Map<String, Object> sortedWithoutDigest = (Map<String, Object>) deepSort(withoutDigest);
        byte[] canonicalBytes;
        try {
            canonicalBytes = CANONICAL_MAPPER.writeValueAsBytes(sortedWithoutDigest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String digest = DIGEST_PREFIX + sha256Hex(canonicalBytes);
        Map<String, Object> withDigest = new TreeMap<>(sortedWithoutDigest);
        withDigest.put("specDigest", digest);
        @SuppressWarnings("unchecked")
        Map<String, Object> sortedWithDigest = (Map<String, Object>) deepSort(withDigest);
        try {
            return new String(CANONICAL_MAPPER.writeValueAsBytes(sortedWithDigest), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] canonicalBytes(Map<String, Object> spec) {
        Map<String, Object> withoutDigest = new LinkedHashMap<>(spec);
        withoutDigest.remove("specDigest");
        @SuppressWarnings("unchecked")
        Map<String, Object> sorted = (Map<String, Object>) deepSort(withoutDigest);
        try {
            return CANONICAL_MAPPER.writeValueAsBytes(sorted);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String digestForCanonicalBytes(byte[] canonicalBytes) {
        return DIGEST_PREFIX + sha256Hex(canonicalBytes);
    }

    @SuppressWarnings("unchecked")
    private static Object deepSort(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), deepSort(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> list = new ArrayList<>(collection.size());
            for (Object item : collection) {
                list.add(deepSort(item));
            }
            return list;
        }
        if (value instanceof Number number) {
            return normalizeNumber(number);
        }
        return value;
    }

    private static Number normalizeNumber(Number number) {
        if (number instanceof BigInteger || number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return number;
        }
        if (number instanceof BigDecimal decimal) {
            BigDecimal stripped = decimal.stripTrailingZeros();
            if (stripped.scale() <= 0) {
                try {
                    return stripped.toBigIntegerExact();
                } catch (ArithmeticException e) {
                    return stripped;
                }
            }
            return stripped;
        }
        double doubleValue = number.doubleValue();
        if (Double.isInfinite(doubleValue) || Double.isNaN(doubleValue)) {
            return number;
        }
        if (doubleValue == Math.rint(doubleValue)) {
            long asLong = doubleValue < 0 ? (long) Math.ceil(doubleValue) : (long) Math.floor(doubleValue);
            if (asLong == doubleValue) {
                return asLong;
            }
        }
        return number;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
