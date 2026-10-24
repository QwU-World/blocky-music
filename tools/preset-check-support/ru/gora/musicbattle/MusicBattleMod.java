package ru.gora.musicbattle;

/** Test-only launch boundary: preset storage must not register/start the Minecraft mod. */
public final class MusicBattleMod {
    public static final CheckLogger LOGGER = new CheckLogger();

    public static final class CheckLogger {
        public void warn(String message, Object... arguments) {
            for (Object argument : arguments) {
                if (argument instanceof Throwable cause) {
                    System.err.println("Expected/read diagnostic: " + cause.getMessage());
                }
            }
        }
    }
}
