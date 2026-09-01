package com.mymmer.castledefense.assets;

import com.badlogic.gdx.utils.ObjectMap;

/**
 * Everything the renderer needs to draw one logical unit — and nothing the
 * simulation needs.
 *
 * <p>The separation the brief insists on lives here: a unit's <b>gameplay</b>
 * box (width, height, hitbox, grab region) comes from configuration and is
 * identical on every skin; a unit's <b>visual</b> scale and offset come from the
 * skin and cannot affect collision. A 20% larger drawing is still the same
 * target to hit.
 *
 * <p>{@link #isProcedural()} is how the fallback is wired: when a skin supplies
 * no artwork for an id, the visual still exists and still answers questions
 * about attachments — it simply tells the renderer to draw the shape version.
 */
public final class UnitVisual {

    private final VisualId id;
    private final String region;
    private final float scale;
    private final float offsetX;
    private final float offsetY;
    private final AnimationSet animations;
    private final ObjectMap<String, AttachmentPoint> attachments;
    private final boolean procedural;

    public UnitVisual(VisualId id, String region, float scale, float offsetX, float offsetY,
                      AnimationSet animations,
                      ObjectMap<String, AttachmentPoint> attachments,
                      boolean procedural) {
        this.id = id;
        this.region = region;
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.animations = animations;
        this.attachments = attachments;
        this.procedural = procedural;
    }

    /** A visual with no artwork: the renderer draws it from primitives. */
    public static UnitVisual procedural(VisualId id) {
        return new UnitVisual(id, id.key(), 1f, 0f, 0f,
                new AnimationSet(id.key()), new ObjectMap<String, AttachmentPoint>(), true);
    }

    public VisualId id() {
        return id;
    }

    /** Base atlas region name. Meaningless when {@link #isProcedural()}. */
    public String region() {
        return region;
    }

    /** Drawing scale relative to the gameplay box. Never affects collision. */
    public float scale() {
        return scale;
    }

    /** Drawing offset in gameplay units, x. Never affects collision. */
    public float offsetX() {
        return offsetX;
    }

    /** Drawing offset in gameplay units, y. Never affects collision. */
    public float offsetY() {
        return offsetY;
    }

    public AnimationSet animations() {
        return animations;
    }

    /** True when no artwork exists and the procedural renderer must draw this. */
    public boolean isProcedural() {
        return procedural;
    }

    /** An attachment by name, or null when this skin does not define it. */
    public AttachmentPoint attachment(String name) {
        return attachments.get(name);
    }

    public boolean hasAttachment(String name) {
        return attachments.containsKey(name);
    }

    public int attachmentCount() {
        return attachments.size;
    }

    /** Region to draw for a state at a time offset. Never null. */
    public String regionAt(AnimationState state, float seconds) {
        return animations.regionAt(state, seconds);
    }
}
