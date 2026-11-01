import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.data.BattleModeVote;
import ru.gora.musicbattle.data.BattleWinMilestones;
import ru.gora.musicbattle.data.VoteTiming;
import ru.gora.musicbattle.server.PlayerBattleStats;

/**
 * The battle rules a tester can count: what the test NPC's random modes do to a battle, when its vote counts, whom the
 * deadly party spares, which win earns which advancement, and that the wins themselves are counted per player and
 * survive a restart.
 *
 * <p>Each part reproduces the old behaviour first where there was one: the NPC's roll could only ever take away a mode
 * the player had picked, an NPC on a server without tracks of its own never voted, and an unreadable stats file threw
 * out of the win that tried to record itself before being overwritten. The win counts run the real
 * {@link PlayerBattleStats} against a throwaway game folder; the advancement files are read from the resources.
 *
 * <p>Argument: the project folder.
 */
public final class BattleRulesChecks {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        npcRollAddsToThePlayersModes();
        npcVoteClosesTheVote();
        npcRollsCountBetweenMobs(project);
        npcRollNamesItsSide(project);
        spectatorClientErrorIsHeard(project);
        deadlyPartySparesAnInvulnerableNpc(project);
        milestonesAreReachedOnTheirWin();
        advancementsMatchTheCode(project);
        winsAreCountedPerPlayerAndKept();
        if (failures > 0) {
            System.err.println(failures + " of " + checks + " battle rule check(s) failed.");
            System.exit(1);
        }
        System.out.println("Battle rule checks passed: " + checks);
        System.out.println("NOT COVERED: the toast and chat line in the game, and the setup screen against the NPC");
    }

    /** The rule before: with random modes on, a mode needed both sides, and the speed needed the same one. */
    private static BattleModeVote.Modes oldRule(BattleModeVote.Modes player, BattleModeVote.Modes npc, boolean random) {
        if (!random) {
            return player;
        }
        if (npc == null) {
            return new BattleModeVote.Modes(false, false, 1.0F);
        }
        return new BattleModeVote.Modes(player.sharedNotes() && npc.sharedNotes(), player.deadlyParty() && npc.deadlyParty(),
                Math.abs(player.speed() - npc.speed()) < 0.01F ? player.speed() : 1.0F);
    }

    private static void npcRollAddsToThePlayersModes() {
        List<BattleModeVote.Modes> rolls = new ArrayList<>();
        for (boolean coin : new boolean[]{false, true}) {
            for (int speedRoll = -3; speedRoll < 6; speedRoll++) {
                BattleModeVote.Modes roll = BattleModeVote.npcRoll(true, coin, speedRoll);
                check(!roll.deadlyParty(), "the NPC never rolls the deadly party");
                check(roll.sharedNotes() == coin, "the coin is the NPC's shared notes");
                rolls.add(roll);
            }
            check(BattleModeVote.npcRoll(false, coin, 2).equals(BattleModeVote.Modes.PLAIN), "switched off, the NPC rolls nothing");
        }
        Set<Float> speeds = new HashSet<>();
        for (BattleModeVote.Modes roll : rolls) {
            speeds.add(roll.speed());
        }
        check(speeds.equals(Set.of(1.0F, 1.5F, 2.0F)), "the NPC's speed is one of the three: " + speeds);

        BattleModeVote.Modes plain = BattleModeVote.Modes.PLAIN;
        BattleModeVote.Modes chosen = new BattleModeVote.Modes(true, true, 2.0F);
        // The old roll: shared notes on a coin, the deadly party one time in four, one of the three speeds.
        boolean oldEverAdded = false;
        double oldKept = 0.0D;
        for (boolean shared : new boolean[]{false, true}) {
            for (boolean deadly : new boolean[]{false, true}) {
                for (float speed : new float[]{1.0F, 1.5F, 2.0F}) {
                    BattleModeVote.Modes roll = new BattleModeVote.Modes(shared, deadly, speed);
                    oldEverAdded |= !oldRule(plain, roll, true).equals(plain);
                    if (oldRule(chosen, roll, true).equals(chosen)) {
                        oldKept += 0.5D * (deadly ? 0.25D : 0.75D) / 3.0D;
                    }
                }
            }
        }
        check(!oldEverAdded, "reproduced: the old roll never added a mode to a plain battle");
        check(oldKept < 0.05D, "reproduced: and kept a player's shared notes, deadly party and double speed in "
                + Math.round(oldKept * 1000.0D) / 10.0D + "% of battles");
        check(oldRule(chosen, null, true).equals(plain), "reproduced: an NPC that never voted turned every mode down");

        int added = 0;
        for (BattleModeVote.Modes roll : rolls) {
            for (boolean shared : new boolean[]{false, true}) {
                for (boolean deadly : new boolean[]{false, true}) {
                    for (float speed : new float[]{1.0F, 1.5F, 2.0F}) {
                        BattleModeVote.Modes own = new BattleModeVote.Modes(shared, deadly, speed);
                        BattleModeVote.Modes battle = BattleModeVote.againstTestNpc(own, roll, true);
                        check(!shared || battle.sharedNotes(), "the player's shared notes hold");
                        check(battle.deadlyParty() == deadly, "the deadly party is the player's alone");
                        check(speed <= 1.0F || battle.speed() == speed, "the player's speed holds");
                        check(battle.sharedNotes() == (shared || roll.sharedNotes()), "the NPC's shared notes are added");
                        check(speed > 1.0F || battle.speed() == roll.speed(), "the NPC's speed applies when the player left it");
                        check(BattleModeVote.againstTestNpc(own, roll, false).equals(own), "switched off, the player's own");
                        check(BattleModeVote.againstTestNpc(own, null, true).equals(own), "and before the NPC voted");
                    }
                }
            }
            if (!BattleModeVote.againstTestNpc(plain, roll, true).equals(plain)) {
                added++;
            }
        }
        check(added > rolls.size() / 2, "a plain battle against the NPC now often gets its random modes: "
                + added + " of " + rolls.size());

        // The server's draws, as BattleManager makes them: a coin and a third of three.
        Random random = new Random(67L);
        int changed = 0;
        int battles = 30_000;
        for (int i = 0; i < battles; i++) {
            BattleModeVote.Modes roll = BattleModeVote.npcRoll(true, random.nextBoolean(), random.nextInt(3));
            if (!BattleModeVote.againstTestNpc(plain, roll, true).equals(plain)) {
                changed++;
            }
        }
        double share = changed / (double) battles;
        check(Math.abs(share - 5.0D / 6.0D) < 0.02D, "five battles in six get at least one random mode: " + share);
        check(BattleModeVote.normalizeSpeed(1.49F) == 1.5F && BattleModeVote.normalizeSpeed(7.0F) == 2.0F
                && BattleModeVote.normalizeSpeed(-1.0F) == 1.0F, "speeds snap to the three a battle offers");
    }

    /**
     * A battle a player sets up between two mobs, either of them the test NPC: the NPC's settings tip says it picks shared
     * notes and the speed itself and adds them to yours, but such a battle read the organiser's votes alone and nothing
     * in it ever rolled. Each NPC's roll now adds to the organiser's modes as it does against a player.
     */
    private static void npcRollsCountBetweenMobs(Path project) throws Exception {
        BattleModeVote.Modes plain = BattleModeVote.Modes.PLAIN;
        BattleModeVote.Modes sharedDouble = BattleModeVote.npcRoll(true, true, 2);
        BattleModeVote.Modes oneAndHalf = BattleModeVote.npcRoll(true, false, 1);
        check(BattleModeVote.betweenMobs(plain, null, null).equals(plain), "two ordinary mobs: the organiser's modes");
        check(BattleModeVote.betweenMobs(plain, sharedDouble, null).equals(new BattleModeVote.Modes(true, false, 2.0F)),
                "an NPC on the left adds shared notes and its speed to a plain vote");
        check(BattleModeVote.betweenMobs(plain, null, oneAndHalf).equals(new BattleModeVote.Modes(false, false, 1.5F)),
                "an NPC on the right adds its speed");
        check(BattleModeVote.betweenMobs(new BattleModeVote.Modes(false, false, 1.5F), sharedDouble, null)
                        .equals(new BattleModeVote.Modes(true, false, 1.5F)),
                "the organiser's own speed holds; the NPC's shared notes still add");
        check(BattleModeVote.betweenMobs(plain, oneAndHalf, sharedDouble).equals(new BattleModeVote.Modes(true, false, 1.5F)),
                "two NPCs: shared notes if either wants them, the first one's speed");
        check(!BattleModeVote.betweenMobs(plain, new BattleModeVote.Modes(true, true, 2.0F), null).deadlyParty(),
                "an NPC never brings the deadly party");
        check(BattleModeVote.betweenMobs(new BattleModeVote.Modes(false, true, 1.0F), sharedDouble, null).deadlyParty(),
                "the organiser's deadly party holds");
        Random random = new Random(42L);
        int one = 0;
        int two = 0;
        int battles = 30_000;
        for (int i = 0; i < battles; i++) {
            BattleModeVote.Modes left = BattleModeVote.npcRoll(true, random.nextBoolean(), random.nextInt(3));
            BattleModeVote.Modes right = BattleModeVote.npcRoll(true, random.nextBoolean(), random.nextInt(3));
            if (!BattleModeVote.betweenMobs(plain, left, null).equals(plain)) {
                one++;
            }
            if (!BattleModeVote.betweenMobs(plain, left, right).equals(plain)) {
                two++;
            }
        }
        check(Math.abs(one / (double) battles - 5.0D / 6.0D) < 0.02D,
                "one NPC adds a mode to five plain battles in six: " + one / (double) battles);
        check(Math.abs(two / (double) battles - 35.0D / 36.0D) < 0.01D,
                "two NPCs add one to 35 in 36: " + two / (double) battles);

        // The server: every mob battle rolls its NPCs, the vote's result and the quick battle both fold the rolls in.
        String source = Files.readString(project.resolve("src/main/java/ru/gora/musicbattle/server/BattleManager.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("public static void startMobBattle(ServerPlayer organizer, LivingEntity first, LivingEntity second, boolean configure)");
        int roll = source.indexOf("rollMobBattleNpcs(battle);", start);
        int vote = source.indexOf("if (configure || noServerTracks)", start);
        int quick = source.indexOf("applyMobBattleModes(battle, BattleModeVote.Modes.PLAIN);", start);
        int play = source.indexOf("beginPlaying(battle, track);", start);
        check(start >= 0 && roll > start && roll < vote, "startMobBattle rolls the NPCs before it opens the vote");
        check(quick > vote && quick < play, "the quick mob battle folds the rolls in before it starts playing");
        int resolve = source.indexOf("private static void resolveModeVotes(Battle battle)");
        int mobs = source.indexOf("if (!battle.hasAnyPlayer()) {", resolve);
        int fold = source.indexOf("applyMobBattleModes(battle,", mobs);
        int next = source.indexOf("return;", mobs);
        check(resolve >= 0 && mobs > resolve && fold > mobs && fold < next,
                "a voted mob battle folds the rolls into the organiser's votes");
        int apply = source.indexOf("private static void applyMobBattleModes(");
        check(apply >= 0 && source.indexOf("BattleModeVote.betweenMobs(organiser, battle.leftNpcRoll, battle.rightNpcRoll)", apply) > apply,
                "and the modes are the rule's");
    }

    /**
     * In a battle between two test NPCs both "rolled extra modes" lines named TEST_RAP_GOD_MASTER, so the organiser
     * could not tell which NPC had rolled what. Between mobs the line names the side as well, Player 1 on the left.
     */
    private static void npcRollNamesItsSide(Path project) throws Exception {
        check(BattleModeVote.announcedSide(true, true) == 0 && BattleModeVote.announcedSide(true, false) == 0,
                "against a player the only NPC is named by its name alone");
        check(BattleModeVote.announcedSide(false, true) == 1, "between mobs the left NPC is Player 1");
        check(BattleModeVote.announcedSide(false, false) == 2, "and the right one Player 2");
        String source = Files.readString(project.resolve("src/main/java/ru/gora/musicbattle/server/BattleManager.java"),
                StandardCharsets.UTF_8);
        int method = source.indexOf("private static void announceNpcRoll(");
        int side = source.indexOf("BattleModeVote.announcedSide(battle.hasAnyPlayer(), npc == battle.leftEntity())", method);
        int keyed = source.indexOf("\"music_battle.l10n.server.npc_random_modes_side\", String.valueOf(side)", method);
        int next = source.indexOf("private static void notifyModeVotes(", method);
        check(method >= 0 && side > method && keyed > side && keyed < next, "announceNpcRoll names the side the rule gives");
        for (String lang : List.of("en_us", "ru_ru")) {
            JsonObject words = JsonParser.parseString(Files.readString(
                    project.resolve("src/main/resources/assets/music_battle/lang/" + lang + ".json"), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String line = words.has("music_battle.l10n.server.npc_random_modes_side")
                    ? words.get("music_battle.l10n.server.npc_random_modes_side").getAsString() : "";
            check(line.split("%s", -1).length == 4, lang + ": the side line takes the side, the name and the modes: " + line);
            check(line.contains(lang.equals("ru_ru") ? "Игрок %s" : "Player %s"), lang + ": and says the side as the battle does");
        }
    }

    /**
     * A spectator the battle waits for reports a failed download, and the server must hear it: reportClientError let
     * only fighters past its first line, so the spectator's own branch was never reached, the battle waited for a file
     * that never came and ended with "track_load_timeout" for everyone.
     */
    private static void spectatorClientErrorIsHeard(Path project) throws Exception {
        String source = Files.readString(project.resolve("src/main/java/ru/gora/musicbattle/server/BattleManager.java"),
                StandardCharsets.UTF_8);
        int method = source.indexOf("public static void reportClientError(");
        int end = source.indexOf("public static void confirmTrackReady(", method);
        String body = method >= 0 && end > method ? source.substring(method, end) : "";
        int waited = body.indexOf("boolean waitedForSpectator = battle != null && battle.phase == Phase.PREPARING\n"
                + "                && battle.preloadSpectatorIds.contains(serverPlayer.getUUID());");
        int guard = body.indexOf("if (battle == null || !battle.hasPlayer(serverPlayer.getUUID()) && !waitedForSpectator) {");
        int branch = body.indexOf("if (waitedForSpectator) {");
        int told = body.indexOf("music_battle.l10n.server.spectator_load_failed", branch);
        check(waited >= 0 && guard > waited, "the first line lets a waited-for spectator through as well as a fighter");
        check(!body.contains("if (battle == null || !battle.hasPlayer(serverPlayer.getUUID())) {"),
                "reproduced before: only a fighter got past it");
        check(branch > guard && told > branch, "and that spectator is dropped from the wait and told why");
    }

    private static void npcVoteClosesTheVote() {
        UUID player = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        Set<UUID> expected = VoteTiming.expectedVoters(player, null, npc, null);
        Set<UUID> trackVoters = Set.of(player);
        check(!VoteTiming.everyoneVoted(expected, trackVoters),
                "reproduced: with no server track for the NPC to name, its vote never arrived and the minute ran out");
        check(VoteTiming.everyoneVoted(expected, BattleModeVote.votersSoFar(trackVoters, npc, true)),
                "once the NPC has rolled its modes the vote closes as soon as the player chooses");
        check(!VoteTiming.everyoneVoted(expected, BattleModeVote.votersSoFar(Set.of(), npc, true)),
                "but not before the player has chosen");
        check(!VoteTiming.everyoneVoted(expected, BattleModeVote.votersSoFar(trackVoters, npc, false)),
                "and not for an NPC that has not rolled yet");
        Set<UUID> both = Set.of(player, npc);
        check(BattleModeVote.votersSoFar(both, npc, true) == both, "an NPC that named a track is counted once");
        check(BattleModeVote.votersSoFar(trackVoters, null, true) == trackVoters, "other battles count track votes only");
    }

    /**
     * The deadly party used genericKill on every loser, a kind of damage that passes the invulnerable flag, so a test
     * NPC switched to "Invulnerable" still died. The rule spares it, and the server asks the rule before it kills.
     */
    private static void deadlyPartySparesAnInvulnerableNpc(Path project) throws Exception {
        check(!BattleModeVote.deadlyPartyKills(true, true), "an invulnerable test NPC survives losing the deadly party");
        check(BattleModeVote.deadlyPartyKills(true, false), "an ordinary test NPC still dies");
        check(BattleModeVote.deadlyPartyKills(false, true) && BattleModeVote.deadlyPartyKills(false, false),
                "every other loser, invulnerable or not, still dies");
        String source = Files.readString(project.resolve("src/main/java/ru/gora/musicbattle/server/BattleManager.java"),
                StandardCharsets.UTF_8);
        int method = source.indexOf("private void killDeadlyLoser(");
        int rule = source.indexOf("BattleModeVote.deadlyPartyKills(loser instanceof TestRapGodMasterEntity, loser.isInvulnerable())",
                method);
        int kill = source.indexOf("genericKill()", method);
        check(method >= 0 && rule > method && kill > rule,
                "the server asks the rule before its genericKill, inside killDeadlyLoser");
    }

    private static void milestonesAreReachedOnTheirWin() {
        Map<String, Integer> reachedAt = new HashMap<>();
        List<String> previous = BattleWinMilestones.reached(0);
        check(previous.isEmpty(), "no milestone before the first win");
        for (int wins = 1; wins <= 100; wins++) {
            List<String> now = BattleWinMilestones.reached(wins);
            check(now.containsAll(previous), "a milestone once reached stays reached");
            for (String name : now) {
                if (!previous.contains(name)) {
                    reachedAt.put(name, wins);
                }
            }
            previous = now;
        }
        check(reachedAt.equals(Map.of("headliner", 25, "meaning_of_life", 42, "six_seven", 67)),
                "the milestones fall on wins 25, 42 and 67: " + reachedAt);
        check(BattleWinMilestones.reached(50).contains("meaning_of_life") && !BattleWinMilestones.reached(50).contains("six_seven"),
                "a player already at 50 wins gets the 42-win advancement with the next win, not never");
        check(BattleWinMilestones.reached(Integer.MAX_VALUE).size() == BattleWinMilestones.ALL.size(), "and every one at the most");
    }

    private static void advancementsMatchTheCode(Path project) throws Exception {
        Path folder = project.resolve("src/main/resources/data/music_battle/advancement");
        check(!Files.exists(folder.resolve("beat_the_master.json")), "beating the test NPC no longer has an advancement");
        Map<String, JsonObject> advancements = new HashMap<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString().replace(".json", "");
                advancements.put(name, JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        }
        for (Map.Entry<String, JsonObject> entry : advancements.entrySet()) {
            JsonElement parent = entry.getValue().get("parent");
            if (parent != null) {
                String parentName = parent.getAsString().replace("music_battle:", "");
                check(advancements.containsKey(parentName), entry.getKey() + " has a parent that exists: " + parentName);
            }
        }
        // Every advancement the code hands out by name has to exist and be one only the code can award.
        Set<String> granted = new HashSet<>();
        Pattern grant = Pattern.compile("MusicBattleAdvancements\\.grant\\([^,]+,\\s*\"([a-z_]+)\"\\)");
        try (Stream<Path> sources = Files.walk(project.resolve("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = grant.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    granted.add(matcher.group(1));
                }
            }
        }
        for (BattleWinMilestones.Milestone milestone : BattleWinMilestones.ALL) {
            granted.add(milestone.advancement());
        }
        check(!granted.contains("beat_the_master"), "nothing awards the removed advancement");
        check(granted.containsAll(List.of("first_win", "full_combo", "headliner", "meaning_of_life", "six_seven")),
                "the win advancements are all awarded: " + granted);
        for (String name : granted) {
            JsonObject advancement = advancements.get(name);
            if (advancement == null) {
                check(false, "the code awards " + name + ", which has no file");
                continue;
            }
            JsonObject criteria = advancement.getAsJsonObject("criteria");
            check(criteria != null && criteria.has("code")
                            && "minecraft:impossible".equals(criteria.getAsJsonObject("code").get("trigger").getAsString()),
                    name + " carries the impossible \"code\" criterion the code awards");
            JsonArray requirements = advancement.getAsJsonArray("requirements");
            check(requirements != null && requirements.toString().equals("[[\"code\"]]"), name + " needs only that criterion");
            JsonObject display = advancement.getAsJsonObject("display");
            check(display != null
                            && ("advancements.music_battle." + name + ".title").equals(
                                    display.getAsJsonObject("title").get("translate").getAsString())
                            && ("advancements.music_battle." + name + ".description").equals(
                                    display.getAsJsonObject("description").get("translate").getAsString()),
                    name + " is titled by its own translation keys");
        }
        check(parentOf(advancements, "meaning_of_life").equals("headliner")
                        && parentOf(advancements, "six_seven").equals("meaning_of_life"),
                "42 wins follows 25, and 67 follows 42");
    }

    private static String parentOf(Map<String, JsonObject> advancements, String name) {
        JsonObject advancement = advancements.get(name);
        return advancement == null || !advancement.has("parent") ? ""
                : advancement.get("parent").getAsString().replace("music_battle:", "");
    }

    private static void winsAreCountedPerPlayerAndKept() throws Exception {
        Path game = Files.createTempDirectory("blocky-battle-rules-");
        FMLPaths.loadAbsolutePaths(game);
        Path file = game.resolve("music_battle_player_stats.json");
        UUID ada = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        Set<String> earned = new HashSet<>();
        Map<String, Integer> earnedOn = new HashMap<>();
        for (int battle = 1; battle <= 70; battle++) {
            int wins = PlayerBattleStats.recordWin(ada, "Ada", battle % 7 == 0);
            check(wins == battle, "each win counts once: " + wins + " after " + battle);
            if (battle % 2 == 0) {
                PlayerBattleStats.recordLoss(ben, "Ben");
            } else {
                PlayerBattleStats.recordSurrender(ben, "Ben");
            }
            for (String milestone : BattleWinMilestones.reached(wins)) {
                if (earned.add(milestone)) {
                    earnedOn.put(milestone, wins);
                }
            }
            if (battle == 30) {
                restart();
                check(PlayerBattleStats.get(ada, "").wins() == 30, "a restart keeps the count");
            }
        }
        check(earnedOn.equals(Map.of("headliner", 25, "meaning_of_life", 42, "six_seven", 67)),
                "a player playing on earns each advancement on its own win: " + earnedOn);
        restart();
        PlayerBattleStats.Stats a = PlayerBattleStats.get(ada, "");
        PlayerBattleStats.Stats b = PlayerBattleStats.get(ben, "");
        check(a.wins() == 70 && a.fullComboWins() == 10 && a.losses() == 0 && "Ada".equals(a.name()),
                "Ada's record comes back after a restart: " + a.wins() + " wins, " + a.fullComboWins() + " flawless");
        check(b.wins() == 0 && b.losses() == 70 && b.surrenders() == 35 && "Ben".equals(b.name()),
                "and Ben's is his own: " + b.losses() + " losses, " + b.surrenders() + " surrenders");

        String broken = "{\"version\": 1, \"players\": {\"" + ada + "\": {\"wins\": 70,";
        boolean oldThrew = false;
        try {
            new com.google.gson.Gson().fromJson(broken, JsonObject.class);
        } catch (JsonSyntaxException exception) {
            oldThrew = true;
        }
        check(oldThrew, "reproduced: the old reader let a half-written file's error out of the win that tried to record itself");
        Files.writeString(file, broken);
        restart();
        check(PlayerBattleStats.recordWin(ben, "Ben", false) == 1, "a broken file does not stop the next win being counted");
        List<Path> aside;
        try (Stream<Path> files = Files.list(game)) {
            aside = files.filter(path -> path.getFileName().toString().startsWith("music_battle_player_stats.json.unreadable-"))
                    .toList();
        }
        check(aside.size() == 1 && Files.readString(aside.get(0)).equals(broken),
                "and the broken file is kept beside it, whole, for whoever wants to mend it");
        check(JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("players").size() == 1,
                "while the new file holds only what was counted since");

        Files.writeString(file, "{\"version\": 1}");
        restart();
        PlayerBattleStats.recordLoss(ada, "Ada");
        try (Stream<Path> files = Files.list(game)) {
            check(files.filter(path -> path.getFileName().toString().contains(".unreadable-")).count() == 2,
                    "a file with no players table is set aside too, not written over");
        }

        UUID cleo = UUID.randomUUID();
        Files.writeString(file, "{\"version\": 1, \"players\": {"
                + "\"" + ada + "\": {\"name\": \"Ada\", \"wins\": 2147483647},"
                + "\"" + ben + "\": {\"name\": \"Ben\", \"wins\": \"41\", \"losses\": -5},"
                + "\"not-a-uuid\": {\"wins\": 9},"
                + "\"" + cleo + "\": [1, 2, 3]}}");
        restart();
        check(PlayerBattleStats.recordWin(ada, "Ada", false) == Integer.MAX_VALUE, "a count at the top stays there");
        int benWins = PlayerBattleStats.recordWin(ben, "Ben", false);
        check(benWins == 42 && BattleWinMilestones.reached(benWins).contains("meaning_of_life"),
                "a count written as text still counts, and the 42nd win earns its advancement: " + benWins);
        check(PlayerBattleStats.get(ben, "").losses() == 0, "a negative count reads as none");
        check(PlayerBattleStats.get(cleo, "").wins() == 0, "a broken entry is skipped, the rest kept");
    }

    /** Forgets everything in memory, as a server restart does, so the next call reads the file again. */
    private static void restart() throws Exception {
        Field loaded = PlayerBattleStats.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.setBoolean(null, false);
        Field saveBlocked = PlayerBattleStats.class.getDeclaredField("saveBlocked");
        saveBlocked.setAccessible(true);
        saveBlocked.setBoolean(null, false);
        Field stats = PlayerBattleStats.class.getDeclaredField("STATS");
        stats.setAccessible(true);
        ((Map<?, ?>) stats.get(null)).clear();
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) {
            failures++;
            System.err.println("FAIL: " + message);
        }
    }
}
