package com.mymmer.castledefense.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.mymmer.castledefense.boss.Boss;
import com.mymmer.castledefense.boss.BossType;
import com.mymmer.castledefense.boss.DroppedItem;
import com.mymmer.castledefense.defence.DefenceTower;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.interaction.CursorInteraction;

/**
 * {@code draw_grab_cursor}: the prompts that tell the player what they can do.
 *
 * <h2>Why this layer matters more than it looks</h2>
 *
 * <p>This is where the game teaches itself. "DRAG BACK TO OVERCHARGE" over a
 * hovered Cannon, "RIP OFF THE CROWN" over a Troll King, "SMACK THE CLAWS" over
 * a Dragon — none of those mechanics is discoverable without the prompt, and the
 * source draws every one of them here. It was the one layer
 * {@code DrawOrder.Layer} named and Phase 11 did not implement; this closes it.
 *
 * <h2>It reads the cursor's state; it decides nothing</h2>
 *
 * <p>What is being charged, stripped, smacked, grabbed or carried are all
 * {@link CursorInteraction}'s answers. This draws them. In particular the
 * overcharge power comes from {@code CursorInteraction.overchargePower} — the
 * same function the shot itself uses — so the bar cannot promise a stronger shot
 * than the player will get.
 *
 * <h2>It is drawn into the shaken world</h2>
 *
 * <p>The source paints it into the scene surface, so it moves with a castle hit.
 * Its <b>positions come from gameplay</b> — the tower's muzzle, the boss's
 * regalia rectangle, the pointer's world position — and the pointer's world
 * position is computed against the unshaken camera. So the prompt jitters with
 * the world it is annotating and still points at the thing it means.
 */
public final class CursorPainter {

    private static final Color OVERCHARGE_COLD = Palette.rgb(150, 200, 255);
    private static final Color OVERCHARGE_HOT = Palette.rgb(255, 170, 90);
    private static final Color SMACK = Palette.rgb(255, 190, 120);
    private static final Color REGALIA = Palette.rgb(255, 226, 140);
    private static final Color HEAVY = Palette.rgb(255, 150, 120);

    /** The shapes. Assumes a filled pass is open. */
    public void paint(RenderContext ctx, CursorInteraction cursor, float pointerX,
                      float pointerY, boolean pointerDown) {
        float px = pointerX;
        float py = WorldGeometry.toDrawY(pointerY);

        DefenceTower charging = cursor.charging();
        if (charging != null) {
            slingshot(ctx, charging, px, py, pointerX, pointerY);
            return;         // the source returns here too: nothing else applies
        }
        Boss smacking = cursor.smacking();
        if (smacking != null) {
            smackTarget(ctx, smacking);
        }
    }

    /**
     * The slingshot draw-back on a hand-aimed tower.
     *
     * <p>The dotted arc shows where the shot will go — <b>opposite the pull</b>,
     * which is the whole point of a slingshot and the thing a player gets wrong
     * first. The power bar and the percentage come from the same function the
     * launch uses.
     */
    private void slingshot(RenderContext ctx, DefenceTower tower, float px,
                           float py, float worldPx, float worldPy) {
        float gx = tower.muzzleX();
        float gy = WorldGeometry.toDrawY(tower.muzzleY());
        float power = CursorInteraction.overchargePower(tower, worldPx, worldPy);
        Color col = ctx.mix(OVERCHARGE_COLD, OVERCHARGE_HOT, power);

        ctx.kit.line(gx, gy, px, py, 3f, col);
        //  The predicted flight, six dots along the reversed pull.
        float dx = gx - px;
        float dy = gy - py;
        float d = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
        for (int k = 1; k < 7; k++) {
            float ax = gx + dx / d * k * 34f;
            float ay = gy + dy / d * k * 34f - 0.5f * 9.8f * (k * 0.1f) * (k * 0.1f) * 30f;
            ctx.kit.circle(ax, ay, Math.max(1f, 4f - k / 2f), col);
        }
        ctx.kit.circleOutline(gx, gy, 8f + 10f * power, 2f, col);
        ctx.kit.bar(gx - 26f, gy + 28f, 52f, 6f, power, col);
    }

    /**
     * The Dragon's claws: the spot to drag on, and how far the battering has got.
     *
     * <p>The anchor is the boss's own interactive point -- the same one the
     * cursor hit-tests with {@code regaliaCovers} -- so the ring is drawn around
     * exactly what can be dragged, not near it.
     */
    private void smackTarget(RenderContext ctx, Boss boss) {
        if (!boss.regaliaAnchorInto(anchor)) {
            return;
        }
        float x = anchor[0];
        float y = WorldGeometry.toDrawY(anchor[1]);
        ctx.kit.circleOutline(x, y, 26f, 2f, SMACK);
        ctx.kit.bar(x - 30f, y + 34f, 60f, 6f, boss.disruptionProgress(), SMACK);
    }

    private final float[] anchor = new float[2];

    /**
     * The labels, in a batch pass.
     *
     * <p>Split from the shapes because libGDX cannot mix a {@code ShapeRenderer}
     * and a {@code SpriteBatch} in one pass — the same split every other painter
     * makes, and the layer's text stays in the layer.
     */
    public void paintLabels(RenderContext ctx, CursorInteraction cursor,
                            com.mymmer.castledefense.game.RunWorld run,
                            float pointerX, float pointerY) {
        DefenceTower charging = cursor.charging();
        if (charging != null) {
            float power = CursorInteraction.overchargePower(charging, pointerX,
                    pointerY);
            ctx.textCentered(charging.muzzleX(),
                    WorldGeometry.toDrawY(charging.muzzleY()) + 42f,
                    ((int) (power * 100f)) + "%", 18f,
                    ctx.mix(OVERCHARGE_COLD, OVERCHARGE_HOT, power), true);
            return;
        }
        Boss smacking = cursor.smacking();
        if (smacking != null && smacking.regaliaAnchorInto(anchor)) {
            ctx.textCentered(anchor[0], WorldGeometry.toDrawY(anchor[1]) + 34f,
                    "SMACK THE CLAWS!", 17f, SMACK, true);
        }
        DroppedItem held = cursor.heldItem();
        if (held != null) {
            ctx.textCentered(held.x(), WorldGeometry.toDrawY(held.y()) + 34f,
                    "FLING IT!", 18f, REGALIA, true);
            return;
        }
        //  The discovery prompts: a boss still wearing its regalia under the
        //  pointer, and a heavy unit that can be prised apart.
        Boss owner = regaliaUnder(run, pointerX, pointerY);
        if (owner != null) {
            //  isSmackTarget separates the Dragon's claws from a crown or staff
            //  the player can actually take -- the same distinction the cursor
            //  makes when deciding what a press does.
            String label = owner.isSmackTarget() ? "DRAG ON THE CLAWS"
                    : (owner.bossType() == BossType.TROLL_KING
                            ? "RIP OFF THE CROWN" : "FLICK THE STAFF AWAY");
            ctx.textCentered(owner.x(),
                    WorldGeometry.toDrawY(owner.y() - owner.height() / 2f) + 46f,
                    label, 18f, REGALIA, true);
        }
        Enemy heavy = cursor.stripping() != null ? cursor.stripping()
                : strippableUnder(run, pointerX, pointerY);
        if (heavy != null) {
            ctx.textCentered(heavy.x(),
                    WorldGeometry.toDrawY(heavy.y() - heavy.height() / 2f) + 26f,
                    heavy.layers() > 0 ? "DRAG AWAY TO STRIP THE PLATING"
                            : "DRAG TOWARDS THE CASTLE TO SHOVE",
                    16f, HEAVY, true);
        }
    }

    /** A boss whose regalia rectangle is under the pointer, or null. */
    private Boss regaliaUnder(com.mymmer.castledefense.game.RunWorld run, float px,
                              float py) {
        com.badlogic.gdx.utils.Array<Boss> live = run.bossRegistry().liveBosses();
        for (int i = 0; i < live.size; i++) {
            Boss b = live.get(i);
            if (b.regaliaCovers(px, py)) {
                return b;
            }
        }
        return null;
    }

    /** A strippable or shovable heavy under the pointer, or null. */
    private Enemy strippableUnder(com.mymmer.castledefense.game.RunWorld run,
                                  float px, float py) {
        for (int i = 0; i < run.horde().size(); i++) {
            Enemy e = run.horde().get(i);
            if (e == null || !e.alive() || !(e.strippable() || e.shovable())) {
                continue;
            }
            if (Math.abs(e.x() - px) <= e.width() / 2f
                    && Math.abs(e.y() - py) <= e.height() / 2f) {
                return e;
            }
        }
        return null;
    }
}
