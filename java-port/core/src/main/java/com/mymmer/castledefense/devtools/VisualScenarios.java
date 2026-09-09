package com.mymmer.castledefense.devtools;

import com.mymmer.castledefense.boss.BossType;
import com.mymmer.castledefense.defence.TowerType;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.RunWorld;
import com.mymmer.castledefense.skill.SkillId;

/**
 * Controlled game states for looking at, without playing to reach them.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Some of the most interesting things to look at are the hardest to reach: two
 * bosses at once, a Siege Ram with one plate left, a Lich Lord with his staff on
 * the ground and his ward up. Reaching them by playing takes minutes and lands
 * somewhere slightly different every time, which makes "did that change?"
 * unanswerable.
 *
 * <h2>Real objects, never a parallel implementation</h2>
 *
 * <p>Every scenario here <b>plays the real game</b>: it spawns real enemies
 * through {@code RunWorld}, buys real towers through the real shop, and strips
 * real armour by applying real damage. There is no fake enemy, no stub boss and
 * no second rendering path. That is the whole discipline — a harness that built
 * its own objects would be showing something the game cannot produce, which is
 * worse than showing nothing.
 *
 * <p>Development only. Nothing in the shipped game calls this; the desktop
 * launcher does, through {@code --scenario}.
 */
public final class VisualScenarios {

    /** The scenarios, by the name {@code --scenario} takes. */
    public static final String[] NAMES = {
        "all-enemies", "all-bosses", "all-towers", "armour", "regalia",
        "dragon-breath", "lich-ward", "fire-zone", "tornado", "storm",
        "projectiles", "particles", "endless-late", "mixed-wave", "lightning",
        "prisoner-full", "prisoner-hurt", "prisoner-dying",
    };

    private VisualScenarios() {
    }

    /**
     * Builds one scenario into a live run.
     *
     * @return false when the name is not one of {@link #NAMES}
     */
    public static boolean build(RunWorld run, String name) {
        if (run == null || name == null) {
            return false;
        }
        switch (name) {
            case "all-enemies":   allEnemies(run); return true;
            case "lightning":     lightning(run); return true;
            case "prisoner-full":   prisoner(run, 1.0f, false); return true;
            case "prisoner-hurt":   prisoner(run, 0.55f, true); return true;
            case "prisoner-dying":  prisoner(run, 0.05f, true); return true;
            case "all-bosses":    allBosses(run); return true;
            case "all-towers":    allTowers(run); return true;
            case "armour":        armour(run); return true;
            case "regalia":       regalia(run); return true;
            case "dragon-breath": dragonBreath(run); return true;
            case "lich-ward":     lichWard(run); return true;
            case "fire-zone":     fireZone(run); return true;
            case "tornado":       tornado(run); return true;
            case "storm":         storm(run); return true;
            case "projectiles":   projectiles(run); return true;
            case "particles":     particles(run); return true;
            case "endless-late":  endlessLate(run); return true;
            case "mixed-wave":    mixedWave(run); return true;
            default:              return false;
        }
    }

    // ========================================================================
    //  Rosters
    // ========================================================================

    /** One of every ordinary unit, spread across the field. */
    private static void allEnemies(RunWorld run) {
        EnemyType[] types = EnemyType.values();
        float step = 980f / Math.max(1, types.length);
        for (int i = 0; i < types.length; i++) {
            Enemy e = run.spawnEnemy(types[i], 6);
            if (e != null) {
                e.setX(320f + i * step);
            }
        }
    }

    /** All three bosses on the field together. */
    private static void allBosses(RunWorld run) {
        BossType[] types = BossType.values();
        for (int i = 0; i < types.length; i++) {
            Enemy b = run.summonBoss(types[i], 12);
            if (b != null) {
                b.setX(460f + i * 280f);
            }
        }
    }

    /** Every tower type on the wall, at a level that shows its upgrades. */
    private static void allTowers(RunWorld run) {
        run.session().addGold(100000);
        for (TowerType type : TowerType.values()) {
            run.shop().buy(type.id());
            run.shop().buy(type.id());
        }
        //  Reinforced walls make more slots, and change the keep's whole look.
        for (int i = 0; i < 3; i++) {
            run.shop().buy("wall");
        }
    }

    // ========================================================================
    //  States that are hard to reach by playing
    // ========================================================================

    /**
     * Three Siege Rams: fully plated, half stripped, bare.
     *
     * <p>The plates come off by applying the real strip mechanic, so what is
     * shown is a genuinely stripped Ram rather than a picture of one.
     */
    private static void armour(RunWorld run) {
        for (int i = 0; i < 3; i++) {
            Enemy ram = run.spawnEnemy(EnemyType.SIEGE_RAM, 10);
            if (ram == null) {
                continue;
            }
            ram.setX(420f + i * 300f);
            //  Real stripping, through the real mechanic: applyStrip is what
            //  the cursor calls, so a plate here comes off the way one does in
            //  play.  STRIP_DISTANCE per plate.
            for (int strip = 0; strip < i; strip++) {
                ram.applyStrip(com.mymmer.castledefense.config.GameConfig
                        .STRIP_DISTANCE + 1f);
            }
        }
    }

    /** A Troll King with his crown off, and the crown on the ground. */
    private static void regalia(RunWorld run) {
        Enemy king = run.summonBoss(BossType.TROLL_KING, 8);
        if (king != null) {
            king.setX(520f);
        }
        Enemy lich = run.summonBoss(BossType.LICH_LORD, 15);
        if (lich != null) {
            lich.setX(900f);
        }
        //  Through detachRegalia, which is the method the player's drag calls:
        //  the crown really leaves the boss and a real DroppedItem appears.
        for (int i = 0; i < run.bossRegistry().liveBosses().size; i++) {
            run.bossRegistry().liveBosses().get(i).detachRegalia();
        }
    }

    private static void dragonBreath(RunWorld run) {
        Enemy dragon = run.summonBoss(BossType.DRAGON, 10);
        if (dragon != null) {
            dragon.setX(760f);
        }
        for (int i = 0; i < 6; i++) {
            Enemy e = run.spawnEnemy(EnemyType.FOOT_SOLDIER, 10);
            if (e != null) {
                e.setX(400f + i * 60f);
            }
        }
        //  No cheat: step until the Dragon's own breath timer fires, so what
        //  is shown is the real ability rather than a pose.
        for (int i = 0; i < 900 && !anyDragonBreathing(run); i++) {
            run.step();
        }
    }

    private static void lichWard(RunWorld run) {
        Enemy lich = run.summonBoss(BossType.LICH_LORD, 15);
        if (lich != null) {
            lich.setX(820f);
        }
        for (int i = 0; i < 8; i++) {
            run.spawnEnemy(EnemyType.SKELETON, 15);
        }
    }

    // ========================================================================
    //  Skill and weather effects
    // ========================================================================

    private static void fireZone(RunWorld run) {
        unlockSkills(run);
        run.skills().castAt(SkillId.METEOR, 700f, 200f);
        mixedWave(run);
    }

    private static void tornado(RunWorld run) {
        unlockSkills(run);
        run.skills().castAt(SkillId.TORNADO, 620f, 160f);
        mixedWave(run);
        steps(run, 90);         // let it pick some mobs up
    }

    /**
     * A storm, mobs in the air, and bolts coming down.
     *
     * <p>Both of the source's lightning paths at once: three mobs lifted above
     * {@code STORM_CEILING} and struck, and a Lightning Strike cast into the
     * crowd. Every strike goes through the real mechanic, so the damage, the
     * cooldowns and the readouts are the game's own -- this only arranges for
     * them to happen while somebody is looking.
     *
     * <p>It exists because the bolts, the sprays and the "ZAP" readouts were
     * missing entirely and no scenario would have shown it.
     */
    private static void lightning(RunWorld run) {
        for (int i = 0; i < 400 && !run.weather().storm(); i++) {
            run.weather().roll();
        }
        mixedWave(run);
        //  Three in the storm ceiling, struck where they hang.
        for (int i = 0; i < 3; i++) {
            Enemy e = run.spawnEnemy(EnemyType.SCOUT, 4);
            if (e == null) {
                continue;
            }
            e.setX(420f + i * 260f);
            e.setY(com.mymmer.castledefense.config.GameConfig.STORM_CEILING - 12f);
            run.weather().strike(e);
        }
        //  ...and the skill, whose bolts last longest.
        while (run.skills().unlockedCount() == 0) {
            run.skills().unlockNext();
        }
        run.skills().castAt(com.mymmer.castledefense.skill.SkillId.LIGHTNING,
                760f, com.mymmer.castledefense.config.GameConfig.GROUND_Y);
    }

    /**
     * A Necromancer in the Outpost's cage, at a chosen fraction of his pool.
     *
     * <p>The cage, the prisoner and his health bar were absent from this port
     * entirely, so no scenario would have shown any of it. Three of these exist
     * because the bar changes colour below a third and the "UNDER FIRE" line
     * only appears while he is being shot at.
     *
     * <p>The capture goes through the real {@code trap}, and the damage through
     * the real {@code hurtPrisoner}, so the state on screen is the state the
     * game would actually be in.
     */
    private static void prisoner(RunWorld run, float fraction, boolean underFire) {
        run.session().addGold(100000);
        run.shop().buy("outpost");
        Enemy necro = run.spawnEnemy(EnemyType.NECROMANCER, 6);
        if (!(necro instanceof com.mymmer.castledefense.defence.Trappable)
                || !run.outpost().trap(
                        (com.mymmer.castledefense.defence.Trappable) necro)) {
            return;
        }
        float pool = run.outpost().prisonerMax();
        float wanted = pool * Math.max(0f, Math.min(1f, fraction));
        if (wanted < pool) {
            run.outpost().hurtPrisoner(pool - wanted);
        }
        if (!underFire) {
            //  Let the recently-hit flash lapse so only the bar shows.
            for (int i = 0; i < 90; i++) {
                run.outpost().updatePrisoner(1d / 60d);
            }
        }
        mixedWave(run);
    }

    private static void storm(RunWorld run) {
        //  Weather.roll() is what a wave start calls; roll until it breaks.
        for (int i = 0; i < 400 && !run.weather().storm(); i++) {
            run.weather().roll();
        }
        mixedWave(run);
    }

    private static void projectiles(RunWorld run) {
        allTowers(run);
        mixedWave(run);
        steps(run, 120);        // let the towers get shots into the air
    }

    private static void particles(RunWorld run) {
        //  Kill a crowd at once: the busiest the particle system ever gets.
        for (int i = 0; i < 30; i++) {
            Enemy e = run.spawnEnemy(EnemyType.VOLATILE, 8);
            if (e != null) {
                e.setX(400f + (i % 10) * 70f);
            }
        }
        killEveryone(run);
        steps(run, 2);
    }

    // ========================================================================
    //  Scenes
    // ========================================================================

    /** A busy, ordinary wave: the picture most of the game looks like. */
    private static void mixedWave(RunWorld run) {
        EnemyType[] roster = {EnemyType.SCOUT, EnemyType.FOOT_SOLDIER,
            EnemyType.SHIELD_BEARER, EnemyType.BERZERKER, EnemyType.GARGOYLE,
            EnemyType.SKELETON, EnemyType.ASSASSIN};
        for (int i = 0; i < 22; i++) {
            Enemy e = run.spawnEnemy(roster[i % roster.length], 8);
            if (e != null) {
                e.setX(340f + (i * 43f) % 940f);
            }
        }
    }

    /** A late Endless tier: tinted mobs, two bosses, a full wall. */
    private static void endlessLate(RunWorld run) {
        allTowers(run);
        for (int i = 0; i < 26; i++) {
            Enemy e = run.spawnEnemy(EnemyType.values()[i % EnemyType.values().length],
                    40);
            if (e != null) {
                e.setX(300f + (i * 39f) % 980f);
            }
        }
        Enemy a = run.summonBoss(BossType.TROLL_KING, 40);
        Enemy b = run.summonBoss(BossType.DRAGON, 40);
        if (a != null) {
            a.setX(560f);
        }
        if (b != null) {
            b.setX(940f);
        }
    }

    // --- small helpers, all built on the real API ---------------------------

    private static void steps(RunWorld run, int n) {
        for (int i = 0; i < n; i++) {
            run.step();
        }
    }

    private static boolean anyDragonBreathing(RunWorld run) {
        for (int i = 0; i < run.bossRegistry().liveBosses().size; i++) {
            if (run.bossRegistry().liveBosses().get(i).venting()) {
                return true;
            }
        }
        return false;
    }

    /** Kills the whole horde through the real death path, payouts and all. */
    private static void killEveryone(RunWorld run) {
        try (com.mymmer.castledefense.entity.EntityList<Enemy>.Snapshot snap
                = run.horde().beginSnapshot()) {
            for (int i = 0; i < snap.size(); i++) {
                Enemy e = snap.get(i);
                if (e != null && e.alive()) {
                    e.die();
                }
            }
        }
    }

    private static void unlockSkills(RunWorld run) {
        while (run.skills().unlockedCount() < SkillId.values().length) {
            if (run.skills().unlockNext() == null) {
                break;
            }
        }
    }
}
