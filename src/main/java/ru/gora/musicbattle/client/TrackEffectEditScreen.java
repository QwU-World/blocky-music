package ru.gora.musicbattle.client;

import java.util.ArrayDeque;
import ru.gora.musicbattle.client.ui.Palette;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.data.FxRange;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.data.TrackEffectSettings;

/** One inspector for both the placement template and a concrete timeline effect. */
public final class TrackEffectEditScreen extends Screen implements DjPanelOwnedScreen {
    private static final int LIVE_PREVIEW_DEBOUNCE_TICKS = 1;
    private static final String[] TEXT_ANIMATIONS = {"none", "letters", "words", "fade", "slide_left", "slide_right", "slide_up", "slide_down", "zoom", "pop", "spin"};
    private static final String[] TEXT_EXIT_ANIMATIONS = {"none", "fade", "slide_left", "slide_right", "slide_up", "slide_down", "zoom", "shrink", "spin"};
    private static final String[] HIGHLIGHT_MODES = {"both", "p1", "p2", "alternate", "pulse"};
    private static final String[] VISUALIZER_POSITIONS = {"bottom", "top", "left", "right"};
    private static final String[] STAR_DIRECTIONS = {"down_right", "down", "down_left", "left", "up_left", "up", "up_right", "right"};
    private static final String[] PLACEMENT_MODES = {"point", "length", "range"};
    private static final String[] BACKGROUNDS = {"world", "dark", "light", "checker", "red", "green"};
    private final TrackEffectEditHost host;
    private final TrackEffect original;
    private final TrackEffect seed;
    private TrackEffect.Type type;
    private int lane;
    private String fontId;
    private String animation;
    private String exitAnimation;
    private String variant;
    private String placementMode;
    private int backgroundIndex;
    private boolean beatReactive;
    private boolean tvSweepEnabled;
    private boolean cinematicAnimated;
    private boolean fishEyeRemoveBlackEdges;
    private boolean overlayText;
    private boolean overlayInterface;
    private boolean overlayPlayers;
    private boolean overlayEntities;
    private boolean highlightSpectators;
    private boolean highlightEntities;
    private boolean highlightModelGlow;
    private boolean hideParticles;
    private boolean hideFirstPersonHand;
    private boolean hideHud;
    private boolean hideBlockyMusicHud;
    private boolean fadeInEnabled;
    private boolean fadeOutEnabled;
    private String visualizerPosition;
    private String starDirection;
    private Button typeButton;
    private Button layerButton;
    private Button fontButton;
    private Button animationButton;
    private Button exitAnimationButton;
    private Button variantButton;
    private Button gradientButton;
    private Button sectionsButton;
    private Button placementButton;
    private Button backgroundButton;
    private Button beatReactiveButton;
    private Button flashBeatButton;
    private Button tvSweepButton;
    private Button cinematicAnimationButton;
    private Button fishEyeRemoveBlackEdgesButton;
    private Button overlayTextButton;
    private Button overlayInterfaceButton;
    private Button overlayPlayersButton;
    private Button overlayEntitiesButton;
    private Button highlightSpectatorsButton;
    private Button highlightEntitiesButton;
    private Button highlightModelGlowButton;
    private Button hideParticlesButton;
    private Button hideFirstPersonHandButton;
    private Button hideHudButton;
    private Button hideBlockyMusicHudButton;
    private Button fadeInButton;
    private Button fadeOutButton;
    private Button visualizerPositionButton;
    private Button starDirectionButton;
    private Button copyToBrushButton;
    private Button copyFromBrushButton;
    private EditBox startBox;
    private EditBox durationBox;
    private EditBox intensityBox;
    private EditBox softnessBox;
    private ExactColorButton colorAButton;
    private ExactColorButton colorBButton;
    private EditBox textBox;
    private EditBox positionXBox;
    private EditBox positionYBox;
    private EditBox opacityBox;
    private EditBox textSpeedBox;
    private EditBox outlineThicknessBox;
    private EditBox glowStrengthBox;
    private EditBox tvScanlineSpacingBox;
    private EditBox tvScanlineOpacityBox;
    private EditBox tvSweepWidthBox;
    private EditBox tvSweepOpacityBox;
    private EditBox tvSweepSpeedBox;
    private EditBox tvNoiseAmountBox;
    private EditBox tvNoiseOpacityBox;
    private EditBox tvFlickerAmountBox;
    private EditBox vignetteRadiusBox;
    private EditBox vignetteSoftnessBox;
    private EditBox vignetteRoundnessBox;
    private EditBox fishEyeStrengthBox;
    private EditBox fishEyeRadiusBox;
    private EditBox fishEyeZoomBox;
    private EditBox fishEyeChromaticBox;
    private EditBox fishEyeEdgeDarkeningBox;
    private EditBox cinematicTopHeightBox;
    private EditBox cinematicBottomHeightBox;
    private EditBox cinematicFeatherBox;
    private EditBox cinematicAnimationSpeedBox;
    private EditBox cinematicCenterOffsetBox;
    private EditBox cinematicShadowBox;
    private EditBox fadeInTicksBox;
    private EditBox fadeOutTicksBox;
    private EditBox visualizerOffsetBox;
    private EditBox colorBrightnessBox;
    private EditBox colorContrastBox;
    private EditBox colorSaturationBox;
    private EditBox colorHueBox;
    private EditBox colorGammaBox;
    private EditBox colorTemperatureBox;
    private ExactColorButton highlightSpectatorColorButton;
    private ExactColorButton highlightEntityColorButton;
    private EditBox objectOutlineColorSpeedBox;
    private EditBox starSizeBox;
    private EditBox starDensityBox;
    private EditBox starTwinkleSpeedBox;
    private EditBox starDriftSpeedBox;
    private EditBox starSpreadBox;
    private EditBox starBrightnessBox;
    private EditBox starIridescenceBox;
    private Button objectOutlineAddColorButton;
    private Button objectOutlineRemoveColorButton;
    private Button objectOutlineMoveColorLeftButton;
    private Button objectOutlineMoveColorRightButton;
    private final List<Integer> objectOutlineColors = new ArrayList<>();
    private final List<ExactColorButton> objectOutlineColorButtons = new ArrayList<>();
    private int selectedObjectOutlineColor;
    private String error = "";
    private String notice = "";
    private long previewStartedNanos;
    private boolean previewOnly;
    private boolean fontPickerOpen;
    private boolean effectPickerOpen;
    private final List<AbstractWidget> contentWidgets = new ArrayList<>();
    /** Every numeric box keeps a slider beside it; the box stays the way to type an exact value. */
    private final Map<EditBox, FxSlider> sliders = new IdentityHashMap<>();
    private boolean syncingBoxes;
    private final List<AbstractWidget> actionWidgets = new ArrayList<>();
    private final List<LabeledControl> visibleControls = new ArrayList<>();
    private final Deque<Draft> settingsUndo = new ArrayDeque<>();
    private final Deque<Draft> settingsRedo = new ArrayDeque<>();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int contentTop;
    private int contentBottom;
    private int scrollOffset;
    private int maxScroll;
    private boolean windowInitialized;
    private boolean draggingWindow;
    private boolean resizingWindow;
    private double windowGrabX;
    private double windowGrabY;
    private int resizeStartWidth;
    private int resizeStartHeight;
    private TrackEffect pendingLivePreview;
    private TrackEffect lastPublishedLivePreview;
    private int livePreviewCooldown;
    private boolean livePreviewPublished;
    private boolean livePreviewCommitted;

    public TrackEffectEditScreen(TrackEditorScreen parent, TrackEffect original, TrackEffect seed, String placementMode) {
        this(editorHost(parent), original, seed, placementMode);
    }

    public TrackEffectEditScreen(TrackEffectEditHost host, TrackEffect original,
                                 TrackEffect seed, String placementMode) {
        super(host.inspectorTitle(original));
        this.host = host;
        this.original = original;
        this.seed = seed;
        this.type = seed.type();
        this.lane = seed.layer();
        this.fontId = seed.font();
        this.animation = seed.animation();
        this.exitAnimation = seed.exitAnimation();
        this.variant = seed.variant();
        this.beatReactive = seed.beatReactive();
        this.tvSweepEnabled = seed.settings().tvSweepEnabled();
        this.cinematicAnimated = seed.settings().cinematicAnimated();
        this.fishEyeRemoveBlackEdges = seed.settings().fishEyeRemoveBlackEdges();
        this.overlayText = seed.settings().overlayText();
        this.overlayInterface = seed.settings().overlayInterface();
        this.overlayPlayers = seed.settings().overlayPlayers();
        this.overlayEntities = seed.settings().overlayEntities();
        this.highlightSpectators = seed.settings().highlightSpectators();
        this.highlightEntities = seed.settings().highlightEntities();
        this.highlightModelGlow = seed.settings().highlightModelGlow();
        this.hideParticles = seed.settings().hideParticles();
        this.hideFirstPersonHand = seed.settings().hideFirstPersonHand();
        this.hideHud = seed.settings().hideHud();
        this.hideBlockyMusicHud = seed.settings().hideBlockyMusicHud();
        this.fadeInEnabled = seed.settings().fadeInEnabled();
        this.fadeOutEnabled = seed.settings().fadeOutEnabled();
        this.visualizerPosition = seed.settings().visualizerPosition();
        this.starDirection = seed.settings().starDirection();
        this.objectOutlineColors.addAll(seed.objectOutlinePalette());
        this.placementMode = normalize(placementMode, PLACEMENT_MODES, "point");
        this.lastPublishedLivePreview = seed;
    }

    private static TrackEffectEditHost editorHost(TrackEditorScreen parent) {
        return new TrackEffectEditHost() {
            @Override
            public Screen parentScreen() {
                return parent;
            }

            @Override
            public java.nio.file.Path effectPreviewAudioPath() {
                return parent.effectPreviewAudioPath();
            }

            @Override
            public void saveEffect(TrackEffect original, TrackEffect edited, String placementMode) {
                if (original == null) {
                    ClientEffectToolSettings.savePreset(edited, placementMode);
                    parent.configureEffectTemplate(edited, placementMode);
                } else {
                    parent.replaceEffect(original, edited);
                }
            }

            @Override
            public void deleteEffect(TrackEffect original) {
                if (original != null) parent.removeEffect(original);
            }

            @Override
            public void refreshEffectBrush(TrackEffect.Type type) {
                parent.refreshEffectBrush(type);
            }
        };
    }

    @Override
    protected void init() {
        Draft retainedDraft = startBox == null ? null : captureDraft();
        contentWidgets.clear();
        sliders.clear();
        actionWidgets.clear();
        visibleControls.clear();
        previewStartedNanos = System.nanoTime();
        if (host.managesPreviewAudio()) {
            ClientAudioAnalyzer.activate(host.effectPreviewAudioPath());
        }
        if (!windowInitialized) {
            this.panelWidth = Math.min(540, this.width - 24);
            this.panelHeight = Math.min(300, this.height - 24);
            this.panelLeft = (this.width - this.panelWidth) / 2;
            this.panelTop = (this.height - this.panelHeight) / 2;
            windowInitialized = true;
        }
        int panelWidth = this.panelWidth;
        int left = this.panelLeft;
        int top = this.panelTop;
        int inner = Math.max(240, panelWidth - 28);
        int gap = 6;
        int quarter = (inner - gap * 3) / 4;
        int x0 = left + 14;
        int row1 = top + 38;
        typeButton = addButton(typeLabel(), x0, row1, quarter, this::openEffectPicker);
        layerButton = addButton(layerLabel(), x0 + (quarter + gap), row1, quarter, () -> mutate(this::cycleLayer));
        placementButton = addButton(placementLabel(), x0 + (quarter + gap) * 2, row1, quarter, () -> mutate(this::cyclePlacement));
        backgroundButton = addButton(backgroundLabel(), x0 + (quarter + gap) * 3, row1, quarter, () -> mutate(this::cycleBackground));

        int row2 = top + 82;
        startBox = addBox(x0, row2, quarter, Integer.toString(seed.tick()));
        durationBox = addBox(x0 + (quarter + gap), row2, quarter, Integer.toString(seed.durationTicks()));
        intensityBox = addBox(x0 + (quarter + gap) * 2, row2, quarter, format(seed.intensity()));
        opacityBox = addBox(x0 + (quarter + gap) * 3, row2, quarter, format(seed.opacity()));

        int row3 = top + 126;
        colorAButton = addColorButton(x0, row3, quarter, Component.translatable("music_battle.l10n.fx.label_001"), seed.colorA(),
                color -> mutate(() -> colorAButton.setColor(color)));
        colorBButton = addColorButton(x0 + (quarter + gap), row3, quarter, Component.translatable("music_battle.l10n.fx.label_002"), seed.colorB(),
                color -> mutate(() -> colorBButton.setColor(color)));
        positionXBox = addBox(x0 + (quarter + gap) * 2, row3, quarter, format(seed.positionX()));
        positionYBox = addBox(x0 + (quarter + gap) * 3, row3, quarter, format(seed.positionY()));

        int row4 = top + 170;
        textBox = addBox(x0, row4, inner, seed.text());

        int third = (inner - gap * 2) / 3;
        int row5 = top + 214;
        fontButton = addButton(fontLabel(), x0, row5, third, this::openFontPicker);
        animationButton = addButton(animationLabel(), x0 + third + gap, row5, third, () -> mutate(this::cycleAnimation));
        exitAnimationButton = addButton(exitAnimationLabel(), x0 + (third + gap) * 2, row5, third, () -> mutate(this::cycleExitAnimation));
        variantButton = addButton(variantLabel(), x0 + (third + gap) * 2, row5, third, () -> mutate(this::cycleVariant));

        int row6 = top + 258;
        textSpeedBox = addBox(x0, row6, quarter, format(seed.textSpeed()));
        softnessBox = addBox(x0 + quarter + gap, row6, quarter, format(seed.softness()));
        outlineThicknessBox = addBox(x0 + (quarter + gap) * 2, row6, quarter, format(seed.outlineThickness()));
        glowStrengthBox = addBox(x0 + (quarter + gap) * 3, row6, quarter, format(seed.glowStrength()));
        TrackEffectSettings settings = seed.settings();
        tvScanlineSpacingBox = addBox(x0, row6, quarter, format(settings.tvScanlineSpacing()));
        tvScanlineOpacityBox = addBox(x0, row6, quarter, format(settings.tvScanlineOpacity()));
        tvSweepWidthBox = addBox(x0, row6, quarter, format(settings.tvSweepWidth()));
        tvSweepOpacityBox = addBox(x0, row6, quarter, format(settings.tvSweepOpacity()));
        tvSweepSpeedBox = addBox(x0, row6, quarter, format(settings.tvSweepSpeed()));
        tvNoiseAmountBox = addBox(x0, row6, quarter, format(settings.tvNoiseAmount()));
        tvNoiseOpacityBox = addBox(x0, row6, quarter, format(settings.tvNoiseOpacity()));
        tvFlickerAmountBox = addBox(x0, row6, quarter, format(settings.tvFlickerAmount()));
        vignetteRadiusBox = addBox(x0, row6, quarter, format(settings.vignetteRadius()));
        vignetteSoftnessBox = addBox(x0, row6, quarter, format(settings.vignetteSoftness()));
        vignetteRoundnessBox = addBox(x0, row6, quarter, format(settings.vignetteRoundness()));
        fishEyeStrengthBox = addBox(x0, row6, quarter, format(settings.fishEyeStrength()));
        fishEyeRadiusBox = addBox(x0, row6, quarter, format(settings.fishEyeRadius()));
        fishEyeZoomBox = addBox(x0, row6, quarter, format(settings.fishEyeZoom()));
        fishEyeChromaticBox = addBox(x0, row6, quarter, format(settings.fishEyeChromatic()));
        fishEyeEdgeDarkeningBox = addBox(x0, row6, quarter, format(settings.fishEyeEdgeDarkening()));
        cinematicTopHeightBox = addBox(x0, row6, quarter, format(settings.cinematicTopHeight()));
        cinematicBottomHeightBox = addBox(x0, row6, quarter, format(settings.cinematicBottomHeight()));
        cinematicFeatherBox = addBox(x0, row6, quarter, format(settings.cinematicFeather()));
        cinematicAnimationSpeedBox = addBox(x0, row6, quarter, format(settings.cinematicAnimationSpeed()));
        cinematicCenterOffsetBox = addBox(x0, row6, quarter, format(settings.cinematicCenterOffset()));
        cinematicShadowBox = addBox(x0, row6, quarter, format(settings.cinematicShadow()));
        fadeInTicksBox = addBox(x0, row6, quarter, format(settings.fadeInTicks()));
        fadeOutTicksBox = addBox(x0, row6, quarter, format(settings.fadeOutTicks()));
        visualizerOffsetBox = addBox(x0, row6, quarter, format(settings.visualizerOffset()));
        colorBrightnessBox = addBox(x0, row6, quarter, format(settings.colorBrightness()));
        colorContrastBox = addBox(x0, row6, quarter, format(settings.colorContrast()));
        colorSaturationBox = addBox(x0, row6, quarter, format(settings.colorSaturation()));
        colorHueBox = addBox(x0, row6, quarter, format(settings.colorHue()));
        colorGammaBox = addBox(x0, row6, quarter, format(settings.colorGamma()));
        colorTemperatureBox = addBox(x0, row6, quarter, format(settings.colorTemperature()));
        highlightSpectatorColorButton = addColorButton(x0, row6, quarter, Component.translatable("music_battle.l10n.fx.label_003"),
                settings.highlightSpectatorColor(), color -> mutate(() -> highlightSpectatorColorButton.setColor(color)));
        highlightEntityColorButton = addColorButton(x0, row6, quarter, Component.translatable("music_battle.l10n.fx.label_004"),
                settings.highlightEntityColor(), color -> mutate(() -> highlightEntityColorButton.setColor(color)));
        objectOutlineColorSpeedBox = addBox(x0, row6, quarter, format(settings.objectOutlineColorSpeed()));
        starSizeBox = addBox(x0, row6, quarter, format(settings.starSize()));
        starDensityBox = addBox(x0, row6, quarter, format(settings.starDensity()));
        starTwinkleSpeedBox = addBox(x0, row6, quarter, format(settings.starTwinkleSpeed()));
        starDriftSpeedBox = addBox(x0, row6, quarter, format(settings.starDriftSpeed()));
        starSpreadBox = addBox(x0, row6, quarter, format(settings.starSpread()));
        starBrightnessBox = addBox(x0, row6, quarter, format(settings.starBrightness()));
        starIridescenceBox = addBox(x0, row6, quarter, format(settings.starIridescence()));
        // Every numeric field gets a slider over the exact range its clamp accepts. Timeline ticks
        // have no upper clamp, so they take the shared default span while the box still accepts more.
        attachSlider(startBox, FxRange.TICK, true);
        attachSlider(durationBox, FxRange.DURATION_TICKS, true);
        attachSlider(intensityBox, FxRange.INTENSITY);
        attachSlider(opacityBox, FxRange.OPACITY);
        attachSlider(positionXBox, FxRange.POSITION_X);
        attachSlider(positionYBox, FxRange.POSITION_Y);
        attachSlider(softnessBox, FxRange.SOFTNESS);
        attachSlider(textSpeedBox, FxRange.TEXT_SPEED);
        attachSlider(outlineThicknessBox, FxRange.outlineThickness(type));
        attachSlider(glowStrengthBox, FxRange.GLOW_STRENGTH);
        attachSlider(tvScanlineSpacingBox, FxRange.TV_SCANLINE_SPACING);
        attachSlider(tvScanlineOpacityBox, FxRange.TV_SCANLINE_OPACITY);
        attachSlider(tvSweepWidthBox, FxRange.TV_SWEEP_WIDTH);
        attachSlider(tvSweepOpacityBox, FxRange.TV_SWEEP_OPACITY);
        attachSlider(tvSweepSpeedBox, FxRange.TV_SWEEP_SPEED);
        attachSlider(tvNoiseAmountBox, FxRange.TV_NOISE_AMOUNT);
        attachSlider(tvNoiseOpacityBox, FxRange.TV_NOISE_OPACITY);
        attachSlider(tvFlickerAmountBox, FxRange.TV_FLICKER_AMOUNT);
        attachSlider(vignetteRadiusBox, FxRange.VIGNETTE_RADIUS);
        attachSlider(vignetteSoftnessBox, FxRange.VIGNETTE_SOFTNESS);
        attachSlider(vignetteRoundnessBox, FxRange.VIGNETTE_ROUNDNESS);
        attachSlider(fishEyeStrengthBox, FxRange.FISH_EYE_STRENGTH);
        attachSlider(fishEyeRadiusBox, FxRange.FISH_EYE_RADIUS);
        attachSlider(fishEyeZoomBox, FxRange.FISH_EYE_ZOOM);
        attachSlider(fishEyeChromaticBox, FxRange.FISH_EYE_CHROMATIC);
        attachSlider(fishEyeEdgeDarkeningBox, FxRange.FISH_EYE_EDGE_DARKENING);
        attachSlider(cinematicTopHeightBox, FxRange.CINEMATIC_TOP_HEIGHT);
        attachSlider(cinematicBottomHeightBox, FxRange.CINEMATIC_BOTTOM_HEIGHT);
        attachSlider(cinematicFeatherBox, FxRange.CINEMATIC_FEATHER);
        attachSlider(cinematicAnimationSpeedBox, FxRange.CINEMATIC_ANIMATION_SPEED);
        attachSlider(cinematicCenterOffsetBox, FxRange.CINEMATIC_CENTER_OFFSET);
        attachSlider(cinematicShadowBox, FxRange.CINEMATIC_SHADOW);
        attachSlider(fadeInTicksBox, FxRange.FADE_IN_TICKS);
        attachSlider(fadeOutTicksBox, FxRange.FADE_OUT_TICKS);
        attachSlider(visualizerOffsetBox, FxRange.VISUALIZER_OFFSET);
        attachSlider(colorBrightnessBox, FxRange.COLOR_BRIGHTNESS);
        attachSlider(colorContrastBox, FxRange.COLOR_CONTRAST);
        attachSlider(colorSaturationBox, FxRange.COLOR_SATURATION);
        attachSlider(colorHueBox, FxRange.COLOR_HUE);
        attachSlider(colorGammaBox, FxRange.COLOR_GAMMA);
        attachSlider(colorTemperatureBox, FxRange.COLOR_TEMPERATURE);
        attachSlider(objectOutlineColorSpeedBox, FxRange.OBJECT_OUTLINE_COLOR_SPEED);
        attachSlider(starSizeBox, FxRange.STAR_SIZE);
        attachSlider(starDensityBox, FxRange.STAR_DENSITY);
        attachSlider(starTwinkleSpeedBox, FxRange.STAR_TWINKLE_SPEED);
        attachSlider(starDriftSpeedBox, FxRange.STAR_DRIFT_SPEED);
        attachSlider(starSpreadBox, FxRange.STAR_SPREAD);
        attachSlider(starBrightnessBox, FxRange.STAR_BRIGHTNESS);
        attachSlider(starIridescenceBox, FxRange.STAR_IRIDESCENCE);
        objectOutlineColorButtons.clear();
        for (int index = 0; index < TrackEffectSettings.MAX_OBJECT_OUTLINE_COLORS; index++) {
            final int colorIndex = index;
            int initialColor = index < objectOutlineColors.size() ? objectOutlineColors.get(index) : seed.colorA();
            objectOutlineColorButtons.add(addColorButton(x0, row6, quarter,
                    Component.translatable("music_battle.l10n.fx.outline_color_number", index + 1), initialColor,
                    color -> mutate(() -> setObjectOutlineColor(colorIndex, color))));
        }
        objectOutlineAddColorButton = addButton(Component.translatable("music_battle.l10n.fx.label_006"), x0, row6, quarter,
                () -> mutate(this::addObjectOutlineColor));
        objectOutlineRemoveColorButton = addButton(Component.translatable("music_battle.l10n.fx.label_007"), x0, row6, quarter,
                () -> mutate(this::removeObjectOutlineColor));
        objectOutlineMoveColorLeftButton = addButton(Component.translatable("music_battle.l10n.fx.label_008"), x0, row6, quarter,
                () -> mutate(() -> moveObjectOutlineColor(-1)));
        objectOutlineMoveColorRightButton = addButton(Component.translatable("music_battle.l10n.fx.label_009"), x0, row6, quarter,
                () -> mutate(() -> moveObjectOutlineColor(1)));
        gradientButton = addButton(gradientLabel(), x0, row6, third, () -> mutate(this::cycleVisualizerGradient));
        sectionsButton = addButton(sectionsLabel(), x0 + third + gap, row6, third, () -> mutate(this::cycleVisualizerSections));

        beatReactiveButton = addButton(beatReactiveLabel(), x0, top + 302, inner, () -> mutate(this::toggleBeatReactive));
        flashBeatButton = addButton(flashBeatLabel(), x0, top + 302, inner, () -> mutate(this::toggleFlashBeat));
        tvSweepButton = addButton(tvSweepLabel(), x0, top + 302, inner, () -> mutate(this::toggleTvSweep));
        cinematicAnimationButton = addButton(cinematicAnimationLabel(), x0, top + 302, inner, () -> mutate(this::toggleCinematicAnimation));
        fishEyeRemoveBlackEdgesButton = addButton(fishEyeRemoveBlackEdgesLabel(), x0, top + 302, inner,
                () -> mutate(this::toggleFishEyeRemoveBlackEdges));
        overlayTextButton = addButton(overlayTextLabel(), x0, top + 302, inner, () -> mutate(this::toggleOverlayText));
        overlayInterfaceButton = addButton(overlayInterfaceLabel(), x0, top + 302, inner, () -> mutate(this::toggleOverlayInterface));
        overlayPlayersButton = addButton(overlayPlayersLabel(), x0, top + 302, inner,
                () -> mutate(this::toggleOverlayPlayers));
        overlayEntitiesButton = addButton(overlayEntitiesLabel(), x0, top + 302, inner,
                () -> mutate(this::toggleOverlayEntities));
        highlightSpectatorsButton = addButton(highlightSpectatorsLabel(), x0, top + 302, inner,
                () -> mutate(this::toggleHighlightSpectators));
        highlightEntitiesButton = addButton(highlightEntitiesLabel(), x0, top + 302, inner,
                () -> mutate(this::toggleHighlightEntities));
        highlightModelGlowButton = addButton(highlightModelGlowLabel(), x0, top + 302, inner,
                () -> mutate(this::toggleHighlightModelGlow));
        hideParticlesButton = addButton(hideParticlesLabel(), x0, top + 302, inner,
                () -> mutate(() -> { hideParticles = !hideParticles; hideParticlesButton.setMessage(hideParticlesLabel()); }));
        hideFirstPersonHandButton = addButton(hideHandLabel(), x0, top + 302, inner,
                () -> mutate(() -> { hideFirstPersonHand = !hideFirstPersonHand; hideFirstPersonHandButton.setMessage(hideHandLabel()); }));
        hideHudButton = addButton(hideHudLabel(), x0, top + 302, inner,
                () -> mutate(() -> { hideHud = !hideHud; hideHudButton.setMessage(hideHudLabel()); }));
        hideBlockyMusicHudButton = addButton(hideBlockyMusicHudLabel(), x0, top + 302, inner,
                () -> mutate(() -> { hideBlockyMusicHud = !hideBlockyMusicHud; hideBlockyMusicHudButton.setMessage(hideBlockyMusicHudLabel()); }));
        fadeInButton = addButton(fadeInLabel(), x0, top + 302, inner, () -> mutate(this::toggleFadeIn));
        fadeOutButton = addButton(fadeOutLabel(), x0, top + 302, inner, () -> mutate(this::toggleFadeOut));
        visualizerPositionButton = addButton(visualizerPositionLabel(), x0, top + 302, inner,
                () -> mutate(this::cycleVisualizerPosition));
        starDirectionButton = addButton(starDirectionLabel(), x0, top + 302, inner,
                () -> mutate(this::cycleStarDirection));
        copyToBrushButton = addButton(Component.translatable("music_battle.l10n.fx.label_010"), x0, top + 326, third, this::copyToBrush);
        copyFromBrushButton = addButton(Component.translatable("music_battle.l10n.fx.label_011"), x0 + third + gap, top + 326, third, this::copyFromBrush);

        int actionY = top + 346;
        addAction(Component.translatable(original == null ? "music_battle.l10n.fx.label_012" : "music_battle.l10n.fx.label_013"), this::save, x0, actionY);
        addAction(Component.translatable("music_battle.l10n.fx.label_014"), this::resetSettings, x0 + third + gap, actionY);
        if (original != null) addAction(Component.translatable("music_battle.l10n.fx.label_015"), this::delete, x0 + (third + gap) * 2, actionY);
        addAction(Component.translatable("music_battle.l10n.fx.label_016"), this::onClose, x0 + (third + gap) * (original == null ? 2 : 3), actionY);
        refreshObjectOutlinePaletteButtons();
        updateVisibility();
        relayout();
        if (retainedDraft != null) applyDraft(retainedDraft);
    }

    private Button addButton(Component label, int x, int y, int width, Runnable action) {
        Button button = this.addRenderableWidget(FancyButton.create(label, ignored -> action.run(), x, y, width, 20));
        contentWidgets.add(button);
        return button;
    }

    private ExactColorButton addColorButton(int x, int y, int width, Component pickerTitle, int color,
                                            IntConsumer onApply) {
        ExactColorButton button = this.addRenderableWidget(
                new ExactColorButton(this, x, y, width, 20, pickerTitle, color, onApply));
        contentWidgets.add(button);
        return button;
    }

    private EditBox addBox(int x, int y, int width, String value) {
        EditBox box = new ru.gora.musicbattle.client.ui.FancyEditBox(this.font, x, y, width, 20, Component.empty());
        box.setMaxLength(160);
        box.setValue(value == null ? "" : value);
        this.addRenderableWidget(box);
        contentWidgets.add(box);
        return box;
    }

    private void attachSlider(EditBox box, FxRange range) {
        attachSlider(box, range, false);
    }

    /**
     * Pairs one numeric box with a slider over the same range. Dragging writes the formatted value
     * into the box, so every existing reader (buildEffect, drafts, undo) keeps working unchanged.
     */
    private void attachSlider(EditBox box, FxRange range, boolean wholeText) {
        if (box == null) return;
        FxSlider slider = new FxSlider(box, range, wholeText);
        this.addRenderableWidget(slider);
        contentWidgets.add(slider);
        sliders.put(box, slider);
        box.setResponder(ignored -> {
            if (!syncingBoxes) slider.syncFromBox();
        });
    }

    private static float parseOr(EditBox box, float fallback) {
        try {
            return parseFloat(box);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void addAction(Component label, Runnable action, int x, int y) {
        Button button = this.addRenderableWidget(FancyButton.create(label, ignored -> action.run(), x, y, 80, 22));
        actionWidgets.add(button);
    }

    @Override
    public void tick() {
        super.tick();
        DjPanelScreen owner = owningDjPanel();
        if (owner != null) {
            owner.tickCameraPreviewRequest();
        }
        if (!host.supportsLivePreview() || livePreviewCommitted || startBox == null) {
            return;
        }
        if (livePreviewCooldown > 0) {
            livePreviewCooldown--;
        }

        TrackEffect candidate = buildEffect(false);
        if (candidate == null || candidate.equals(lastPublishedLivePreview)) {
            // Never publish a stale last-valid value while a numeric/color field is incomplete,
            // and discard a queued value if the user has already returned to the published one.
            pendingLivePreview = null;
        } else if (!candidate.equals(pendingLivePreview)) {
            pendingLivePreview = candidate;
            if (livePreviewCooldown <= 0) {
                livePreviewCooldown = LIVE_PREVIEW_DEBOUNCE_TICKS;
            }
        }

        if (pendingLivePreview != null && livePreviewCooldown <= 0) {
            publishLivePreview(pendingLivePreview);
        }
    }

    private void publishLivePreview(TrackEffect effect) {
        pendingLivePreview = null;
        livePreviewCooldown = 0;
        if (!host.supportsLivePreview() || effect == null || effect.equals(lastPublishedLivePreview)) {
            return;
        }
        host.previewEffect(original, effect, placementMode);
        lastPublishedLivePreview = effect;
        livePreviewPublished = true;
    }

    private void cancelLivePreview() {
        pendingLivePreview = null;
        livePreviewCooldown = 0;
        if (host.supportsLivePreview() && livePreviewPublished && !livePreviewCommitted) {
            host.cancelEffectPreview(original, seed, placementMode);
            lastPublishedLivePreview = seed;
            livePreviewPublished = false;
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        TrackEffect preview = buildEffect(false);
        float tick = 0.0F;
        int previewBpm = Math.max(20, host.previewBpm());
        float previewSpeed = Mth.clamp(host.previewPlaybackSpeed(), 1.0F, 2.0F);
        int effectivePreviewBpm = Math.max(20, Math.round(previewBpm * previewSpeed));
        ClientTrackEffects.EffectState state = ClientTrackEffects.sample(List.of(), -1.0F,
                effectivePreviewBpm, previewSpeed);
        if (preview != null) {
            float elapsed = Math.max(0L, System.nanoTime() - previewStartedNanos) / 50_000_000.0F;
            int loop = Math.max(24, preview.visibleDurationTicks() + 10);
            tick = preview.tick() + elapsed % loop;
            state = ClientTrackEffects.sample(List.of(preview), tick, effectivePreviewBpm, previewSpeed);
        }
        boolean activeCameraVisible = drawPreviewBackground(graphics, partialTick, state);
        if (preview != null) {
            ClientTrackEffects.EffectState orderedState = state;
            if (!activeCameraVisible) {
                drawHighlightPreview(graphics, state, tick);
            }
            boolean realOutlineSource = activeCameraVisible
                    && DjCameraPreviewRenderer.hasObjectOutlineSource();
            if (!realOutlineSource) {
                drawObjectOutlinePreview(graphics, state, tick);
            }
            graphics.flush();
            if (activeCameraVisible) {
                DjCameraPreviewRenderer.withObjectOutlineSource(
                        () -> ClientTrackEffects.renderOverlay(graphics, orderedState));
            } else {
                ClientTrackEffects.renderOverlay(graphics, state);
            }
            graphics.flush();
        }

        ClientUiForeground.render(graphics.pose(), graphics::flush,
                () -> renderInspectorUi(graphics, mouseX, mouseY, partialTick));
    }

    private void renderInspectorUi(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (effectPickerOpen) {
            drawEffectPicker(graphics, mouseX, mouseY);
            return;
        }
        if (fontPickerOpen) {
            drawFontPicker(graphics, mouseX, mouseY);
            return;
        }

        if (previewOnly) {
            return;
        }

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xFF121C25);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 3, type.color());
        graphics.drawCenteredString(this.font, this.title, panelLeft + panelWidth / 2, panelTop + 11, 0xFFFFFFFF);
        graphics.enableScissor(panelLeft + 7, contentTop, panelLeft + panelWidth - 12, contentBottom);
        for (LabeledControl control : visibleControls) {
            label(graphics, control.label(), control.widget().getX(), control.widget().getY() - 10);
        }
        for (AbstractWidget widget : contentWidgets) {
            if (widget.visible) widget.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.disableScissor();
        if (maxScroll > 0) drawScrollbar(graphics);
        int messageY = panelTop + panelHeight - 47;
        String message = !error.isBlank() ? error : notice;
        if (!message.isBlank()) {
            graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(message, panelWidth - 24),
                    panelLeft + panelWidth / 2, messageY, error.isBlank() ? 0xFF92C5D8 : 0xFFFF8798);
        }
        for (AbstractWidget widget : actionWidgets) widget.render(graphics, mouseX, mouseY, partialTick);
        int grip = 10;
        graphics.fill(panelLeft + panelWidth - grip, panelTop + panelHeight - 2,
                panelLeft + panelWidth, panelTop + panelHeight, 0xFFE3A6FF);
        graphics.fill(panelLeft + panelWidth - 2, panelTop + panelHeight - grip,
                panelLeft + panelWidth, panelTop + panelHeight, 0xFFE3A6FF);
    }

    private boolean drawPreviewBackground(GuiGraphics graphics, float partialTick,
                                          ClientTrackEffects.EffectState effectState) {
        String mode = BACKGROUNDS[backgroundIndex];
        if ("world".equals(mode)) {
            DjPanelScreen owner = owningDjPanel();
            if (owner != null && owner.renderActiveCameraPreview(
                    graphics, 0, 0, this.width, this.height, partialTick, effectState)) {
                graphics.fill(0, 0, this.width, this.height, 0x30070A10);
                return true;
            }
            // No camera to show - none linked, a shader in the way, the chunk not loaded. Drawing nothing left the
            // world and the live effects visible underneath the one being edited, which is exactly the double
            // image that was reported. Fall back to the plain dark background instead.
            graphics.fill(0, 0, this.width, this.height, 0xFF090C12);
            return false;
        }
        int first = "light".equals(mode) ? 0xFFE8EDF1 : "red".equals(mode) ? 0xFF50151D : "green".equals(mode) ? 0xFF143E28 : 0xFF090C12;
        graphics.fill(0, 0, this.width, this.height, first);
        if ("checker".equals(mode)) {
            int cell = 24;
            for (int y = 0; y < this.height; y += cell) {
                for (int x = 0; x < this.width; x += cell) {
                    graphics.fill(x, y, x + cell, y + cell, ((x / cell + y / cell) & 1) == 0 ? 0xFF202632 : 0xFFD4D9DE);
                }
            }
        }
        return false;
    }

    private float beatEnvelope(float tick, int bpm, float playbackSpeed) {
        float phase = ClientTrackEffects.beatPhase(tick, bpm, playbackSpeed);
        return Mth.clamp(1.0F - phase * 4.0F, 0.0F, 1.0F);
    }

    private void drawHighlightPreview(GuiGraphics graphics, ClientTrackEffects.EffectState state, float tick) {
        TrackEffect effect = state.highlightEffect();
        if (effect == null) {
            return;
        }
        int y = this.height - 108;
        float visibility = ClientTrackEffects.visibility(effect, tick);
        boolean left = highlightOwner(effect, tick, state.bpm(), state.playbackSpeed(), 0);
        boolean right = highlightOwner(effect, tick, state.bpm(), state.playbackSpeed(), 1);
        drawPreviewPerson(graphics, 14, y, effect, effect.colorA(), left, visibility);
        drawPreviewPerson(graphics, this.width - 48, y, effect, effect.colorB(), right, visibility);
    }

    private boolean highlightOwner(TrackEffect effect, float tick, int bpm, float playbackSpeed, int owner) {
        float beatTicks = ClientTrackEffects.beatDurationTicks(bpm, playbackSpeed);
        return switch (effect.variant()) {
            case "p1" -> owner == 0;
            case "p2" -> owner == 1;
            case "alternate" -> ((Mth.floor(Math.max(0.0F, tick) / beatTicks) & 1) == owner);
            case "pulse" -> ClientTrackEffects.beatPhase(tick, bpm, playbackSpeed) < 0.35F;
            default -> true;
        };
    }

    private void drawPreviewPerson(GuiGraphics graphics, int x, int y, TrackEffect effect, int color,
                                   boolean highlighted, float visibility) {
        if (highlighted) {
            int shells = Mth.clamp(1 + Mth.ceil(effect.glowStrength()), 1, 6);
            for (int shell = shells - 1; shell >= 0; shell--) {
                float distance = shells <= 1 ? 0.0F : shell / (float) (shells - 1);
                int spread = Math.max(1, Math.round(1.0F + effect.outlineThickness() * 2.0F
                        + effect.glowStrength() * 2.8F * distance));
                float glow01 = effect.glowStrength() / 5.0F;
                int alpha = Math.round(255.0F * effect.opacity() * visibility
                        * Mth.lerp(distance, 0.90F, 0.08F + 0.12F * glow01));
                int glow = (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
                drawPersonSilhouette(graphics, x, y, spread, glow);
            }
        }
        graphics.fill(x + 8, y, x + 24, y + 16, 0xFFB88A62);
        graphics.fill(x + 5, y + 17, x + 27, y + 48, 0xFF456B91);
        graphics.fill(x, y + 18, x + 5, y + 44, 0xFFB88A62);
        graphics.fill(x + 27, y + 18, x + 32, y + 44, 0xFFB88A62);
        graphics.fill(x + 6, y + 48, x + 14, y + 74, 0xFF28313C);
        graphics.fill(x + 18, y + 48, x + 26, y + 74, 0xFF28313C);
        if (highlighted && effect.settings().highlightModelGlow()) {
            int alpha = Mth.clamp(Math.round(150.0F * effect.opacity() * visibility), 0, 255);
            drawPersonSilhouette(graphics, x, y, 0, (alpha << 24) | (color & 0x00FFFFFF));
        }
    }

    private void drawObjectOutlinePreview(GuiGraphics graphics, ClientTrackEffects.EffectState state, float tick) {
        TrackEffect effect = state.objectOutlineEffect();
        if (effect == null) return;
        float visibility = ClientTrackEffects.visibility(effect, tick);
        float rhythm = effect.beatReactive()
                ? 0.18F + 0.82F * beatEnvelope(tick, state.bpm(), state.playbackSpeed()) : 1.0F;
        int alpha = Mth.clamp(Math.round(255.0F * effect.opacity() * visibility * rhythm), 0, 255);
        int animatedColor = ClientTrackEffects.objectOutlineColor(effect, tick, state.playbackSpeed());
        int color = (alpha << 24) | (animatedColor & 0x00FFFFFF);
        int thickness = Mth.clamp(Math.round(effect.outlineThickness()), 1, 5);
        int glow = Mth.clamp(Math.round(effect.glowStrength()), 0, 5);
        drawPreviewObjectOutline(graphics, this.width / 2 - 86, this.height / 2 - 42,
                this.width / 2 - 18, this.height / 2 + 18, thickness, glow, color);
        drawPreviewObjectOutline(graphics, this.width / 2 + 12, this.height / 2 - 15,
                this.width / 2 + 92, this.height / 2 + 54, thickness, glow, color);
    }

    private void drawPreviewObjectOutline(GuiGraphics graphics, int left, int top, int right, int bottom,
                                          int thickness, int glow, int color) {
        for (int spread = glow; spread >= 1; spread--) {
            int alpha = ((color >>> 24) * (glow - spread + 1)) / Math.max(1, glow * 5);
            drawPreviewRectangle(graphics, left - spread, top - spread, right + spread, bottom + spread,
                    1, (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF));
        }
        drawPreviewRectangle(graphics, left, top, right, bottom, thickness, color);
    }

    private void drawPreviewRectangle(GuiGraphics graphics, int left, int top, int right, int bottom,
                                      int thickness, int color) {
        graphics.fill(left, top, right, top + thickness, color);
        graphics.fill(left, bottom - thickness, right, bottom, color);
        graphics.fill(left, top + thickness, left + thickness, bottom - thickness, color);
        graphics.fill(right - thickness, top + thickness, right, bottom - thickness, color);
    }

    private void drawPersonSilhouette(GuiGraphics graphics, int x, int y, int spread, int color) {
        graphics.fill(x + 8 - spread, y - spread, x + 24 + spread, y + 16 + spread, color);
        graphics.fill(x + 5 - spread, y + 17 - spread, x + 27 + spread, y + 48 + spread, color);
        graphics.fill(x - spread, y + 18 - spread, x + 5 + spread, y + 44 + spread, color);
        graphics.fill(x + 27 - spread, y + 18 - spread, x + 32 + spread, y + 44 + spread, color);
        graphics.fill(x + 6 - spread, y + 48 - spread, x + 14 + spread, y + 74 + spread, color);
        graphics.fill(x + 18 - spread, y + 48 - spread, x + 26 + spread, y + 74 + spread, color);
    }

    private void label(GuiGraphics graphics, String text, int x, int y) {
        graphics.drawString(this.font, text, x, y, Palette.TEXT_2, false);
    }

    private void relayout() {
        if (contentWidgets.isEmpty()) return;
        List<List<LabeledControl>> rows = controlRows();
        int maxWidth = Math.max(360, this.width - 10);
        int maxHeight = Math.max(190, this.height - 10);
        panelWidth = Mth.clamp(panelWidth, Math.min(400, maxWidth), maxWidth);
        panelHeight = Mth.clamp(panelHeight, Math.min(210, maxHeight), maxHeight);
        panelLeft = Mth.clamp(panelLeft, 5, Math.max(5, this.width - panelWidth - 5));
        panelTop = Mth.clamp(panelTop, 5, Math.max(5, this.height - panelHeight - 5));
        contentTop = panelTop + 29;
        contentBottom = panelTop + panelHeight - 52;
        int viewportHeight = Math.max(40, contentBottom - contentTop);
        maxScroll = Math.max(0, rows.size() * 40 - viewportHeight);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        contentWidgets.forEach(widget -> widget.visible = false);
        visibleControls.clear();
        int innerLeft = panelLeft + 14;
        int innerWidth = panelWidth - 34;
        int gap = 6;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<LabeledControl> row = rows.get(rowIndex);
            int count = row.size();
            int cellWidth = Math.max(48, (innerWidth - gap * (count - 1)) / count);
            int widgetY = contentTop + 13 + rowIndex * 40 - scrollOffset;
            for (int column = 0; column < count; column++) {
                LabeledControl control = row.get(column);
                AbstractWidget widget = control.widget();
                int cellX = innerLeft + column * (cellWidth + gap);
                boolean visible = widgetY + 20 > contentTop && widgetY < contentBottom;
                AbstractWidget companion = control.companion();
                if (companion == null) {
                    widget.setRectangle(cellWidth, 20, cellX, widgetY);
                } else {
                    // Slider first, then a box just wide enough to type an exact value into.
                    int boxWidth = Mth.clamp(cellWidth / 3, 30, 52);
                    int sliderWidth = Math.max(24, cellWidth - boxWidth - 4);
                    widget.setRectangle(sliderWidth, 20, cellX, widgetY);
                    companion.setRectangle(boxWidth, 20, cellX + sliderWidth + 4, widgetY);
                    companion.visible = visible;
                }
                widget.visible = visible;
                if (visible) visibleControls.add(control);
            }
        }
        int count = actionWidgets.size();
        int actionGap = 6;
        int actionWidth = (panelWidth - 28 - actionGap * (count - 1)) / count;
        int actionY = panelTop + panelHeight - 31;
        for (int index = 0; index < count; index++) {
            actionWidgets.get(index).setRectangle(actionWidth, 22,
                    panelLeft + 14 + index * (actionWidth + actionGap), actionY);
            actionWidgets.get(index).visible = true;
        }
    }

    private List<List<LabeledControl>> controlRows() {
        List<List<LabeledControl>> rows = new ArrayList<>();
        List<LabeledControl> header = new ArrayList<>();
        header.add(control(typeButton, tr("music_battle.l10n.fx.label_017")));
        header.add(control(backgroundButton, tr("music_battle.l10n.fx.label_018")));
        rows.add(header);

        if (original != null && host.showBrushActions()) {
            rows.add(List.of(control(copyToBrushButton, tr("music_battle.l10n.fx.label_019")),
                    control(copyFromBrushButton, tr("music_battle.l10n.fx.label_019"))));
        }
        if (type == TrackEffect.Type.HIDE_BATTLE_PANELS || type == TrackEffect.Type.HIDE_PARTICLES_HAND) {
            if (type == TrackEffect.Type.HIDE_PARTICLES_HAND) {
                rows.add(List.of(control(hideParticlesButton, tr("music_battle.fx.hide_particles")),
                        control(hideFirstPersonHandButton, tr("music_battle.fx.hide_hand"))));
                rows.add(List.of(control(hideHudButton, tr("music_battle.fx.hide_hud"))));
                rows.add(List.of(control(hideBlockyMusicHudButton, tr("music_battle.fx.hide_blocky_music_hud"))));
            }
            if (host.showTimelineControls()) {
                List<LabeledControl> timeline = new ArrayList<>();
                if (original != null) timeline.add(control(startBox, tr("music_battle.l10n.fx.label_020")));
                timeline.add(control(durationBox, tr("music_battle.l10n.fx.label_021")));
                rows.add(timeline);
            }
            return rows;
        }
        if (type != TrackEffect.Type.OBJECT_OUTLINE) {
            rows.add(List.of(control(overlayInterfaceButton, tr("music_battle.l10n.fx.label_022"))));
            rows.add(List.of(control(overlayPlayersButton, tr("music_battle.l10n.fx.label_023")),
                    control(overlayEntitiesButton, tr("music_battle.l10n.fx.label_024"))));
        }

        List<LabeledControl> common = new ArrayList<>();
        if (host.showTimelineControls()) {
            if (original != null) common.add(control(startBox, tr("music_battle.l10n.fx.label_020")));
            common.add(control(durationBox, tr("music_battle.l10n.fx.label_025")));
        }
        if (type != TrackEffect.Type.PLAYER_HIGHLIGHT) common.add(control(intensityBox, intensityLabel()));
        if (type != TrackEffect.Type.SHAKE && type != TrackEffect.Type.PULSE) {
            common.add(control(opacityBox, tr("music_battle.l10n.fx.label_026")));
        }
        rows.add(common);

        rows.add(List.of(
                control(fadeInButton, tr("music_battle.l10n.fx.label_027")),
                control(fadeInTicksBox, tr("music_battle.l10n.fx.label_028"))));
        rows.add(List.of(
                control(fadeOutButton, tr("music_battle.l10n.fx.label_029")),
                control(fadeOutTicksBox, tr("music_battle.l10n.fx.label_030"))));

        if (type != TrackEffect.Type.TEXT && type != TrackEffect.Type.SHAKE && type != TrackEffect.Type.OBJECT_OUTLINE) {
            rows.add(List.of(control(overlayTextButton, tr("music_battle.l10n.fx.label_031"))));
        }

        boolean colors = type == TrackEffect.Type.FLASH || type == TrackEffect.Type.PULSE || type == TrackEffect.Type.VISUALIZER || type == TrackEffect.Type.TEXT
                || type == TrackEffect.Type.PIXEL_FIRE || type == TrackEffect.Type.STARS
                || type == TrackEffect.Type.PLAYER_HIGHLIGHT || type == TrackEffect.Type.VIGNETTE
                || type == TrackEffect.Type.CINEMATIC_BARS || type == TrackEffect.Type.OBJECT_OUTLINE;
        if (type == TrackEffect.Type.OBJECT_OUTLINE) {
            for (int start = 0; start < objectOutlineColors.size(); start += 4) {
                List<LabeledControl> paletteRow = new ArrayList<>();
                for (int index = start; index < Math.min(start + 4, objectOutlineColors.size()); index++) {
                    paletteRow.add(control(objectOutlineColorButtons.get(index),
                            tr(index == selectedObjectOutlineColor ? "music_battle.l10n.fx.selected_palette_color"
                                    : "music_battle.l10n.fx.palette_color", index + 1, colorText(objectOutlineColors.get(index)))));
                }
                rows.add(paletteRow);
            }
            rows.add(List.of(control(objectOutlineAddColorButton, tr("music_battle.l10n.fx.label_034")),
                    control(objectOutlineRemoveColorButton, tr("music_battle.l10n.fx.label_035")),
                    control(objectOutlineMoveColorLeftButton, tr("music_battle.l10n.fx.label_036")),
                    control(objectOutlineMoveColorRightButton, tr("music_battle.l10n.fx.label_037"))));
            if (objectOutlineColors.size() >= 2) {
                rows.add(List.of(control(objectOutlineColorSpeedBox, tr("music_battle.l10n.fx.label_038"))));
            }
        } else if (colors) {
            List<LabeledControl> colorRow = new ArrayList<>();
            colorRow.add(control(colorAButton, type == TrackEffect.Type.FLASH ? tr("music_battle.l10n.fx.label_039")
                    : type == TrackEffect.Type.PLAYER_HIGHLIGHT ? tr("music_battle.l10n.fx.label_040")
                    : type == TrackEffect.Type.VIGNETTE ? tr("music_battle.l10n.fx.label_041")
                    : type == TrackEffect.Type.CINEMATIC_BARS ? tr("music_battle.l10n.fx.label_042")
                    : type == TrackEffect.Type.STARS ? tr("effect.music_battle.stars.setting.main_color") : tr("music_battle.l10n.fx.label_043")));
            if (type != TrackEffect.Type.FLASH && type != TrackEffect.Type.VIGNETTE
                    && type != TrackEffect.Type.CINEMATIC_BARS) {
                colorRow.add(control(colorBButton, type == TrackEffect.Type.TEXT ? tr("music_battle.l10n.fx.label_044")
                        : type == TrackEffect.Type.PLAYER_HIGHLIGHT ? tr("music_battle.l10n.fx.label_045")
                        : type == TrackEffect.Type.STARS ? tr("effect.music_battle.stars.setting.shimmer_color") : tr("music_battle.l10n.fx.label_046")));
            }
            if (type == TrackEffect.Type.TEXT) {
                colorRow.add(control(positionXBox, tr("music_battle.l10n.fx.label_047")));
                colorRow.add(control(positionYBox, tr("music_battle.l10n.fx.label_048")));
            }
            rows.add(colorRow);
        }
        if (type == TrackEffect.Type.VISUALIZER) {
            rows.add(List.of(control(visualizerPositionButton, tr("music_battle.l10n.fx.label_049")),
                    control(visualizerOffsetBox, tr("music_battle.l10n.fx.label_050"))));
            rows.add(List.of(control(positionXBox, tr("music_battle.l10n.fx.label_051")),
                    control(positionYBox, tr("music_battle.l10n.fx.label_052")),
                    control(variantButton, tr("music_battle.l10n.fx.label_053"))));
            rows.add(List.of(control(gradientButton, tr("music_battle.l10n.fx.label_054")), control(sectionsButton, tr("music_battle.l10n.fx.label_055"))));
        }
        if (type == TrackEffect.Type.OLD_TV) {
            rows.add(List.of(control(tvScanlineSpacingBox, tr("music_battle.l10n.fx.label_056")),
                    control(tvScanlineOpacityBox, tr("music_battle.l10n.fx.label_057")),
                    control(tvFlickerAmountBox, tr("music_battle.l10n.fx.label_058"))));
            rows.add(List.of(control(tvNoiseAmountBox, tr("music_battle.l10n.fx.label_059")),
                    control(tvNoiseOpacityBox, tr("music_battle.l10n.fx.label_060"))));
            rows.add(List.of(control(tvSweepButton, tr("music_battle.l10n.fx.label_061")),
                    control(tvSweepWidthBox, tr("music_battle.l10n.fx.label_062")),
                    control(tvSweepOpacityBox, tr("music_battle.l10n.fx.label_063")),
                    control(tvSweepSpeedBox, tr("music_battle.l10n.fx.label_064"))));
        }
        if (type == TrackEffect.Type.STARS) {
            rows.add(List.of(control(positionXBox, tr("effect.music_battle.stars.setting.center_x")),
                    control(positionYBox, tr("effect.music_battle.stars.setting.center_y")),
                    control(starSpreadBox, tr("effect.music_battle.stars.setting.spread"))));
            rows.add(List.of(control(starSizeBox, tr("effect.music_battle.stars.setting.size")),
                    control(starDensityBox, tr("effect.music_battle.stars.setting.density")),
                    control(starBrightnessBox, tr("effect.music_battle.stars.setting.brightness"))));
            rows.add(List.of(control(starTwinkleSpeedBox, tr("effect.music_battle.stars.setting.twinkle_speed")),
                    control(starDriftSpeedBox, tr("effect.music_battle.stars.setting.drift_speed")),
                    control(starIridescenceBox, tr("effect.music_battle.stars.setting.iridescence"))));
            rows.add(List.of(control(starDirectionButton, tr("effect.music_battle.stars.setting.direction"))));
        }
        if (type == TrackEffect.Type.VIGNETTE) {
            rows.add(List.of(control(vignetteRadiusBox, tr("music_battle.l10n.fx.label_065")),
                    control(vignetteSoftnessBox, tr("music_battle.l10n.fx.label_066")),
                    control(vignetteRoundnessBox, tr("music_battle.l10n.fx.label_067"))));
        }
        if (type == TrackEffect.Type.FISH_EYE) {
            rows.add(List.of(control(fishEyeStrengthBox, tr("music_battle.l10n.fx.label_068")),
                    control(fishEyeRadiusBox, tr("music_battle.l10n.fx.label_069")),
                    control(fishEyeZoomBox, tr("music_battle.l10n.fx.label_070"))));
            rows.add(List.of(control(fishEyeChromaticBox, tr("music_battle.l10n.fx.label_071")),
                    control(fishEyeEdgeDarkeningBox, tr("music_battle.l10n.fx.label_072")),
                    control(fishEyeRemoveBlackEdgesButton, tr("music_battle.l10n.fx.label_073"))));
            rows.add(List.of(control(positionXBox, tr("music_battle.l10n.fx.label_074")), control(positionYBox, tr("music_battle.l10n.fx.label_075"))));
        }
        if (type == TrackEffect.Type.CINEMATIC_BARS) {
            rows.add(List.of(control(cinematicTopHeightBox, tr("music_battle.l10n.fx.label_076")),
                    control(cinematicBottomHeightBox, tr("music_battle.l10n.fx.label_077")),
                    control(cinematicCenterOffsetBox, tr("music_battle.l10n.fx.label_078"))));
            rows.add(List.of(control(cinematicFeatherBox, tr("music_battle.l10n.fx.label_079")),
                    control(cinematicShadowBox, tr("music_battle.l10n.fx.label_080")),
                    control(cinematicAnimationSpeedBox, tr("music_battle.l10n.fx.label_081")),
                    control(cinematicAnimationButton, tr("music_battle.l10n.fx.label_082"))));
        }
        if (type == TrackEffect.Type.COLOR_CORRECTION) {
            rows.add(List.of(control(colorBrightnessBox, tr("music_battle.l10n.fx.label_083")),
                    control(colorContrastBox, tr("music_battle.l10n.fx.label_084")),
                    control(colorSaturationBox, tr("music_battle.l10n.fx.label_085"))));
            rows.add(List.of(control(colorHueBox, tr("music_battle.l10n.fx.label_086")),
                    control(colorGammaBox, tr("music_battle.l10n.fx.label_087")),
                    control(colorTemperatureBox, tr("music_battle.l10n.fx.label_088"))));
        }
        if (type == TrackEffect.Type.TEXT) {
            rows.add(List.of(control(textBox, tr("music_battle.l10n.fx.label_089"))));
            rows.add(List.of(control(fontButton, tr("music_battle.l10n.fx.label_090")), control(animationButton, tr("music_battle.l10n.fx.label_091")), control(exitAnimationButton, tr("music_battle.l10n.fx.label_092"))));
            rows.add(List.of(control(textSpeedBox, tr("music_battle.l10n.fx.label_093")), control(outlineThicknessBox, tr("music_battle.l10n.fx.label_094"))));
        } else if (type == TrackEffect.Type.FLASH) {
            rows.add(List.of(control(flashBeatButton, tr("music_battle.l10n.fx.label_095")),
                    control(softnessBox, tr("music_battle.l10n.fx.label_096"))));
        } else if (type == TrackEffect.Type.PLAYER_HIGHLIGHT) {
            rows.add(List.of(control(variantButton, tr("music_battle.l10n.fx.label_097")),
                    control(outlineThicknessBox, tr("music_battle.l10n.fx.label_098")), control(glowStrengthBox, tr("music_battle.l10n.fx.label_099"))));
            rows.add(List.of(control(highlightSpectatorsButton, tr("music_battle.l10n.fx.label_100")),
                    control(highlightSpectatorColorButton, tr("music_battle.l10n.fx.label_003"))));
            rows.add(List.of(control(highlightEntitiesButton, tr("music_battle.l10n.fx.label_101")),
                    control(highlightEntityColorButton, tr("music_battle.l10n.fx.label_004"))));
            rows.add(List.of(control(highlightModelGlowButton,
                    tr("music_battle.l10n.fx.label_102"))));
        } else if (type == TrackEffect.Type.OBJECT_OUTLINE) {
            rows.add(List.of(control(outlineThicknessBox, tr("music_battle.l10n.fx.label_103")),
                    control(glowStrengthBox, tr("music_battle.l10n.fx.label_104"))));
        }
        if (type != TrackEffect.Type.PULSE) rows.add(List.of(control(beatReactiveButton,
                type == TrackEffect.Type.OBJECT_OUTLINE ? tr("music_battle.l10n.fx.label_105")
                        : tr("music_battle.l10n.fx.label_106"))));
        return rows;
    }

    private LabeledControl control(AbstractWidget widget, String label) {
        FxSlider slider = widget instanceof EditBox box ? sliders.get(box) : null;
        // A numeric field occupies its cell as slider + box; everything else keeps the whole cell.
        return slider == null ? new LabeledControl(widget, label)
                : new LabeledControl(slider, label, widget);
    }

    private void drawScrollbar(GuiGraphics graphics) {
        int x = panelLeft + panelWidth - 8;
        graphics.fill(x, contentTop, x + 3, contentBottom, 0xFF263847);
        int track = contentBottom - contentTop;
        int thumb = Math.max(18, Math.round(track * track / (float) (track + maxScroll)));
        int y = contentTop + Math.round((track - thumb) * scrollOffset / (float) maxScroll);
        graphics.fill(x, y, x + 3, y + thumb, 0xFFE3A6FF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!fontPickerOpen && maxScroll > 0 && mouseX >= panelLeft && mouseX < panelLeft + panelWidth
                && mouseY >= contentTop && mouseY < contentBottom) {
            scrollOffset = Mth.clamp(scrollOffset - (int) Math.round(scrollY * 28.0D), 0, maxScroll);
            relayout();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void cycleType() {
        TrackEffect.Type[] values = TrackEffect.Type.values();
        selectType(values[(type.ordinal() + 1) % values.length]);
    }

    private void selectType(TrackEffect.Type selectedType) {
        TrackEffect current = buildEffect(false);
        if (original == null && current != null) ClientEffectToolSettings.savePreset(current, placementMode);
        type = selectedType;
        TrackEffect next = ClientEffectToolSettings.preset(type);
        if (original != null) next = next.withLayer(original.layer()).withTimeline(original.lane(), original.tick(), original.durationTicks());
        else next = next.withLayer(lane);
        placementMode = original == null ? ClientEffectToolSettings.placementMode(type) : placementMode;
        applyEffect(next);
        scrollOffset = 0;
        relayout();
        previewStartedNanos = System.nanoTime();
    }

    private void cycleLayer() { lane = (lane + 1) % 4; layerButton.setMessage(layerLabel()); }
    private void cyclePlacement() { placementMode = next(PLACEMENT_MODES, placementMode); placementButton.setMessage(placementLabel()); }
    private void cycleBackground() { backgroundIndex = (backgroundIndex + 1) % BACKGROUNDS.length; backgroundButton.setMessage(backgroundLabel()); }
    private void openFontPicker() { fontPickerOpen = true; }
    private void openEffectPicker() { effectPickerOpen = true; }
    private void cycleAnimation() { animation = next(TEXT_ANIMATIONS, animation); animationButton.setMessage(animationLabel()); }
    private void cycleExitAnimation() { exitAnimation = next(TEXT_EXIT_ANIMATIONS, exitAnimation); exitAnimationButton.setMessage(exitAnimationLabel()); }
    private void cycleVariant() {
        variant = type == TrackEffect.Type.PLAYER_HIGHLIGHT ? next(HIGHLIGHT_MODES, variant)
                : type == TrackEffect.Type.VISUALIZER ? visualizerVariant(!visualizerFullWidth(variant), visualizerVertical(variant), visualizerSections(variant))
                : next(new String[]{"both", "mirror", "center"}, variant);
        variantButton.setMessage(variantLabel());
    }

    private void cycleVisualizerGradient() {
        variant = visualizerVariant(visualizerFullWidth(variant), !visualizerVertical(variant), visualizerSections(variant));
        gradientButton.setMessage(gradientLabel());
    }

    private void cycleVisualizerSections() {
        int[] values = {8, 12, 16, 24, 32, 48, 64};
        int current = visualizerSections(variant);
        int next = values[0];
        for (int index = 0; index < values.length; index++) {
            if (values[index] == current) {
                next = values[(index + 1) % values.length];
                break;
            }
        }
        variant = visualizerVariant(visualizerFullWidth(variant), visualizerVertical(variant), next);
        sectionsButton.setMessage(sectionsLabel());
    }

    private void cycleVisualizerPosition() {
        visualizerPosition = next(VISUALIZER_POSITIONS, visualizerPosition);
        visualizerPositionButton.setMessage(visualizerPositionLabel());
    }

    private void cycleStarDirection() {
        starDirection = next(STAR_DIRECTIONS, starDirection);
        starDirectionButton.setMessage(starDirectionLabel());
    }

    private void toggleBeatReactive() {
        beatReactive = !beatReactive;
        beatReactiveButton.setMessage(beatReactiveLabel());
    }

    private void toggleFlashBeat() {
        variant = flashFollowsBeat() ? "steady" : "both";
        flashBeatButton.setMessage(flashBeatLabel());
    }

    private void toggleOverlayPlayers() {
        overlayPlayers = !overlayPlayers;
        overlayPlayersButton.setMessage(overlayPlayersLabel());
    }

    private void toggleOverlayEntities() {
        overlayEntities = !overlayEntities;
        overlayEntitiesButton.setMessage(overlayEntitiesLabel());
    }

    private void toggleHighlightSpectators() {
        highlightSpectators = !highlightSpectators;
        highlightSpectatorsButton.setMessage(highlightSpectatorsLabel());
        highlightSpectatorColorButton.active = highlightSpectators;
    }

    private void toggleHighlightEntities() {
        highlightEntities = !highlightEntities;
        highlightEntitiesButton.setMessage(highlightEntitiesLabel());
        highlightEntityColorButton.active = highlightEntities;
    }

    private void toggleHighlightModelGlow() {
        highlightModelGlow = !highlightModelGlow;
        highlightModelGlowButton.setMessage(highlightModelGlowLabel());
        hideParticlesButton.setMessage(hideParticlesLabel());
        hideFirstPersonHandButton.setMessage(hideHandLabel());
        hideHudButton.setMessage(hideHudLabel());
        hideBlockyMusicHudButton.setMessage(hideBlockyMusicHudLabel());
    }

    private void setObjectOutlineColor(int index, int color) {
        if (index < 0 || index >= objectOutlineColors.size()) return;
        selectedObjectOutlineColor = index;
        objectOutlineColors.set(index, 0xFF000000 | (color & 0x00FFFFFF));
        refreshObjectOutlinePaletteButtons();
        relayout();
    }

    private void addObjectOutlineColor() {
        if (objectOutlineColors.size() >= TrackEffectSettings.MAX_OBJECT_OUTLINE_COLORS) {
            notice = tr("music_battle.l10n.fx.label_107");
            return;
        }
        int source = objectOutlineColors.isEmpty() ? colorAButton.color()
                : objectOutlineColors.get(Mth.clamp(selectedObjectOutlineColor, 0, objectOutlineColors.size() - 1));
        objectOutlineColors.add(source);
        selectedObjectOutlineColor = objectOutlineColors.size() - 1;
        if (objectOutlineColors.size() == 2) {
            try {
                if (parseFloat(objectOutlineColorSpeedBox) <= 0.0F) {
                    objectOutlineColorSpeedBox.setValue(format(0.5F));
                }
            } catch (RuntimeException ignored) {
                objectOutlineColorSpeedBox.setValue(format(0.5F));
            }
        }
        refreshObjectOutlinePaletteButtons();
        relayout();
    }

    private void removeObjectOutlineColor() {
        if (objectOutlineColors.size() <= 1) {
            notice = tr("music_battle.l10n.fx.label_108");
            return;
        }
        objectOutlineColors.remove(Mth.clamp(selectedObjectOutlineColor, 0, objectOutlineColors.size() - 1));
        selectedObjectOutlineColor = Mth.clamp(selectedObjectOutlineColor, 0, objectOutlineColors.size() - 1);
        refreshObjectOutlinePaletteButtons();
        relayout();
    }

    private void moveObjectOutlineColor(int direction) {
        if (objectOutlineColors.size() < 2) return;
        int from = Mth.clamp(selectedObjectOutlineColor, 0, objectOutlineColors.size() - 1);
        int to = Mth.clamp(from + direction, 0, objectOutlineColors.size() - 1);
        if (from == to) return;
        Collections.swap(objectOutlineColors, from, to);
        selectedObjectOutlineColor = to;
        refreshObjectOutlinePaletteButtons();
        relayout();
    }

    private void applyObjectOutlinePalette(TrackEffect effect) {
        objectOutlineColors.clear();
        objectOutlineColors.addAll(effect.objectOutlinePalette().stream()
                .limit(TrackEffectSettings.MAX_OBJECT_OUTLINE_COLORS)
                .map(color -> 0xFF000000 | (color & 0x00FFFFFF))
                .toList());
        if (objectOutlineColors.isEmpty()) objectOutlineColors.add(0xFF000000 | (effect.colorA() & 0x00FFFFFF));
        selectedObjectOutlineColor = Mth.clamp(selectedObjectOutlineColor, 0, objectOutlineColors.size() - 1);
        refreshObjectOutlinePaletteButtons();
    }

    private void refreshObjectOutlinePaletteButtons() {
        if (objectOutlineColors.isEmpty()) {
            objectOutlineColors.add(colorAButton == null ? seed.colorA() : colorAButton.color());
        }
        selectedObjectOutlineColor = Mth.clamp(selectedObjectOutlineColor, 0, objectOutlineColors.size() - 1);
        // This palette is retained while switching effect types and rebuilding the inspector.
        // Only OBJECT_OUTLINE owns color A through it; other effects own their picker value.
        if (type == TrackEffect.Type.OBJECT_OUTLINE && colorAButton != null) {
            colorAButton.setColor(objectOutlineColors.getFirst());
        }
        for (int index = 0; index < objectOutlineColorButtons.size(); index++) {
            ExactColorButton button = objectOutlineColorButtons.get(index);
            boolean used = index < objectOutlineColors.size();
            button.active = used;
            if (used) button.setColor(objectOutlineColors.get(index));
        }
        if (objectOutlineAddColorButton != null) {
            objectOutlineAddColorButton.active = objectOutlineColors.size() < TrackEffectSettings.MAX_OBJECT_OUTLINE_COLORS;
            objectOutlineRemoveColorButton.active = objectOutlineColors.size() > 1;
            objectOutlineMoveColorLeftButton.active = selectedObjectOutlineColor > 0;
            objectOutlineMoveColorRightButton.active = selectedObjectOutlineColor + 1 < objectOutlineColors.size();
        }
    }

    private List<Integer> currentObjectOutlineColors() {
        if (objectOutlineColorButtons.size() < objectOutlineColors.size()) {
            return List.copyOf(objectOutlineColors);
        }
        List<Integer> colors = new ArrayList<>(objectOutlineColors.size());
        for (int index = 0; index < objectOutlineColors.size(); index++) {
            colors.add(objectOutlineColorButtons.get(index).color());
        }
        return List.copyOf(colors);
    }

    private void toggleTvSweep() {
        tvSweepEnabled = !tvSweepEnabled;
        tvSweepButton.setMessage(tvSweepLabel());
    }

    private void toggleCinematicAnimation() {
        cinematicAnimated = !cinematicAnimated;
        cinematicAnimationButton.setMessage(cinematicAnimationLabel());
    }

    private void toggleFishEyeRemoveBlackEdges() {
        fishEyeRemoveBlackEdges = !fishEyeRemoveBlackEdges;
        fishEyeRemoveBlackEdgesButton.setMessage(fishEyeRemoveBlackEdgesLabel());
    }

    private void toggleOverlayText() {
        overlayText = !overlayText;
        overlayTextButton.setMessage(overlayTextLabel());
    }

    private void toggleOverlayInterface() {
        overlayInterface = !overlayInterface;
        overlayInterfaceButton.setMessage(overlayInterfaceLabel());
    }

    private void toggleFadeIn() {
        fadeInEnabled = !fadeInEnabled;
        updateFadeControls();
    }

    private void toggleFadeOut() {
        fadeOutEnabled = !fadeOutEnabled;
        updateFadeControls();
    }

    private void copyToBrush() {
        TrackEffect current = buildEffect(true);
        if (current == null) return;
        TrackEffect brush = ClientEffectToolSettings.preset(current.type());
        TrackEffect transferred = copyEffectSettings(current, current,
                current.type() == TrackEffect.Type.TEXT ? brush.text() : current.text());
        String brushMode = ClientEffectToolSettings.placementMode(current.type());
        ClientEffectToolSettings.savePreset(transferred, brushMode);
        host.refreshEffectBrush(current.type());
        error = "";
        notice = current.type() == TrackEffect.Type.TEXT
                ? tr("music_battle.l10n.fx.label_109")
                : tr("music_battle.l10n.fx.label_110");
    }

    private void copyFromBrush() {
        TrackEffect current = buildEffect(true);
        if (current == null) return;
        TrackEffect brush = ClientEffectToolSettings.preset(current.type());
        TrackEffect transferred = copyEffectSettings(brush, current,
                current.type() == TrackEffect.Type.TEXT ? current.text() : brush.text());
        mutate(() -> applyEffect(transferred));
        error = "";
        notice = current.type() == TrackEffect.Type.TEXT
                ? tr("music_battle.l10n.fx.label_111")
                : tr("music_battle.l10n.fx.label_112");
        previewStartedNanos = System.nanoTime();
    }

    private static TrackEffect copyEffectSettings(TrackEffect source, TrackEffect timeline, String text) {
        return new TrackEffect(source.type(), timeline.lane(), timeline.layer(), timeline.tick(), source.durationTicks(),
                source.intensity(), source.softness(), source.colorA(), source.colorB(), text, source.font(),
                source.positionX(), source.positionY(), source.animation(), source.exitAnimation(), source.variant(),
                source.beatReactive(), source.opacity(), source.textSpeed(), source.outlineThickness(), source.glowStrength(),
                source.settings());
    }

    private void updateVisibility() {
        if (softnessBox != null) {
            softnessBox.visible = type == TrackEffect.Type.FLASH;
        }
        if (intensityBox != null) intensityBox.visible = type != TrackEffect.Type.PLAYER_HIGHLIGHT;
        if (opacityBox != null) opacityBox.visible = type != TrackEffect.Type.SHAKE && type != TrackEffect.Type.PULSE;
        if (positionXBox != null) positionXBox.visible = type == TrackEffect.Type.TEXT || type == TrackEffect.Type.VISUALIZER
                || type == TrackEffect.Type.FISH_EYE || type == TrackEffect.Type.STARS;
        if (positionYBox != null) positionYBox.visible = type == TrackEffect.Type.TEXT || type == TrackEffect.Type.VISUALIZER
                || type == TrackEffect.Type.FISH_EYE || type == TrackEffect.Type.STARS;
        if (textSpeedBox != null) textSpeedBox.visible = type == TrackEffect.Type.TEXT;
        if (outlineThicknessBox != null) outlineThicknessBox.visible = type == TrackEffect.Type.PLAYER_HIGHLIGHT
                || type == TrackEffect.Type.OBJECT_OUTLINE || type == TrackEffect.Type.TEXT;
        if (glowStrengthBox != null) glowStrengthBox.visible = type == TrackEffect.Type.PLAYER_HIGHLIGHT
                || type == TrackEffect.Type.OBJECT_OUTLINE;
        if (beatReactiveButton != null) beatReactiveButton.visible = type != TrackEffect.Type.PULSE;
        if (colorAButton != null) {
            boolean colors = type == TrackEffect.Type.FLASH || type == TrackEffect.Type.PULSE || type == TrackEffect.Type.VISUALIZER || type == TrackEffect.Type.TEXT
                    || type == TrackEffect.Type.PIXEL_FIRE || type == TrackEffect.Type.STARS
                    || type == TrackEffect.Type.PLAYER_HIGHLIGHT || type == TrackEffect.Type.VIGNETTE
                    || type == TrackEffect.Type.CINEMATIC_BARS || type == TrackEffect.Type.OBJECT_OUTLINE;
            colorAButton.visible = colors && type != TrackEffect.Type.OBJECT_OUTLINE;
            colorBButton.visible = colors && type != TrackEffect.Type.FLASH && type != TrackEffect.Type.VIGNETTE
                    && type != TrackEffect.Type.CINEMATIC_BARS && type != TrackEffect.Type.OBJECT_OUTLINE;
        }
        if (textBox != null) {
            boolean text = type == TrackEffect.Type.TEXT;
            textBox.visible = text;
            fontButton.visible = text;
            animationButton.visible = text;
            exitAnimationButton.visible = text;
        }
        if (variantButton != null) {
            variantButton.visible = type == TrackEffect.Type.PLAYER_HIGHLIGHT || type == TrackEffect.Type.VISUALIZER;
        }
        if (gradientButton != null) gradientButton.visible = type == TrackEffect.Type.VISUALIZER;
        if (sectionsButton != null) sectionsButton.visible = type == TrackEffect.Type.VISUALIZER;
        if (starDirectionButton != null) starDirectionButton.visible = type == TrackEffect.Type.STARS;
        if (tvSweepButton != null) tvSweepButton.visible = type == TrackEffect.Type.OLD_TV;
        if (flashBeatButton != null) flashBeatButton.visible = type == TrackEffect.Type.FLASH;
        if (overlayPlayersButton != null) overlayPlayersButton.visible = true;
        if (overlayEntitiesButton != null) overlayEntitiesButton.visible = true;
        if (highlightSpectatorsButton != null) highlightSpectatorsButton.visible = type == TrackEffect.Type.PLAYER_HIGHLIGHT;
        if (highlightSpectatorColorButton != null) {
            highlightSpectatorColorButton.visible = type == TrackEffect.Type.PLAYER_HIGHLIGHT;
            highlightSpectatorColorButton.active = highlightSpectators;
        }
        if (highlightEntitiesButton != null) highlightEntitiesButton.visible = type == TrackEffect.Type.PLAYER_HIGHLIGHT;
        if (highlightEntityColorButton != null) {
            highlightEntityColorButton.visible = type == TrackEffect.Type.PLAYER_HIGHLIGHT;
            highlightEntityColorButton.active = highlightEntities;
        }
        if (highlightModelGlowButton != null) {
            highlightModelGlowButton.visible = type == TrackEffect.Type.PLAYER_HIGHLIGHT;
        }
        if (cinematicAnimationButton != null) cinematicAnimationButton.visible = type == TrackEffect.Type.CINEMATIC_BARS;
        if (fishEyeRemoveBlackEdgesButton != null) fishEyeRemoveBlackEdgesButton.visible = type == TrackEffect.Type.FISH_EYE;
        updateFadeControls();
        if (placementButton != null) {
            placementButton.visible = host.showTimelineControls() && original == null;
        }
        if (typeButton != null) typeButton.active = host.allowTypeChange();
        relayout();
    }

    private TrackEffect buildEffect(boolean strict) {
        try {
            List<Integer> outlineColors = currentObjectOutlineColors();
            int tick = Math.max(0, Integer.parseInt(startBox.getValue().trim()));
            int duration = Math.max(0, Integer.parseInt(durationBox.getValue().trim()));
            // Bounds come from the shared range table, the same one the record clamps use.
            float intensity = FxRange.INTENSITY.clamp(parseFloat(intensityBox));
            float softness = FxRange.SOFTNESS.clamp(parseFloat(softnessBox));
            int colorA = type == TrackEffect.Type.OBJECT_OUTLINE
                    ? outlineColors.getFirst() : colorAButton.color();
            int colorB = colorBButton.color();
            float x = FxRange.POSITION_X.clamp(parseFloat(positionXBox));
            float y = FxRange.POSITION_Y.clamp(parseFloat(positionYBox));
            float opacity = FxRange.OPACITY.clamp(parseFloat(opacityBox));
            float textSpeed = FxRange.TEXT_SPEED.clamp(parseFloat(textSpeedBox));
            float outlineThickness = FxRange.outlineThickness(type).clamp(parseFloat(outlineThicknessBox));
            float glowStrength = FxRange.GLOW_STRENGTH.clamp(parseFloat(glowStrengthBox));
            TrackEffectSettings settings = new TrackEffectSettings(
                    parseFloat(tvScanlineSpacingBox), parseFloat(tvScanlineOpacityBox), parseFloat(tvSweepWidthBox),
                    parseFloat(tvSweepOpacityBox), parseFloat(tvSweepSpeedBox), tvSweepEnabled,
                    parseFloat(tvNoiseAmountBox), parseFloat(tvNoiseOpacityBox), parseFloat(tvFlickerAmountBox),
                    parseFloat(vignetteRadiusBox), parseFloat(vignetteSoftnessBox), parseFloat(vignetteRoundnessBox),
                    parseFloat(fishEyeStrengthBox), parseFloat(fishEyeRadiusBox), parseFloat(fishEyeZoomBox),
                    parseFloat(fishEyeChromaticBox),
                    parseFloat(fishEyeEdgeDarkeningBox),
                    fishEyeRemoveBlackEdges,
                    parseFloat(cinematicTopHeightBox), parseFloat(cinematicBottomHeightBox), parseFloat(cinematicFeatherBox),
                    parseFloat(cinematicAnimationSpeedBox), cinematicAnimated, parseFloat(cinematicCenterOffsetBox),
                    parseFloat(cinematicShadowBox), overlayText, parseFloat(fadeInTicksBox), parseFloat(fadeOutTicksBox),
                    fadeInEnabled, fadeOutEnabled, visualizerPosition, parseFloat(visualizerOffsetBox), overlayInterface,
                    overlayPlayers, overlayEntities, highlightSpectators, highlightSpectatorColorButton.color(),
                    highlightEntities, highlightEntityColorButton.color(),
                    highlightModelGlow,
                    parseFloat(colorBrightnessBox), parseFloat(colorContrastBox), parseFloat(colorSaturationBox),
                    parseFloat(colorHueBox), parseFloat(colorGammaBox), parseFloat(colorTemperatureBox),
                    parseFloat(starSizeBox), parseFloat(starDensityBox), parseFloat(starTwinkleSpeedBox),
                    parseFloat(starDriftSpeedBox), parseFloat(starSpreadBox), parseFloat(starBrightnessBox),
                    parseFloat(starIridescenceBox), starDirection,
                    type == TrackEffect.Type.OBJECT_OUTLINE && outlineColors.size() >= 2
                            ? outlineColors : List.of(),
                    parseFloat(objectOutlineColorSpeedBox), hideParticles, hideFirstPersonHand, hideHud, hideBlockyMusicHud);
            return new TrackEffect(type, seed.lane(), lane, tick, duration, intensity, softness, colorA, colorB,
                    textBox.getValue(), fontId, x, y, animation, exitAnimation, variant, beatReactive, opacity, textSpeed,
                    outlineThickness, glowStrength, settings);
        } catch (RuntimeException exception) {
            if (strict) {
                error = tr("music_battle.l10n.fx.label_113");
            }
            return null;
        }
    }

    private void applyEffect(TrackEffect effect) {
        type = effect.type();
        lane = effect.layer();
        fontId = effect.font();
        animation = effect.animation();
        exitAnimation = effect.exitAnimation();
        variant = effect.variant();
        beatReactive = effect.beatReactive();
        tvSweepEnabled = effect.settings().tvSweepEnabled();
        cinematicAnimated = effect.settings().cinematicAnimated();
        fishEyeRemoveBlackEdges = effect.settings().fishEyeRemoveBlackEdges();
        overlayText = effect.settings().overlayText();
        overlayInterface = effect.settings().overlayInterface();
        overlayPlayers = effect.settings().overlayPlayers();
        overlayEntities = effect.settings().overlayEntities();
        highlightSpectators = effect.settings().highlightSpectators();
        highlightEntities = effect.settings().highlightEntities();
        highlightModelGlow = effect.settings().highlightModelGlow();
        hideParticles = effect.settings().hideParticles();
        hideFirstPersonHand = effect.settings().hideFirstPersonHand();
        hideHud = effect.settings().hideHud();
        hideBlockyMusicHud = effect.settings().hideBlockyMusicHud();
        fadeInEnabled = effect.settings().fadeInEnabled();
        fadeOutEnabled = effect.settings().fadeOutEnabled();
        visualizerPosition = effect.settings().visualizerPosition();
        startBox.setValue(Integer.toString(effect.tick()));
        durationBox.setValue(Integer.toString(effect.durationTicks()));
        intensityBox.setValue(format(effect.intensity()));
        softnessBox.setValue(format(effect.softness()));
        colorAButton.setColor(effect.colorA());
        colorBButton.setColor(effect.colorB());
        textBox.setValue(effect.text());
        positionXBox.setValue(format(effect.positionX()));
        positionYBox.setValue(format(effect.positionY()));
        opacityBox.setValue(format(effect.opacity()));
        textSpeedBox.setValue(format(effect.textSpeed()));
        outlineThicknessBox.setValue(format(effect.outlineThickness()));
        glowStrengthBox.setValue(format(effect.glowStrength()));
        TrackEffectSettings settings = effect.settings();
        tvScanlineSpacingBox.setValue(format(settings.tvScanlineSpacing()));
        tvScanlineOpacityBox.setValue(format(settings.tvScanlineOpacity()));
        tvSweepWidthBox.setValue(format(settings.tvSweepWidth()));
        tvSweepOpacityBox.setValue(format(settings.tvSweepOpacity()));
        tvSweepSpeedBox.setValue(format(settings.tvSweepSpeed()));
        tvNoiseAmountBox.setValue(format(settings.tvNoiseAmount()));
        tvNoiseOpacityBox.setValue(format(settings.tvNoiseOpacity()));
        tvFlickerAmountBox.setValue(format(settings.tvFlickerAmount()));
        vignetteRadiusBox.setValue(format(settings.vignetteRadius()));
        vignetteSoftnessBox.setValue(format(settings.vignetteSoftness()));
        vignetteRoundnessBox.setValue(format(settings.vignetteRoundness()));
        fishEyeStrengthBox.setValue(format(settings.fishEyeStrength()));
        fishEyeRadiusBox.setValue(format(settings.fishEyeRadius()));
        fishEyeZoomBox.setValue(format(settings.fishEyeZoom()));
        fishEyeChromaticBox.setValue(format(settings.fishEyeChromatic()));
        fishEyeEdgeDarkeningBox.setValue(format(settings.fishEyeEdgeDarkening()));
        cinematicTopHeightBox.setValue(format(settings.cinematicTopHeight()));
        cinematicBottomHeightBox.setValue(format(settings.cinematicBottomHeight()));
        cinematicFeatherBox.setValue(format(settings.cinematicFeather()));
        cinematicAnimationSpeedBox.setValue(format(settings.cinematicAnimationSpeed()));
        cinematicCenterOffsetBox.setValue(format(settings.cinematicCenterOffset()));
        cinematicShadowBox.setValue(format(settings.cinematicShadow()));
        fadeInTicksBox.setValue(format(settings.fadeInTicks()));
        fadeOutTicksBox.setValue(format(settings.fadeOutTicks()));
        visualizerOffsetBox.setValue(format(settings.visualizerOffset()));
        colorBrightnessBox.setValue(format(settings.colorBrightness()));
        colorContrastBox.setValue(format(settings.colorContrast()));
        colorSaturationBox.setValue(format(settings.colorSaturation()));
        colorHueBox.setValue(format(settings.colorHue()));
        colorGammaBox.setValue(format(settings.colorGamma()));
        colorTemperatureBox.setValue(format(settings.colorTemperature()));
        highlightSpectatorColorButton.setColor(settings.highlightSpectatorColor());
        highlightEntityColorButton.setColor(settings.highlightEntityColor());
        objectOutlineColorSpeedBox.setValue(format(settings.objectOutlineColorSpeed()));
        starSizeBox.setValue(format(settings.starSize()));
        starDensityBox.setValue(format(settings.starDensity()));
        starTwinkleSpeedBox.setValue(format(settings.starTwinkleSpeed()));
        starDriftSpeedBox.setValue(format(settings.starDriftSpeed()));
        starSpreadBox.setValue(format(settings.starSpread()));
        starBrightnessBox.setValue(format(settings.starBrightness()));
        starIridescenceBox.setValue(format(settings.starIridescence()));
        starDirection = settings.starDirection();
        applyObjectOutlinePalette(effect);
        refreshButtonLabels();
        updateVisibility();
    }

    private void refreshButtonLabels() {
        typeButton.setMessage(typeLabel());
        layerButton.setMessage(layerLabel());
        placementButton.setMessage(placementLabel());
        backgroundButton.setMessage(backgroundLabel());
        fontButton.setMessage(fontLabel());
        animationButton.setMessage(animationLabel());
        exitAnimationButton.setMessage(exitAnimationLabel());
        variantButton.setMessage(variantLabel());
        gradientButton.setMessage(gradientLabel());
        sectionsButton.setMessage(sectionsLabel());
        beatReactiveButton.setMessage(beatReactiveLabel());
        flashBeatButton.setMessage(flashBeatLabel());
        tvSweepButton.setMessage(tvSweepLabel());
        cinematicAnimationButton.setMessage(cinematicAnimationLabel());
        fishEyeRemoveBlackEdgesButton.setMessage(fishEyeRemoveBlackEdgesLabel());
        overlayTextButton.setMessage(overlayTextLabel());
        overlayInterfaceButton.setMessage(overlayInterfaceLabel());
        overlayPlayersButton.setMessage(overlayPlayersLabel());
        overlayEntitiesButton.setMessage(overlayEntitiesLabel());
        highlightSpectatorsButton.setMessage(highlightSpectatorsLabel());
        highlightEntitiesButton.setMessage(highlightEntitiesLabel());
        highlightModelGlowButton.setMessage(highlightModelGlowLabel());
        hideParticlesButton.setMessage(hideParticlesLabel());
        hideFirstPersonHandButton.setMessage(hideHandLabel());
        hideHudButton.setMessage(hideHudLabel());
        hideBlockyMusicHudButton.setMessage(hideBlockyMusicHudLabel());
        visualizerPositionButton.setMessage(visualizerPositionLabel());
        starDirectionButton.setMessage(starDirectionLabel());
        updateFadeControls();
    }

    private void resetSettings() {
        mutate(() -> {
            TrackEffect reset = original == null ? ClientEffectToolSettings.reset(type) : ClientEffectToolSettings.defaults(type);
            if (original == null) {
                reset = reset.withLayer(lane);
                placementMode = "point";
            } else {
                reset = reset.withLayer(original.layer()).withTimeline(original.lane(), original.tick(), original.durationTicks());
            }
            applyEffect(reset);
            error = "";
            previewStartedNanos = System.nanoTime();
        });
    }

    private void mutate(Runnable change) {
        pushSettingsUndo();
        error = "";
        notice = "";
        change.run();
    }

    private void pushSettingsUndo() {
        if (startBox == null) return;
        Draft draft = captureDraft();
        if (settingsUndo.isEmpty() || !settingsUndo.peekLast().equals(draft)) {
            settingsUndo.addLast(draft);
            while (settingsUndo.size() > 80) settingsUndo.removeFirst();
        }
        settingsRedo.clear();
    }

    private void undoSettings() {
        if (settingsUndo.isEmpty()) return;
        settingsRedo.addLast(captureDraft());
        applyDraft(settingsUndo.removeLast());
    }

    private void redoSettings() {
        if (settingsRedo.isEmpty()) return;
        settingsUndo.addLast(captureDraft());
        applyDraft(settingsRedo.removeLast());
    }

    private Draft captureDraft() {
        return new Draft(type, lane, fontId, animation, exitAnimation, variant, placementMode, backgroundIndex, beatReactive,
                tvSweepEnabled, cinematicAnimated, fishEyeRemoveBlackEdges, overlayText, overlayInterface,
                overlayPlayers, overlayEntities, highlightSpectators, highlightEntities, highlightModelGlow, hideParticles, hideFirstPersonHand, hideHud, hideBlockyMusicHud,
                fadeInEnabled, fadeOutEnabled, visualizerPosition, starDirection,
                startBox.getValue(), durationBox.getValue(), intensityBox.getValue(), softnessBox.getValue(),
                colorAButton.color(), colorBButton.color(), textBox.getValue(), positionXBox.getValue(),
                positionYBox.getValue(), opacityBox.getValue(), textSpeedBox.getValue(),
                outlineThicknessBox.getValue(), glowStrengthBox.getValue(),
                tvScanlineSpacingBox.getValue(), tvScanlineOpacityBox.getValue(), tvSweepWidthBox.getValue(),
                tvSweepOpacityBox.getValue(), tvSweepSpeedBox.getValue(), tvNoiseAmountBox.getValue(),
                tvNoiseOpacityBox.getValue(), tvFlickerAmountBox.getValue(),
                vignetteRadiusBox.getValue(), vignetteSoftnessBox.getValue(), vignetteRoundnessBox.getValue(),
                fishEyeStrengthBox.getValue(), fishEyeRadiusBox.getValue(), fishEyeZoomBox.getValue(),
                fishEyeChromaticBox.getValue(),
                fishEyeEdgeDarkeningBox.getValue(), cinematicTopHeightBox.getValue(), cinematicBottomHeightBox.getValue(),
                cinematicFeatherBox.getValue(), cinematicAnimationSpeedBox.getValue(), cinematicCenterOffsetBox.getValue(),
                cinematicShadowBox.getValue(), fadeInTicksBox.getValue(), fadeOutTicksBox.getValue(),
                visualizerOffsetBox.getValue(), colorBrightnessBox.getValue(), colorContrastBox.getValue(),
                colorSaturationBox.getValue(), colorHueBox.getValue(), colorGammaBox.getValue(),
                colorTemperatureBox.getValue(), highlightSpectatorColorButton.color(), highlightEntityColorButton.color(),
                starSizeBox.getValue(), starDensityBox.getValue(), starTwinkleSpeedBox.getValue(),
                starDriftSpeedBox.getValue(), starSpreadBox.getValue(), starBrightnessBox.getValue(),
                starIridescenceBox.getValue(), List.copyOf(objectOutlineColors),
                objectOutlineColorSpeedBox.getValue(), selectedObjectOutlineColor);
    }

    private void applyDraft(Draft draft) {
        type = draft.type();
        lane = draft.layer();
        fontId = draft.fontId();
        animation = draft.animation();
        exitAnimation = draft.exitAnimation();
        variant = draft.variant();
        placementMode = draft.placementMode();
        backgroundIndex = draft.backgroundIndex();
        beatReactive = draft.beatReactive();
        tvSweepEnabled = draft.tvSweepEnabled();
        cinematicAnimated = draft.cinematicAnimated();
        fishEyeRemoveBlackEdges = draft.fishEyeRemoveBlackEdges();
        overlayText = draft.overlayText();
        overlayInterface = draft.overlayInterface();
        overlayPlayers = draft.overlayPlayers();
        overlayEntities = draft.overlayEntities();
        highlightSpectators = draft.highlightSpectators();
        highlightEntities = draft.highlightEntities();
        highlightModelGlow = draft.highlightModelGlow();
        hideParticles = draft.hideParticles();
        hideFirstPersonHand = draft.hideFirstPersonHand();
        hideHud = draft.hideHud();
        hideBlockyMusicHud = draft.hideBlockyMusicHud();
        fadeInEnabled = draft.fadeInEnabled();
        fadeOutEnabled = draft.fadeOutEnabled();
        visualizerPosition = draft.visualizerPosition();
        starDirection = draft.starDirection();
        startBox.setValue(draft.start());
        durationBox.setValue(draft.duration());
        intensityBox.setValue(draft.intensity());
        softnessBox.setValue(draft.softness());
        colorAButton.setColor(draft.colorA());
        colorBButton.setColor(draft.colorB());
        textBox.setValue(draft.text());
        positionXBox.setValue(draft.positionX());
        positionYBox.setValue(draft.positionY());
        opacityBox.setValue(draft.opacity());
        textSpeedBox.setValue(draft.textSpeed());
        outlineThicknessBox.setValue(draft.outline());
        glowStrengthBox.setValue(draft.glow());
        tvScanlineSpacingBox.setValue(draft.tvScanlineSpacing());
        tvScanlineOpacityBox.setValue(draft.tvScanlineOpacity());
        tvSweepWidthBox.setValue(draft.tvSweepWidth());
        tvSweepOpacityBox.setValue(draft.tvSweepOpacity());
        tvSweepSpeedBox.setValue(draft.tvSweepSpeed());
        tvNoiseAmountBox.setValue(draft.tvNoise());
        tvNoiseOpacityBox.setValue(draft.tvNoiseOpacity());
        tvFlickerAmountBox.setValue(draft.tvFlicker());
        vignetteRadiusBox.setValue(draft.vignetteRadius());
        vignetteSoftnessBox.setValue(draft.vignetteSoftness());
        vignetteRoundnessBox.setValue(draft.vignetteRoundness());
        fishEyeStrengthBox.setValue(draft.fishEyeStrength());
        fishEyeRadiusBox.setValue(draft.fishEyeRadius());
        fishEyeZoomBox.setValue(draft.fishEyeZoom());
        fishEyeChromaticBox.setValue(draft.fishEyeChromatic());
        fishEyeEdgeDarkeningBox.setValue(draft.fishEyeEdgeDarkening());
        cinematicTopHeightBox.setValue(draft.cinematicTopHeight());
        cinematicBottomHeightBox.setValue(draft.cinematicBottomHeight());
        cinematicFeatherBox.setValue(draft.cinematicFeather());
        cinematicAnimationSpeedBox.setValue(draft.cinematicAnimationSpeed());
        cinematicCenterOffsetBox.setValue(draft.cinematicCenterOffset());
        cinematicShadowBox.setValue(draft.cinematicShadow());
        fadeInTicksBox.setValue(draft.fadeInTicks());
        fadeOutTicksBox.setValue(draft.fadeOutTicks());
        visualizerOffsetBox.setValue(draft.visualizerOffset());
        colorBrightnessBox.setValue(draft.colorBrightness());
        colorContrastBox.setValue(draft.colorContrast());
        colorSaturationBox.setValue(draft.colorSaturation());
        colorHueBox.setValue(draft.colorHue());
        colorGammaBox.setValue(draft.colorGamma());
        colorTemperatureBox.setValue(draft.colorTemperature());
        highlightSpectatorColorButton.setColor(draft.highlightSpectatorColor());
        highlightEntityColorButton.setColor(draft.highlightEntityColor());
        starSizeBox.setValue(draft.starSize());
        starDensityBox.setValue(draft.starDensity());
        starTwinkleSpeedBox.setValue(draft.starTwinkleSpeed());
        starDriftSpeedBox.setValue(draft.starDriftSpeed());
        starSpreadBox.setValue(draft.starSpread());
        starBrightnessBox.setValue(draft.starBrightness());
        starIridescenceBox.setValue(draft.starIridescence());
        objectOutlineColors.clear();
        objectOutlineColors.addAll(draft.objectOutlineColors());
        if (objectOutlineColors.isEmpty()) objectOutlineColors.add(draft.colorA());
        objectOutlineColorSpeedBox.setValue(draft.objectOutlineColorSpeed());
        selectedObjectOutlineColor = Mth.clamp(draft.selectedObjectOutlineColor(), 0, objectOutlineColors.size() - 1);
        refreshObjectOutlinePaletteButtons();
        refreshButtonLabels();
        updateVisibility();
    }

    private void save() {
        TrackEffect effect = buildEffect(true);
        if (effect == null) {
            return;
        }
        publishLivePreview(effect);
        livePreviewCommitted = true;
        host.saveEffect(original, effect, placementMode);
        this.minecraft.setScreen(host.parentScreen());
    }

    private void delete() {
        pendingLivePreview = null;
        livePreviewCommitted = true;
        if (original != null) {
            host.deleteEffect(original);
        }
        this.minecraft.setScreen(host.parentScreen());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (effectPickerOpen) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                effectPickerOpen = false;
            }
            return true;
        }
        if (fontPickerOpen) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                fontPickerOpen = false;
            }
            return true;
        }
        if (hasControlDown() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Z) {
            if (hasShiftDown()) redoSettings(); else undoSettings();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
            previewOnly = !previewOnly;
            return true;
        }
        boolean editableKey = this.getFocused() instanceof EditBox
                && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE);
        if (editableKey) pushSettingsUndo();
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.getFocused() instanceof EditBox) pushSettingsUndo();
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (effectPickerOpen) {
            if (button == 0) {
                int index = effectPresetAt(mouseX, mouseY);
                if (index >= 0) {
                    pushSettingsUndo();
                    selectType(TrackEffect.Type.values()[index]);
                    effectPickerOpen = false;
                }
            }
            return true;
        }
        if (fontPickerOpen) {
            if (button == 0) {
                int index = fontPresetAt(mouseX, mouseY);
                if (index >= 0) {
                    pushSettingsUndo();
                    fontId = BattleTextFonts.presets().get(index).id();
                    fontButton.setMessage(fontLabel());
                    fontPickerOpen = false;
                }
            }
            return true;
        }
        if (!previewOnly && button == 0 && type == TrackEffect.Type.OBJECT_OUTLINE) {
            for (int index = 0; index < objectOutlineColors.size(); index++) {
                if (objectOutlineColorButtons.get(index).isMouseOver(mouseX, mouseY)) {
                    selectedObjectOutlineColor = index;
                    refreshObjectOutlinePaletteButtons();
                    relayout();
                    break;
                }
            }
        }
        if (!previewOnly && button == 0) {
            if (mouseX >= panelLeft + panelWidth - 12 && mouseX <= panelLeft + panelWidth
                    && mouseY >= panelTop + panelHeight - 12 && mouseY <= panelTop + panelHeight) {
                resizingWindow = true;
                resizeStartWidth = panelWidth;
                resizeStartHeight = panelHeight;
                windowGrabX = mouseX;
                windowGrabY = mouseY;
                return true;
            }
            if (mouseX >= panelLeft && mouseX <= panelLeft + panelWidth
                    && mouseY >= panelTop && mouseY <= panelTop + 27) {
                draggingWindow = true;
                windowGrabX = mouseX - panelLeft;
                windowGrabY = mouseY - panelTop;
                return true;
            }
        }
        return previewOnly || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingWindow) {
            panelLeft = (int)Math.round(mouseX - windowGrabX);
            panelTop = (int)Math.round(mouseY - windowGrabY);
            relayout();
            return true;
        }
        if (button == 0 && resizingWindow) {
            panelWidth = resizeStartWidth + (int)Math.round(mouseX - windowGrabX);
            panelHeight = resizeStartHeight + (int)Math.round(mouseY - windowGrabY);
            relayout();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && (draggingWindow || resizingWindow)) {
            draggingWindow = false;
            resizingWindow = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void drawEffectPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.flush();
        TrackEffect.Type[] types = TrackEffect.Type.values();
        int columns = effectPickerColumns();
        int rows = (types.length + columns - 1) / columns;
        int cardHeight = effectPickerCardHeight(rows);
        int pickerWidth = Math.min(this.width - 20, columns * 204 + 28);
        int pickerHeight = 50 + rows * (cardHeight + 6) + 22;
        int left = this.width / 2 - pickerWidth / 2;
        int top = Math.max(4, this.height / 2 - pickerHeight / 2);
        int cardWidth = (pickerWidth - 28 - (columns - 1) * 6) / columns;
        graphics.fill(0, 0, this.width, this.height, 0xFF081018);
        graphics.fill(left, top, left + pickerWidth, top + pickerHeight, 0xFA111B25);
        graphics.fill(left, top, left + pickerWidth, top + 3, 0xFFE3A6FF);
        graphics.drawCenteredString(this.font, tr("music_battle.l10n.fx.label_114"),
                this.width / 2, top + 13, 0xFFFFFFFF);
        for (int index = 0; index < types.length; index++) {
            TrackEffect.Type candidate = types[index];
            int column = index % columns;
            int row = index / columns;
            int x = left + 14 + column * (cardWidth + 6);
            int y = top + 36 + row * (cardHeight + 6);
            boolean hovered = mouseX >= x && mouseX < x + cardWidth && mouseY >= y && mouseY < y + cardHeight;
            boolean selected = candidate == type;
            int border = selected ? 0xFFE3A6FF : hovered ? Palette.ACCENT : 0xFF304658;
            graphics.fill(x, y, x + cardWidth, y + cardHeight, border);
            graphics.fill(x + 2, y + 2, x + cardWidth - 2, y + cardHeight - 2,
                    hovered ? 0xFF263947 : 0xFF182631);
            graphics.fill(x + 2, y + 2, x + 7, y + cardHeight - 2, candidate.color());
            graphics.drawString(this.font, effectName(candidate), x + 13, y + 7,
                    selected ? 0xFFFFD7FF : 0xFFFFFFFF, false);
            String description = this.font.plainSubstrByWidth(effectDescription(candidate), cardWidth - 22);
            graphics.drawString(this.font, description, x + 13, y + 25, Palette.TEXT_3, false);
        }
    }

    private int effectPresetAt(double mouseX, double mouseY) {
        TrackEffect.Type[] types = TrackEffect.Type.values();
        int columns = effectPickerColumns();
        int rows = (types.length + columns - 1) / columns;
        int cardHeight = effectPickerCardHeight(rows);
        int pickerWidth = Math.min(this.width - 20, columns * 204 + 28);
        int pickerHeight = 50 + rows * (cardHeight + 6) + 22;
        int left = this.width / 2 - pickerWidth / 2;
        int top = Math.max(4, this.height / 2 - pickerHeight / 2);
        int cardWidth = (pickerWidth - 28 - (columns - 1) * 6) / columns;
        for (int index = 0; index < types.length; index++) {
            int x = left + 14 + (index % columns) * (cardWidth + 6);
            int y = top + 36 + (index / columns) * (cardHeight + 6);
            if (mouseX >= x && mouseX < x + cardWidth && mouseY >= y && mouseY < y + cardHeight) {
                return index;
            }
        }
        return -1;
    }

    private int effectPickerColumns() {
        if (this.width >= 820) return 4;
        return this.width >= 520 ? 3 : 2;
    }

    private int effectPickerCardHeight(int rows) {
        int availablePerRow = Math.max(38, (this.height - 78) / Math.max(1, rows) - 6);
        return Mth.clamp(availablePerRow, 38, 52);
    }

    private static String effectDescription(TrackEffect.Type candidate) {
        return Component.translatable(candidate.translationKey() + ".description").getString();
    }

    private void drawFontPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.flush();
        List<BattleTextFonts.FontPreset> presets = BattleTextFonts.presets();
        int columns = this.width >= 600 ? 3 : 2;
        int panelWidth = Math.min(this.width - 20, columns * 204 + 28);
        int rows = (presets.size() + columns - 1) / columns;
        int cardHeight = 46;
        int panelHeight = 50 + rows * (cardHeight + 6) + 22;
        int left = this.width / 2 - panelWidth / 2;
        int top = Math.max(4, this.height / 2 - panelHeight / 2);
        int cardWidth = (panelWidth - 28 - (columns - 1) * 6) / columns;
        graphics.fill(0, 0, this.width, this.height, 0xFF081018);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFA111B25);
        graphics.fill(left, top, left + panelWidth, top + 3, 0xFFE3A6FF);
        graphics.drawCenteredString(this.font, tr("music_battle.l10n.fx.label_115"), this.width / 2, top + 13, 0xFFFFFFFF);
        String sample = textBox == null || textBox.getValue().isBlank() ? "Blocky Music 123" : textBox.getValue();
        for (int index = 0; index < presets.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            int x = left + 14 + column * (cardWidth + 6);
            int y = top + 36 + row * (cardHeight + 6);
            boolean hovered = mouseX >= x && mouseX < x + cardWidth && mouseY >= y && mouseY < y + cardHeight;
            boolean selected = presets.get(index).id().equals(fontId);
            int border = selected ? 0xFFE3A6FF : hovered ? Palette.ACCENT : 0xFF304658;
            graphics.fill(x, y, x + cardWidth, y + cardHeight, border);
            graphics.fill(x + 2, y + 2, x + cardWidth - 2, y + cardHeight - 2, hovered ? 0xFF263947 : 0xFF182631);
            graphics.drawString(this.font, presets.get(index).displayName(), x + 6, y + 5,
                    selected ? 0xFFFFD7FF : Palette.TEXT_2, false);
            String fitted = this.font.plainSubstrByWidth(sample,
                    Math.max(20, Math.round((cardWidth - 12) / presets.get(index).widthScale())));
            graphics.enableScissor(x + 3, y + 20, x + cardWidth - 3, y + cardHeight - 3);
            BattleTextFonts.drawCentered(graphics, this.font, fitted, presets.get(index).id(),
                    x + cardWidth / 2.0F, y + 25.0F, 0xFFFFFFFF, 0xFF000000, 0.0F);
            graphics.disableScissor();
        }
    }

    private int fontPresetAt(double mouseX, double mouseY) {
        int columns = this.width >= 600 ? 3 : 2;
        int panelWidth = Math.min(this.width - 20, columns * 204 + 28);
        int rows = (BattleTextFonts.presets().size() + columns - 1) / columns;
        int cardHeight = 46;
        int panelHeight = 50 + rows * (cardHeight + 6) + 22;
        int left = this.width / 2 - panelWidth / 2;
        int top = Math.max(4, this.height / 2 - panelHeight / 2);
        int cardWidth = (panelWidth - 28 - (columns - 1) * 6) / columns;
        for (int index = 0; index < BattleTextFonts.presets().size(); index++) {
            int x = left + 14 + (index % columns) * (cardWidth + 6);
            int y = top + 36 + (index / columns) * (cardHeight + 6);
            if (mouseX >= x && mouseX < x + cardWidth && mouseY >= y && mouseY < y + cardHeight) {
                return index;
            }
        }
        return -1;
    }

    private Component typeLabel() { return Component.translatable("music_battle.l10n.fx.effect_value", Component.translatable(type.translationKey())); }

    private static String effectName(TrackEffect.Type type) {
        return I18n.get(type.translationKey());
    }
    private Component layerLabel() {
        int firstRow = lane * TrackEffect.FX_ROWS_PER_LAYER + 1;
        return Component.translatable("music_battle.l10n.fx.rows_value", firstRow, firstRow + TrackEffect.FX_ROWS_PER_LAYER - 1);
    }
    private Component placementLabel() { return Component.translatable("music_battle.l10n.fx.placement_value", Component.translatable("music_battle.l10n.fx.placement." + placementMode)); }
    private Component backgroundLabel() { return Component.translatable("music_battle.l10n.fx.background_value", Component.translatable("music_battle.l10n.fx.background." + BACKGROUNDS[backgroundIndex])); }
    private Component fontLabel() { return Component.translatable("music_battle.l10n.fx.font_value", BattleTextFonts.resolve(fontId).displayName()); }
    private Component animationLabel() { return Component.translatable("music_battle.l10n.fx.entrance_value", animationName(animation)); }
    private Component exitAnimationLabel() { return Component.translatable("music_battle.l10n.fx.exit_value", animationName(exitAnimation)); }
    private Component variantLabel() {
        if (type == TrackEffect.Type.VISUALIZER) return checkboxLabel(visualizerFullWidth(variant), tr("music_battle.l10n.fx.label_120"));
        return Component.translatable("music_battle.l10n.fx.mode_value", Component.translatable("music_battle.l10n.fx.highlight." + variant));
    }
    private Component gradientLabel() { return Component.translatable(visualizerVertical(variant) ? "music_battle.l10n.fx.label_121" : "music_battle.l10n.fx.label_122"); }
    private Component sectionsLabel() { return Component.translatable("music_battle.l10n.fx.bars_value", visualizerSections(variant)); }
    private Component visualizerPositionLabel() { return Component.translatable("music_battle.l10n.fx.position_value", visualizerPositionName(visualizerPosition)); }
    private Component starDirectionLabel() {
        return Component.translatable("effect.music_battle.stars.direction.value",
                Component.translatable("effect.music_battle.stars.direction." + starDirection));
    }
    private Component beatReactiveLabel() {
        return checkboxLabel(beatReactive, type == TrackEffect.Type.OBJECT_OUTLINE
                ? tr("music_battle.l10n.fx.label_125") : tr("music_battle.l10n.fx.label_126"));
    }
    private boolean flashFollowsBeat() { return !"steady".equalsIgnoreCase(variant); }
    private Component flashBeatLabel() { return checkboxLabel(flashFollowsBeat(), tr("music_battle.l10n.fx.label_125")); }
    private Component tvSweepLabel() { return checkboxLabel(tvSweepEnabled, tr("music_battle.l10n.fx.label_061")); }
    private Component cinematicAnimationLabel() { return checkboxLabel(cinematicAnimated, tr("music_battle.l10n.fx.label_127")); }
    private Component fishEyeRemoveBlackEdgesLabel() { return checkboxLabel(fishEyeRemoveBlackEdges, tr("music_battle.l10n.fx.label_128")); }
    private Component overlayTextLabel() { return checkboxLabel(overlayText, tr("music_battle.l10n.fx.label_129")); }
    private Component overlayInterfaceLabel() { return checkboxLabel(overlayInterface, tr("music_battle.l10n.fx.label_130")); }
    private Component overlayPlayersLabel() { return checkboxLabel(overlayPlayers, tr("music_battle.l10n.fx.label_131")); }
    private Component overlayEntitiesLabel() { return checkboxLabel(overlayEntities, tr("music_battle.l10n.fx.label_132")); }
    private Component highlightSpectatorsLabel() { return checkboxLabel(highlightSpectators, tr("music_battle.l10n.fx.label_133")); }
    private Component highlightEntitiesLabel() { return checkboxLabel(highlightEntities, tr("music_battle.l10n.fx.label_134")); }
    private Component hideParticlesLabel() { return checkboxLabel(hideParticles, tr("music_battle.fx.hide_particles")); }
    private Component hideHandLabel() { return checkboxLabel(hideFirstPersonHand, tr("music_battle.fx.hide_hand")); }
    private Component hideHudLabel() { return checkboxLabel(hideHud, tr("music_battle.fx.hide_hud")); }
    private Component hideBlockyMusicHudLabel() { return checkboxLabel(hideBlockyMusicHud, tr("music_battle.fx.hide_blocky_music_hud")); }

    private Component highlightModelGlowLabel() { return checkboxLabel(highlightModelGlow, tr("music_battle.l10n.fx.label_135")); }
    private Component fadeInLabel() { return checkboxLabel(fadeInEnabled, tr("music_battle.l10n.fx.label_027")); }
    private Component fadeOutLabel() { return checkboxLabel(fadeOutEnabled, tr("music_battle.l10n.fx.label_029")); }

    private static Component checkboxLabel(boolean checked, String text) {
        return Component.literal((checked ? "☑ " : "☐ ") + text);
    }

    private void updateFadeControls() {
        if (fadeInButton != null) fadeInButton.setMessage(fadeInLabel());
        if (fadeOutButton != null) fadeOutButton.setMessage(fadeOutLabel());
        if (fadeInTicksBox != null) fadeInTicksBox.active = fadeInEnabled;
        if (fadeOutTicksBox != null) fadeOutTicksBox.active = fadeOutEnabled;
    }

    private String intensityLabel() {
        return switch (type) {
            case FLASH -> tr("music_battle.l10n.fx.label_136");
            case OLD_TV -> tr("music_battle.l10n.fx.label_137");
            case FISH_EYE -> tr("music_battle.l10n.fx.label_138");
            case CINEMATIC_BARS -> tr("music_battle.l10n.fx.label_139");
            case SHAKE -> tr("music_battle.l10n.fx.label_140");
            case PULSE -> tr("music_battle.l10n.fx.label_141");
            case VISUALIZER -> tr("music_battle.l10n.fx.label_142");
            case TEXT -> tr("music_battle.l10n.fx.label_143");
            case PIXEL_FIRE -> tr("music_battle.l10n.fx.label_144");
            case STARS -> tr("effect.music_battle.stars.setting.intensity");
            case COLOR_CORRECTION -> tr("music_battle.l10n.fx.label_145");
            case VIGNETTE -> tr("music_battle.l10n.fx.label_146");
            case PLAYER_HIGHLIGHT -> "";
            case OBJECT_OUTLINE -> tr("music_battle.l10n.fx.label_148");
            case HIDE_BATTLE_PANELS, HIDE_PARTICLES_HAND -> "";
        };
    }

    private static float parseFloat(EditBox box) { return Float.parseFloat(box.getValue().trim().replace(',', '.')); }

    private static String tr(String key, Object... args) {
        return I18n.get(key, args);
    }

    private static String colorText(int color) { return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF); }
    private static String format(float value) { return String.format(Locale.ROOT, "%.2f", value); }

    private static String next(String[] values, String current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    private static String animationName(String value) {
        return switch (value == null ? "none" : value) {
            case "letters" -> tr("music_battle.l10n.fx.label_149");
            case "words" -> tr("music_battle.l10n.fx.label_150");
            case "fade" -> tr("music_battle.l10n.fx.label_151");
            case "slide_left" -> tr("music_battle.l10n.fx.label_152");
            case "slide_right" -> tr("music_battle.l10n.fx.label_153");
            case "slide_up" -> tr("music_battle.l10n.fx.label_154");
            case "slide_down" -> tr("music_battle.l10n.fx.label_155");
            case "zoom" -> tr("music_battle.l10n.fx.label_156");
            case "shrink" -> tr("music_battle.l10n.fx.label_157");
            case "pop" -> tr("music_battle.l10n.fx.label_158");
            case "spin" -> tr("music_battle.l10n.fx.label_159");
            default -> tr("music_battle.l10n.fx.label_160");
        };
    }

    private static String normalize(String value, String[] values, String fallback) {
        for (String candidate : values) {
            if (candidate.equals(value)) {
                return candidate;
            }
        }
        return fallback;
    }

    private static boolean visualizerFullWidth(String value) {
        return value != null && (value.equals("full_width") || value.startsWith("full_"));
    }

    private static boolean visualizerVertical(String value) {
        return value != null && value.contains("_v_");
    }

    private static int visualizerSections(String value) {
        if (value != null) {
            int split = value.lastIndexOf('_');
            if (split >= 0 && split + 1 < value.length()) {
                try {
                    return Mth.clamp(Integer.parseInt(value.substring(split + 1)), 8, 64);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 24;
    }

    private static String visualizerVariant(boolean fullWidth, boolean vertical, int sections) {
        return (fullWidth ? "full" : "compact") + (vertical ? "_v_" : "_h_") + Mth.clamp(sections, 8, 64);
    }

    private static String visualizerPositionName(String value) {
        return switch (value == null ? "bottom" : value) {
            case "top" -> tr("music_battle.l10n.fx.edge.top");
            case "left" -> tr("music_battle.l10n.fx.edge.left");
            case "right" -> tr("music_battle.l10n.fx.edge.right");
            default -> tr("music_battle.l10n.fx.edge.bottom");
        };
    }

    /** A row cell: one control, plus the optional numeric box that shares the cell with it. */
    private record LabeledControl(AbstractWidget widget, String label, AbstractWidget companion) {
        private LabeledControl(AbstractWidget widget, String label) {
            this(widget, label, null);
        }
    }

    /** Slider bound to one numeric box: dragging writes the box, typing moves the slider. */
    private final class FxSlider extends AbstractSliderButton {
        private final EditBox box;
        private final FxRange range;
        private final boolean wholeText;

        private FxSlider(EditBox box, FxRange range, boolean wholeText) {
            super(0, 0, 100, 20, Component.empty(),
                    range.toFraction(parseOr(box, range.fallback())));
            this.box = box;
            this.range = range;
            this.wholeText = wholeText;
            updateMessage();
        }

        /** Follows the box while the user types; an incomplete number leaves the handle alone. */
        private void syncFromBox() {
            float parsed = parseOr(box, Float.NaN);
            if (!Float.isFinite(parsed)) return;
            value = range.toFraction(parsed);
            updateMessage();
        }

        private String text() {
            float current = range.fromFraction(value);
            return wholeText ? Integer.toString(Math.round(current)) : format(current);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(text()));
        }

        @Override
        protected void applyValue() {
            String written = text();
            if (box.getValue().equals(written)) return;
            syncingBoxes = true;
            try {
                box.setValue(written);
            } finally {
                syncingBoxes = false;
            }
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            // A slider scrolled out of the viewport must not keep taking arrow keys.
            return visible && active && super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int width = getWidth();
            int height = getHeight();
            graphics.fill(x, y, x + width, y + height, active ? Palette.BG_1 : 0xFF121A20);
            graphics.fill(x, y, x + width, y + 1, active ? Palette.ACCENT_STRONG : 0xFF2A3A44);
            graphics.fill(x + 5, y + height / 2 - 2, x + width - 5, y + height / 2 + 2, 0xFF0B1218);
            int fill = x + 5 + Math.round((width - 10) * (float) value);
            graphics.fill(x + 5, y + height / 2 - 2, fill, y + height / 2 + 2,
                    active ? Palette.ACCENT : 0xFF3C6B7C);
            graphics.fill(fill - 2, y + 2, fill + 2, y + height - 2, active ? Palette.TEXT_1 : 0xFF8AA0AC);
        }
    }

    private record Draft(TrackEffect.Type type, int layer, String fontId, String animation, String exitAnimation, String variant,
                         String placementMode, int backgroundIndex, boolean beatReactive, boolean tvSweepEnabled,
                          boolean cinematicAnimated, boolean fishEyeRemoveBlackEdges, boolean overlayText, boolean overlayInterface,
                           boolean overlayPlayers, boolean overlayEntities, boolean highlightSpectators,
                           boolean highlightEntities, boolean highlightModelGlow, boolean hideParticles, boolean hideFirstPersonHand, boolean hideHud, boolean hideBlockyMusicHud,
                          boolean fadeInEnabled, boolean fadeOutEnabled,
                         String visualizerPosition, String starDirection, String start,
                          String duration, String intensity, String softness, int colorA, int colorB,
                         String text, String positionX, String positionY, String opacity, String textSpeed,
                         String outline, String glow, String tvScanlineSpacing, String tvScanlineOpacity,
                         String tvSweepWidth, String tvSweepOpacity, String tvSweepSpeed, String tvNoise,
                         String tvNoiseOpacity, String tvFlicker,
                         String vignetteRadius, String vignetteSoftness, String vignetteRoundness,
                         String fishEyeStrength, String fishEyeRadius, String fishEyeZoom, String fishEyeChromatic,
                         String fishEyeEdgeDarkening, String cinematicTopHeight, String cinematicBottomHeight,
                         String cinematicFeather, String cinematicAnimationSpeed, String cinematicCenterOffset,
                         String cinematicShadow, String fadeInTicks, String fadeOutTicks, String visualizerOffset,
                         String colorBrightness, String colorContrast, String colorSaturation, String colorHue,
                          String colorGamma, String colorTemperature, int highlightSpectatorColor,
                          int highlightEntityColor, String starSize, String starDensity,
                          String starTwinkleSpeed, String starDriftSpeed, String starSpread,
                          String starBrightness, String starIridescence, List<Integer> objectOutlineColors,
                          String objectOutlineColorSpeed, int selectedObjectOutlineColor) {
    }

    @Override
    public void onClose() {
        cancelLivePreview();
        if (original == null && host.persistTemplateOnClose()) {
            TrackEffect draft = buildEffect(false);
            if (draft != null) ClientEffectToolSettings.savePreset(draft, placementMode);
        }
        this.minecraft.setScreen(host.parentScreen());
    }

    @Override
    public DjPanelScreen owningDjPanel() {
        Screen parent = host.parentScreen();
        if (parent instanceof DjPanelScreen panel) {
            return panel;
        }
        return parent instanceof DjPanelOwnedScreen nested ? nested.owningDjPanel() : null;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
