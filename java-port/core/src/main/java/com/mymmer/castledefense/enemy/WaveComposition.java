package com.mymmer.castledefense.enemy;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.mymmer.castledefense.util.Rng;

/**
 * What a wave is made of — pure composition, no pacing.
 *
 * <p>Deliberately separate from spawning. This answers "wave 12 contains these
 * things, in this order"; the Classic and Endless directors in Phase 8 decide
 * <em>when</em> each one walks on, how the intervals shorten, and what happens
 * between waves. Keeping them apart means the composition can be tested with a
 * seed and nothing else.
 *
 * <h2>Bosses</h2>
 *
 * <p>Boss placement is composed here in full — waves 5, 10, 15, then every fifth
 * on rotation, plus a second older boss from wave 20 — but a boss entry is a
 * {@link SpawnSpec} carrying a stable id string. No boss class exists until
 * Phase 7, and none is faked.
 */
public final class WaveComposition {

    /** Treasure Goblins are a bonus roll, not part of the wave budget. */
    public static final int GOBLIN_FROM_WAVE = 4;
    public static final float GOBLIN_CHANCE = 0.55f;

    /** The three bosses, by stable id, in rotation order. Phase 7 implements them. */
    public static final String[] BOSS_ROTATION = {"troll_king", "dragon", "lich_lord"};

    /** Hard caps from the source: the picker never exceeds either. */
    private static final int MAX_PICKS = 70;
    private static final int MAX_GUARD = 500;

    private final EnemyTable table;

    public WaveComposition(EnemyTable table) {
        if (table == null) {
            throw new IllegalArgumentException("table must not be null");
        }
        this.table = table;
    }

    /**
     * Which boss belongs on a wave, or null.
     *
     * <p>Bosses at 5 / 10 / 15, then every fifth wave on rotation.
     */
    public String bossForWave(int wave) {
        if (wave <= 0 || wave % 5 != 0) {
            return null;
        }
        int idx = (wave / 5) - 1;
        return idx < 3 ? BOSS_ROTATION[idx] : BOSS_ROTATION[idx % 3];
    }

    /** Every type available on a wave, in unlock-table order. */
    public Array<EnemyTable.UnlockEntry> unlockedTypes(int wave) {
        Array<EnemyTable.UnlockEntry> out = new Array<>(false, table.unlocks().size);
        for (EnemyTable.UnlockEntry u : table.unlocks()) {
            if (wave >= u.firstWave) {
                out.add(u);
            }
        }
        return out;
    }

    /** The types that appear for the first time on this exact wave. */
    public Array<EnemyType> newlyUnlocked(int wave) {
        Array<EnemyType> out = new Array<>(false, 4);
        for (EnemyTable.UnlockEntry u : table.unlocks()) {
            if (u.firstWave == wave) {
                out.add(u.type);
            }
        }
        return out;
    }

    /**
     * Composes one wave.
     *
     * <p>The order of operations is Python's and all of it is observable:
     *
     * <ol>
     *   <li>Guarantee a couple of any newly introduced type, so the player
     *       actually meets it — <b>one</b> Siege Ram rather than two, because two
     *       at once on wave 6 is not a introduction, it is a wall. Each guaranteed
     *       pick is charged against the budget.</li>
     *   <li>Spend the remaining budget ({@code 6 + wave*3.1}) on weighted random
     *       picks. The pick weight is the <b>reciprocal</b> of the budget cost, so
     *       cheap units come up far more often <em>and</em> eat less budget.</li>
     *   <li>Shuffle, so the guaranteed picks are not all at the front.</li>
     *   <li>Roll for a Treasure Goblin and insert it at a random index.</li>
     *   <li>Insert the boss just behind the vanguard, at index 3, and from wave
     *       20 append a second, older boss at the end.</li>
     * </ol>
     *
     * @param rng the gameplay stream; a seeded run composes identical waves
     */
    public Array<SpawnSpec> build(int wave, Rng rng) {
        Array<EnemyTable.UnlockEntry> pool = unlockedTypes(wave);
        if (pool.size == 0) {
            //  before wave 1 nothing is unlocked; Python falls back to Scouts
            pool = new Array<>(false, 1);
            for (EnemyTable.UnlockEntry u : table.unlocks()) {
                if (u.type == EnemyType.SCOUT) {
                    pool.add(u);
                    break;
                }
            }
        }

        Array<SpawnSpec> picks = new Array<>(false, 32);
        float budget = 6f + wave * 3.1f;

        for (EnemyType type : newlyUnlocked(wave)) {
            int count = type == EnemyType.SIEGE_RAM ? 1 : 2;
            for (int i = 0; i < count; i++) {
                picks.add(SpawnSpec.of(type));
                budget -= costOf(pool, type);
            }
        }

        FloatArray weights = new FloatArray(pool.size);
        for (EnemyTable.UnlockEntry u : pool) {
            weights.add(1f / u.weight);     // cheap units appear more often
        }

        int guard = 0;
        while (budget > 0f && picks.size < MAX_PICKS && guard < MAX_GUARD) {
            guard++;
            EnemyTable.UnlockEntry chosen = weightedPick(pool, weights, rng);
            picks.add(SpawnSpec.of(chosen.type));
            budget -= chosen.weight;
        }

        shuffle(picks, rng);

        if (wave >= GOBLIN_FROM_WAVE && rng.game().nextFloat() < GOBLIN_CHANCE) {
            int at = rng.rangeInclusive(0, Math.max(0, picks.size - 1));
            picks.insert(Math.min(at, picks.size), SpawnSpec.of(EnemyType.TREASURE_GOBLIN));
        }

        String boss = bossForWave(wave);
        if (boss != null) {
            //  the boss walks in a little after the vanguard
            picks.insert(Math.min(picks.size, 3), SpawnSpec.boss(boss));
            if (wave >= 20) {
                picks.add(SpawnSpec.boss(BOSS_ROTATION[(wave / 5) % 3]));
            }
        }
        return picks;
    }

    private static float costOf(Array<EnemyTable.UnlockEntry> pool, EnemyType type) {
        for (EnemyTable.UnlockEntry u : pool) {
            if (u.type == type) {
                return u.weight;
            }
        }
        return 0f;
    }

    /**
     * Python's {@code random.choices(classes, weights, k=1)}: a cumulative-weight
     * draw against one uniform sample.
     */
    private static EnemyTable.UnlockEntry weightedPick(Array<EnemyTable.UnlockEntry> pool,
                                                       FloatArray weights, Rng rng) {
        float total = 0f;
        for (int i = 0; i < weights.size; i++) {
            total += weights.get(i);
        }
        float roll = rng.game().nextFloat() * total;
        float acc = 0f;
        for (int i = 0; i < pool.size; i++) {
            acc += weights.get(i);
            if (roll < acc) {
                return pool.get(i);
            }
        }
        return pool.get(pool.size - 1);     // float slop at the very top
    }

    /** Fisher-Yates on the gameplay stream. */
    private static void shuffle(Array<SpawnSpec> list, Rng rng) {
        for (int i = list.size - 1; i > 0; i--) {
            int j = rng.game().nextInt(i + 1);
            SpawnSpec tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }
}
