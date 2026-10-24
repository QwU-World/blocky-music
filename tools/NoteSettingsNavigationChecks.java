package ru.gora.musicbattle.client;

import java.nio.file.Files;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.fml.loading.FMLPaths;

/** Real screen initialization and scrolling; does not render fonts or open Minecraft. */
public final class NoteSettingsNavigationChecks {
    public static void main(String[] args) throws Exception {
        FMLPaths.loadAbsolutePaths(Files.createTempDirectory("blocky-note-navigation-"));
        for (int[] size : new int[][]{{320,240},{426,240},{640,360},{854,480},{1280,720}}) {
            MusicBattleSettingsScreen screen=new MusicBattleSettingsScreen(null);
            screen.width=size[0]; screen.height=size[1]; screen.init();
            SettingsScreenLayout layout=SettingsScreenLayout.create(size[0],size[1]);
            List<String> keys=List.of("music_battle.calibration.general","music_battle.calibration.title",
                    "music_battle.editor","music_battle.settings.guide","screen.music_battle.workshop.nav");
            var buttons=new java.util.ArrayList<AbstractWidget>();
            for(String key:keys) {
                AbstractWidget button=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                        .filter(widget->widget.getMessage().getContents() instanceof TranslatableContents tr && tr.getKey().equals(key))
                        .findFirst().orElseThrow(()->new AssertionError("Missing navigation button: "+key));
                require(button.visible && button.getY()>=0 && button.getX()>=0 && button.getX()+button.getWidth()<=size[0],
                        "Navigation button is hidden/outside screen: "+key);
                require(button.getY()+button.getHeight()<Math.min(layout.controls().y(),layout.preview().y()),
                        "Navigation button remains inside scrollable settings: "+key+" at "+size[0]);
                for(AbstractWidget previous:buttons)require(button.getX()>=previous.getX()+previous.getWidth()
                        || previous.getX()>=button.getX()+button.getWidth() || button.getY()>=previous.getY()+previous.getHeight()
                        || previous.getY()>=button.getY()+button.getHeight(),"Navigation buttons overlap");
                buttons.add(button);
            }
            int[] y=buttons.stream().mapToInt(AbstractWidget::getY).toArray();
            screen.mouseScrolled(layout.controls().x()+1,layout.controls().y()+1,0,-100);
            for(int i=0;i<buttons.size();i++)require(buttons.get(i).visible && buttons.get(i).getY()==y[i],
                    "Navigation moves/hides when settings are scrolled");
        }
        System.out.println("PASS: five fixed navigation buttons stay above preview/settings at five GUI sizes and after scrolling");
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
