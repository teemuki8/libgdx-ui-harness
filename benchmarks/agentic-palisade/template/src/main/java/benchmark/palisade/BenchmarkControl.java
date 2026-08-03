package benchmark.palisade;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;
import com.badlogic.gdx.utils.ScreenUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Executes a finite, local NDJSON command stream on the render thread. */
public final class BenchmarkControl implements AutoCloseable {
    private static final int MAX_COMMANDS = 256;
    private static final long MAX_COMMAND_BYTES = 1_048_576L;
    private static final int MAX_LINE_CHARACTERS = 16_384;
    private static final int MAX_RESULT_BYTES = 262_144;
    private static final int MAX_DIMENSION = 4096;
    private static final Pattern CAPTURE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Map<String, String> LIBGDX_KEY_NAMES = libgdxKeyNames();

    private final List<JsonValue> commands;
    private final AtomicEvidence evidence;
    private int nextCommand;
    private PendingResult pending;
    private String pendingCaptureId;
    private boolean closeRequested;
    private boolean exitRequested;
    private long completedFrames;

    private BenchmarkControl(List<JsonValue> commands, Path evidenceDirectory) throws IOException {
        this.commands = commands;
        evidence = new AtomicEvidence(evidenceDirectory);
    }

    /** Loads and bounds all commands before the native application starts. */
    public static BenchmarkControl open(Path commandFile, Path evidenceDirectory)
            throws IOException {
        Path normalizedCommands = commandFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedCommands, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Commands must be a local regular file");
        }
        long size = Files.size(normalizedCommands);
        if (size > MAX_COMMAND_BYTES) {
            throw new IllegalArgumentException("Command file exceeds the byte limit");
        }

        List<JsonValue> parsed = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(
                normalizedCommands, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.length() > MAX_LINE_CHARACTERS) {
                    throw new IllegalArgumentException("Command line exceeds the character limit");
                }
                if (parsed.size() == MAX_COMMANDS) {
                    throw new IllegalArgumentException("Command stream has too many commands");
                }
                JsonValue value;
                try {
                    value = new JsonReader().parse(line);
                } catch (RuntimeException malformed) {
                    throw new IllegalArgumentException("Malformed NDJSON command", malformed);
                }
                if (!value.isObject()) {
                    throw new IllegalArgumentException("Each command must be a JSON object");
                }
                String command = requireString(value, "command", 64);
                if (command.isBlank()) {
                    throw new IllegalArgumentException("Command name must not be blank");
                }
                parsed.add(value);
            }
        }
        return new BenchmarkControl(List.copyOf(parsed), evidenceDirectory);
    }

    /** Applies exactly one ordered command before the next deterministic draw. */
    public void beforeFrame(Stage stage) {
        if (pending != null) {
            return;
        }
        if (nextCommand >= commands.size()) {
            exitRequested = true;
            return;
        }

        JsonValue input = commands.get(nextCommand);
        int sequence = nextCommand++;
        String command = input.getString("command");
        try {
            switch (command) {
                case "capture" -> prepareCapture(input, sequence);
                case "resize" -> applyResize(input, sequence);
                case "key" -> applyKey(input, stage, sequence);
                case "pointer" -> applyPointer(input, stage, sequence);
                case "close" -> applyClose(input, sequence);
                default -> pending = PendingResult.error(
                        sequence, command, "UNKNOWN_COMMAND");
            }
        } catch (CommandRejected rejected) {
            pending = PendingResult.error(sequence, command, rejected.code);
        }
    }

    /** Records a result and any capture only after the Stage has finished drawing. */
    public void afterCompletedFrame(CandidateState state) {
        afterCompletedFrame(null, state);
    }

    /** Records trusted render-thread measurements with completed capture frames. */
    public void afterCompletedFrame(Stage stage, CandidateState state) {
        completedFrames++;
        if (pending == null) {
            return;
        }
        if (!pending.resizeCompleted()) {
            return;
        }
        try {
            Map<String, Object> trustedStructural = null;
            if (pendingCaptureId != null && pending.ok) {
                String relative = "captures/" + pendingCaptureId + ".png";
                Pixmap capture = captureCompletedFrame();
                if (stage != null) {
                    trustedStructural = TrustedStructuralProbe.capture(
                            stage, state, capture, completedFrames);
                }
                evidence.writeCapture(pendingCaptureId, capture);
                pending.artifact = relative;
            }
            evidence.appendResult(resultJson(pending, state, trustedStructural));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write benchmark evidence", failure);
        } finally {
            pending = null;
            pendingCaptureId = null;
        }
        if (closeRequested || nextCommand >= commands.size()) {
            exitRequested = true;
        }
    }

    /** Returns whether the finite command stream has requested application exit. */
    public boolean exitRequested() {
        return exitRequested;
    }

    @Override public void close() {
        // All writers are opened and closed for each atomic replacement.
    }

    private void prepareCapture(JsonValue input, int sequence) {
        requireOnly(input, "command", "id");
        String id = requireString(input, "id", 64);
        if (!CAPTURE_ID.matcher(id).matches()) {
            throw new CommandRejected("INVALID_CAPTURE_ID");
        }
        pendingCaptureId = id;
        pending = PendingResult.ok(sequence, "capture");
    }

    private void applyResize(JsonValue input, int sequence) {
        requireOnly(input, "command", "width", "height");
        int width = requireInteger(input, "width");
        int height = requireInteger(input, "height");
        if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) {
            throw new CommandRejected("INVALID_VIEWPORT");
        }
        if (!Gdx.graphics.setWindowedMode(width, height)) {
            throw new CommandRejected("RESIZE_FAILED");
        }
        pending = PendingResult.resize(sequence, width, height);
    }

    private void applyKey(JsonValue input, Stage stage, int sequence) {
        KeyCommand command = parseKey(input);
        Throwable failure = null;
        boolean shiftNeedsRelease = false;
        boolean controlNeedsRelease = false;
        boolean keyNeedsRelease = false;
        boolean persistentKeyDown = false;
        try {
            if (command.shift) {
                shiftNeedsRelease = true;
                stage.keyDown(Input.Keys.SHIFT_LEFT);
            }
            if (command.control) {
                controlNeedsRelease = true;
                stage.keyDown(Input.Keys.CONTROL_LEFT);
            }
            switch (command.action) {
                case TYPE -> stage.keyTyped(command.character);
                case DOWN -> {
                    keyNeedsRelease = true;
                    stage.keyDown(command.keyCode);
                    keyNeedsRelease = false;
                    persistentKeyDown = true;
                }
                case UP -> {
                    keyNeedsRelease = true;
                    stage.keyUp(command.keyCode);
                    keyNeedsRelease = false;
                }
                case PRESS -> {
                    keyNeedsRelease = true;
                    stage.keyDown(command.keyCode);
                    if (command.character != null) {
                        stage.keyTyped(command.character);
                    }
                    stage.keyUp(command.keyCode);
                    keyNeedsRelease = false;
                }
            }
        } catch (RuntimeException | Error callbackFailure) {
            failure = callbackFailure;
        } finally {
            if (keyNeedsRelease) {
                failure = releaseKey(stage, command.keyCode, failure);
            }
            if (controlNeedsRelease) {
                failure = releaseKey(stage, Input.Keys.CONTROL_LEFT, failure);
            }
            if (shiftNeedsRelease) {
                failure = releaseKey(stage, Input.Keys.SHIFT_LEFT, failure);
            }
            if (persistentKeyDown && failure != null) {
                failure = releaseKey(stage, command.keyCode, failure);
            }
        }
        rethrow(failure);
        pending = PendingResult.ok(sequence, "key");
    }

    private static KeyCommand parseKey(JsonValue input) {
        requireOnly(input, "command", "action", "key", "character", "shift", "control");
        String actionText = optionalString(input, "action", "press", 16);
        KeyAction action = switch (actionText) {
            case "type" -> KeyAction.TYPE;
            case "down" -> KeyAction.DOWN;
            case "up" -> KeyAction.UP;
            case "press" -> KeyAction.PRESS;
            default -> throw new CommandRejected("INVALID_KEY_ACTION");
        };
        boolean shift = optionalBoolean(input, "shift");
        boolean control = optionalBoolean(input, "control");
        if (action == KeyAction.TYPE) {
            requireOnly(input, "command", "action", "character", "shift", "control");
            return new KeyCommand(action, -1, requireCharacter(input), shift, control);
        }

        if (action == KeyAction.DOWN || action == KeyAction.UP) {
            requireOnly(input, "command", "action", "key", "shift", "control");
        }
        String keyName = requireString(input, "key", 32);
        String libgdxKeyName = LIBGDX_KEY_NAMES.get(keyName);
        int keyCode = libgdxKeyName == null ? -1 : Input.Keys.valueOf(libgdxKeyName);
        if (keyCode < 0) {
            throw new CommandRejected("INVALID_KEY");
        }
        Character character = input.get("character") == null
                ? null : requireCharacter(input);
        return new KeyCommand(action, keyCode, character, shift, control);
    }
    private static Map<String, String> libgdxKeyNames() {
        Map<String, String> names = new HashMap<>();
        for (int keyCode = 0; keyCode <= Input.Keys.MAX_KEYCODE; keyCode++) {
            String displayName = Input.Keys.toString(keyCode);
            if (displayName != null) {
                names.put(displayName.toUpperCase(Locale.ROOT), displayName);
            }
        }
        return Map.copyOf(names);
    }

    private static char requireCharacter(JsonValue input) {
        String character = requireString(input, "character", 2);
        if (character.length() != 1) {
            throw new CommandRejected("INVALID_KEY");
        }
        return character.charAt(0);
    }

    private static Throwable releaseKey(Stage stage, int keyCode, Throwable failure) {
        try {
            stage.keyUp(keyCode);
        } catch (RuntimeException | Error releaseFailure) {
            if (failure == null) {
                return releaseFailure;
            }
            if (failure != releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
        }
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void applyPointer(JsonValue input, Stage stage, int sequence) {
        requireOnly(input, "command", "action", "x", "y", "button", "amountX", "amountY");
        String action = requireString(input, "action", 16);
        if ("scroll".equals(action)) {
            float amountX = requireBoundedFloat(input, "amountX", -100f, 100f);
            float amountY = requireBoundedFloat(input, "amountY", -100f, 100f);
            stage.scrolled(amountX, amountY);
        } else {
            int x = requireInteger(input, "x");
            int y = requireInteger(input, "y");
            if (x < 0 || x >= Gdx.graphics.getWidth()
                    || y < 0 || y >= Gdx.graphics.getHeight()) {
                throw new CommandRejected("INVALID_POINTER");
            }
            switch (action) {
                case "move" -> stage.mouseMoved(x, y);
                case "drag" -> stage.touchDragged(x, y, 0);
                case "down" -> stage.touchDown(x, y, 0, pointerButton(input));
                case "up" -> stage.touchUp(x, y, 0, pointerButton(input));
                default -> throw new CommandRejected("INVALID_POINTER_ACTION");
            }
        }
        pending = PendingResult.ok(sequence, "pointer");
    }

    private void applyClose(JsonValue input, int sequence) {
        requireOnly(input, "command");
        closeRequested = true;
        pending = PendingResult.ok(sequence, "close");
    }

    private static int pointerButton(JsonValue input) {
        String button = optionalString(input, "button", "left", 16);
        return switch (button) {
            case "left" -> Input.Buttons.LEFT;
            case "right" -> Input.Buttons.RIGHT;
            case "middle" -> Input.Buttons.MIDDLE;
            default -> throw new CommandRejected("INVALID_POINTER_BUTTON");
        };
    }

    private static Pixmap captureCompletedFrame() {
        int width = Gdx.graphics.getBackBufferWidth();
        int height = Gdx.graphics.getBackBufferHeight();
        byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, width, height, true);
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        ByteBuffer target = pixmap.getPixels();
        target.clear();
        target.put(pixels);
        target.flip();
        return pixmap;
    }

    private static String resultJson(
            PendingResult result,
            CandidateState state,
            Map<String, Object> trustedStructural) {
        JsonValue object = new JsonValue(JsonValue.ValueType.object);
        object.addChild("sequence", new JsonValue((long) result.sequence));
        object.addChild("command", new JsonValue(result.command));
        object.addChild("ok", new JsonValue(result.ok));
        if (result.error != null) {
            object.addChild("error", new JsonValue(result.error));
        }
        if (result.artifact != null) {
            object.addChild("artifact", new JsonValue(result.artifact));
        }
        JsonValue stateObject = new JsonValue(JsonValue.ValueType.object);
        for (Map.Entry<String, Object> entry : state.values().entrySet()) {
            stateObject.addChild(entry.getKey(), jsonScalar(entry.getValue()));
        }
        object.addChild("state", stateObject);
        if (trustedStructural != null) {
            object.addChild("trustedStructural", jsonScalar(trustedStructural));
        }
        String json = object.toJson(JsonWriter.OutputType.json);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("Candidate result exceeds the byte limit");
        }
        return json;
    }

    private static JsonValue jsonScalar(Object value) {
        if (value == null) {
            return new JsonValue(JsonValue.ValueType.nullValue);
        }
        if (value instanceof Map<?, ?> map) {
            JsonValue object = new JsonValue(JsonValue.ValueType.object);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.addChild((String) entry.getKey(), jsonScalar(entry.getValue()));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            JsonValue array = new JsonValue(JsonValue.ValueType.array);
            for (Object element : list) {
                array.addChild(jsonScalar(element));
            }
            return array;
        }
        if (value instanceof String text) {
            return new JsonValue(text);
        }
        if (value instanceof Boolean bool) {
            return new JsonValue(bool);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return new JsonValue(((Number) value).longValue());
        }
        return new JsonValue(((Number) value).doubleValue());
    }

    private static void requireOnly(JsonValue object, String... allowedNames) {
        Set<String> allowed = new HashSet<>(Arrays.asList(allowedNames));
        for (JsonValue child = object.child; child != null; child = child.next) {
            if (!allowed.contains(child.name)) {
                throw new CommandRejected("UNKNOWN_FIELD");
            }
        }
    }

    private static String requireString(JsonValue object, String name, int maxLength) {
        JsonValue value = object.get(name);
        if (value == null || !value.isString()) {
            throw new CommandRejected("INVALID_COMMAND");
        }
        String text = value.asString();
        if (text.length() > maxLength) {
            throw new CommandRejected("INVALID_COMMAND");
        }
        return text;
    }

    private static String optionalString(
            JsonValue object, String name, String defaultValue, int maxLength) {
        JsonValue value = object.get(name);
        return value == null ? defaultValue : requireString(object, name, maxLength);
    }

    private static boolean optionalBoolean(JsonValue object, String name) {
        JsonValue value = object.get(name);
        if (value == null) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new CommandRejected("INVALID_COMMAND");
        }
        return value.asBoolean();
    }

    private static int requireInteger(JsonValue object, String name) {
        JsonValue value = object.get(name);
        if (value == null || !value.isLong()) {
            throw new CommandRejected("INVALID_COMMAND");
        }
        long number = value.asLong();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new CommandRejected("INVALID_COMMAND");
        }
        return (int) number;
    }

    private static float requireBoundedFloat(
            JsonValue object, String name, float minimum, float maximum) {
        JsonValue value = object.get(name);
        if (value == null || !value.isNumber()) {
            throw new CommandRejected("INVALID_COMMAND");
        }
        float number = value.asFloat();
        if (!Float.isFinite(number) || number < minimum || number > maximum) {
            throw new CommandRejected("INVALID_COMMAND");
        }
        return number;
    }

    private enum KeyAction {
        TYPE,
        DOWN,
        UP,
        PRESS
    }

    private record KeyCommand(
            KeyAction action,
            int keyCode,
            Character character,
            boolean shift,
            boolean control) {
    }

    private static final class CommandRejected extends RuntimeException {
        private final String code;

        private CommandRejected(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    private static final class PendingResult {
        private final int sequence;
        private final String command;
        private final boolean ok;
        private final String error;
        private final int resizeWidth;
        private final int resizeHeight;
        private String artifact;
        private int resizeWaitFrames;

        private PendingResult(
                int sequence,
                String command,
                boolean ok,
                String error,
                int resizeWidth,
                int resizeHeight) {
            this.sequence = sequence;
            this.command = command;
            this.ok = ok;
            this.error = error;
            this.resizeWidth = resizeWidth;
            this.resizeHeight = resizeHeight;
        }

        private static PendingResult ok(int sequence, String command) {
            return new PendingResult(sequence, command, true, null, 0, 0);
        }

        private static PendingResult resize(int sequence, int width, int height) {
            return new PendingResult(sequence, "resize", true, null, width, height);
        }

        private static PendingResult error(int sequence, String command, String error) {
            return new PendingResult(sequence, command, false, error, 0, 0);
        }

        private boolean resizeCompleted() {
            if (resizeWidth == 0
                    || (Gdx.graphics.getBackBufferWidth() == resizeWidth
                            && Gdx.graphics.getBackBufferHeight() == resizeHeight)) {
                return true;
            }
            if (++resizeWaitFrames > 120) {
                throw new IllegalStateException("Timed out waiting for completed resize");
            }
            return false;
        }
    }

    private static final class AtomicEvidence {
        private final Path root;
        private final Path captures;
        private final Path results;
        private final StringBuilder resultContent = new StringBuilder();

        private AtomicEvidence(Path directory) throws IOException {
            root = directory.toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Evidence must be a local directory");
            }
            captures = root.resolve("captures");
            Files.createDirectories(captures);
            if (!Files.isDirectory(captures, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Capture evidence path is not a directory");
            }
            results = root.resolve("results.ndjson");
            replaceAtomically(results, new byte[0]);
        }

        private void appendResult(String json) throws IOException {
            resultContent.append(json).append('\n');
            replaceAtomically(results,
                    resultContent.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void writeCapture(String id, Pixmap pixmap) throws IOException {
            Path destination = captures.resolve(id + ".png");
            Path temporary = Files.createTempFile(captures, ".capture.tmp-", ".png");
            boolean moved = false;
            try {
                PixmapIO.writePNG(new FileHandle(temporary.toFile()), pixmap);
                forceFile(temporary);
                atomicMove(temporary, destination);
                moved = true;
            } finally {
                pixmap.dispose();
                if (!moved) {
                    Files.deleteIfExists(temporary);
                }
            }
        }

        private static void replaceAtomically(Path destination, byte[] bytes)
                throws IOException {
            Path temporary = Files.createTempFile(
                    destination.getParent(), ".results.tmp-", "");
            boolean moved = false;
            try {
                Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                forceFile(temporary);
                atomicMove(temporary, destination);
                moved = true;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temporary);
                }
            }
        }

        private static void forceFile(Path path) throws IOException {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }

        private static void atomicMove(Path source, Path destination) throws IOException {
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Evidence filesystem does not support atomic moves", unsupported);
            }
        }
    }
}
