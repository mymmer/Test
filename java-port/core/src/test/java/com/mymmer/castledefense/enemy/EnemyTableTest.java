package com.mymmer.castledefense.enemy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.JsonSource;
import com.mymmer.castledefense.testsupport.InMemoryJsonSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped enemy data, and the validation that guards it.
 *
 * <p>The first tests read the real {@code assets/data/enemies.json} and check
 * every number against {@code enemies.py}. The rest feed deliberately broken
 * documents in, and each proves the failure happens <b>at load</b>, naming the
 * file and the field — not at wave 26 as a mob that quietly behaves wrongly.
 */
class EnemyTableTest {

    private static EnemyTable shipped() {
        return EnemyTable.load(new EnemyJson());
    }

    @Test
    @DisplayName("the shipped roster matches enemies.py exactly")
    void shippedRoster() {
        EnemyTable t = shipped();

        check(t, EnemyType.SCOUT, 26f, 122f, 5f, 0.8f, 6, 26f, 34f);
        check(t, EnemyType.FOOT_SOLDIER, 62f, 72f, 10f, 1.0f, 11, 26f, 34f);
        check(t, EnemyType.SHIELD_BEARER, 160f, 46f, 12f, 1.4f, 19, 26f, 34f);
        check(t, EnemyType.BERZERKER, 48f, 134f, 24f, 0.7f, 15, 26f, 32f);
        check(t, EnemyType.SIEGE_RAM, 520f, 28f, 58f, 2.2f, 52, 76f, 44f);
        check(t, EnemyType.SKELETON, 20f, 92f, 6f, 0.9f, 2, 18f, 28f);
        check(t, EnemyType.NECROMANCER, 90f, 54f, 14f, 1.1f, 26, 26f, 38f);
        check(t, EnemyType.ASSASSIN, 58f, 96f, 20f, 0.65f, 24, 22f, 30f);
        check(t, EnemyType.GARGOYLE, 78f, 92f, 13f, 0.9f, 26, 30f, 26f);
        check(t, EnemyType.VOLATILE, 46f, 84f, 8f, 1.1f, 16, 26f, 28f);
        check(t, EnemyType.TREASURE_GOBLIN, 70f, 104f, 0f, 1.1f, 140, 24f, 30f);
    }

    private static void check(EnemyTable t, EnemyType type, float hp, float speed,
                              float damage, float rate, int gold, float w, float h) {
        EnemyConfig c = t.config(type);
        String at = type.id();
        assertEquals(hp, c.baseHp, 0f, at + " hp");
        assertEquals(speed, c.baseSpeed, 0f, at + " speed");
        assertEquals(damage, c.baseDamage, 0f, at + " damage");
        assertEquals(rate, c.attackRate, 1e-6f, at + " attack rate");
        assertEquals(gold, c.gold, at + " gold");
        assertEquals(w, c.width, 0f, at + " width");
        assertEquals(h, c.height, 0f, at + " height");
    }

    @Test
    @DisplayName("the shipped flags match enemies.py")
    void shippedFlags() {
        EnemyTable t = shipped();

        //  masses -- these decide what the cursor can lift
        assertEquals(0.8f, t.config(EnemyType.SCOUT).mass, 0f);
        assertEquals(3.0f, t.config(EnemyType.SHIELD_BEARER).mass, 0f);
        assertEquals(9.0f, t.config(EnemyType.SIEGE_RAM).mass, 0f);

        //  armour
        assertEquals(0.60f, t.config(EnemyType.SHIELD_BEARER).armor, 0f);
        assertEquals(0.60f, t.config(EnemyType.SIEGE_RAM).armor, 0f);
        assertEquals(0f, t.config(EnemyType.SCOUT).armor, 0f);

        //  the Shield Bearer is armoured but NOT heavy: no plates, no shove,
        //  no cannon bonus.  Its counter is the throw, not the drag.
        assertFalse(t.config(EnemyType.SHIELD_BEARER).heavy);
        assertFalse(t.config(EnemyType.SHIELD_BEARER).strippable);
        assertEquals(0, t.config(EnemyType.SHIELD_BEARER).armorLayers);

        //  the Siege Ram is the tank
        assertTrue(t.config(EnemyType.SIEGE_RAM).heavy);
        assertTrue(t.config(EnemyType.SIEGE_RAM).strippable);
        assertTrue(t.config(EnemyType.SIEGE_RAM).grabbable);
        assertEquals(3, t.config(EnemyType.SIEGE_RAM).armorLayers);

        //  the flyer
        assertTrue(t.config(EnemyType.GARGOYLE).flying);
        assertEquals(232f, t.config(EnemyType.GARGOYLE).flyY, 0f);
        assertFalse(t.config(EnemyType.SCOUT).flying);

        //  the only trappable unit in the game
        assertTrue(t.config(EnemyType.NECROMANCER).trappable);
        for (EnemyType type : EnemyType.values()) {
            if (type != EnemyType.NECROMANCER) {
                assertFalse(t.config(type).trappable, type.id() + " must not be trappable");
            }
        }
    }

    @Test
    @DisplayName("the shipped endgame tiers match sprites.py")
    void shippedTiers() {
        EnemyTable t = shipped();
        assertEquals(3, t.tierCount());

        EndgameTier bloodied = t.tier(0);
        assertEquals("Bloodied", bloodied.name);
        assertEquals(16, bloodied.firstWave);
        assertEquals(1.35f, bloodied.hpMult, 0f);
        assertEquals(1.22f, bloodied.damageMult, 0f);
        assertEquals(1.05f, bloodied.speedMult, 0f);
        assertEquals(0.42f, bloodied.tintStrength, 0f);

        EndgameTier frost = t.tier(1);
        assertEquals(26, frost.firstWave);
        assertEquals(1.85f, frost.hpMult, 0f);
        assertEquals(1.46f, frost.damageMult, 0f);
        assertEquals(1.10f, frost.speedMult, 0f);

        EndgameTier voidt = t.tier(2);
        assertEquals(36, voidt.firstWave);
        assertEquals(2.60f, voidt.hpMult, 0f);
        assertEquals(1.80f, voidt.damageMult, 0f);
        assertEquals(1.16f, voidt.speedMult, 0f);
    }

    @Test
    @DisplayName("the shipped unlock table matches enemies.py")
    void shippedUnlocks() {
        EnemyTable t = shipped();
        assertEquals(9, t.unlocks().size);
        assertWeight(t, EnemyType.SCOUT, 1, 1.0f);
        assertWeight(t, EnemyType.FOOT_SOLDIER, 2, 1.5f);
        assertWeight(t, EnemyType.SHIELD_BEARER, 3, 2.6f);
        assertWeight(t, EnemyType.BERZERKER, 4, 2.0f);
        assertWeight(t, EnemyType.SIEGE_RAM, 6, 4.5f);
        assertWeight(t, EnemyType.NECROMANCER, 7, 3.2f);
        assertWeight(t, EnemyType.ASSASSIN, 8, 2.8f);
        assertWeight(t, EnemyType.GARGOYLE, 9, 2.8f);
        assertWeight(t, EnemyType.VOLATILE, 11, 2.2f);
    }

    private static void assertWeight(EnemyTable t, EnemyType type, int wave, float weight) {
        for (EnemyTable.UnlockEntry u : t.unlocks()) {
            if (u.type == type) {
                assertEquals(wave, u.firstWave, type.id() + " first wave");
                assertEquals(weight, u.weight, 1e-6f, type.id() + " weight");
                return;
            }
        }
        throw new AssertionError(type.id() + " missing from the unlock table");
    }

    // --- validation ---------------------------------------------------------

    /**
     * A minimal valid enemy object, with one field <b>replaced</b>.
     *
     * <p>Replaced, not appended: a duplicate key in one JSON object is not an
     * override — the reader keeps the first — so appending would produce a valid
     * document and the test would pass for the wrong reason. (It did, until this
     * was fixed. The same trap caught the defence-table tests.)
     *
     * @param override {@code "field": value}, or null for the plain entry
     */
    private static String enemy(String id, String override) {
        String[][] fields = {
                {"id", "\"" + id + "\""},
                {"name", "\"E\""},
                {"baseHp", "10"},
                {"baseSpeed", "10"},
                {"baseDamage", "1"},
                {"attackRate", "1"},
                {"mass", "1"},
                {"width", "10"},
                {"height", "10"},
        };
        String key = null;
        String value = null;
        if (override != null) {
            int colon = override.indexOf(':');
            key = override.substring(0, colon).trim().replace("\"", "");
            value = override.substring(colon + 1).trim();
        }

        StringBuilder sb = new StringBuilder("{");
        boolean replaced = false;
        for (String[] f : fields) {
            if (sb.length() > 1) {
                sb.append(",");
            }
            String v = f[1];
            if (key != null && f[0].equals(key)) {
                v = value;
                replaced = true;
            }
            sb.append(" \"").append(f[0]).append("\": ").append(v);
        }
        if (key != null && !replaced) {
            sb.append(", \"").append(key).append("\": ").append(value);
        }
        return sb.append(" }").toString();
    }

    /** Every type present and valid, with one entry replaced. */
    private static JsonSource roster(String brokenId, String extra) {
        StringBuilder sb = new StringBuilder("{ \"enemies\": [");
        boolean first = true;
        for (EnemyType t : EnemyType.values()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(enemy(t.id(), t.id().equals(brokenId) ? extra : null));
        }
        sb.append("], \"endgameTiers\": [], \"unlocks\": ["
                + "{ \"id\": \"scout\", \"firstWave\": 1, \"weight\": 1 }] }");
        return new InMemoryJsonSource().put(EnemyTable.PATH, sb.toString());
    }

    private static void expectFailure(JsonSource src, String... mustMention) {
        DataException e = assertThrows(DataException.class, () -> EnemyTable.load(src));
        String message = String.valueOf(e.getMessage());
        for (String fragment : mustMention) {
            assertTrue(message.contains(fragment),
                    "the message should name '" + fragment + "': " + message);
        }
    }

    @Test
    @DisplayName("a complete, minimal roster loads")
    void minimalRosterLoads() {
        EnemyTable t = EnemyTable.load(roster(null, null));
        assertEquals(0, t.tierCount());
        assertEquals(1, t.unlocks().size);
    }

    @Test
    @DisplayName("a missing enemy is rejected")
    void missingEnemy() {
        expectFailure(new InMemoryJsonSource().put(EnemyTable.PATH,
                        "{ \"enemies\": [" + enemy("scout", null) + "],"
                                + " \"endgameTiers\": [], \"unlocks\": [] }"),
                "no stat block for enemy");
    }

    @Test
    @DisplayName("a duplicate or unknown enemy id is rejected")
    void badEnemyIds() {
        expectFailure(new InMemoryJsonSource().put(EnemyTable.PATH,
                        "{ \"enemies\": [" + enemy("scout", null) + ","
                                + enemy("scout", null) + "],"
                                + " \"endgameTiers\": [], \"unlocks\": [] }"),
                "duplicate enemy id", "scout");
        expectFailure(new InMemoryJsonSource().put(EnemyTable.PATH,
                        "{ \"enemies\": [" + enemy("wyvern", null) + "],"
                                + " \"endgameTiers\": [], \"unlocks\": [] }"),
                "unknown enemy id", "wyvern", "scout");
    }

    @Test
    @DisplayName("non-positive stats are rejected")
    void badStats() {
        expectFailure(roster("scout", "\"baseHp\": 0"), "baseHp must be > 0");
        expectFailure(roster("scout", "\"baseSpeed\": -1"), "baseSpeed must be > 0");
        expectFailure(roster("scout", "\"mass\": 0"), "mass must be > 0");
        expectFailure(roster("scout", "\"attackRate\": 0"), "attackRate must be > 0");
        expectFailure(roster("scout", "\"baseDamage\": -1"), "baseDamage must not be negative");
        expectFailure(roster("scout", "\"gold\": -5"), "gold must not be negative");
        expectFailure(roster("scout", "\"armor\": 1.5"), "armor");
        expectFailure(roster("scout", "\"width\": 0"), "width");
    }

    @Test
    @DisplayName("armour flags that would silently do nothing are rejected")
    void incoherentArmourFlags() {
        //  Both of these load fine and then quietly never fire: a strippable
        //  unit with no plates can never be stripped, and plating on a unit that
        //  is not heavy never locks anything, because armored() checks HEAVY.
        expectFailure(roster("scout", "\"strippable\": true"),
                "strippable needs armorLayers > 0");
        expectFailure(roster("scout", "\"armorLayers\": 2"),
                "armorLayers only apply to a heavy unit");
        expectFailure(roster("scout", "\"armorLayers\": -1"),
                "armorLayers must not be negative");
    }

    @Test
    @DisplayName("endgame tiers must be in ascending wave order")
    void tiersMustAscend() {
        //  tierIndex() keeps the LAST match, so out of order a wave-36 mob would
        //  come out Bloodied instead of Voidtouched.
        expectFailure(tiers("[{ \"id\": \"b\", \"name\": \"B\", \"firstWave\": 30,"
                        + " \"tint\": \"#FFFFFF\", \"tintStrength\": 0.5,"
                        + " \"hpMult\": 1.2, \"damageMult\": 1.2, \"speedMult\": 1.2 },"
                        + "{ \"id\": \"a\", \"name\": \"A\", \"firstWave\": 20,"
                        + " \"tint\": \"#FFFFFF\", \"tintStrength\": 0.5,"
                        + " \"hpMult\": 1.2, \"damageMult\": 1.2, \"speedMult\": 1.2 }]"),
                "ascending wave order");
    }

    @Test
    @DisplayName("a tier multiplier below 1.0 or a bad tint is rejected")
    void badTiers() {
        expectFailure(tiers("[{ \"id\": \"a\", \"name\": \"A\", \"firstWave\": 16,"
                        + " \"tint\": \"#FFFFFF\", \"tintStrength\": 0.5,"
                        + " \"hpMult\": 0.5, \"damageMult\": 1.2, \"speedMult\": 1.2 }]"),
                "hpMult", "at least 1.0");
        expectFailure(tiers("[{ \"id\": \"a\", \"name\": \"A\", \"firstWave\": 16,"
                        + " \"tint\": \"nope\", \"tintStrength\": 0.5,"
                        + " \"hpMult\": 1.2, \"damageMult\": 1.2, \"speedMult\": 1.2 }]"),
                "tint must be #RRGGBB");
        expectFailure(tiers("[{ \"id\": \"a\", \"name\": \"A\", \"firstWave\": 16,"
                        + " \"tint\": \"#FFFFFF\", \"tintStrength\": 2.0,"
                        + " \"hpMult\": 1.2, \"damageMult\": 1.2, \"speedMult\": 1.2 }]"),
                "tintStrength");
    }

    private static JsonSource tiers(String tierArray) {
        StringBuilder sb = new StringBuilder("{ \"enemies\": [");
        boolean first = true;
        for (EnemyType t : EnemyType.values()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(enemy(t.id(), null));
        }
        sb.append("], \"endgameTiers\": ").append(tierArray)
                .append(", \"unlocks\": [{ \"id\": \"scout\", \"firstWave\": 1,"
                        + " \"weight\": 1 }] }");
        return new InMemoryJsonSource().put(EnemyTable.PATH, sb.toString());
    }

    @Test
    @DisplayName("a bad unlock entry is rejected")
    void badUnlocks() {
        expectFailure(unlocks("[{ \"id\": \"wyvern\", \"firstWave\": 1, \"weight\": 1 }]"),
                "unknown enemy id", "wyvern");
        expectFailure(unlocks("[{ \"id\": \"scout\", \"firstWave\": 1, \"weight\": 1 },"
                        + "{ \"id\": \"scout\", \"firstWave\": 2, \"weight\": 1 }]"),
                "appears twice");
        expectFailure(unlocks("[{ \"id\": \"scout\", \"firstWave\": 0, \"weight\": 1 }]"),
                "firstWave must be at least 1");
        //  the pick weight is 1/weight, so zero divides by zero
        expectFailure(unlocks("[{ \"id\": \"scout\", \"firstWave\": 1, \"weight\": 0 }]"),
                "weight must be > 0");
        expectFailure(unlocks("[]"), "'unlocks' must be a non-empty array");
    }

    private static JsonSource unlocks(String unlockArray) {
        StringBuilder sb = new StringBuilder("{ \"enemies\": [");
        boolean first = true;
        for (EnemyType t : EnemyType.values()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(enemy(t.id(), null));
        }
        sb.append("], \"endgameTiers\": [], \"unlocks\": ")
                .append(unlockArray).append(" }");
        return new InMemoryJsonSource().put(EnemyTable.PATH, sb.toString());
    }

    @Test
    @DisplayName("missing sections are rejected")
    void missingSections() {
        expectFailure(new InMemoryJsonSource().put(EnemyTable.PATH, "{ }"),
                "'enemies' must be a non-empty array");
    }

    @Test
    @DisplayName("enemy ids round-trip by their stable ids")
    void enemyTypeIds() {
        for (EnemyType t : EnemyType.values()) {
            assertEquals(t, EnemyType.byId(t.id(), null), t.id());
        }
        assertEquals(EnemyType.SCOUT, EnemyType.byId("nope", EnemyType.SCOUT));
        assertEquals(EnemyType.SCOUT, EnemyType.byId(null, EnemyType.SCOUT));

        for (EnemyState s : EnemyState.values()) {
            assertEquals(s, EnemyState.byId(s.id(), null), s.id());
        }
        assertTrue(EnemyState.WALK.isOnFoot());
        assertTrue(EnemyState.ATTACK.isOnFoot());
        assertFalse(EnemyState.AIR.isOnFoot());
        assertFalse(EnemyState.GRABBED.isOnFoot());
        assertFalse(EnemyState.TRAPPED.isOnFoot());
    }

    @Test
    @DisplayName("the factory builds the right class for every type")
    void factoryClasses() {
        TestEnemyWorld w = new TestEnemyWorld();
        assertTrue(w.build(EnemyType.SCOUT, 1) instanceof Scout);
        assertTrue(w.build(EnemyType.FOOT_SOLDIER, 1) instanceof FootSoldier);
        assertTrue(w.build(EnemyType.SHIELD_BEARER, 1) instanceof ShieldBearer);
        assertTrue(w.build(EnemyType.BERZERKER, 1) instanceof Berzerker);
        assertTrue(w.build(EnemyType.SIEGE_RAM, 1) instanceof SiegeRam);
        assertTrue(w.build(EnemyType.SKELETON, 1) instanceof Skeleton);
        assertTrue(w.build(EnemyType.NECROMANCER, 1) instanceof Necromancer);
        assertTrue(w.build(EnemyType.ASSASSIN, 1) instanceof Assassin);
        assertTrue(w.build(EnemyType.GARGOYLE, 1) instanceof Gargoyle);
        assertTrue(w.build(EnemyType.VOLATILE, 1) instanceof Volatile);
        assertTrue(w.build(EnemyType.TREASURE_GOBLIN, 1) instanceof TreasureGoblin);
    }

    @Test
    @DisplayName("gameplay dimensions come from config, never from artwork")
    void dimensionsAreGameplayOnly() {
        //  A skin can draw a Siege Ram at any size.  Its hitbox is 76x44 because
        //  the data says so, and that is what collision, grabbing, slamming,
        //  stripping and blocking all measure.
        TestEnemyWorld w = new TestEnemyWorld();
        Enemy ram = w.spawn(EnemyType.SIEGE_RAM, 900f);
        assertEquals(76f, ram.width(), 0f);
        assertEquals(44f, ram.height(), 0f);
        assertTrue(ram.covers(ram.x() + 37f, ram.y()), "just inside the hitbox");
        assertFalse(ram.covers(ram.x() + 39f, ram.y()), "just outside it");
        //  the grab box is the hitbox inflated by 16 on each axis
        assertTrue(ram.grabCovers(ram.x() + 45f, ram.y()));
        assertFalse(ram.grabCovers(ram.x() + 47f, ram.y()));
    }
}
