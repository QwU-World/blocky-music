package ru.gora.musicbattle.client;

import java.util.ArrayDeque;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.ui.UiTooltip;
import net.minecraft.client.Minecraft;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
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

/**
 * One inspector for both the placement template and a concrete timeline effect.
 *
 * <p>Drawn with the toolkit like the DJ screens around it: a raised rounded panel in the page colours with the effect's
 * colour as a dot beside its heading, every label in Nunito, the toolkit's sliders, switches with the Workshop's check
 * box, its scrollbar and its pickers' cards. It was a flat slab of the first look's blue-grey with a strip of the
 * effect's colour on top, the bitmap font and square teal sliders. Every control is where it always was, and the panel
 * still moves by its heading and resizes from its corner.
 */
public final class TrackEffectEditScreen extends Screen implements DjPanelOwnedScreen {
    /**
     * The wash over the preview behind the panel. It stays this light on purpose: an effect is judged against the world
     * (or the broadcast camera) behind it, so the world must stay in sight; it only takes the glare off so the panel
     * reads. The page colour at the alpha the literal wash always had, 48 of 255.
     */
    static final int WASH = Palette.withAlpha(Palette.BG_0, 48 / 255.0F);
    /** The "dark" preview background: the page colour, the darkest the toolkit has. */
    static final int DARK_BACKGROUND = Palette.BG_0;
    /** Behind a picker of effects or fonts, which hides the preview while it is open, as it always did. */
    static final int PICKER_BACKDROP = Palette.withAlpha(Palette.BG_0, 0.94F);
    private static final int LIVE_PREVIEW_DEBOUNCE_TICKS = 1;
    private static final String[] TEXT_ANIMATIONS = {"none", "letters", "words", "fade", "slide_left", "slide_right", "slide_up", "slide_down", "zoom", "pop", "spin"};
    private static final String[] TEXT_EXIT_ANIMATIONS = {"none", "fade", "slide_left", "slide_right", "slide_up", "slide_down", "zoom", "shrink", "spin"};
    private static final String[] HIGHLIGHT_MODES = {"both", "p1", "p2", "alternate", "pulse"};
    private static final String[] VISUALIZER_POSITIONS = {"bottom", "top", "left", "right"};
    /** The visualizer's bar counts, in the order its button steps through them. */
    private static final int[] VISUALIZER_SECTIONS = {8, 12, 16, 24, 32, 48, 64};
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
    /** How far the open picker's cards are scrolled; each picker opens at its top. */
    private int pickerScroll;
    private final List<AbstractWidget> contentWidgets = new ArrayList<>();
    /** Every numeric box keeps a slider beside it; the box stays the way to type an exact value. */
    private final Map<EditBox, FxSlider> sliders = new IdentityHashMap<>();
    private boolean syncingBoxes;
    private final List<AbstractWidget> actionWidgets = new ArrayList<>();
    /** The labels of the controls in the viewport, with the lines the layout set them in. */
    private final List<Shown> visibleLabels = new ArrayList<>();
    /** The font metrics the rows are laid out with; see {@link #measurer()}. */
    private ru.gora.musicbattle.client.ui.TextMeasurer measurer;
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
        this.backgroundIndex = startingBackground();
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

            // The chart's own tempo and beat 1, so the preview pulses on the beats the effect will meet in the song;
            // it used to beat at the default 120 BPM from 0 s whatever the chart said.
            @Override
            public int previewBpm() {
                return parent.chartBpm();
            }

            @Override
            public int previewBeatOffsetMs() {
                return parent.chartOffsetMs();
            }
        };
    }

    @Override
    protected void init() {
        Draft retainedDraft = startBox == null ? null : captureDraft();
        contentWidgets.clear();
        sliders.clear();
        actionWidgets.clear();
        visibleLabels.clear();
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
        variantButton = addToggle(variantLabel(), x0 + (third + gap) * 2, row5, third,
                () -> type == TrackEffect.Type.VISUALIZER ? visualizerFullWidth(variant) : null, () -> mutate(this::cycleVariant));

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

        beatReactiveButton = addToggle(beatReactiveLabel(), x0, top + 302, inner, () -> beatReactive, () -> mutate(this::toggleBeatReactive));
        flashBeatButton = addToggle(flashBeatLabel(), x0, top + 302, inner, this::flashFollowsBeat, () -> mutate(this::toggleFlashBeat));
        tvSweepButton = addToggle(tvSweepLabel(), x0, top + 302, inner, () -> tvSweepEnabled, () -> mutate(this::toggleTvSweep));
        cinematicAnimationButton = addToggle(cinematicAnimationLabel(), x0, top + 302, inner, () -> cinematicAnimated,
                () -> mutate(this::toggleCinematicAnimation));
        fishEyeRemoveBlackEdgesButton = addToggle(fishEyeRemoveBlackEdgesLabel(), x0, top + 302, inner, () -> fishEyeRemoveBlackEdges,
                () -> mutate(this::toggleFishEyeRemoveBlackEdges));
        overlayTextButton = addToggle(overlayTextLabel(), x0, top + 302, inner, () -> overlayText, () -> mutate(this::toggleOverlayText));
        overlayInterfaceButton = addToggle(overlayInterfaceLabel(), x0, top + 302, inner, () -> overlayInterface,
                () -> mutate(this::toggleOverlayInterface));
        overlayPlayersButton = addToggle(overlayPlayersLabel(), x0, top + 302, inner, () -> overlayPlayers,
                () -> mutate(this::toggleOverlayPlayers));
        overlayEntitiesButton = addToggle(overlayEntitiesLabel(), x0, top + 302, inner, () -> overlayEntities,
                () -> mutate(this::toggleOverlayEntities));
        highlightSpectatorsButton = addToggle(highlightSpectatorsLabel(), x0, top + 302, inner, () -> highlightSpectators,
                () -> mutate(this::toggleHighlightSpectators));
        highlightEntitiesButton = addToggle(highlightEntitiesLabel(), x0, top + 302, inner, () -> highlightEntities,
                () -> mutate(this::toggleHighlightEntities));
        highlightModelGlowButton = addToggle(highlightModelGlowLabel(), x0, top + 302, inner, () -> highlightModelGlow,
                () -> mutate(this::toggleHighlightModelGlow));
        hideParticlesButton = addToggle(hideParticlesLabel(), x0, top + 302, inner, () -> hideParticles,
                () -> mutate(() -> { hideParticles = !hideParticles; hideParticlesButton.setMessage(hideParticlesLabel()); }));
        hideFirstPersonHandButton = addToggle(hideHandLabel(), x0, top + 302, inner, () -> hideFirstPersonHand,
                () -> mutate(() -> { hideFirstPersonHand = !hideFirstPersonHand; hideFirstPersonHandButton.setMessage(hideHandLabel()); }));
        hideHudButton = addToggle(hideHudLabel(), x0, top + 302, inner, () -> hideHud,
                () -> mutate(() -> { hideHud = !hideHud; hideHudButton.setMessage(hideHudLabel()); }));
        hideBlockyMusicHudButton = addToggle(hideBlockyMusicHudLabel(), x0, top + 302, inner, () -> hideBlockyMusicHud,
                () -> mutate(() -> { hideBlockyMusicHud = !hideBlockyMusicHud; hideBlockyMusicHudButton.setMessage(hideBlockyMusicHudLabel()); }));
        fadeInButton = addToggle(fadeInLabel(), x0, top + 302, inner, () -> fadeInEnabled, () -> mutate(this::toggleFadeIn));
        fadeOutButton = addToggle(fadeOutLabel(), x0, top + 302, inner, () -> fadeOutEnabled, () -> mutate(this::toggleFadeOut));
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
        Button button = this.addRenderableWidget(new InspectorButton(label, ignored -> action.run(), x, y, width, 20, null));
        contentWidgets.add(button);
        return button;
    }

    /** A switch: its words, and a check box that reads {@code state} as it draws (null for a plain button). */
    private Button addToggle(Component label, int x, int y, int width, java.util.function.Supplier<Boolean> state,
                             Runnable action) {
        Button button = this.addRenderableWidget(new InspectorButton(label, ignored -> action.run(), x, y, width, 20, state));
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
        Button button = this.addRenderableWidget(new InspectorButton(label, ignored -> action.run(), x, y, 80, 22, null));
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

    /** What {@link #tip} was asked to show this frame, and over what; drawn and cleared at the end of render. */
    private Component pendingTip;
    private Rect pendingTipOver;

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
        float beatOrigin = (float) ru.gora.musicbattle.data.ChartBeat.originTicks(
                ru.gora.musicbattle.data.ChartBeat.effectiveOffsetMs(host.previewBeatOffsetMs(), previewSpeed));
        ClientTrackEffects.EffectState state = ClientTrackEffects.sample(List.of(), -1.0F,
                effectivePreviewBpm, previewSpeed, 1.0F, beatOrigin);
        if (preview != null) {
            float elapsed = Math.max(0L, System.nanoTime() - previewStartedNanos) / 50_000_000.0F;
            int loop = Math.max(24, preview.visibleDurationTicks() + 10);
            tick = preview.tick() + elapsed % loop;
            state = ClientTrackEffects.sample(List.of(preview), tick, effectivePreviewBpm, previewSpeed, 1.0F, beatOrigin);
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
        // Whatever was asked to show whole this frame, in the toolkit's card over everything, kept off what it explains.
        Component shownTip = pendingTip;
        Rect shownOver = pendingTipOver;
        pendingTip = null;
        pendingTipOver = null;
        if (shownTip != null) {
            // Above the inspector's layer: in it, a number box's shadowed value (drawn 0.03 forward) printed through it.
            ClientUiForeground.renderTip(graphics.pose(), graphics::flush, () -> {
                UiTooltip.draw(graphics, shownTip, mouseX, mouseY, shownOver, this.width, this.height);
                graphics.flush();
            });
        }
    }

    /**
     * Shows {@code text} whole under the pointer this frame: a control's name the layout had to cut, or a picker card's
     * name and description. These used to go to vanilla's tooltip, the purple frame in the bitmap font, in exactly the
     * languages the dj-dialogs check never measures (a third-party language pack with longer words). Now they are the
     * toolkit's card ({@link UiTooltip}), kept clear of {@code over}, the control the pointer rests on.
     */
    private void tip(Component text, Rect over) {
        pendingTip = text;
        pendingTipOver = over;
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

        // The toolkit's dialog panel, opaque as the old one was: the controls read over any effect and any world.
        RoundedRect.shadow(graphics, panelLeft, panelTop + 2.0F, panelWidth, panelHeight, Palette.RADIUS_LG, 12.0F,
                Palette.SHADOW_LIFTED);
        RoundedRect.fill(graphics, panelLeft, panelTop, panelWidth, panelHeight, Palette.RADIUS_LG, Palette.BG_1);
        RoundedRect.stroke(graphics, panelLeft, panelTop, panelWidth, panelHeight, Palette.RADIUS_LG, 1.0F, Palette.LINE);
        drawHeading(graphics, mouseX, mouseY);
        UiClip.push(graphics, panelLeft + 7, contentTop, panelLeft + panelWidth - 12, contentBottom);
        for (Shown shown : visibleLabels) {
            label(graphics, shown, mouseX, mouseY);
        }
        for (AbstractWidget widget : contentWidgets) {
            if (widget.visible) widget.render(graphics, mouseX, mouseY, partialTick);
        }
        UiClip.pop(graphics);
        if (maxScroll > 0) drawScrollbar(graphics);
        int messageY = panelTop + panelHeight - 47;
        String message = !error.isBlank() ? error : notice;
        if (!message.isBlank()) {
            String fitted = TextFit.ellipsize(TextStyles.measurer(), message, TextStyle.CAPTION, panelWidth - 24.0F);
            TextStyles.queueCentered(graphics, TextStyles.text(fitted, TextStyle.CAPTION).getVisualOrderText(),
                    panelLeft + panelWidth / 2.0F, messageY, error.isBlank() ? Palette.TEXT_3 : Palette.DANGER);
        }
        for (AbstractWidget widget : actionWidgets) widget.render(graphics, mouseX, mouseY, partialTick);
        drawGrip(graphics, mouseX, mouseY);
    }

    /**
     * The heading the panel is dragged by: the effect's name with a dot of the effect's colour before it, the colour the
     * timeline gives its blocks, where a strip of it used to run along the top.
     */
    private void drawHeading(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean over = mouseX >= panelLeft && mouseX <= panelLeft + panelWidth && mouseY >= panelTop && mouseY <= panelTop + 27;
        if (over || draggingWindow) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        float room = panelWidth - 48.0F;
        var heading = TextStyles.ellipsize(TextStyles.styled(this.title, TextStyle.TITLE), room);
        float headingWidth = TextStyles.width(heading);
        float dot = 6.0F;
        float left = panelLeft + (panelWidth - headingWidth - dot - 6.0F) / 2.0F;
        float capY = panelTop + 9.0F;
        float dotY = capY + TextStyle.TITLE.capHeight() / 2.0F;
        RoundedRect.circle(graphics, left + dot / 2.0F, dotY, dot / 2.0F, 0xFF000000 | type.color());
        // A faint ring, so an effect whose colour is black or as dark as the panel still shows its dot.
        RoundedRect.stroke(graphics, left, dotY - dot / 2.0F, dot, dot, dot / 2.0F, 1.0F,
                Palette.withAlpha(Palette.TEXT_1, 0.22F));
        TextStyles.queue(graphics, heading, left + dot + 6.0F, capY, Palette.TEXT_1);
        RoundedRect.fill(graphics, panelLeft + 14, panelTop + 25, panelWidth - 28.0F, 1.0F, 0.0F,
                Palette.withAlpha(Palette.LINE, 0.7F));
    }

    /** The resize corner: three short diagonal rules, lit while it is pointed at or dragged. */
    private void drawGrip(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean over = mouseX >= panelLeft + panelWidth - 12 && mouseX <= panelLeft + panelWidth
                && mouseY >= panelTop + panelHeight - 12 && mouseY <= panelTop + panelHeight;
        if (over || resizingWindow) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        int colour = over || resizingWindow ? Palette.ACCENT : Palette.TEXT_MUTE;
        float right = panelLeft + panelWidth - 4.0F;
        float bottom = panelTop + panelHeight - 4.0F;
        for (int step = 0; step < 3; step++) {
            float reach = 3.0F + step * 3.0F;
            for (int dotIndex = 0; dotIndex <= step; dotIndex++) {
                RoundedRect.circle(graphics, right - reach + dotIndex * 3.0F, bottom - dotIndex * 3.0F, 0.9F, colour);
            }
        }
    }

    /**
     * "World" in a world, "Dark" without one: the editor opened from the title screen has no world behind it, and the
     * first background a player sees there should be a test ground, not a stand-in. They can still pick "World" and get
     * the title's panorama.
     */
    private static int startingBackground() {
        Minecraft game = Minecraft.getInstance();
        return startingBackground(game == null || game.level != null);
    }

    /** The background the editor opens on: "World" in a world, "Dark" without one (D04). Pure, for ui-leftovers. */
    static int startingBackground(boolean inWorld) {
        return inWorld ? 0 : java.util.Arrays.asList(BACKGROUNDS).indexOf("dark");
    }

    private boolean drawPreviewBackground(GuiGraphics graphics, float partialTick,
                                          ClientTrackEffects.EffectState effectState) {
        String mode = BACKGROUNDS[backgroundIndex];
        if ("world".equals(mode)) {
            DjPanelScreen owner = owningDjPanel();
            if (owner != null && owner.renderActiveCameraPreview(
                    graphics, 0, 0, this.width, this.height, partialTick, effectState)) {
                graphics.fill(0, 0, this.width, this.height, WASH);
                return true;
            }
            if (this.minecraft == null || this.minecraft.level == null) {
                // Opened from the title screen (library, editor, FX): there is no world to show, and the empty frame
                // under the wash was black. The title's panorama stands in for it, under the same wash.
                renderPanorama(graphics, partialTick);
                graphics.fill(0, 0, this.width, this.height, WASH);
                return false;
            }
            // No camera to show - none linked, a shader in the way, the chunk not loaded: the world around the player
            // is the background, under the camera's light wash. This was an opaque fill for a while, to hide the
            // running effects behind the one being edited; that stack is silent for whoever has an effect editor
            // open (ClientBattleController.authoringDjFx), so the fill only hid the world the effect is judged against.
            graphics.fill(0, 0, this.width, this.height, WASH);
            return false;
        }
        // The other backgrounds are test grounds for an effect, not interface: white, a red, a green and a checkerboard
        // keep the colours that make each one useful; the dark one is the toolkit's page colour.
        int first = "light".equals(mode) ? 0xFFE8EDF1 : "red".equals(mode) ? 0xFF50151D : "green".equals(mode) ? 0xFF143E28
                : DARK_BACKGROUND;
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

    private float beatEnvelope(float tick, int bpm, float playbackSpeed, float origin) {
        float phase = ClientTrackEffects.beatPhase(tick, bpm, playbackSpeed, origin);
        return Mth.clamp(1.0F - phase * 4.0F, 0.0F, 1.0F);
    }

    private void drawHighlightPreview(GuiGraphics graphics, ClientTrackEffects.EffectState state, float tick) {
        TrackEffect effect = state.highlightEffect();
        if (effect == null) {
            return;
        }
        int y = this.height - 108;
        float visibility = ClientTrackEffects.visibility(effect, tick);
        float sinceBeatOne = tick - state.beatOriginTicks();
        boolean left = highlightOwner(effect, sinceBeatOne, state.bpm(), state.playbackSpeed(), 0);
        boolean right = highlightOwner(effect, sinceBeatOne, state.bpm(), state.playbackSpeed(), 1);
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
                ? 0.18F + 0.82F * beatEnvelope(tick, state.bpm(), state.playbackSpeed(), state.beatOriginTicks()) : 1.0F;
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

    /**
     * A control's name over its cell, in the quiet label face of a form (the colour picker's), on the one or two lines the
     * layout set it in, the last of them just above the control. Many names carry their range ("Длительность появления
     * 0–200 тиков (0 = стандарт)"); they used to be cut to a quarter of the panel, with the whole name as a tooltip. The
     * layout now gives every name the room it needs; only a language the dj-dialogs check never measured can leave one
     * cut, and then it still shows whole under the pointer.
     */
    private void label(GuiGraphics graphics, Shown shown, int mouseX, int mouseY) {
        LabeledControl control = shown.control();
        AbstractWidget widget = control.widget();
        AbstractWidget companion = control.companion();
        float cell = companion == null ? widget.getWidth() : companion.getX() + companion.getWidth() - widget.getX();
        float y = widget.getY() - (float) InspectorLayout.LABEL_LINE * shown.lines().size();
        for (String line : shown.lines()) {
            TextStyles.queue(graphics, TextStyles.text(line, TextStyle.CAPTION).getVisualOrderText(), widget.getX(), y,
                    Palette.TEXT_3);
            y += InspectorLayout.LABEL_LINE;
        }
        boolean whole = String.join(" ", shown.lines()).equals(String.join(" ", control.label().trim().split(" +")));
        float top = widget.getY() - InspectorLayout.LABEL_LINE * Math.max(1, shown.lines().size()) - 2;
        boolean over = mouseX >= widget.getX() && mouseX < widget.getX() + cell && mouseY >= top
                && mouseY < widget.getY() + widget.getHeight() && mouseY >= contentTop && mouseY < contentBottom;
        if (over && !whole) {
            tip(Component.literal(control.label()), Rect.of(widget.getX(), top, cell, widget.getY() + widget.getHeight() - top));
        }
    }

    /**
     * The font metrics the rows are laid out with: the game's own font in play. The dj-dialogs check sets Minecraft's
     * glyph metrics here before it builds the screen without a game; the other checks that build it to test its state,
     * not its words, get six units a letter, the bitmap font's advance, so that their layout still runs.
     */
    private ru.gora.musicbattle.client.ui.TextMeasurer measurer() {
        if (measurer == null) {
            measurer = minecraft != null ? TextStyles.measurer()
                    : (text, style) -> text == null ? 0.0F : text.length() * 6.0F;
        }
        return measurer;
    }

    private void relayout() {
        if (contentWidgets.isEmpty()) return;
        List<List<LabeledControl>> rows = controlRows();
        // Never wider than the window: a floor of 360 units pushed the panel's right third off a 320-unit window.
        int maxWidth = Math.max(240, this.width - 10);
        int maxHeight = Math.max(190, this.height - 10);
        panelWidth = Mth.clamp(panelWidth, Math.min(400, maxWidth), maxWidth);
        panelHeight = Mth.clamp(panelHeight, Math.min(210, maxHeight), maxHeight);
        panelLeft = Mth.clamp(panelLeft, 5, Math.max(5, this.width - panelWidth - 5));
        panelTop = Mth.clamp(panelTop, 5, Math.max(5, this.height - panelHeight - 5));
        contentTop = panelTop + 29;
        contentBottom = panelTop + panelHeight - 52;
        int viewportHeight = Math.max(40, contentBottom - contentTop);
        InspectorLayout layout = InspectorLayout.arrange(cells(rows), panelWidth - 34, measurer());
        maxScroll = Math.max(0, layout.height() - viewportHeight);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        contentWidgets.forEach(widget -> widget.visible = false);
        visibleLabels.clear();
        int innerLeft = panelLeft + 14;
        for (InspectorLayout.Placed placed : layout.cells()) {
            LabeledControl control = rows.get(placed.row()).get(placed.column());
            AbstractWidget widget = control.widget();
            int cellX = innerLeft + placed.x();
            int cellWidth = placed.width();
            int widgetY = contentTop + placed.y() - scrollOffset;
            boolean visible = widgetY + InspectorLayout.CONTROL > contentTop && widgetY < contentBottom;
            AbstractWidget companion = control.companion();
            if (companion == null) {
                widget.setRectangle(cellWidth, InspectorLayout.CONTROL, cellX, widgetY);
            } else {
                // Slider first, then a box just wide enough to type an exact value into.
                int boxWidth = InspectorLayout.boxWidth(cellWidth);
                int sliderWidth = cellWidth - boxWidth - InspectorLayout.BOX_GAP;
                widget.setRectangle(sliderWidth, InspectorLayout.CONTROL, cellX, widgetY);
                companion.setRectangle(boxWidth, InspectorLayout.CONTROL, cellX + sliderWidth + InspectorLayout.BOX_GAP,
                        widgetY);
                companion.visible = visible;
            }
            if (widget instanceof InspectorButton button) {
                button.face = placed.face();
            }
            widget.visible = visible;
            if (visible) visibleLabels.add(new Shown(control, placed.label()));
        }
        int count = actionWidgets.size();
        int actionGap = 6;
        int actionWidth = (panelWidth - 28 - actionGap * (count - 1)) / count;
        int actionY = panelTop + panelHeight - 31;
        List<String> actionLabels = new ArrayList<>();
        for (AbstractWidget action : actionWidgets) {
            actionLabels.add(action.getMessage().getString());
        }
        TextStyle actionFace = InspectorLayout.actionFace(actionLabels, actionWidth, measurer());
        for (int index = 0; index < count; index++) {
            AbstractWidget action = actionWidgets.get(index);
            action.setRectangle(actionWidth, 22, panelLeft + 14 + index * (actionWidth + actionGap), actionY);
            action.visible = true;
            if (action instanceof InspectorButton button) {
                button.face = actionFace;
            }
        }
    }

    /** The rows as the layout reads them: every control's label, what kind of control it is and every word it shows. */
    private List<List<InspectorLayout.Cell>> cells(List<List<LabeledControl>> rows) {
        List<List<InspectorLayout.Cell>> cells = new ArrayList<>(rows.size());
        for (List<LabeledControl> row : rows) {
            List<InspectorLayout.Cell> line = new ArrayList<>(row.size());
            for (LabeledControl control : row) {
                line.add(cell(control));
            }
            cells.add(line);
        }
        return cells;
    }

    /** The rows of the inspector as it stands, for the dj-dialogs check, which lays them out at every width. */
    List<List<InspectorLayout.Cell>> inspectorCells() {
        return cells(controlRows());
    }

    private InspectorLayout.Cell cell(LabeledControl control) {
        AbstractWidget widget = control.widget();
        List<String> labels = new ArrayList<>();
        labels.add(control.label());
        labels.addAll(control.alternatives());
        if (control.companion() != null) {
            return new InspectorLayout.Cell(labels, InspectorLayout.Kind.SLIDER, List.of());
        }
        if (widget instanceof InspectorButton button) {
            boolean check = button.state != null && button.state.get() != null;
            return new InspectorLayout.Cell(labels, check ? InspectorLayout.Kind.SWITCH : InspectorLayout.Kind.BUTTON,
                    buttonTexts(button));
        }
        return new InspectorLayout.Cell(labels, widget instanceof ExactColorButton ? InspectorLayout.Kind.COLOR
                : InspectorLayout.Kind.FIELD, List.of());
    }

    /**
     * Every word a button can show: the values a cycling button steps through, or the one label of any other. The layout
     * makes room for the longest, so a row never rearranges itself under the pointer while the DJ clicks through them.
     * The effect's button does not cycle: it opens the picker, and the inspector is laid out anew for the effect chosen,
     * so its current name is all it needs room for.
     */
    private List<String> buttonTexts(Button button) {
        List<String> texts = new ArrayList<>();
        if (button == backgroundButton) {
            for (String background : BACKGROUNDS) {
                texts.add(Component.translatable("music_battle.l10n.fx.background_value",
                        Component.translatable("music_battle.l10n.fx.background." + background)).getString());
            }
        } else if (button == fontButton) {
            for (BattleTextFonts.FontPreset preset : BattleTextFonts.presets()) {
                texts.add(Component.translatable("music_battle.l10n.fx.font_value", preset.displayName()).getString());
            }
        } else if (button == animationButton || button == exitAnimationButton) {
            boolean entrance = button == animationButton;
            for (String value : entrance ? TEXT_ANIMATIONS : TEXT_EXIT_ANIMATIONS) {
                texts.add(Component.translatable(entrance ? "music_battle.l10n.fx.entrance_value"
                        : "music_battle.l10n.fx.exit_value", animationName(value)).getString());
            }
        } else if (button == variantButton && type != TrackEffect.Type.VISUALIZER) {
            for (String mode : HIGHLIGHT_MODES) {
                texts.add(Component.translatable("music_battle.l10n.fx.mode_value",
                        Component.translatable("music_battle.l10n.fx.highlight." + mode)).getString());
            }
        } else if (button == gradientButton) {
            texts.add(tr("music_battle.l10n.fx.label_121"));
            texts.add(tr("music_battle.l10n.fx.label_122"));
        } else if (button == sectionsButton) {
            for (int sections : VISUALIZER_SECTIONS) {
                texts.add(Component.translatable("music_battle.l10n.fx.bars_value", sections).getString());
            }
        } else if (button == visualizerPositionButton) {
            for (String position : VISUALIZER_POSITIONS) {
                texts.add(Component.translatable("music_battle.l10n.fx.position_value", visualizerPositionName(position))
                        .getString());
            }
        } else if (button == starDirectionButton) {
            for (String direction : STAR_DIRECTIONS) {
                texts.add(Component.translatable("effect.music_battle.stars.direction.value",
                        Component.translatable("effect.music_battle.stars.direction." + direction)).getString());
            }
        } else {
            texts.add(button.getMessage().getString());
        }
        return texts;
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
                // Each label names what its switch hides; the switch keeps the words the guide quotes ("Hide HUD").
                rows.add(List.of(control(hideParticlesButton, tr("music_battle.fx.particles")),
                        control(hideFirstPersonHandButton, tr("music_battle.fx.hand"))));
                rows.add(List.of(control(hideHudButton, tr("music_battle.fx.hud"))));
                rows.add(List.of(control(hideBlockyMusicHudButton, tr("music_battle.fx.blocky_music_hud"))));
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
            // A colour's label names it selected or not, with its code; the layout measures both forms with the widest
            // code there is, so choosing or recolouring one never rearranges the palette under the pointer.
            String widestCode = widestColorCode();
            for (int start = 0; start < objectOutlineColors.size(); start += 4) {
                List<LabeledControl> paletteRow = new ArrayList<>();
                for (int index = start; index < Math.min(start + 4, objectOutlineColors.size()); index++) {
                    LabeledControl swatch = control(objectOutlineColorButtons.get(index),
                            tr(index == selectedObjectOutlineColor ? "music_battle.l10n.fx.selected_palette_color"
                                    : "music_battle.l10n.fx.palette_color", index + 1, colorText(objectOutlineColors.get(index))));
                    paletteRow.add(swatch.withAlternatives(List.of(
                            tr("music_battle.l10n.fx.selected_palette_color", index + 1, widestCode),
                            tr("music_battle.l10n.fx.palette_color", index + 1, widestCode))));
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
                : new LabeledControl(slider, label, widget, List.of());
    }

    /** A colour code as wide as any can be in the caption face: each of its six digits the widest hex digit. */
    private String widestColorCode() {
        char widest = '0';
        float width = -1.0F;
        for (char digit : "0123456789ABCDEF".toCharArray()) {
            float candidate = measurer().width(String.valueOf(digit), TextStyle.CAPTION);
            if (candidate > width) {
                width = candidate;
                widest = digit;
            }
        }
        return "#" + String.valueOf(widest).repeat(6);
    }

    /** The Workshop's scrollbar in the gutter at the panel's right edge. */
    private void drawScrollbar(GuiGraphics graphics) {
        int track = contentBottom - contentTop;
        WorkshopDraw.scrollbar(graphics, new Rect(panelLeft + panelWidth - 13.0F, contentTop, 8.0F, track), scrollOffset,
                track + maxScroll, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (effectPickerOpen || fontPickerOpen) {
            PickerLayout picker = effectPickerOpen ? effectPicker() : fontPicker();
            pickerScroll = Mth.clamp(pickerScroll - (int) Math.round(scrollY * 28.0D), 0, picker.maxScroll());
            return true;
        }
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
    private void openFontPicker() { fontPickerOpen = true; pickerScroll = 0; }
    private void openEffectPicker() { effectPickerOpen = true; pickerScroll = 0; }
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
        int[] values = VISUALIZER_SECTIONS;
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
        // Ctrl on every system, Cmd too on a Mac (Screen.hasControlDown() there is Cmd only), as in the chart editor.
        if (EditorShortcuts.shortcutDown() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Z) {
            if (EditorShortcuts.shiftDown()) redoSettings(); else undoSettings();
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

    /**
     * The effects to choose from, as cards in a dialog over the preview. The dialog stays inside the window and its cards
     * scroll when the window is too short for all sixteen: on a 320x240 window the old one ran off the bottom.
     */
    private void drawEffectPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.flush();
        TrackEffect.Type[] types = TrackEffect.Type.values();
        List<String> names = new ArrayList<>(types.length);
        for (TrackEffect.Type candidate : types) {
            names.add(effectName(candidate));
        }
        PickerLayout picker = effectPicker();
        pickerScroll = Mth.clamp(pickerScroll, 0, picker.maxScroll());
        drawPickerPanel(graphics, picker);
        UiClip.push(graphics, picker.left() + 4, picker.viewportTop() - 2, picker.right() - 4, picker.viewportBottom() + 2);
        float textWidth = picker.cardWidth() - PickerLayout.EFFECT_TEXT_PAD;
        String tooltip = null;
        Rect tooltipCard = null;
        for (int index = 0; index < types.length; index++) {
            TrackEffect.Type candidate = types[index];
            Rect card = picker.card(index, pickerScroll);
            if (!picker.visible(card)) {
                continue;
            }
            boolean hovered = picker.hit(card, mouseX, mouseY);
            boolean selected = candidate == type;
            drawPickerCard(graphics, card, hovered, selected);
            // The effect's colour, the one its blocks have on the timeline, as the card's rail.
            RoundedRect.pill(graphics, card.x() + 2.0F, card.y() + 5.0F, 3.0F, card.h() - 10.0F, 0xFF000000 | candidate.color());
            TextStyle face = PickerLayout.nameFace(TextStyles.measurer(), names.get(index), textWidth);
            String name = TextFit.ellipsize(TextStyles.measurer(), names.get(index), face, textWidth);
            TextStyles.queue(graphics, TextStyles.text(name, face).getVisualOrderText(), card.x() + 11, card.y() + 7,
                    selected ? Palette.ACCENT : Palette.TEXT_1);
            String description = effectDescription(candidate);
            List<String> lines = TextFit.wrap(TextStyles.measurer(), description, TextStyle.CAPTION, textWidth, 2);
            float lineY = card.y() + 21;
            for (String line : lines) {
                TextStyles.queue(graphics, TextStyles.text(line, TextStyle.CAPTION).getVisualOrderText(), card.x() + 11, lineY,
                        Palette.TEXT_3);
                lineY += ru.gora.musicbattle.client.workshop.layout.Metrics.lineHeight(TextStyle.CAPTION);
            }
            // What a card cannot say whole, it says under the pointer.
            boolean cut = !name.equals(names.get(index)) || !String.join(" ", lines).equals(String.join(" ", description.split(" +")));
            if (hovered && cut) {
                tooltip = names.get(index) + "\n" + description;
                tooltipCard = card;
            }
        }
        UiClip.pop(graphics);
        WorkshopDraw.scrollbar(graphics, new Rect(picker.left() + 14, picker.viewportTop(), picker.width() - 22.0F,
                picker.viewportBottom() - picker.viewportTop()), pickerScroll, picker.contentHeight(), false);
        if (tooltip != null) {
            tip(Component.literal(tooltip), tooltipCard);
        }
    }

    /** A picker's dialog over the preview: the page colour behind it, the raised panel and its heading over a hairline. */
    private void drawPickerPanel(GuiGraphics graphics, PickerLayout picker) {
        graphics.fill(0, 0, this.width, this.height, PICKER_BACKDROP);
        RoundedRect.shadow(graphics, picker.left(), picker.top() + 2.0F, picker.width(), picker.height(), Palette.RADIUS_LG,
                12.0F, Palette.SHADOW_LIFTED);
        RoundedRect.fill(graphics, picker.left(), picker.top(), picker.width(), picker.height(), Palette.RADIUS_LG, Palette.BG_1);
        RoundedRect.stroke(graphics, picker.left(), picker.top(), picker.width(), picker.height(), Palette.RADIUS_LG, 1.0F,
                Palette.LINE);
        float y = picker.top() + PickerLayout.HEADING_Y;
        for (String line : picker.heading()) {
            TextStyles.queueCentered(graphics, TextStyles.text(line, picker.headingFace()).getVisualOrderText(),
                    picker.left() + picker.width() / 2.0F, y, Palette.TEXT_1);
            y += ru.gora.musicbattle.client.workshop.layout.Metrics.lineHeight(picker.headingFace());
        }
        RoundedRect.fill(graphics, picker.left() + 14, picker.ruleY(), picker.width() - 28.0F, 1.0F, 0.0F, Palette.LINE);
    }

    /** A choice in a picker, as the Workshop draws a card: lifted under the pointer, the current one in the accent. */
    private static void drawPickerCard(GuiGraphics graphics, Rect card, boolean hovered, boolean selected) {
        if (hovered) {
            HandCursor.request(HandCursor.Kind.HAND);
            RoundedRect.shadow(graphics, card.x(), card.y() + 2, card.w(), card.h(), Palette.RADIUS_MD, 7.0F,
                    Palette.SHADOW_LIFTED);
        }
        int fill = selected ? Palette.mix(Palette.BG_3, Palette.ACCENT_DEEP, 0.85F) : hovered ? Palette.BG_3 : Palette.BG_2;
        RoundedRect.fill(graphics, card.x(), card.y(), card.w(), card.h(), Palette.RADIUS_MD, fill);
        if (selected || hovered) {
            RoundedRect.stroke(graphics, card.x(), card.y(), card.w(), card.h(), Palette.RADIUS_MD, 1.0F,
                    selected ? Palette.ACCENT : Palette.fade(Palette.ACCENT, 0.5F));
        }
    }

    private int effectPresetAt(double mouseX, double mouseY) {
        PickerLayout picker = effectPicker();
        for (int index = 0; index < TrackEffect.Type.values().length; index++) {
            if (picker.hit(picker.card(index, pickerScroll), mouseX, mouseY)) {
                return index;
            }
        }
        return -1;
    }

    private PickerLayout effectPicker() {
        List<String> names = new ArrayList<>();
        for (TrackEffect.Type candidate : TrackEffect.Type.values()) {
            names.add(effectName(candidate));
        }
        return PickerLayout.fitting(this.width, this.height, names, this.width >= 820 ? 4 : this.width >= 520 ? 3 : 2,
                PickerLayout.EFFECT_CARD, PickerLayout.EFFECT_TEXT_PAD, TextStyle.CAPTION,
                tr("music_battle.l10n.fx.label_114"), TextStyles.measurer());
    }

    private static String effectDescription(TrackEffect.Type candidate) {
        return Component.translatable(candidate.translationKey() + ".description").getString();
    }

    /** The fonts of a text effect, each card showing the effect's own text in that font; scrolls like the effects. */
    private void drawFontPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.flush();
        List<BattleTextFonts.FontPreset> presets = BattleTextFonts.presets();
        PickerLayout picker = fontPicker();
        pickerScroll = Mth.clamp(pickerScroll, 0, picker.maxScroll());
        drawPickerPanel(graphics, picker);
        String sample = textBox == null || textBox.getValue().isBlank() ? "Blocky Music 123" : textBox.getValue();
        String tooltip = null;
        Rect tooltipCard = null;
        for (int index = 0; index < presets.size(); index++) {
            Rect card = picker.card(index, pickerScroll);
            if (!picker.visible(card)) {
                continue;
            }
            int x = Math.round(card.x());
            int y = Math.round(card.y());
            int cardWidth = Math.round(card.w());
            int cardHeight = Math.round(card.h());
            boolean hovered = picker.hit(card, mouseX, mouseY);
            boolean selected = presets.get(index).id().equals(fontId);
            UiClip.push(graphics, picker.left() + 4, picker.viewportTop() - 2, picker.right() - 4, picker.viewportBottom() + 2);
            drawPickerCard(graphics, card, hovered, selected);
            String fullName = presets.get(index).displayName();
            String name = TextFit.ellipsize(TextStyles.measurer(), fullName, TextStyle.LABEL,
                    cardWidth - PickerLayout.FONT_TEXT_PAD);
            TextStyles.queue(graphics, TextStyles.text(name, TextStyle.LABEL).getVisualOrderText(), x + 6, y + 6,
                    selected ? Palette.ACCENT : Palette.TEXT_2);
            UiClip.pop(graphics);
            if (hovered && !name.equals(fullName)) {
                tooltip = fullName;
                tooltipCard = card;
            }
            // The sample is the font itself, drawn by the battle's own text renderer: it is the thing being chosen.
            String fitted = this.font.plainSubstrByWidth(sample,
                    Math.max(20, Math.round((cardWidth - 12) / presets.get(index).widthScale())));
            int sampleTop = Math.max(y + 20, Math.round(picker.viewportTop()));
            int sampleBottom = Math.min(y + cardHeight - 3, Math.round(picker.viewportBottom()));
            if (sampleBottom > sampleTop) {
                UiClip.push(graphics, x + 3, sampleTop, x + cardWidth - 3, sampleBottom);
                BattleTextFonts.drawCentered(graphics, this.font, fitted, presets.get(index).id(),
                        x + cardWidth / 2.0F, y + 25.0F, 0xFFFFFFFF, 0xFF000000, 0.0F);
                UiClip.pop(graphics);
            }
        }
        WorkshopDraw.scrollbar(graphics, new Rect(picker.left() + 14, picker.viewportTop(), picker.width() - 22.0F,
                picker.viewportBottom() - picker.viewportTop()), pickerScroll, picker.contentHeight(), false);
        if (tooltip != null) {
            tip(Component.literal(tooltip), tooltipCard);
        }
    }

    private int fontPresetAt(double mouseX, double mouseY) {
        PickerLayout picker = fontPicker();
        for (int index = 0; index < BattleTextFonts.presets().size(); index++) {
            if (picker.hit(picker.card(index, pickerScroll), mouseX, mouseY)) {
                return index;
            }
        }
        return -1;
    }

    private PickerLayout fontPicker() {
        List<String> names = new ArrayList<>();
        for (BattleTextFonts.FontPreset preset : BattleTextFonts.presets()) {
            names.add(preset.displayName());
        }
        return PickerLayout.fitting(this.width, this.height, names, this.width >= 600 ? 3 : 2, PickerLayout.FONT_CARD,
                PickerLayout.FONT_TEXT_PAD, TextStyle.LABEL, tr("music_battle.l10n.fx.label_115"), TextStyles.measurer());
    }

    /**
     * Where a picker of effects or fonts sits: a panel no wider than the window less twenty units and no taller than the
     * window less eight, its heading in up to two lines of the title face, and a viewport of cards under a hairline that
     * scrolls when the window is too short for all of them. Pure, for the dj-dialogs check.
     */
    record PickerLayout(int left, int top, int width, int height, List<String> heading, TextStyle headingFace, int ruleY,
                        int viewportTop, int viewportBottom, int columns, int cardWidth, int cardHeight, int contentHeight) {
        /** An effect's card: its name and two lines of its description. */
        static final int EFFECT_CARD = 46;
        /** A font's card: its name and a sample of the text in the font. */
        static final int FONT_CARD = 46;
        static final int HEADING_Y = 10;
        static final int CARD_GAP = 6;
        /** What a card keeps beside its name: the rail and the margins of an effect's card, the margins of a font's. */
        static final float EFFECT_TEXT_PAD = 22.0F;
        static final float FONT_TEXT_PAD = 12.0F;

        /**
         * The picker with as many columns as the window allows, or fewer: the first count, down to one, in which every name
         * is whole in its card in the smallest face it may take. In Russian "Скрытие панелей над игроками" does not fit a
         * card of two columns on a 320-unit window even in captions, so there the picker is one column that scrolls.
         */
        static PickerLayout fitting(int windowWidth, int windowHeight, List<String> names, int maxColumns, int cardHeight,
                                    float textPad, TextStyle smallest, String title,
                                    ru.gora.musicbattle.client.ui.TextMeasurer measurer) {
            PickerLayout layout = null;
            for (int columns = maxColumns; columns >= 1; columns--) {
                layout = of(windowWidth, windowHeight, names.size(), columns, cardHeight, title, measurer);
                boolean whole = true;
                for (String name : names) {
                    whole &= TextFit.width(measurer, name, smallest) <= layout.cardWidth() - textPad;
                }
                if (whole) {
                    return layout;
                }
            }
            return layout;
        }

        static PickerLayout of(int windowWidth, int windowHeight, int count, int columns, int cardHeight, String title,
                               ru.gora.musicbattle.client.ui.TextMeasurer measurer) {
            int width = Math.max(120, Math.min(windowWidth - 20, columns * 204 + 28));
            int cardWidth = (width - 28 - (columns - 1) * CARD_GAP) / columns;
            // The heading in the title face on two lines at most, else in the strong face on two, then three: the
            // Russian "Выбери FX: каждый инструмент сохраняет свои настройки" needs three on a 320-unit window.
            TextStyle headingFace = TextStyle.STRONG;
            List<String> heading = null;
            for (Object[] shape : new Object[][]{{TextStyle.TITLE, 2}, {TextStyle.STRONG, 2}, {TextStyle.STRONG, 3}}) {
                TextStyle face = (TextStyle) shape[0];
                List<String> lines = TextFit.wrap(measurer, title, face, width - 28.0F);
                boolean wordsWhole = true;
                for (String word : title.split(" ")) {
                    wordsWhole &= TextFit.width(measurer, word, face) <= width - 28.0F;
                }
                if (wordsWhole && lines.size() <= (int) shape[1]) {
                    headingFace = face;
                    heading = lines;
                    break;
                }
            }
            if (heading == null) {
                heading = TextFit.wrap(measurer, title, headingFace, width - 28.0F, 3);
            }
            int ruleY = (int) Math.ceil(HEADING_Y + heading.size()
                    * ru.gora.musicbattle.client.workshop.layout.Metrics.lineHeight(headingFace)) + 4;
            int rows = (count + columns - 1) / columns;
            int contentHeight = rows * (cardHeight + CARD_GAP) - CARD_GAP;
            int needed = ruleY + 9 + contentHeight + 14;
            int height = Math.min(Math.max(ruleY + 9 + cardHeight + 14, windowHeight - 8), needed);
            int top = Math.max(4, (windowHeight - height) / 2);
            int left = (windowWidth - width) / 2;
            return new PickerLayout(left, top, width, height, heading, headingFace, top + ruleY, top + ruleY + 9,
                    top + height - 14, columns, cardWidth, cardHeight, contentHeight);
        }

        /** The face a name takes in its card: the strong face, else body, else captions (cut, with a tooltip, past that). */
        static TextStyle nameFace(ru.gora.musicbattle.client.ui.TextMeasurer measurer, String name, float width) {
            for (TextStyle face : new TextStyle[]{TextStyle.STRONG, TextStyle.BODY, TextStyle.CAPTION}) {
                if (TextFit.width(measurer, name, face) <= width) {
                    return face;
                }
            }
            return TextStyle.CAPTION;
        }

        int right() {
            return left + width;
        }

        int maxScroll() {
            return Math.max(0, contentHeight - (viewportBottom - viewportTop));
        }

        Rect card(int index, int scroll) {
            return new Rect(left + 14 + (index % columns) * (cardWidth + CARD_GAP),
                    viewportTop + (index / columns) * (cardHeight + CARD_GAP) - scroll, cardWidth, cardHeight);
        }

        /** Whether any of a card shows inside the scrolling viewport. */
        boolean visible(Rect card) {
            return card.bottom() > viewportTop && card.y() < viewportBottom;
        }

        /** A click lands on a card only where the card shows. */
        boolean hit(Rect card, double mouseX, double mouseY) {
            return card.contains(mouseX, mouseY) && mouseY >= viewportTop && mouseY < viewportBottom;
        }
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
        if (type == TrackEffect.Type.VISUALIZER) return switchLabel(tr("music_battle.l10n.fx.label_120"));
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
        return switchLabel(type == TrackEffect.Type.OBJECT_OUTLINE
                ? tr("music_battle.l10n.fx.label_125") : tr("music_battle.l10n.fx.label_126"));
    }
    private boolean flashFollowsBeat() { return !"steady".equalsIgnoreCase(variant); }
    private Component flashBeatLabel() { return switchLabel(tr("music_battle.l10n.fx.label_125")); }
    /**
     * The sweep switch says what it does: its row's label above it names the sweeping scanline, and the switch used to
     * repeat that name word for word.
     */
    private Component tvSweepLabel() { return switchLabel(tr("music_battle.l10n.fx.tv_sweep_switch")); }
    private Component cinematicAnimationLabel() { return switchLabel(tr("music_battle.l10n.fx.label_127")); }
    private Component fishEyeRemoveBlackEdgesLabel() { return switchLabel(tr("music_battle.l10n.fx.label_128")); }
    private Component overlayTextLabel() { return switchLabel(tr("music_battle.l10n.fx.label_129")); }
    private Component overlayInterfaceLabel() { return switchLabel(tr("music_battle.l10n.fx.label_130")); }
    private Component overlayPlayersLabel() { return switchLabel(tr("music_battle.l10n.fx.label_131")); }
    private Component overlayEntitiesLabel() { return switchLabel(tr("music_battle.l10n.fx.label_132")); }
    private Component highlightSpectatorsLabel() { return switchLabel(tr("music_battle.l10n.fx.label_133")); }
    private Component highlightEntitiesLabel() { return switchLabel(tr("music_battle.l10n.fx.label_134")); }
    private Component hideParticlesLabel() { return switchLabel(tr("music_battle.fx.hide_particles")); }
    private Component hideHandLabel() { return switchLabel(tr("music_battle.fx.hide_hand")); }
    private Component hideHudLabel() { return switchLabel(tr("music_battle.fx.hide_hud")); }
    private Component hideBlockyMusicHudLabel() { return switchLabel(tr("music_battle.fx.hide_blocky_music_hud")); }

    private Component highlightModelGlowLabel() { return switchLabel(tr("music_battle.l10n.fx.label_135")); }
    /** The fade switches say what they do; their rows' labels above them name the fade, as the sweep's does. */
    private Component fadeInLabel() { return switchLabel(tr("music_battle.l10n.fx.fade_in_switch")); }
    private Component fadeOutLabel() { return switchLabel(tr("music_battle.l10n.fx.fade_out_switch")); }

    /** A switch's words; whether it is on is its check box (InspectorButton), which reads the state as it draws. */
    private static Component switchLabel(String text) {
        return Component.literal(text);
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

    /**
     * A row cell: one control, the optional numeric box that shares the cell with it, and the other forms its label can
     * take, which the layout measures too.
     */
    private record LabeledControl(AbstractWidget widget, String label, AbstractWidget companion, List<String> alternatives) {
        private LabeledControl(AbstractWidget widget, String label) {
            this(widget, label, null, List.of());
        }

        private LabeledControl withAlternatives(List<String> forms) {
            return new LabeledControl(widget, label, companion, List.copyOf(forms));
        }
    }

    /** A label in the viewport and the lines the layout set it in. */
    private record Shown(LabeledControl control, List<String> lines) {
    }

    /**
     * Where the inspector's controls sit across the panel's inner width, and how their words are set. A row keeps its
     * cells equal, as it always did, while every label is whole over its cell on one line of captions; else on two; else
     * the row breaks into lines of fewer cells (four become two and two, three become two and one), then fewer again,
     * until each control has the room its words need. A label never breaks inside a word. A button's words are whole too:
     * every word a cycling button can show, in one face for the whole line of buttons - the body face where all of them
     * fit it, captions where one does not - so «Перекрывать игроков» no longer sits in one size beside its neighbour in
     * another. Numbers have a floor: a slider keeps room for its knob and the box beside it, a colour button for its swatch
     * and its code.
     *
     * <p>Rows used to be cut into equal cells whatever their words. At 427x240, the window most players get with the
     * automatic GUI scale, 90 of the Russian labels were cut, and so were switches such as «Плавный въезд/выезд»; even the
     * default panel cut the fish-eye's «Приближение 0.25–8 (1 = без приближения)». The dj-dialogs check lays out every
     * effect's rows in English and Russian at every inner width the panel can have and at every GUI scale, with
     * Minecraft's own glyph metrics, and fails on the first word that would not fit. Pure, for that check.
     */
    static final class InspectorLayout {
        /** Between two cells of a line. */
        static final int GAP = 6;
        /** A line whose labels take one line: thirteen units of label over the twenty-unit control, seven under it. */
        static final int ROW = 40;
        static final int ABOVE = 13;
        static final int CONTROL = 20;
        /** The advance of a label's next line: the caption face's line height (Metrics.lineHeight, 9.0). */
        static final int LABEL_LINE = 9;
        static final int MAX_LABEL_LINES = 2;
        /** InspectorButton gives its words its width less ten, and a switch's check box takes twelve more. */
        static final float BUTTON_PAD = 10.0F;
        static final float CHECK = 12.0F;
        /** The gap between a slider and the box for its exact value. */
        static final int BOX_GAP = 4;
        /**
         * A slider with room to drag beside a box of thirty; a colour button's swatch and its widest "#RRGGBB" in captions
         * (ExactColorButton.codeRoom, which the dj-fx-leftovers check measures at every GUI scale); the text field.
         */
        static final int SLIDER_MIN = 76;
        static final int COLOR_MIN = 68;
        static final int FIELD_MIN = 80;

        enum Kind { BUTTON, SWITCH, SLIDER, COLOR, FIELD }

        /**
         * One control. {@code labels} holds its label first and then any other form the label can take; {@code texts}
         * every word a button can show. All of them are measured.
         */
        record Cell(List<String> labels, Kind kind, List<String> texts) {
            String label() {
                return labels.isEmpty() ? "" : labels.getFirst();
            }
        }

        /**
         * A cell placed: its row and column in the rows given, its left edge and its control's top from the content's
         * top-left, its width, its label's lines and the face of its line's buttons.
         */
        record Placed(int row, int column, int x, int y, int width, List<String> label, TextStyle face) {
        }

        /** One line of cells as fitted: their width, their labels' lines, the lines the labels need and the face. */
        private record Line(int first, int cellWidth, List<List<String>> labels, int labelLines, TextStyle face) {
        }

        private final List<Placed> cells;
        private final int height;
        private final List<String> problems;

        private InspectorLayout(List<Placed> cells, int height, List<String> problems) {
            this.cells = List.copyOf(cells);
            this.height = height;
            this.problems = List.copyOf(problems);
        }

        List<Placed> cells() {
            return cells;
        }

        /** The content's height: what the viewport scrolls through. */
        int height() {
            return height;
        }

        /** Every row no arrangement fitted, with why; empty on every window the check measures. */
        List<String> problems() {
            return problems;
        }

        /** The box for a slider's exact value: a third of the cell, from thirty to fifty-two units. */
        static int boxWidth(int cellWidth) {
            return Mth.clamp(cellWidth / 3, 30, 52);
        }

        static InspectorLayout arrange(List<List<Cell>> rows, int width, ru.gora.musicbattle.client.ui.TextMeasurer measurer) {
            List<Placed> placed = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            int top = 0;
            for (int row = 0; row < rows.size(); row++) {
                List<Cell> cells = rows.get(row);
                if (cells.isEmpty()) {
                    continue;
                }
                List<Line> lines = fit(cells, width, measurer);
                if (lines == null) {
                    problems.add("row " + (row + 1) + " " + labels(cells) + " fits no arrangement in " + width + " units");
                    lines = fallback(cells, width, measurer);
                }
                for (Line line : lines) {
                    int controlY = top + ABOVE + LABEL_LINE * (line.labelLines() - 1);
                    for (int index = 0; index < line.labels().size(); index++) {
                        placed.add(new Placed(row, line.first() + index, index * (line.cellWidth() + GAP), controlY,
                                line.cellWidth(), line.labels().get(index), line.face()));
                    }
                    top += ROW + LABEL_LINE * (line.labelLines() - 1);
                }
            }
            return new InspectorLayout(placed, top, problems);
        }

        /**
         * The first arrangement of a row in which everything fits: all its cells on one line, with one-line labels and then
         * two-line ones, then on two lines of cells, and so on to one cell a line. Null when even that does not fit.
         */
        private static List<Line> fit(List<Cell> cells, int width, ru.gora.musicbattle.client.ui.TextMeasurer measurer) {
            for (int count = 1; count <= cells.size(); count++) {
                for (int labelLines = 1; labelLines <= MAX_LABEL_LINES; labelLines++) {
                    List<Line> lines = new ArrayList<>(count);
                    int start = 0;
                    for (int line = 0; line < count && lines != null; line++) {
                        // As even as the cells allow, the earlier lines taking one more: four make two and two, three
                        // make two and one.
                        int size = (cells.size() - start + count - line - 1) / (count - line);
                        Line fitted = line(cells.subList(start, start + size), start, width, labelLines, measurer);
                        if (fitted == null) {
                            lines = null;
                        } else {
                            lines.add(fitted);
                            start += size;
                        }
                    }
                    if (lines != null) {
                        return lines;
                    }
                }
            }
            return null;
        }

        /** One line of equal cells, or null when a label needs more lines than allowed or a word more room than a cell. */
        private static Line line(List<Cell> cells, int first, int width, int labelLines,
                                 ru.gora.musicbattle.client.ui.TextMeasurer measurer) {
            int cellWidth = (width - GAP * (cells.size() - 1)) / cells.size();
            List<List<String>> labels = new ArrayList<>(cells.size());
            int lines = 1;
            boolean body = true;
            for (Cell cell : cells) {
                if (cellWidth < minimum(cell)) {
                    return null;
                }
                List<String> label = null;
                for (String form : cell.labels()) {
                    List<String> wrapped = wrapWhole(measurer, form, cellWidth);
                    if (wrapped == null || wrapped.size() > labelLines) {
                        return null;
                    }
                    if (label == null) {
                        label = wrapped;
                    }
                }
                label = label == null ? List.of() : label;
                labels.add(label);
                lines = Math.max(lines, label.size());
                if (cell.kind() == Kind.BUTTON || cell.kind() == Kind.SWITCH) {
                    float room = room(cell, cellWidth);
                    for (String text : cell.texts()) {
                        body &= TextFit.width(measurer, text, TextStyle.BODY) <= room;
                        if (TextFit.width(measurer, text, TextStyle.CAPTION) > room) {
                            return null;
                        }
                    }
                }
            }
            return new Line(first, cellWidth, labels, lines, body ? TextStyle.BODY : TextStyle.CAPTION);
        }

        /** Nothing fits (only in a language the check never measured): one cell a line, cut where it must be. */
        private static List<Line> fallback(List<Cell> cells, int width, ru.gora.musicbattle.client.ui.TextMeasurer measurer) {
            List<Line> lines = new ArrayList<>();
            for (int index = 0; index < cells.size(); index++) {
                List<String> label = TextFit.wrap(measurer, cells.get(index).label(), TextStyle.CAPTION, width,
                        MAX_LABEL_LINES);
                lines.add(new Line(index, width, List.of(label), Math.max(1, label.size()), TextStyle.CAPTION));
            }
            return lines;
        }

        /** The room a button leaves its words in a cell of this width. */
        static float room(Cell cell, int cellWidth) {
            return cellWidth - BUTTON_PAD - (cell.kind() == Kind.SWITCH ? CHECK : 0.0F);
        }

        static int minimum(Cell cell) {
            return switch (cell.kind()) {
                case SLIDER -> SLIDER_MIN;
                case COLOR -> COLOR_MIN;
                case FIELD -> FIELD_MIN;
                case BUTTON, SWITCH -> 0;
            };
        }

        /** A label's lines at a width, or null when one of its words is wider than the width on its own. */
        static List<String> wrapWhole(ru.gora.musicbattle.client.ui.TextMeasurer measurer, String text, float width) {
            if (text == null || text.isBlank()) {
                return List.of();
            }
            for (String word : text.trim().split(" +")) {
                if (TextFit.width(measurer, word, TextStyle.CAPTION) > width) {
                    return null;
                }
            }
            return TextFit.wrap(measurer, text.trim(), TextStyle.CAPTION, width);
        }

        /** The face of the action buttons along the bottom: body where every label fits it, else captions, else null. */
        static TextStyle actionFace(List<String> labels, int buttonWidth, ru.gora.musicbattle.client.ui.TextMeasurer measurer) {
            for (TextStyle face : new TextStyle[]{TextStyle.BODY, TextStyle.CAPTION}) {
                boolean whole = true;
                for (String label : labels) {
                    whole &= TextFit.width(measurer, label, face) <= buttonWidth - BUTTON_PAD;
                }
                if (whole) {
                    return face;
                }
            }
            return null;
        }

        private static List<String> labels(List<Cell> cells) {
            List<String> labels = new ArrayList<>(cells.size());
            for (Cell cell : cells) {
                labels.add(cell.label());
            }
            return labels;
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

        /**
         * The toolkit's slider without a label: the box beside it shows the exact value, so the slider is the track and
         * its knob alone, centred in the field. It was a square teal track with a white bar of a handle.
         */
        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            ru.gora.musicbattle.client.ui.Slider.paint(graphics, Rect.of(getX(), getY(), getWidth(), getHeight()), value,
                    Component.empty(), isHoveredOrFocused(), active);
        }
    }

    /**
     * A button of the inspector, drawn as FancyButton draws the mod's buttons - the toolkit's surface, the label in Nunito
     * - with two differences. A switch shows the Workshop's check box in front of its words, ticked while it is on: its
     * words used to begin with ☑ or ☐, symbols Nunito does not have, which Minecraft drew in its bitmap font. And its face
     * is the one the layout chose for its whole line of buttons, the body face or captions, with room for every word the
     * button can show. Only a language the layout was never measured with can leave a label too long even so; it is cut
     * then, and shows whole as a tooltip under the pointer.
     */
    private static final class InspectorButton extends Button {
        private final java.util.function.Supplier<Boolean> state;
        /** The face {@link InspectorLayout} chose for this button's line; null until it is laid out. */
        private TextStyle face;

        private InspectorButton(Component message, OnPress onPress, int x, int y, int width, int height,
                                java.util.function.Supplier<Boolean> state) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.state = state;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
            boolean hovered = isHoveredOrFocused();
            if (hovered && active) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            WorkshopDraw.button(graphics, rect, WorkshopDraw.ButtonStyle.SECONDARY, hovered, active);
            Boolean ticked = state == null ? null : state.get();
            int colour = !active ? Palette.TEXT_MUTE : hovered ? Palette.TEXT_1 : Palette.TEXT_2;
            float mark = ticked == null ? 0.0F : InspectorLayout.CHECK;
            float room = Math.max(4.0F, getWidth() - InspectorLayout.BUTTON_PAD - mark);
            String label = getMessage().getString();
            TextStyle face = this.face != null ? this.face
                    : getHeight() >= 18 && TextFit.width(TextStyles.measurer(), label, TextStyle.BODY) <= room
                    ? TextStyle.BODY : TextStyle.CAPTION;
            String text = TextFit.ellipsize(TextStyles.measurer(), label, face, room);
            float left = rect.centerX() - (mark + TextFit.width(TextStyles.measurer(), text, face)) / 2.0F;
            if (ticked != null) {
                WorkshopDraw.check(graphics, new Rect(left, rect.centerY() - 4.0F, 8.0F, 8.0F), ticked, false, active);
            }
            TextStyles.queue(graphics, TextStyles.text(text, face).getVisualOrderText(), left + mark,
                    TextStyles.centeredCapY(face, rect.y(), rect.h()), colour);
            if (isHovered() && !text.equals(label)
                    && net.minecraft.client.Minecraft.getInstance().screen instanceof TrackEffectEditScreen editor) {
                editor.tip(getMessage(), rect);
            }
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
