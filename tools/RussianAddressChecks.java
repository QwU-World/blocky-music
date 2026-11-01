import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Russian interface speaks to the player with «ты», everywhere in ru_ru.json: no «вы», «вас», «вам», «ваш…», no
 * imperative or present tense of the second person plural («нажмите», «попробуйте», «дождитесь», «можете»), and no
 * «Уверены?» or «Готовы к битве?». The mod always meant to; the guide did, while the Workshop's screens, its errors,
 * the advancements and a dozen older strings («Карты не найдены: создайте первую») said «вы», about two hundred strings
 * in all.
 *
 * <p>It also keeps two of the mod's terms. The Workshop is a name, «Мастерская» with a capital letter, as it was in two
 * thirds of its uses («из мастерской» in the rest). And where the English says "song", the Russian says «песня», never
 * «музыка» («Выбери музыку» under "Choose the song").
 *
 * <p>A word that only looks like such a verb is a noun in the prepositional case («на сайте», «в сете», «в полёте»). The
 * prepositional case never comes without its preposition, so a word right after «в», «во», «на», «о», «об», «обо» or
 * «при» is taken as a noun; a verb never stands there. So is a word after a preposition and one of the adjectives in
 * {@link #MODIFIERS} («в новом сете»). That list is closed on purpose: a word between a preposition and a verb can be a
 * noun or a pronoun that ends like an adjective («В Мастерской нажмите…», «При этом выберите…», «В нём выберите…»),
 * and those must still fail, so a demonstrative («этом», «той»), a personal pronoun («нём», «ней») and a noun made from
 * an adjective («Мастерской») are never in it. Other nouns that meet a plural verb's ending (a dative «защите», a noun
 * after «в этом») are listed in {@link #NOUNS}; a new one fails here until it is added there.
 *
 * <p>A past tense in the plural can speak to the reader («Правила ещё не приняли», «Ещё не вошли?») or about other people
 * («Модераторы приняли решение», «Все выбрали»). The verbs only the player does in this mod ({@link #READER_VERBS}:
 * accepting the rules, signing in, choosing, saving…) fail in the plural unless a plural subject comes before them in
 * the same sentence ({@link #PLURAL_SUBJECTS}); a «ты» line says the state instead («Правила ещё не приняты»), since a
 * past tense to «ты» needs a gender the mod does not know.
 *
 * <p>Fixed sentences test the rules themselves, so a noun dropped from the list or a pattern that stops matching fails
 * too. NOT COVERED: grammar beyond the endings and the words above (a plural past tense of a verb not in the list);
 * English.
 */
public final class RussianAddressChecks {
    private static final Pattern PRONOUN = Pattern.compile(
            "(?<![а-яё])(вы|вас|вам|вами|ваш|ваша|ваше|ваши|вашего|вашей|вашему|вашим|ваших|вашу|вашем|вашими)(?![а-яё])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PLURAL_VERB = Pattern.compile(
            "(?<![а-яё])([а-яё]+(?:ите|йте|ьте|ете|ёте)(?:сь)?)(?![а-яё])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * A short plural adjective asked of the reader: «Уверены?», «Готовы к битве?». It is a question when its sentence
     * ends with «?», wherever in the sentence the «?» stands; a plural subject before it («Все готовы?») asks about
     * others instead.
     */
    private static final Pattern PLURAL_QUESTION = Pattern.compile("(?<![а-яё])(уверены|готовы|согласны|довольны)(?![а-яё])"
            + "[^.!?…]*\\?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** A plural past tense: «приняли», «вошли», «согласились». */
    private static final Pattern PLURAL_PAST = Pattern.compile("(?<![а-яё])([а-яё]+ли(?:сь)?)(?![а-яё])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * Past tenses (plural) of what only the reader does in this mod: accept the rules, sign in, choose, save, upload,
     * vote, press, forget. Said with no plural subject before them, they speak to the reader as «вы».
     */
    private static final Set<String> READER_VERBS = Set.of("приняли", "вошли", "выбрали", "указали", "ввели", "сохранили",
            "загрузили", "нажали", "согласились", "забыли", "подтвердили", "отметили", "подписались", "прочитали",
            "прочли", "проголосовали", "опубликовали", "установили", "скачали", "хотели", "смогли", "успели", "зашли",
            "вышли", "создали", "добавили", "привязали", "поставили", "открыли");
    /** Plural subjects that let such a verb speak about others: «Все выбрали», «Модераторы приняли». */
    private static final Set<String> PLURAL_SUBJECTS = Set.of("все", "оба", "обе", "они", "модераторы", "игроки", "зрители",
            "участники", "правообладатели", "люди", "авторы", "кураторы", "администраторы", "мобы", "слушатели", "сервера",
            "серверы", "другие", "многие");
    /** Prepositions of the prepositional case: the word after one is a noun, never a verb. */
    private static final Set<String> PREPOSITIONS = Set.of("в", "во", "на", "о", "об", "обо", "при");
    /**
     * Adjectives in the prepositional case that the mod's words put before a noun: «в новом сете», «на последней ракете».
     * Closed on purpose: no demonstratives («при этом», «в том»), personal pronouns («в нём», «на ней»), nouns that
     * decline like adjectives («в Мастерской») or adjectives that make a set phrase with the preposition alone («в целом»,
     * «в общем»), since a plural verb can follow each of those («В нём выберите песню», «В целом выберите любую карту»).
     */
    private static final Set<String> MODIFIERS = Set.of("новом", "новой", "новых", "старом", "старой", "старых", "последнем",
            "последней", "последних", "первом", "первой", "первых", "следующем", "следующей", "следующих", "каждом",
            "каждой", "своём", "своей", "своих", "твоём", "твоей", "твоих", "нашем", "нашей", "наших", "общей", "общих",
            "другом", "другой", "других", "любом", "любой", "целой", "отдельном", "отдельной");
    /**
     * Nouns in the prepositional case whose ending matches a plural verb's: the ones the mod's words use or are likely to
     * («в сете» is a DJ's set, «в хите» a hit, «в куплете» a verse).
     */
    private static final Set<String> NOUNS = Set.of("сайте", "пакете", "сете", "цвете", "бите", "хите", "лимите", "ответе",
            "совете", "интернете", "предмете", "куплете", "дуэте", "билете", "секрете", "бюджете", "кредите", "транзите",
            "защите", "ракете", "планете", "монете", "кассете", "анкете", "макете", "портрете", "силуэте", "приоритете",
            "полёте", "отчёте", "счёте", "расчёте", "учёте", "визите", "орбите", "элите", "аппетите");
    /** Lines left as they are on purpose, with the reason in a comment beside each. */
    private static final Set<String> KEPT = Set.of(
            // The owner keeps this quote word for word, «вы» and all («Верите, не верите…»); dj-cable-models pins it.
            "tooltip.music_battle.smiley_ball_spawn_egg.flavor");
    /** Sentences the rules must let through, and sentences they must catch, with the word each one catches. */
    private static final List<String> CLEAN = List.of("Сыграй в сете сразу для четырёх слушателей.",
            "Цвет в цвете превью можно сменить на сайте.", "Подробности в ответе сервера и в пакете.",
            "Все готовы к битве.", "Нажми «+», чтобы добавить карту из Мастерской.", "Возьми микрофон.",
            "Песня в новом сете играет дольше.", "Карта в полёте над Мастерской.", "Подробности в последнем отчёте.",
            "Все выбрали", "Модераторы приняли решение.", "Правила ещё не приняты.", "Оба игрока успели.",
            "Модераторы заблокировали эту настройку.", "Не найдено. Возможно, это удалили.",
            "Песня звучит в кабинете.", "Встреча в старом университете.", "Песня в этом сете короче.",
            "Подробности в твоём последнем отчёте и на сайте.", "Все готовы к битве?", "Игроки уверены? Начинаем.",
            "Готовы все пятеро. Сыграем ещё?");
    private static final Map<String, String> CAUGHT = Map.ofEntries(Map.entry("Возьмите микрофон.", "Возьмите"),
            Map.entry("Вы против %s", "Вы"), Map.entry("Поделитесь картой.", "Поделитесь"),
            Map.entry("Дождитесь конца песни.", "Дождитесь"), Map.entry("Уверены? Карта уйдёт на сервер.", "Уверены?"),
            Map.entry("Можете начинать.", "Можете"), Map.entry("Правила ещё не приняли.", "приняли"),
            Map.entry("Ещё не вошли в аккаунт?", "вошли"), Map.entry("Вы против %s. Карта в сете.", "Вы"),
            Map.entry("Довольны?", "Довольны?"), Map.entry("Нажмите в меню Play.", "Нажмите"),
            Map.entry("Все готовы. Выбрали карту?", "Выбрали"),
            Map.entry("В Мастерской нажмите «Загрузить».", "нажмите"), Map.entry("При этом выберите карту.", "выберите"),
            Map.entry("В нём выберите песню.", "выберите"), Map.entry("На ней нажмите «Играть».", "нажмите"),
            Map.entry("В новой вкладке дождитесь входа.", "дождитесь"), Map.entry("Потом первой выберите песню.", "выберите"),
            Map.entry("В целом выберите любую карту.", "выберите"), Map.entry("В общем нажмите «Готово».", "нажмите"),
            Map.entry("Готовы к битве?", "Готовы?"), Map.entry("Карта уйдёт на сервер. Уверены, что хочешь этого?", "Уверены?"));

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path lang = root.resolve("src/main/resources/assets/music_battle/lang");
        Map<String, String> strings = read(lang.resolve("ru_ru.json"));
        Map<String, String> english = read(lang.resolve("en_us.json"));
        List<String> failures = new ArrayList<>();
        int checks = 0;
        for (String sentence : CLEAN) {
            checks++;
            List<String> found = vyForms(sentence);
            if (!found.isEmpty()) {
                failures.add("the rules take «" + sentence + "» for «вы»: " + found);
            }
        }
        for (Map.Entry<String, String> sentence : CAUGHT.entrySet()) {
            checks++;
            if (!vyForms(sentence.getKey()).contains(sentence.getValue())) {
                failures.add("the rules miss «" + sentence.getValue() + "» in «" + sentence.getKey() + "»");
            }
        }
        for (Map.Entry<String, String> entry : strings.entrySet()) {
            checks++;
            if (KEPT.contains(entry.getKey())) {
                continue;
            }
            List<String> found = vyForms(entry.getValue());
            if (!found.isEmpty()) {
                failures.add(entry.getKey() + " " + found + ": " + entry.getValue());
            }
            checks++;
            Matcher workshop = Pattern.compile("(?<![А-Яа-яЁё])мастерск[а-яё]*").matcher(entry.getValue());
            if (workshop.find()) {
                failures.add(entry.getKey() + " writes the Workshop «" + workshop.group() + "» with a small letter: "
                        + entry.getValue());
            }
            String en = english.get(entry.getKey());
            if (en != null && Pattern.compile("\\bsongs?\\b", Pattern.CASE_INSENSITIVE).matcher(en).find()) {
                checks++;
                if (!entry.getValue().toLowerCase(Locale.ROOT).contains("песн")) {
                    failures.add(entry.getKey() + " says \"song\" in English and not «песня» in Russian: " + entry.getValue());
                }
            }
        }
        checks++;
        if (!"Карты не найдены: создай первую".equals(strings.get("music_battle.l10n.client.129"))) {
            failures.add("music_battle.l10n.client.129 reads '" + strings.get("music_battle.l10n.client.129")
                    + "', not 'Карты не найдены: создай первую'");
        }
        failures.stream().limit(40).forEach(failure -> System.err.println("FAIL: " + failure));
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " Russian strings break the «ты» form or the mod's terms");
        }
        System.out.println("Russian address PASS: " + checks + " checks over " + strings.size() + " strings: every one says «ты»"
                + " to the player, writes «Мастерская» with a capital and says «песня» where the English says song");
        System.out.println("NOT COVERED: grammar beyond the endings (a plural past tense of a verb outside READER_VERBS, a"
                + " gender); English");
    }

    /** The «вы» forms in a line: its pronouns, its plural imperatives and present tenses, a plural «Уверены?». */
    private static List<String> vyForms(String text) {
        List<String> found = new ArrayList<>();
        Matcher pronoun = PRONOUN.matcher(text);
        while (pronoun.find()) {
            found.add(pronoun.group(1));
        }
        Matcher verb = PLURAL_VERB.matcher(text);
        while (verb.find()) {
            if (!NOUNS.contains(verb.group(1).toLowerCase(Locale.ROOT)) && !afterPreposition(text, verb.start(1))) {
                found.add(verb.group(1));
            }
        }
        Matcher past = PLURAL_PAST.matcher(text);
        while (past.find()) {
            if (READER_VERBS.contains(past.group(1).toLowerCase(Locale.ROOT)) && !pluralSubjectBefore(text, past.start(1))) {
                found.add(past.group(1));
            }
        }
        Matcher question = PLURAL_QUESTION.matcher(text);
        while (question.find()) {
            if (!pluralSubjectBefore(text, question.start(1))) {
                found.add(question.group(1) + "?");
            }
        }
        return found;
    }

    /** The words before {@code at}, nearest first, back to the start of the clause (a comma, a dash or a full stop). */
    private static List<String> wordsBefore(String text, int at, String stops) {
        int from = at;
        while (from > 0 && stops.indexOf(text.charAt(from - 1)) < 0) {
            from--;
        }
        List<String> words = new ArrayList<>(List.of(text.substring(from, at).toLowerCase(Locale.ROOT).trim().split("[^а-яё]+")));
        words.removeIf(String::isEmpty);
        java.util.Collections.reverse(words);
        return words;
    }

    /** «в сете», «в новом сете»: the word is the noun of a prepositional phrase («в нём выберите» is not). */
    private static boolean afterPreposition(String text, int at) {
        List<String> before = wordsBefore(text, at, ".,;:!?—–«»\"()");
        if (before.isEmpty()) {
            return false;
        }
        if (PREPOSITIONS.contains(before.get(0))) {
            return true;
        }
        return before.size() > 1 && MODIFIERS.contains(before.get(0)) && PREPOSITIONS.contains(before.get(1));
    }

    /** A plural subject earlier in the same sentence: «Все выбрали», «Модераторы ещё не приняли». */
    private static boolean pluralSubjectBefore(String text, int at) {
        for (String word : wordsBefore(text, at, ".!?;")) {
            if (PLURAL_SUBJECTS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> read(Path file) throws Exception {
        Map<String, String> strings = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject().entrySet()) {
            strings.put(entry.getKey(), entry.getValue().getAsString());
        }
        return strings;
    }
}
