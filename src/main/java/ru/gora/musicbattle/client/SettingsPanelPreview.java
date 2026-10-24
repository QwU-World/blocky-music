package ru.gora.musicbattle.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.Palette;

/** A silent demonstration clock and viewport around the actual gameplay renderer. */
final class SettingsPanelPreview {
    private SettingsPanelPreview() { }
    record DemoNote(int owner, int lane, int tick, int durationTicks, boolean started, boolean resolved)
            implements BattlePanelRenderer.NoteView { }
    record Frame(List<DemoNote> notes, int[] flashes, int[] bursts, int score, int combo) { }

    static Frame frame(float time) {
        int step = (int) Math.floor(time / 12F);
        List<DemoNote> notes = new ArrayList<>();
        int[] flashes = new int[4];
        int[] bursts = new int[4];
        for (int index = step - 2; index <= step + 10; index++) {
            int lane = Math.floorMod(index, 4);
            int tick = index * 12;
            int duration = Math.floorMod(index, 8) == 2 ? 18 : 0;
            float age = time - tick;
            boolean started = duration > 0 && age >= 0 && age < duration;
            notes.add(new DemoNote(0, lane, tick, duration, started, age >= duration));
            if (age >= 0 && age < 8) {
                bursts[lane] = Math.max(bursts[lane], 8 - (int) age);
                flashes[lane] = Math.max(flashes[lane], Math.max(0, 6 - (int) age));
            }
            if (started) flashes[lane] = Math.max(flashes[lane], 3);
        }
        return new Frame(List.copyOf(notes), flashes, bursts, Math.max(0, step) * 100, Math.max(0, step));
    }

    static void draw(GuiGraphics graphics, Font font, SettingsScreenLayout.Rect viewport, float time, BattlePanelRenderer.Panel settings) {
        graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        try {
            // The preview sits on the settings screen's own background, so the panel looks exactly as it will on a
            // calm dark backdrop rather than on a separate navy box.
            graphics.fill(viewport.x(), viewport.y(), viewport.right(), viewport.bottom(), Palette.BG_0);
            graphics.drawCenteredString(font, Component.translatable("music_battle.settings.preview"),
                    viewport.x() + viewport.width() / 2, viewport.y() + 4, Palette.TEXT_2);
            Frame frame = frame(time);
            BattlePanelRenderer.Panel panel = new BattlePanelRenderer.Panel(0, 0, true, settings.name(), settings.speed(), settings.color(),
                    settings.panelOpacity(), settings.laneOpacity(), settings.widthScale(), settings.heightScale(),
                    settings.noteWidthScale(), settings.noteHeightScale(), frame.score(), frame.combo(),
                    frame.flashes(), frame.bursts(), settings.laneLabels(), settings.appearance());
            // Fixed maximum gameplay bounds preserve visible changes from both panel-size sliders.
            float scale = Math.max(.01F, Math.min((viewport.width() - 16) / 320F, (viewport.height() - 24) / 472F));
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(viewport.x() + (viewport.width() - 320 * scale) / 2F - 20 * scale,
                        viewport.y() + 19 - 42 * scale, 0);
                graphics.pose().scale(scale, scale, 1);
                BattlePanelRenderer.draw(graphics, font, 960, 540, time, 100, 1F, frame.notes(), panel);
            } finally { graphics.pose().popPose(); }
        } finally { graphics.disableScissor(); }
    }
}
