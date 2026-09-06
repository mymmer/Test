package com.mymmer.castledefense.render;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.entity.EntityList;
import java.util.Comparator;

/**
 * The painter order, transcribed from {@code Game.draw}.
 *
 * <h2>The source order</h2>
 *
 * <pre>
 *   s.blit(self.bg)                   background
 *   self.outpost.draw(s)              background scenery, behind the fight
 *   self.castle.draw(s)               (its own draw paints spikes and towers)
 *   self.barricade.draw(s)
 *   sorted(self.enemies, key=lambda e: (0 if e.flying else 1, e.depth, e.x))
 *   self.allies
 *   self.items
 *   self.fire_zones
 *   self.tornados
 *   self.projectiles
 *   self.effects.draw(s)              particles, then floating text
 *   self.draw_weather(s)
 *   self.skills.draw(s)               PLAYING or PAUSED only
 *   self.draw_horn(s)                 PLAYING or PAUSED only
 *   self.draw_grab_cursor(s)          PLAYING only
 *   -- everything above is the world surface; it is blitted with the shake --
 *   self.draw_hud(self.screen)        and the menus: never shaken
 * </pre>
 *
 * <p>Layers are not merged or reordered to save draw calls. The order is the
 * contract; batching lives inside a layer, never across two.
 *
 * <h2>The enemy comparator</h2>
 *
 * <p>{@code (0 if e.flying else 1, e.depth, e.x)}: flyers first — that is,
 * <b>behind</b> everything on the ground — then ground units back-to-front by
 * {@code depth}, then by {@code x}. Two subtleties that are easy to lose:
 *
 * <ul>
 *   <li>{@code depth} is a per-enemy random offset in [-16, 16] fixed at spawn,
 *       not a screen coordinate. It is what stops a crowd from looking like a
 *       single line, and sorting by anything else changes the look of every
 *       wave.</li>
 *   <li>Ties break on {@code x}, ascending, which puts the enemy nearest the
 *       castle behind the one behind it. Python's {@code sorted} is stable, so
 *       a genuine three-way tie falls back on insertion order — and this
 *       comparator is total, so it reaches the same answer.</li>
 * </ul>
 *
 * <h2>It never touches the gameplay list</h2>
 *
 * <p>{@link #sortedEnemies} copies into a buffer owned by this class and sorts
 * <b>that</b>. The authoritative {@link EntityList} keeps its insertion order,
 * which several gameplay rules depend on — Python's own {@code sorted} likewise
 * returns a new list rather than sorting in place. A {@code .sort()} on the live
 * roster would be a rendering concern silently rewriting gameplay, and
 * {@code RenderPurityTest.renderDoesNotReorderTheHorde} fails if it ever does.
 *
 * <p>The buffer is reused between frames, so the per-frame cost is a copy rather
 * than an allocation.
 */
public final class DrawOrder {

    /**
     * The world layers, in the order {@code Game.draw} paints them.
     *
     * <p>Named so the draw-order test can assert against something better than a
     * list of integers, and so a new layer has to be given a position rather
     * than appearing wherever it was convenient.
     */
    public enum Layer {
        BACKGROUND,
        OUTPOST,
        CASTLE,
        BARRICADE,
        ENEMIES,
        ALLIES,
        ITEMS,
        FIRE_ZONES,
        TORNADOS,
        PROJECTILES,
        EFFECTS,
        WEATHER,
        SKILL_BAR,
        HORN,
        GRAB_CURSOR;

        /** The layers, in painter order — first drawn is furthest back. */
        public static final Layer[] ORDER = values();
    }

    /**
     * {@code (0 if flying else 1, depth, x)}.
     *
     * <p>Total, so the sort is deterministic even where the source would have
     * fallen through to list order: the final tie-break is the entity's uid,
     * which is monotonic with insertion.
     */
    public static final Comparator<Enemy> ENEMY_ORDER = new Comparator<Enemy>() {
        @Override
        public int compare(Enemy a, Enemy b) {
            int fa = a.flying() ? 0 : 1;
            int fb = b.flying() ? 0 : 1;
            if (fa != fb) {
                return fa - fb;
            }
            int d = Float.compare(a.depth(), b.depth());
            if (d != 0) {
                return d;
            }
            int x = Float.compare(a.x(), b.x());
            if (x != 0) {
                return x;
            }
            return Long.compare(a.uid(), b.uid());
        }
    };

    /** Reused between frames; never handed out for keeping. */
    private final Array<Enemy> buffer = new Array<>(false, 256, Enemy.class);

    /**
     * The horde in painter order, in a buffer this class owns.
     *
     * <p>The returned array is valid until the next call. Dead entities are
     * skipped here rather than in each painter, so "what is drawn" is decided in
     * exactly one place.
     */
    public Array<Enemy> sortedEnemies(EntityList<Enemy> horde) {
        buffer.clear();
        for (int i = 0; i < horde.size(); i++) {
            Enemy e = horde.get(i);
            if (e != null && e.alive()) {
                buffer.add(e);
            }
        }
        buffer.sort(ENEMY_ORDER);
        return buffer;
    }

    /** How many enemies the last sort produced, for the debug overlay. */
    public int lastCount() {
        return buffer.size;
    }
}
