package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plain text out of the sanitised markdown HTML the workshop returns for descriptions and comments (spec 06 section
 * 6.2 and 6.3: "plain text rendering with links"). Block tags become line breaks, list items get a bullet, entities
 * are decoded and every {@code <a href>} is collected so the screen can offer it through the confirm-link dialog.
 *
 * <p>Deliberately not an HTML parser: the server already sanitises, and anything unknown is simply dropped.
 */
public final class HtmlText {
    public static final int MAX_LENGTH = 20_000;
    private static final Map<String, String> ENTITIES = new LinkedHashMap<>();

    static {
        ENTITIES.put("amp", "&");
        ENTITIES.put("lt", "<");
        ENTITIES.put("gt", ">");
        ENTITIES.put("quot", "\"");
        ENTITIES.put("apos", "'");
        ENTITIES.put("#39", "'");
        ENTITIES.put("nbsp", " ");
        ENTITIES.put("hellip", "…");
        ENTITIES.put("mdash", "—");
        ENTITIES.put("ndash", "–");
        ENTITIES.put("laquo", "«");
        ENTITIES.put("raquo", "»");
    }

    /**
     * One link as the reader meets it: the words that were drawn, and where they go.
     *
     * The two differ whenever the writer used [words](address): what reaches the screen is the words, so matching a
     * clicked word against the address finds nothing. Keeping both is what makes such a link openable at all.
     */
    public record Link(String text, String href) {
    }

    /** The plain text, the links it contained in order, and the words each of them was written as. */
    public record Result(String text, List<String> links, List<Link> anchors) {
    }

    private HtmlText() {
    }

    public static Result parse(String html) {
        List<String> links = new ArrayList<>();
        List<Link> anchors = new ArrayList<>();
        String openHref = null;
        int openAt = -1;
        if (html == null || html.isEmpty()) {
            return new Result("", links, anchors);
        }
        String source = html.length() > MAX_LENGTH ? html.substring(0, MAX_LENGTH) : html;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '<') {
                int end = source.indexOf('>', i);
                if (end < 0) {
                    break;
                }
                String tag = source.substring(i + 1, end).trim();
                String name = tagName(tag);
                switch (name) {
                    case "br", "/p", "/div", "/h1", "/h2", "/h3", "/h4", "/blockquote", "/pre" -> newline(out);
                    case "li" -> {
                        newline(out);
                        out.append("· ");
                    }
                    case "/li", "/ul", "/ol", "/tr" -> newline(out);
                    case "a" -> {
                        String href = attribute(tag, "href");
                        if (href != null && !href.isEmpty()) {
                            links.add(href);
                            openHref = href;
                            openAt = out.length();
                        }
                    }
                    case "/a" -> {
                        if (openHref != null && openAt >= 0 && out.length() > openAt) {
                            String label = out.substring(openAt).strip();
                            if (!label.isEmpty()) {
                                anchors.add(new Link(label, openHref));
                            }
                        }
                        openHref = null;
                        openAt = -1;
                    }
                    default -> {
                        // Inline formatting and unknown tags contribute nothing of their own.
                    }
                }
                i = end + 1;
                continue;
            }
            if (c == '&') {
                int end = source.indexOf(';', i);
                if (end > i && end - i <= 10) {
                    String entity = source.substring(i + 1, end).toLowerCase(Locale.ROOT);
                    String replacement = ENTITIES.get(entity);
                    if (replacement == null && entity.startsWith("#")) {
                        replacement = codePoint(entity.substring(1));
                    }
                    if (replacement != null) {
                        out.append(replacement);
                        i = end + 1;
                        continue;
                    }
                }
            }
            if (c == '\r') {
                i++;
                continue;
            }
            if (c == '\n') {
                out.append(' ');
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return new Result(collapse(out.toString()), links, anchors);
    }

    /** Plain text with no markup at all (comment bodies used as tooltips). */
    public static String plain(String html) {
        return parse(html).text();
    }

    private static void newline(StringBuilder out) {
        while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static String codePoint(String value) {
        try {
            int code = value.startsWith("x") || value.startsWith("X")
                    ? Integer.parseInt(value.substring(1), 16)
                    : Integer.parseInt(value);
            return code > 0 && code <= 0x10FFFF ? new String(Character.toChars(code)) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String tagName(String tag) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (Character.isWhitespace(c) || c == '/' && i > 0) {
                break;
            }
            name.append(Character.toLowerCase(c));
        }
        return name.toString();
    }

    /** The value of an attribute of a tag body, or null. Handles both quote styles. */
    static String attribute(String tag, String name) {
        String lower = tag.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(name.toLowerCase(Locale.ROOT) + "=");
        if (at < 0) {
            return null;
        }
        int start = at + name.length() + 1;
        if (start >= tag.length()) {
            return null;
        }
        char quote = tag.charAt(start);
        if (quote == '"' || quote == '\'') {
            int end = tag.indexOf(quote, start + 1);
            return end < 0 ? null : decodeEntities(tag.substring(start + 1, end));
        }
        int end = start;
        while (end < tag.length() && !Character.isWhitespace(tag.charAt(end))) {
            end++;
        }
        return decodeEntities(tag.substring(start, end));
    }

    private static String decodeEntities(String value) {
        return parse(value.replace("<", "&lt;")).text();
    }

    /** At most one blank line in a row and no trailing spaces. */
    private static String collapse(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int blanks = 0;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                blanks++;
                if (blanks > 1 || out.length() == 0) {
                    continue;
                }
            } else {
                blanks = 0;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(trimmed);
        }
        return out.toString().strip();
    }
}
