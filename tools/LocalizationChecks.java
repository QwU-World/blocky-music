import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.network.PublishTrackResultPayload;

/** Uses actual Minecraft Language replacement to exercise runtime language switching. */
public final class LocalizationChecks {
    static int checks;
    public static void main(String[] args) throws Exception {
        var method=Class.forName("ru.gora.musicbattle.client.ClientTranslations").getMethod("text",String.class);
        Language previous=Language.getInstance();
        var i18nLanguage=I18n.class.getDeclaredMethod("setLanguage",Language.class);i18nLanguage.setAccessible(true);
        var guideSections=Class.forName("ru.gora.musicbattle.client.MusicBattleGuideScreen").getDeclaredMethod("createSections");guideSections.setAccessible(true);
        // Component codec bootstrapping needs NeoForge's real mod loader. This standalone
        // check exercises client-side resolution, not a live network round trip.
        Component received=new PublishTrackResultPayload(UUID.randomUUID(),true,
                Component.translatable("music_battle.calibration.taps",5),"test").message();
        Class<?> publication=Class.forName("ru.gora.musicbattle.server.TrackPublicationManager");
        var sanitizeNotes=publication.getDeclaredMethod("sanitizeNotes",JsonObject.class);sanitizeNotes.setAccessible(true);
        var safeMessage=publication.getDeclaredMethod("safeMessage",Exception.class);safeMessage.setAccessible(true);
        Component validation;
        try {sanitizeNotes.invoke(null,new JsonObject());throw new AssertionError("empty chart accepted");}
        catch(java.lang.reflect.InvocationTargetException failure){
            Object reason=safeMessage.invoke(null,(Exception)failure.getCause());
            check(reason instanceof Component,"known server rejection stays translatable until received");
            validation=(Component)reason;
        }
        try {
            for (String lang : List.of("en_us", "ru_ru", "en_us")) {
                Map<String,String> strings=new HashMap<>();
                try(var input=Files.newInputStream(Path.of("src/main/resources/assets/music_battle/lang/"+lang+".json"))){Language.loadFromJson(input,strings::put);}
                Language.inject(new Language() {
                    public String getOrDefault(String key,String fallback) {return strings.getOrDefault(key,fallback);}
                    public boolean has(String key){return strings.containsKey(key);}
                    public boolean isDefaultRightToLeft(){return false;}
                    public FormattedCharSequence getVisualOrder(FormattedText text){return FormattedCharSequence.EMPTY;}
                });
                i18nLanguage.invoke(null,Language.getInstance());
                check(validation.getString().equals(lang.equals("ru_ru")?"в карте нет нот":"the beatmap has no notes"),"server validation translates without flattening: "+lang);
                String result=(String)method.invoke(null,"music_battle.l10n.client.055");
                check(result.equals(lang.equals("ru_ru")?"Цвет":"Color"),"picker label follows current language: "+lang);
                String template=(String)method.invoke(null,"music_battle.l10n.client.115");
                check(String.format(Locale.ROOT,template,"Range",2.5).equals(lang.equals("ru_ru")?"Range: 2.5 блока":"Range: 2.5 blocks"),"raw labels preserve formatter templates");
                check(received.getString().equals(lang.equals("ru_ru")?"Замеры: 5":"Taps: 5"),"one server packet renders in recipient language: "+lang);
                String guide=guideSections.invoke(null).toString();
                check(!guide.contains("Format error"),"all guide paragraphs format successfully: "+lang);
                check(!guide.contains("guide.music_battle.") && !guide.contains("music_battle.l10n.guide."),
                        "every rendered guide paragraph resolves after language switch: " + lang);
                String resetHint=Component.translatable("screen.music_battle.dj_panel.reset_speakers_tooltip").getString();
                check(resetHint.contains("100%") && !resetHint.contains("100%%") && !resetHint.contains("Format error"),
                        "speaker tooltip shows a single literal percent: " + lang);
                for(int lane=0;lane<4;lane++){
                    String key=List.of("left","down","up","right").get(lane);
                    String expected=(lang.equals("ru_ru")?"Кнопка ":"Button ")+(lane+1);
                    check(Component.translatable("music_battle.settings.lane."+key).getString().equals(expected),
                            "settings lane label is numbered: "+lang+" lane "+lane);
                    check(Component.translatable("key.music_battle.lane_"+key).getString()
                                    .equals("Blocky Music: "+expected),
                            "controls keybind label is numbered: "+lang+" lane "+lane);
                }
                if(lang.equals("en_us")) check(!guide.matches("(?s).*[А-Яа-яЁё].*"),"entire built-in guide is English");
                for(var type:ru.gora.musicbattle.data.TrackEffect.Type.values()){
                    String label=type.displayName();
                    check(!label.contains("music_battle.") && !label.contains("Format error"),"effect name resolves: "+type+" "+lang);
                    if(lang.equals("en_us"))check(!label.matches(".*[А-Яа-яЁё].*"),"English effect label: "+type);
                }
                for(var preset:ru.gora.musicbattle.client.BattleTextFonts.presets()){
                    String label=preset.displayName();
                    check(!label.contains("music_battle.") && !label.contains("Format error"),"font label resolves dynamically: "+preset.id());
                }
            }
        } finally {Language.inject(previous);i18nLanguage.invoke(null,previous);}
        System.out.println("Localization checks passed: "+checks);
    }
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
