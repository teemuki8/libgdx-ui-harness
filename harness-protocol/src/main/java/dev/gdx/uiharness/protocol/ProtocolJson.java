package dev.gdx.uiharness.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Objects;

/** Hardened canonical JSON mapping and byte-bounded transport codec for protocol V1. */
public final class ProtocolJson {
    /** Maximum accepted raw request size before any JSON tokens are parsed. */
    public static final int MAX_REQUEST_BYTES = 1_048_576;
    /** Maximum encoded response size. */
    public static final int MAX_RESPONSE_BYTES = 16_777_216;
    /** Maximum ordinary request/evidence string length enforced by DTO constructors. */
    public static final int MAX_STRING_LENGTH = 16_384;
    /** Maximum JSON number token length. */
    public static final int MAX_NUMBER_LENGTH = 128;
    /** Maximum JSON nesting depth. */
    public static final int MAX_NESTING_DEPTH = 64;
    private static final int MAX_IDENTIFIER_LENGTH = 256;
    private static final ObjectMapper MAPPER =
            createMapper(HarnessResponse.Result.Screenshot.MAX_PNG_BYTES / 3 * 4);
    private static final ObjectMapper REQUEST_MAPPER = createMapper(MAX_STRING_LENGTH);

    private ProtocolJson() {}

    /** Returns the shared immutable-configuration mapper. Never enable default typing on it. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Decodes one raw request after enforcing its byte limit before tokenization. */
    public static HarnessRequest decode(byte[] json) {
        Objects.requireNonNull(json, "json");
        if (json.length > MAX_REQUEST_BYTES) {
            throw new ProtocolJsonException("limit-exceeded",
                    "request exceeds " + MAX_REQUEST_BYTES + " bytes", null);
        }
        try {
            return REQUEST_MAPPER.readValue(json, HarnessRequest.class);
        } catch (IOException failure) {
            throw new ProtocolJsonException("invalid-request", "malformed protocol JSON", failure);
        }
    }

    /** Canonically encodes one request, rejecting locally constructed oversized payloads. */
    public static byte[] encode(HarnessRequest request) {
        return encodeBounded(request, MAX_REQUEST_BYTES, "request");
    }

    /** Canonically encodes one response with a hard response-byte limit. */
    public static byte[] encode(HarnessResponse response) {
        return encodeBounded(response, MAX_RESPONSE_BYTES, "response");
    }

    private static byte[] encodeBounded(Object value, int maximum, String kind) {
        Objects.requireNonNull(value, "value");
        try {
            byte[] encoded = MAPPER.writeValueAsBytes(value);
            if (encoded.length > maximum) {
                throw new ProtocolJsonException("limit-exceeded",
                        kind + " exceeds " + maximum + " bytes", null);
            }
            return encoded;
        } catch (JsonProcessingException failure) {
            throw new ProtocolJsonException("invalid-request",
                    "cannot encode protocol " + kind, failure);
        }
    }

    private static ObjectMapper createMapper(int maxStringLength) {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(maxStringLength)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .build();
        return JsonMapper.builder(factory)
                .enable(MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds identifier limit");
        }
        return value;
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds protocol string limit");
        }
        return value;
    }

    /** Deterministic local codec exception safe to translate at a transport boundary. */
    @SuppressWarnings("serial")
    public static final class ProtocolJsonException extends RuntimeException {
        private final String code;

        ProtocolJsonException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        /** Returns a stable protocol error code. */
        public String code() {
            return code;
        }
    }
}
