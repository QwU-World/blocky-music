package ru.gora.musicbattle.client;

import java.util.function.Consumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import org.lwjgl.glfw.GLFW;

public final class NoteSettingsFocusChecks {
    public static void main(String[] args) throws Exception {
        var type=Class.forName("ru.gora.musicbattle.client.MusicBattleSettingsScreen$RangeSlider");
        var constructor=type.getDeclaredConstructor(int.class,int.class,String.class,float.class,float.class,float.class,boolean.class,Consumer.class);
        constructor.setAccessible(true);
        int[] changes={0};
        AbstractSliderButton slider=(AbstractSliderButton)constructor.newInstance(0,150,"note_width",1F,.35F,2.2F,false,(Consumer<Float>)ignored->changes[0]++);
        slider.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);
        slider.visible=false;
        slider.keyPressed(GLFW.GLFW_KEY_RIGHT,0,0);
        if(changes[0]!=0)throw new AssertionError("Hidden slider still changes and saves values after scrolling it away");
        slider.visible=true;
        slider.keyPressed(GLFW.GLFW_KEY_RIGHT,0,0);
        if(changes[0]!=1)throw new AssertionError("Visible slider no longer responds to keyboard adjustment");
        System.out.println("PASS: hidden slider rejects keyboard edits; visible focused slider remains adjustable");
    }
}
