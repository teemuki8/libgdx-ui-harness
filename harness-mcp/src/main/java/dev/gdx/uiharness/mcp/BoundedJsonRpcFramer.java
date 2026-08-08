package dev.gdx.uiharness.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Byte-counted, strict-UTF-8 newline framer for JSON-RPC stdio messages.
 *
 * <p>Reads newline-delimited frames without ever materializing more than
 * {@code maxFrameBytes} bytes of a candidate frame, so a hostile or broken client cannot
 * grow heap without bound before JSON parsing starts. LF terminates a frame and one
 * preceding CR is stripped. Frames are decoded with a strict UTF-8 decoder
 * ({@link CodingErrorAction#REPORT}), so malformed input is rejected instead of being
 * silently replaced.
 *
 * <p>An in-limit frame that never reaches a terminating LF is a bounded
 * {@code unterminated-frame} rejection at EOF. A frame that exceeds the cap is rejected
 * with {@code frame-too-large} and the remainder of the rejected frame is drained to its
 * terminating LF in constant memory, so the following valid frame is still delivered.
 * Only the rejection code is retained for diagnostics; rejected frame content is never
 * echoed.
 */
final class BoundedJsonRpcFramer {
    private static final String CODE_FRAME_TOO_LARGE = "frame-too-large";
    private static final String CODE_INVALID_UTF8 = "invalid-utf8";
    private static final String CODE_UNTERMINATED = "unterminated-frame";
    private static final int CHUNK_BYTES = 8 * 1024;

    /** Result of one {@link #read()}. */
    sealed interface Frame {
        /** One in-limit, strictly valid UTF-8 frame terminated by LF (one CR stripped). */
        record Message(String json) implements Frame {}

        /** A frame rejected before parsing; carries only its bounded rejection code. */
        record Rejected(String code) implements Frame {}

        /** The input ended with no bytes left for another frame. */
        record EndOfInput() implements Frame {}
    }

    private final InputStream input;
    private final int maxFrameBytes;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private final byte[] chunk = new byte[CHUNK_BYTES];
    private byte[] frame;
    private int frameLength;
    private boolean overflowed;
    private int chunkOffset;
    private int chunkLength;

    BoundedJsonRpcFramer(InputStream input, int maxFrameBytes) {
        this.input = Objects.requireNonNull(input, "input");
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    /** Returns the next frame, or {@link EndOfInput} when the input is exhausted. */
    Frame read() throws IOException {
        frameLength = 0;
        overflowed = false;
        while (true) {
            int value = nextByte();
            if (value < 0) {
                return eofFrame();
            }
            if (value == '\n') {
                if (overflowed) {
                    return new Frame.Rejected(CODE_FRAME_TOO_LARGE);
                }
                try {
                    return new Frame.Message(decodeFrame());
                } catch (CharacterCodingException malformed) {
                    return new Frame.Rejected(CODE_INVALID_UTF8);
                }
            }
            if (frameLength < maxFrameBytes) {
                if (frame == null) {
                    frame = new byte[maxFrameBytes];
                }
                frame[frameLength++] = (byte) value;
            } else {
                overflowed = true;
            }
        }
    }

    private String decodeFrame() throws CharacterCodingException {
        if (frameLength > 0 && frame[frameLength - 1] == '\r') {
            frameLength--;
        }
        if (frameLength == 0) {
            return "";
        }
        decoder.reset();
        CharBuffer decoded = decoder.decode(ByteBuffer.wrap(frame, 0, frameLength));
        return decoded.toString();
    }

    private Frame eofFrame() {
        if (overflowed) {
            return new Frame.Rejected(CODE_FRAME_TOO_LARGE);
        }
        if (frameLength == 0) {
            return new Frame.EndOfInput();
        }
        return new Frame.Rejected(CODE_UNTERMINATED);
    }

    private int nextByte() throws IOException {
        if (chunkOffset >= chunkLength) {
            chunkLength = input.read(chunk, 0, chunk.length);
            chunkOffset = 0;
            if (chunkLength < 0) {
                return -1;
            }
        }
        return chunk[chunkOffset++] & 0xff;
    }
}
