package ru.gora.musicbattle.client.ui;

import java.util.function.Consumer;

/**
 * Registers an optional shader without letting a failure escape. In 1.21.1 an exception from a
 * {@code RegisterShadersEvent} listener aborts the whole shader reload and with it the resource reload, so a
 * driver that rejects {@code flat ivec2} or {@code fwidth} would break the game. The toolkit's shaders are
 * cosmetic: on failure the shader stays null and drawing falls back to vanilla fills.
 */
public final class UiShaderGuard {
    @FunctionalInterface
    public interface Factory<T> {
        T create() throws Exception;
    }

    private UiShaderGuard() {
    }

    /**
     * Builds the shader and hands it to {@code register}. Returns false, after {@code onFailure}, if building
     * or registering throws. Never throws itself; {@code Error}s other than linkage errors still propagate.
     */
    public static <T> boolean register(String name, Factory<T> factory, Consumer<T> register,
                                       Consumer<Throwable> onFailure) {
        T shader = null;
        try {
            shader = factory.create();
            if (shader == null) {
                throw new IllegalStateException("shader factory returned null for " + name);
            }
            register.accept(shader);
            return true;
        } catch (Exception | LinkageError failure) {
            if (shader instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                onFailure.accept(failure);
            } catch (RuntimeException ignored) {
                // Logging must not undo the guard.
            }
            return false;
        }
    }
}
