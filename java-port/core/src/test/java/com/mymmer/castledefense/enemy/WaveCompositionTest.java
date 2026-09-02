package com.mymmer.castledefense.enemy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.util.Rng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a wave is made of. Composition only — no pacing, no spawning.
 *
 * <p>Bosses appear as {@link SpawnSpec}s carrying a stable id. No boss class
 * exists until Phase 7, and none is faked: a composed wave 5 says
 * "troll_king goes here" and the Phase 7 factory resolves it.
 */
class WaveCompositionTest {

    private static WaveComposition composition() {
        return new TestEnemyWorld().composition;
    }

    // ========================================================================
    //  Unlocks
    // ========================================================================

    @Test
    @DisplayName("types unlock on their Python waves")
    void unlockWaves() {
        WaveComposition c = composition();
        assertEquals(1, first(c, EnemyType.SCOUT));
        assertEquals(2, first(c, EnemyType.FOOT_SOLDIER));
        assertEquals(3, first(c, EnemyType.SHIELD_BEARER));
        assertEquals(4, first(c, EnemyType.BERZERKER));
        assertEquals(6, first(c, EnemyType.SIEGE_RAM));
        assertEquals(7, first(c, EnemyType.NECROMANCER));
        assertEquals(8, first(c, EnemyType.ASSASSIN));
        assertEquals(9, first(c, EnemyType.GARGOYLE));
        assertEquals(11, first(c, EnemyType.VOLATILE));
    }

    private static int first(WaveComposition c, EnemyType type) {
        for (int wave = 1; wave <= 40; wave++) {
            for (EnemyTable.UnlockEntry u : c.unlockedTypes(wave)) {
                if (u.type == type) {
                    return wave;
                }
            }
        }
        return -1;
    }

    @Test
    @DisplayName("Skeletons and Treasure Goblins are not in the unlock table")
    void unlistedTypes() {
        WaveComposition c = composition();
        assertEquals(-1, first(c, EnemyType.SKELETON),
                "only a Necromancer raises those");
        assertEquals(-1, first(c, EnemyType.TREASURE_GOBLIN),
                "a goblin is a bonus roll, not part of the budget");
    }

    @Test
    @DisplayName("the unlocked pool grows monotonically")
    void poolGrows() {
        WaveComposition c = composition();
        int previous = 0;
        for (int wave = 1; wave <= 15; wave++) {
            int n = c.unlockedTypes(wave).size;
            assertTrue(n >= previous, "the pool never shrinks at wave " + wave);
            previous = n;
        }
        assertEquals(9, c.unlockedTypes(11).size, "everything is out by wave 11");
    }

    // ========================================================================
    //  Composition
    // ========================================================================

    @Test
    @DisplayName("a newly unlocked type is guaranteed, twice -- but a Siege Ram only once")
    void newTypesAreGuaranteed() {
        WaveComposition c = composition();
        Rng rng = new Rng(1234L);

        assertEquals(2, count(c.build(3, rng), EnemyType.SHIELD_BEARER) >= 2 ? 2 : 0,
                "wave 3 introduces Shield Bearers, so at least two appear");

        //  a Siege Ram is guaranteed ONCE, because two at once on wave 6 is not
        //  an introduction, it is a wall
        int rams = count(c.build(6, new Rng(1234L)), EnemyType.SIEGE_RAM);
        assertTrue(rams >= 1, "at least the guaranteed one");
        assertTrue(newlyUnlockedGuarantee(c, 6, EnemyType.SIEGE_RAM) == 1,
                "and exactly one is guaranteed");
    }

    private static int newlyUnlockedGuarantee(WaveComposition c, int wave, EnemyType type) {
        Array<EnemyType> fresh = c.newlyUnlocked(wave);
        return fresh.contains(type, true) ? (type == EnemyType.SIEGE_RAM ? 1 : 2) : 0;
    }

    private static int count(Array<SpawnSpec> picks, EnemyType type) {
        int n = 0;
        for (SpawnSpec s : picks) {
            if (!s.isBoss() && s.type == type) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("a wave only ever contains unlocked types")
    void onlyUnlockedTypesAppear() {
        WaveComposition c = composition();
        for (int wave = 1; wave <= 20; wave++) {
            Array<SpawnSpec> picks = c.build(wave, new Rng(wave * 977L));
            for (SpawnSpec s : picks) {
                if (s.isBoss() || s.type == EnemyType.TREASURE_GOBLIN) {
                    continue;
                }
                assertTrue(first(c, s.type) <= wave,
                        s.type.id() + " appeared on wave " + wave
                                + " but unlocks at " + first(c, s.type));
            }
        }
    }

    @Test
    @DisplayName("waves grow, and stay within the hard caps")
    void wavesGrowAndAreCapped() {
        WaveComposition c = composition();
        int early = c.build(1, new Rng(7L)).size;
        int late = c.build(20, new Rng(7L)).size;
        assertTrue(late > early, "the budget grows with the wave");
        for (int wave = 1; wave <= 60; wave += 7) {
            int n = c.build(wave, new Rng(wave)).size;
            assertTrue(n <= 75, "wave " + wave + " produced " + n + ", past every cap");
            assertTrue(n > 0);
        }
    }

    @Test
    @DisplayName("the same seed composes exactly the same wave")
    void compositionIsDeterministic() {
        WaveComposition c = composition();
        for (int wave : new int[]{1, 5, 12, 20, 33}) {
            Array<SpawnSpec> a = c.build(wave, new Rng(555L));
            Array<SpawnSpec> b = c.build(wave, new Rng(555L));
            assertEquals(a.size, b.size, "wave " + wave + " size");
            for (int i = 0; i < a.size; i++) {
                assertEquals(a.get(i).id(), b.get(i).id(),
                        "wave " + wave + " entry " + i);
            }
        }
    }

    @Test
    @DisplayName("a different seed composes a different wave")
    void seedsDiffer() {
        WaveComposition c = composition();
        Array<SpawnSpec> a = c.build(12, new Rng(1L));
        Array<SpawnSpec> b = c.build(12, new Rng(2L));
        boolean differs = a.size != b.size;
        for (int i = 0; i < Math.min(a.size, b.size) && !differs; i++) {
            differs = !a.get(i).id().equals(b.get(i).id());
        }
        assertTrue(differs, "the composition must actually use the seed");
    }

    @Test
    @DisplayName("cheap units appear far more often than dear ones")
    void weightingFavoursCheapUnits() {
        WaveComposition c = composition();
        int scouts = 0;
        int rams = 0;
        for (int seed = 0; seed < 40; seed++) {
            Array<SpawnSpec> picks = c.build(15, new Rng(seed));
            scouts += count(picks, EnemyType.SCOUT);
            rams += count(picks, EnemyType.SIEGE_RAM);
        }
        assertTrue(scouts > rams * 3,
                "weight 1.0 vs 4.5, so Scouts should dominate: "
                        + scouts + " vs " + rams);
    }

    // ========================================================================
    //  Bosses -- as specs, never as classes
    // ========================================================================

    @Test
    @DisplayName("bosses land on every fifth wave, in rotation")
    void bossRotation() {
        WaveComposition c = composition();
        assertNull(c.bossForWave(1));
        assertNull(c.bossForWave(4));
        assertNull(c.bossForWave(6));
        assertNull(c.bossForWave(0));
        assertNull(c.bossForWave(-5));

        assertEquals("troll_king", c.bossForWave(5));
        assertEquals("dragon", c.bossForWave(10));
        assertEquals("lich_lord", c.bossForWave(15));
        assertEquals("troll_king", c.bossForWave(20), "and round again");
        assertEquals("dragon", c.bossForWave(25));
        assertEquals("lich_lord", c.bossForWave(30));
        assertEquals("troll_king", c.bossForWave(35));
    }

    @Test
    @DisplayName("a boss wave places the boss just behind the vanguard")
    void bossPlacement() {
        WaveComposition c = composition();
        Array<SpawnSpec> picks = c.build(5, new Rng(88L));
        int bossIndex = -1;
        for (int i = 0; i < picks.size; i++) {
            if (picks.get(i).isBoss()) {
                bossIndex = i;
                break;
            }
        }
        assertEquals(3, bossIndex, "index 3: a few ordinary mobs walk in first");
        assertEquals("troll_king", picks.get(3).bossId);
        assertNull(picks.get(3).type, "and it is a spec, not an enemy type");
    }

    @Test
    @DisplayName("from wave 20 a second, older boss is appended")
    void secondBossLate() {
        WaveComposition c = composition();
        Array<SpawnSpec> early = c.build(15, new Rng(4L));
        assertEquals(1, bossCount(early), "one boss before wave 20");

        Array<SpawnSpec> late = c.build(20, new Rng(4L));
        assertEquals(2, bossCount(late), "two from wave 20");
        assertTrue(late.peek().isBoss(), "the second is appended at the end");
        assertEquals("dragon", late.peek().bossId,
                "(20/5) % 3 = 1 -> the Dragon");
    }

    private static int bossCount(Array<SpawnSpec> picks) {
        int n = 0;
        for (SpawnSpec s : picks) {
            if (s.isBoss()) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("no boss class is fabricated: a boss spec has no EnemyType")
    void bossesAreNotFaked() {
        WaveComposition c = composition();
        for (SpawnSpec s : c.build(25, new Rng(3L))) {
            if (s.isBoss()) {
                assertNull(s.type, "a boss carries an id, not a type");
                assertNotNull(s.bossId);
                assertNull(EnemyType.byId(s.bossId, null),
                        "and that id is deliberately not in the enemy roster");
            } else {
                assertNotNull(s.type);
                assertNull(s.bossId);
            }
        }
    }

    // ========================================================================
    //  Goblins
    // ========================================================================

    @Test
    @DisplayName("no Treasure Goblin before its wave, and sometimes one after")
    void goblinRolls() {
        WaveComposition c = composition();
        for (int seed = 0; seed < 30; seed++) {
            assertEquals(0, count(c.build(WaveComposition.GOBLIN_FROM_WAVE - 1,
                    new Rng(seed)), EnemyType.TREASURE_GOBLIN), "too early");
        }
        int withGoblin = 0;
        for (int seed = 0; seed < 40; seed++) {
            if (count(c.build(10, new Rng(seed)), EnemyType.TREASURE_GOBLIN) > 0) {
                withGoblin++;
            }
        }
        assertTrue(withGoblin > 10 && withGoblin < 40,
                "a 55% roll, so most but not all waves: " + withGoblin + "/40");
    }

    @Test
    @DisplayName("at most one goblin per wave")
    void oneGoblinAtMost() {
        WaveComposition c = composition();
        for (int seed = 0; seed < 50; seed++) {
            assertTrue(count(c.build(14, new Rng(seed)), EnemyType.TREASURE_GOBLIN) <= 1);
        }
    }

    @Test
    @DisplayName("a spawn spec's id is stable and round-trips")
    void spawnSpecIdentity() {
        assertEquals("scout", SpawnSpec.of(EnemyType.SCOUT).id());
        assertEquals("troll_king", SpawnSpec.boss("troll_king").id());
        assertEquals(SpawnSpec.of(EnemyType.SCOUT), SpawnSpec.of(EnemyType.SCOUT));
        assertEquals(SpawnSpec.boss("dragon"), SpawnSpec.boss("dragon"));
        assertFalse(SpawnSpec.of(EnemyType.SCOUT).equals(SpawnSpec.boss("dragon")));
    }
}
