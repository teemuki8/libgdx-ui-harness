package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.gdx.uiharness.protocol.ProtocolJson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class BoundedJsonRpcFramerTest {
    private static final int MAX_FRAME_BYTES = ProtocolJson.MAX_REQUEST_BYTES;

    @Test void frameAtByteLimitIsAccepted() throws Exception {
        assertInstanceOf(BoundedJsonRpcFramer.Frame.Message.class,
                frameOfBytes(MAX_FRAME_BYTES));
    }

    @Test void limitPlusOneFrameIsRejected() throws Exception {
        assertEquals("frame-too-large", rejectionOfBytes(MAX_FRAME_BYTES + 1).code());
    }

    @Test void unterminatedOversizedFrameIsRejectedAtEof() throws Exception {
        assertEquals("frame-too-large",
                rejectionOfUnterminatedBytes(MAX_FRAME_BYTES + 1).code());
    }

    @Test void malformedUtf8IsRejected() throws Exception {
        assertEquals("invalid-utf8",
                rejectionOf(new byte[] {(byte) 0xc3, 0x28, '\n'}).code());
    }

    @Test void emptyInputYieldsEndOfInput() throws Exception {
        assertInstanceOf(BoundedJsonRpcFramer.Frame.EndOfInput.class, framer(new byte[0]).read());
    }

    @Test void unterminatedInLimitFrameIsRejectedAtEof() throws Exception {
        BoundedJsonRpcFramer.Frame frame =
                framer("{\"id\":1}".getBytes(StandardCharsets.UTF_8)).read();
        assertInstanceOf(BoundedJsonRpcFramer.Frame.Rejected.class, frame);
        assertEquals("unterminated-frame",
                ((BoundedJsonRpcFramer.Frame.Rejected) frame).code());
    }

    @Test void crlfTerminatedFrameStripsOneCarriageReturn() throws Exception {
        BoundedJsonRpcFramer.Frame frame =
                framer("{\"id\":1}\r\n".getBytes(StandardCharsets.UTF_8)).read();
        assertInstanceOf(BoundedJsonRpcFramer.Frame.Message.class, frame);
        assertEquals("{\"id\":1}", ((BoundedJsonRpcFramer.Frame.Message) frame).json());
    }

    @Test void multibyteUtf8FrameDecodesStrictly() throws Exception {
        BoundedJsonRpcFramer.Frame frame =
                framer("{\"id\":\"äöü\"}\n".getBytes(StandardCharsets.UTF_8)).read();
        assertInstanceOf(BoundedJsonRpcFramer.Frame.Message.class, frame);
        assertEquals("{\"id\":\"äöü\"}", ((BoundedJsonRpcFramer.Frame.Message) frame).json());
    }

    @Test void oversizedFrameDrainsThroughNewlineThenReturnsNextFrame() throws Exception {
        byte[] oversized = new byte[MAX_FRAME_BYTES + 1];
        Arrays.fill(oversized, (byte) 'x');
        byte[] input = concat(oversized, "\n{\"id\":1}\n".getBytes(StandardCharsets.UTF_8));
        BoundedJsonRpcFramer framer = framer(input);

        BoundedJsonRpcFramer.Frame rejected = framer.read();
        assertInstanceOf(BoundedJsonRpcFramer.Frame.Rejected.class, rejected);
        assertEquals("frame-too-large", ((BoundedJsonRpcFramer.Frame.Rejected) rejected).code());

        BoundedJsonRpcFramer.Frame message = framer.read();
        assertInstanceOf(BoundedJsonRpcFramer.Frame.Message.class, message);
        assertEquals("{\"id\":1}", ((BoundedJsonRpcFramer.Frame.Message) message).json());
    }

    private static BoundedJsonRpcFramer.Frame frameOfBytes(int count) throws IOException {
        byte[] bytes = new byte[count + 1];
        Arrays.fill(bytes, 0, count, (byte) 'x');
        bytes[count] = '\n';
        return framer(bytes).read();
    }

    private static BoundedJsonRpcFramer.Frame.Rejected rejectionOfBytes(int count)
            throws IOException {
        byte[] bytes = new byte[count + 1];
        Arrays.fill(bytes, 0, count, (byte) 'x');
        bytes[count] = '\n';
        return rejected(framer(bytes).read());
    }

    private static BoundedJsonRpcFramer.Frame.Rejected rejectionOfUnterminatedBytes(int count)
            throws IOException {
        byte[] bytes = new byte[count];
        Arrays.fill(bytes, (byte) 'x');
        return rejected(framer(bytes).read());
    }

    private static BoundedJsonRpcFramer.Frame.Rejected rejectionOf(byte[] bytes)
            throws IOException {
        return rejected(framer(bytes).read());
    }

    private static BoundedJsonRpcFramer.Frame.Rejected rejected(BoundedJsonRpcFramer.Frame frame) {
        assertInstanceOf(BoundedJsonRpcFramer.Frame.Rejected.class, frame);
        return (BoundedJsonRpcFramer.Frame.Rejected) frame;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    private static BoundedJsonRpcFramer framer(byte[] bytes) {
        return new BoundedJsonRpcFramer(new ByteArrayInputStream(bytes), MAX_FRAME_BYTES);
    }
}
