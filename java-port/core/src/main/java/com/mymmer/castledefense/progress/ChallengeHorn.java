package com.mymmer.castledefense.progress;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.defence.CombatModifiers;
import com.mymmer.castledefense.enemy.EnemyTable;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.enemy.SpawnSpec;
import com.mymmer.castledefense.util.Rng;

/**
 * The Challenge Horn: taunt the horde for better pay.
 *
 * <p>One mechanic, two behaviours, and the difference between them is a quirk
 * worth stating plainly because it looks like a bug:
 *
 * <table>
 *   <tr><th></th><th>Classic</th><th>Endless</th></tr>
 *   <tr><td>Re-armed</td><td>every {@code start_wave}</td>
 *       <td><b>only in {@code begin_endless}</b></td></tr>
 *   <tr><td>Effectively</td><td>once per wave</td><td>once per <b>run</b></td></tr>
 *   <tr><td>Calls in</td><td>the rest of the wave queue, at once</td>
 *       <td>{@code ENDLESS_HORN_RUSH} = 12 fresh mobs</td></tr>
 *   <tr><td>On Hard</td><td>the chaff in the queue is swapped for elites,
 *       head-count unchanged</td>
 *       <td>a pack of {@code HARD_HORN_RUSH} = 10 elites instead</td></tr>
 * </table>
 *
 * <p><b>Do not "fix" the Endless case into once-per-tier.</b> An Endless run
 * gets one horn however long it lasts, and the reward bonus it turns on lasts
 * the rest of the run with it. That is the source's behaviour and it is listed
 * in {@code PORT_ANALYSIS.md} §13.
 *
 * <h2>Hard packs</h2>
 *
 * <p>{@code HARD_HORN_UNITS} is a weighted roster of heavies and detonators, and
 * a Hard pack is rolled as if the run were {@code HARD_HORN_TIER_BONUS} = 3
 * tiers deeper than it is — so the units arrive scaled up, not as free gold. It
 * is <b>not</b> folded into ordinary wave composition: the source handles it
 * specially and so does this.
 *
 * <p>The elite roster is "every {@code HARD_HORN_UNITS} class unlocked at this
 * wave", and when nothing heavy has unlocked yet it falls back to the
 * <b>last two</b> unlocked classes rather than to Scouts — early Hard horns are
 * still meant to hurt.
 */
public final class ChallengeHorn {

    /** The Hard roster and its weights — {@code main.py:262 HARD_HORN_UNITS}. */
    private static final EnemyType[] HARD_UNITS = {
        EnemyType.SIEGE_RAM,
        EnemyType.VOLATILE,
        EnemyType.BERZERKER,
        EnemyType.SHIELD_BEARER,
        EnemyType.GARGOYLE,
        EnemyType.NECROMANCER,
    };
    private static final float[] HARD_WEIGHTS = {3.0f, 3.0f, 1.6f, 1.4f, 1.2f, 0.8f};

    /** What the Classic horn upgrades away from — {@code HORN_CHAFF}. */
    private static final EnemyType[] CHAFF = {EnemyType.SCOUT, EnemyType.FOOT_SOLDIER};

    private ChallengeHorn() {
    }

    // ------------------------------------------------------------------ Classic

    /**
     * Classic: the rest of the wave charges in at once.
     *
     * <p>Refuses when the horn is spent or the queue is already empty —
     * "nothing left to call in" — and in that second case the horn stays
     * <b>armed</b>, which is the source's behaviour: a wasted click is not a
     * wasted horn.
     *
     * <p>The whole pack is built before anything is spawned, so the roster is
     * extended exactly once however many mobs are involved.
     */
    static boolean blowClassic(DirectorContext ctx, CombatModifiers mods,
                               Array<SpawnSpec> queue) {
        RunSession s = ctx.session();
        if (s.hornUsed() || queue.size == 0) {
            return false;
        }
        int pending = queue.size;
        boolean elite = ctx.eliteHorn();

        Array<SpawnSpec> pack = new Array<>(false, pending);
        if (elite) {
            upgradeQueue(ctx, queue, pack);
        } else {
            pack.addAll(queue);
        }
        queue.clear();

        int wave = s.wave();
        for (SpawnSpec spec : pack) {
            if (spec.isBoss()) {
                //  a boss still in the queue answers the horn like anything
                //  else; the director resolves the id and applies the headstart
                summonQueuedBoss(ctx, spec.bossId);
            } else {
                ctx.spawnEnemy(spec.type, wave);
            }
        }
        finish(ctx, pending, elite);
        return true;
    }

    /**
     * Swaps chaff in the remaining queue for elites, keeping the head-count —
     * {@code main.py:1364 upgrade_horn_queue}.
     *
     * <p>At most {@code max(1, len // 2)} entries are swapped, so a long queue
     * is never wholly replaced. Order is preserved: each entry is either kept or
     * substituted in place.
     */
    private static void upgradeQueue(DirectorContext ctx, Array<SpawnSpec> queue,
                                     Array<SpawnSpec> out) {
        Array<EnemyType> roster = eliteRoster(ctx);
        FloatArray weights = rosterWeights(roster);
        int cap = Math.max(1, queue.size / 2);
        int swapped = 0;
        for (SpawnSpec spec : queue) {
            if (!spec.isBoss() && isChaff(spec.type) && swapped < cap) {
                swapped++;
                out.add(SpawnSpec.of(pick(roster, weights, ctx.rng())));
            } else {
                out.add(spec);
            }
        }
    }

    private static void summonQueuedBoss(DirectorContext ctx, String bossId) {
        com.mymmer.castledefense.boss.BossType type =
                com.mymmer.castledefense.boss.BossType.byId(bossId, null);
        if (type == null) {
            return;
        }
        int wave = ctx.session().wave();
        int effective = Math.max(1, wave + Math.round(wave * ctx.bossHeadstart()));
        ctx.summonBoss(type, effective);
    }

    // ------------------------------------------------------------------ Endless

    /**
     * Endless: a rush arrives out of nowhere.
     *
     * <p>{@code ENDLESS_HORN_RUSH} ordinary mobs rolled from the current tier,
     * or {@code HARD_HORN_RUSH} elites on Hard. Unlike Classic there is no queue
     * to empty, so the only refusal is "already used" — and on Endless that
     * means for the whole run.
     */
    static boolean blowEndless(DirectorContext ctx, CombatModifiers mods,
                               EnemyTable table, EndlessDirector director) {
        RunSession s = ctx.session();
        if (s.hornUsed()) {
            return false;
        }
        boolean elite = ctx.eliteHorn();
        int count = elite ? Tuning.HARD_HORN_RUSH : Tuning.ENDLESS_HORN_RUSH;

        if (elite) {
            spawnElitePack(ctx, count);
        } else {
            int tier = s.wave();
            //  roll the whole pack first, then spawn: the roster is extended
            //  exactly once, as in the source
            Array<EnemyType> rolled = new Array<>(false, count);
            for (int i = 0; i < count; i++) {
                rolled.add(director.rollMob());
            }
            for (EnemyType type : rolled) {
                ctx.spawnEnemy(type, tier);
            }
        }
        finish(ctx, count, elite);
        return true;
    }

    /**
     * A Hard elite pack — {@code main.py:1355 elite_horn_pack}.
     *
     * <p>Rolled at {@code wave + HARD_HORN_TIER_BONUS}, so the units are scaled
     * as though the run were three tiers deeper.
     */
    private static void spawnElitePack(DirectorContext ctx, int count) {
        Array<EnemyType> roster = eliteRoster(ctx);
        FloatArray weights = rosterWeights(roster);
        int wave = ctx.session().wave() + Tuning.HARD_HORN_TIER_BONUS;
        Array<EnemyType> rolled = new Array<>(false, count);
        for (int i = 0; i < count; i++) {
            rolled.add(pick(roster, weights, ctx.rng()));
        }
        for (EnemyType type : rolled) {
            ctx.spawnEnemy(type, wave);
        }
    }

    // ------------------------------------------------------------------ shared

    private static void finish(DirectorContext ctx, int count, boolean elite) {
        RunSession s = ctx.session();
        s.consumeHorn();
        ctx.shake().add(10f);
        ctx.banners().post(elite ? Announcements.Id.HORN_ELITES
                : Announcements.Id.HORN_CALLED, count, 3.2);
        if (ctx.trace().isEnabled()) {
            ctx.trace().event(TraceEvent.HORN_USED, ctx.step(), 0L, count,
                    s.hornBonus(), elite ? "elite" : "rush");
        }
    }

    /**
     * The elite classes available at this wave — {@code main.py:1343
     * elite_roster}.
     *
     * <p>Falls back to the <b>last two unlocked</b> classes when nothing heavy
     * has opened yet, not to Scouts.
     */
    static Array<EnemyType> eliteRoster(DirectorContext ctx) {
        int wave = ctx.session().wave();
        Array<EnemyType> avail = new Array<>(false, HARD_UNITS.length);
        for (EnemyTable.UnlockEntry u : ctx.unlocks()) {
            if (wave >= u.firstWave && indexOfHardUnit(u.type) >= 0) {
                avail.add(u.type);
            }
        }
        if (avail.size > 0) {
            return avail;
        }
        Array<EnemyType> opened = new Array<>(false, 8);
        for (EnemyTable.UnlockEntry u : ctx.unlocks()) {
            if (wave >= u.firstWave) {
                opened.add(u.type);
            }
        }
        Array<EnemyType> out = new Array<>(false, 2);
        for (int i = Math.max(0, opened.size - 2); i < opened.size; i++) {
            out.add(opened.get(i));
        }
        if (out.size == 0) {
            out.add(EnemyType.SCOUT);
        }
        return out;
    }

    private static FloatArray rosterWeights(Array<EnemyType> roster) {
        FloatArray w = new FloatArray(roster.size);
        for (EnemyType t : roster) {
            int idx = indexOfHardUnit(t);
            w.add(idx >= 0 ? HARD_WEIGHTS[idx] : 1.0f);
        }
        return w;
    }

    private static int indexOfHardUnit(EnemyType type) {
        for (int i = 0; i < HARD_UNITS.length; i++) {
            if (HARD_UNITS[i] == type) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isChaff(EnemyType type) {
        for (EnemyType c : CHAFF) {
            if (c == type) {
                return true;
            }
        }
        return false;
    }

    /** Python {@code random.choices(roster, weights, k=1)}. */
    private static EnemyType pick(Array<EnemyType> roster, FloatArray weights, Rng rng) {
        float total = 0f;
        for (int i = 0; i < weights.size; i++) {
            total += weights.get(i);
        }
        float roll = rng.game().nextFloat() * total;
        float running = 0f;
        for (int i = 0; i < roster.size; i++) {
            running += weights.get(i);
            if (roll < running) {
                return roster.get(i);
            }
        }
        return roster.get(roster.size - 1);
    }
}
