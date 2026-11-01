import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ClientPayloadBridge reaches client-only classes by name, so the compiler cannot see those calls:
 * rename ClientBattleController.openDjPanel and the build stays green while the DJ panel silently
 * stops opening, leaving one warning in the log. This check reads the bridge's source and proves
 * that every name it dispatches to exists, with those exact parameter types, as a public static
 * method. It also fails if a dispatch is written in a shape it cannot read, so a new call can never
 * slip past it unchecked.
 *
 * Usage: java -cp <classpath> ClientPayloadBridgeChecks <project root>
 */
public final class ClientPayloadBridgeChecks {
    static int checks;

    private static final String BRIDGE = "src/main/java/ru/gora/musicbattle/network/ClientPayloadBridge.java";
    private static final String CONTROLLER = "ru.gora.musicbattle.client.ClientBattleController";
    /** The second owner: everything the server's Workshop features send. */
    private static final String WORKSHOP_HOOKS = "ru.gora.musicbattle.client.workshop.WorkshopClientHooks";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        String source = Files.readString(root.resolve(BRIDGE));
        ClassLoader loader = ClientPayloadBridgeChecks.class.getClassLoader();
        Class<?> controller = Class.forName(CONTROLLER, false, loader);

        // invokeClient("name", new Class<?>[]{A.class, B.class}, ...), including calls split over lines.
        Matcher dispatch = Pattern.compile(
                "invokeClient\\(\\s*\"(\\w+)\"\\s*,\\s*new\\s+Class<\\?>\\[]\\s*\\{([^}]*)}").matcher(source);
        int dispatches = 0;
        while (dispatch.find()) {
            dispatches++;
            requireStatic(controller, dispatch.group(1), types(dispatch.group(2), loader));
        }
        int written = count(source, "invokeClient\\(\\s*\"");
        check(written == dispatches,
                "every invokeClient call is readable: " + written + " written, " + dispatches + " parsed");
        check(dispatches >= 25, "the bridge still dispatches its 25 client handlers, found " + dispatches);

        // The same rule for the workshop owner, which has its own dispatch helper and its own minimum count.
        Class<?> hooks = Class.forName(WORKSHOP_HOOKS, false, loader);
        Matcher workshop = Pattern.compile(
                "invokeWorkshop\\(\\s*\"(\\w+)\"\\s*,\\s*new\\s+Class<\\?>\\[]\\s*\\{([^}]*)}").matcher(source);
        int workshopDispatches = 0;
        while (workshop.find()) {
            workshopDispatches++;
            requireStatic(hooks, workshop.group(1), types(workshop.group(2), loader));
        }
        int workshopWritten = count(source, "invokeWorkshop\\(\\s*\"");
        check(workshopWritten == workshopDispatches,
                "every invokeWorkshop call is readable: " + workshopWritten + " written, "
                        + workshopDispatches + " parsed");
        check(workshopDispatches >= 8,
                "the bridge still dispatches its 8 workshop handlers, found " + workshopDispatches);

        // Class.forName("owner") followed by getMethod("name", A.class, ...), as the cache lookup does.
        Matcher lookup = Pattern.compile(
                "Class\\.forName\\(\"([\\w.]+)\"\\)(?:(?!Class\\.forName).)*?\\.getMethod\\(\\s*\"(\\w+)\"\\s*((?:,\\s*[\\w.\\[\\]]+\\.class\\s*)*)\\)",
                Pattern.DOTALL).matcher(source);
        int lookups = 0;
        while (lookup.find()) {
            lookups++;
            Class<?> owner = Class.forName(lookup.group(1), false, loader);
            requireStatic(owner, lookup.group(2), types(lookup.group(3).replaceFirst("^\\s*,", ""), loader));
        }
        check(lookups >= 1, "the track-cache lookup behind DJ preload answers is still checked, found " + lookups);

        System.out.println("Client payload bridge checks passed: " + checks
                + " (" + dispatches + " battle dispatches, " + workshopDispatches + " workshop dispatches, "
                + lookups + " direct lookups)");
    }

    private static void requireStatic(Class<?> owner, String name, Class<?>[] parameters) {
        Method method;
        try {
            method = owner.getMethod(name, parameters);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(owner.getSimpleName() + "." + name + signature(parameters)
                    + " is gone: the bridge would log a warning and drop the payload", missing);
        }
        check(Modifier.isStatic(method.getModifiers()),
                owner.getSimpleName() + "." + name + " must be static, the bridge invokes it with no instance");
    }

    private static Class<?>[] types(String list, ClassLoader loader) throws ClassNotFoundException {
        String trimmed = list.trim();
        if (trimmed.isEmpty()) {
            return new Class<?>[0];
        }
        String[] parts = trimmed.split("\\s*,\\s*");
        Class<?>[] result = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = type(parts[i].replaceFirst("\\.class$", "").trim(), loader);
        }
        return result;
    }

    private static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
        if (name.endsWith("[]")) {
            return type(name.substring(0, name.length() - 2), loader).arrayType();
        }
        switch (name) {
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "char": return char.class;
            default: break;
        }
        if (name.contains(".")) {
            return Class.forName(name, false, loader);
        }
        for (String prefix : new String[]{"ru.gora.musicbattle.network.", "java.lang.", "java.util."}) {
            try {
                return Class.forName(prefix + name, false, loader);
            } catch (ClassNotFoundException tryNext) {
                // the next package may own it
            }
        }
        throw new ClassNotFoundException(name + " (looked in network, java.lang and java.util)");
    }

    private static String signature(Class<?>[] parameters) {
        StringBuilder out = new StringBuilder("(");
        for (int i = 0; i < parameters.length; i++) {
            out.append(i == 0 ? "" : ", ").append(parameters[i].getSimpleName());
        }
        return out.append(')').toString();
    }

    private static int count(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    static void check(boolean value, String message) {
        checks++;
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
