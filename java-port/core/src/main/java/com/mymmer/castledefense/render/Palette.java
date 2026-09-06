package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.Color;

/**
 * The source's palette, transcribed.
 *
 * <p>Every constant here is a Python tuple from {@code sprites.py}'s palette
 * block or a literal from a {@code draw} method, converted once. Values are
 * {@code /255f} of the integer triples; where the source writes a colour inline
 * — and it does so constantly, for shading and highlights — it is named here
 * rather than repeated as three floats at the call site.
 *
 * <p>{@link #shade} and {@link #mix} are the source's own helpers, with the same
 * clamping, because a great deal of the look comes from a base colour darkened
 * to 0.6 for an outline and lightened to 1.5 for a highlight.
 *
 * <p>Colours are mutable in libGDX, so every constant here is handed out through
 * a copy or used only as a read source. The {@code tmp*} scratch instances exist
 * so a per-frame shade does not allocate.
 */
public final class Palette {

    private Palette() {
    }

    // --- sprites.py palette block -------------------------------------------
    public static final Color SKY_TOP = rgb(36, 42, 74);
    public static final Color SKY_BOT = rgb(126, 108, 122);
    public static final Color GROUND = rgb(58, 74, 46);
    public static final Color GROUND_DARK = rgb(40, 52, 32);
    public static final Color DIRT = rgb(74, 60, 44);
    public static final Color WHITE = rgb(240, 244, 250);
    public static final Color DIM = rgb(168, 176, 194);
    public static final Color GOLD = rgb(248, 202, 78);
    public static final Color RED = rgb(226, 74, 68);
    public static final Color GREEN = rgb(110, 210, 120);
    public static final Color PANEL = rgb(26, 28, 42);
    public static final Color PANEL_EDGE = rgb(86, 94, 128);
    public static final Color HILITE = rgb(96, 190, 236);

    public static final Color ALLY = rgb(150, 235, 190);
    public static final Color TALENT = rgb(186, 150, 255);
    public static final Color TALENT_ON = rgb(222, 196, 255);
    public static final Color SKILL_READY = rgb(255, 214, 120);
    public static final Color SKILL_COOL = rgb(86, 92, 116);

    public static final Color SKILL_LIGHTNING = rgb(170, 220, 255);
    public static final Color SKILL_METEOR = rgb(255, 150, 70);
    public static final Color SKILL_TORNADO = rgb(168, 214, 232);

    // --- bar defaults, from draw_bar's signature -----------------------------
    public static final Color BAR_BACK = rgb(28, 28, 34);
    public static final Color BAR_BORDER = rgb(12, 12, 16);

    // --- literals that appear in more than one draw method --------------------
    /** Castle cracks. */
    public static final Color CRACK = rgb(26, 22, 26);
    /** The damage flash tint the castle multiplies and adds. */
    public static final Color DAMAGE_FLASH = rgb(255, 90, 70);
    /** Wind streaks. */
    public static final Color WIND = rgb(188, 200, 224);
    /** Lightning: the bright core and the blue inner line. */
    public static final Color BOLT_CORE = rgb(236, 244, 255);
    public static final Color BOLT_INNER = rgb(150, 200, 255);
    /** The storm's full-screen veil, before its alpha is applied. */
    public static final Color STORM_VEIL = rgb(190, 215, 255);
    /** Skin tone, used by every humanoid the source draws. */
    public static final Color SKIN = rgb(226, 190, 150);
    /** Bone, for skeletons and the Lich. */
    public static final Color BONE = rgb(222, 220, 206);
    /** Arrow and bolt tips. */
    public static final Color ARROW_TIP = rgb(250, 250, 235);
    public static final Color BOLT_TIP = rgb(255, 236, 190);
    /** Cannonball body, highlight and trail. */
    public static final Color CANNONBALL = rgb(36, 36, 42);
    public static final Color CANNONBALL_HI = rgb(120, 120, 130);
    public static final Color CANNONBALL_TRAIL = rgb(90, 88, 96);
    /** Fire-zone flame, outer and inner. */
    public static final Color FLAME_OUTER = rgb(255, 150, 60);
    public static final Color FLAME_INNER = rgb(255, 226, 140);

    // --- endgame tier tints, from ENDGAME_TIERS -------------------------------
    public static final Color TIER_BLOODIED = rgb(214, 58, 52);
    public static final Color TIER_FROSTBOUND = rgb(74, 148, 230);
    public static final Color TIER_VOIDTOUCHED = rgb(24, 20, 34);

    // ========================================================================
    //  The source's own colour maths
    // ========================================================================

    /**
     * {@code sprites.shade}: lighten above 1, darken below, clamped.
     *
     * <p>Writes into {@code out} and returns it, because this is called several
     * times per entity per frame and allocating a {@link Color} each time is the
     * kind of garbage that shows up on a phone.
     */
    public static Color shade(Color source, float factor, Color out) {
        return out.set(
                clamp(source.r * factor),
                clamp(source.g * factor),
                clamp(source.b * factor),
                source.a);
    }

    /** {@code sprites.mix}: linear blend, as the source's {@code lerp} does. */
    public static Color mix(Color a, Color b, float t, Color out) {
        return out.set(
                a.r + (b.r - a.r) * t,
                a.g + (b.g - a.g) * t,
                a.b + (b.b - a.b) * t,
                a.a + (b.a - a.a) * t);
    }

    /** A colour with a different alpha, without touching the original. */
    public static Color alpha(Color source, float a, Color out) {
        return out.set(source.r, source.g, source.b, a);
    }

    public static Color rgb(int r, int g, int b) {
        return new Color(r / 255f, g / 255f, b / 255f, 1f);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
