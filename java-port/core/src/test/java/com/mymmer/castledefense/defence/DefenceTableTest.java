package com.mymmer.castledefense.defence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.data.DataException;
import com.mymmer.castledefense.data.JsonSource;
import com.mymmer.castledefense.testsupport.InMemoryJsonSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped defence data, and the validation that guards it.
 *
 * <p>The first test reads the real {@code assets/data/defences.json} and checks
 * every number against {@code castle.py}. A balance change is meant to be a
 * one-line data edit; this is what stops it being a silent one.
 *
 * <p>The rest feed deliberately broken documents in. The point of each is that
 * the failure happens <b>at load</b>, naming the file and the field — not at
 * wave 20 as a tower that quietly does nothing.
 */
class DefenceTableTest {

    private static DefenceTable shipped() {
        return DefenceTable.load(new DefenceJson());
    }

    @Test
    @DisplayName("the shipped tower stats match castle.py exactly")
    void shippedTowerStats() {
        DefenceTable t = shipped();

        TowerConfig bow = t.tower(TowerType.BOWMAN);
        assertEquals(430f, bow.range, 0f);
        assertEquals(0.50, bow.cooldown, 0d);
        assertEquals(11f, bow.damage, 0f);
        assertEquals(70f, bow.maxHp, 0f);
        assertEquals(1.9f, bow.airRangeMult, 0f);
        assertEquals(1f, bow.bonusVsAir, 0f);
        assertEquals(1f, bow.bonusVsHeavy, 0f);
        assertTrue(bow.hitsAir);
        assertFalse(bow.overchargeable);
        assertEquals(880f, bow.projectileSpeed, 0f);
        assertEquals(ProjectileKind.ARROW, bow.projectile);
        assertEquals(22f, bow.width, 0f);
        assertEquals(32f, bow.height, 0f);

        TowerConfig bal = t.tower(TowerType.BALLISTA);
        assertEquals(640f, bal.range, 0f);
        assertEquals(2.5, bal.cooldown, 0d);
        assertEquals(62f, bal.damage, 0f);
        assertEquals(120f, bal.maxHp, 0f);
        assertEquals(1.5f, bal.airRangeMult, 0f);
        assertEquals(3f, bal.bonusVsAir, 0f);
        assertTrue(bal.hitsAir);
        assertTrue(bal.overchargeable);
        assertEquals(1150f, bal.projectileSpeed, 0f);
        assertEquals(ProjectileKind.BOLT, bal.projectile);
        assertEquals(34f, bal.width, 0f);
        assertEquals(30f, bal.height, 0f);

        TowerConfig can = t.tower(TowerType.CANNON);
        assertEquals(600f, can.range, 0f);
        assertEquals(3.1, can.cooldown, 0d);
        assertEquals(40f, can.damage, 0f);
        assertEquals(86f, can.splash, 0f);
        assertEquals(140f, can.maxHp, 0f);
        assertEquals(3f, can.bonusVsHeavy, 0f);
        assertFalse(can.hitsAir);
        assertTrue(can.overchargeable);
        assertEquals(900f, can.overchargeSpeed, 0f);
        assertEquals(ProjectileKind.CANNON, can.projectile);
        assertEquals(36f, can.width, 0f);
        assertEquals(28f, can.height, 0f);

        //  shared across all three in Python's DefenseTower base class
        for (TowerType type : TowerType.values()) {
            assertEquals(0.05f, t.tower(type).regen, 0f, type.id());
            assertEquals(9.0, t.tower(type).rebuildTime, 0d, type.id());
        }
    }

    @Test
    @DisplayName("the shipped castle tiers and slots match castle.py exactly")
    void shippedCastleData() {
        DefenceTable t = shipped();
        assertEquals(6, t.tierCount());
        float[] expected = {400f, 620f, 900f, 1300f, 1800f, 2500f};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], t.tier(i).maxHp, 0f, "tier " + i);
        }
        assertEquals("Wooden Palisade", t.tier(0).name);
        assertEquals("Runed Obsidian", t.tier(5).name);

        assertEquals(9, t.slotCount());
        //  WALL_TOP + 4, KEEP_TOP + 4, and the corner at WALL_TOP - 62
        assertEquals(248f, t.slot(0).x, 0f);
        assertEquals(354f, t.slot(0).y, 0f);
        assertEquals(30f, t.slot(4).x, 0f);
        assertEquals(234f, t.slot(4).y, 0f);
        assertEquals(228f, t.slot(8).x, 0f);
        assertEquals(288f, t.slot(8).y, 0f);
    }

    // --- validation ---------------------------------------------------------

    /** The shipped document, with one tower field replaced. */
    private static JsonSource brokenTower(String field, String value) {
        return new InMemoryJsonSource().put(DefenceTable.PATH,
                "{ \"towers\": ["
                        + tower("bowman", field, value) + ","
                        + tower("ballista", null, null) + ","
                        + tower("cannon", null, null)
                        + "], \"castle\": " + castle() + " }");
    }

    /**
     * A minimal valid tower object, with one field <b>replaced</b>.
     *
     * <p>Replaced rather than appended: a duplicate key in the same JSON object
     * is not an override — the reader keeps the first — so appending would
     * silently produce a valid document and the test would pass for the wrong
     * reason. (It did, until this was fixed.)
     */
    private static String tower(String id, String field, String value) {
        String[][] fields = {
                {"id", "\"" + id + "\""},
                {"name", "\"T\""},
                {"range", "400"},
                {"cooldown", "1.0"},
                {"damage", "10"},
                {"hitsAir", "true"},
                {"maxHp", "50"},
                {"width", "20"},
                {"height", "20"},
                {"regen", "0.05"},
                {"rebuildTime", "9"},
                {"projectile", "\"arrow\""},
                {"projectileSpeed", "800"},
        };
        StringBuilder sb = new StringBuilder("{");
        boolean replaced = false;
        for (String[] f : fields) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            String v = f[1];
            if (field != null && f[0].equals(field)) {
                v = value;
                replaced = true;
            }
            sb.append(" \"").append(f[0]).append("\": ").append(v);
        }
        if (field != null && !replaced) {
            sb.append(", \"").append(field).append("\": ").append(value);
        }
        return sb.append(" }").toString();
    }

    private static String castle() {
        return "{ \"tiers\": [ { \"id\": \"a\", \"name\": \"A\", \"maxHp\": 100 },"
                + " { \"id\": \"b\", \"name\": \"B\", \"maxHp\": 200 } ],"
                + " \"slots\": [ { \"id\": \"s1\", \"x\": 100, \"y\": 100 } ] }";
    }

    private static void expectFailure(JsonSource src, String... mustMention) {
        DataException e = assertThrows(DataException.class, () -> DefenceTable.load(src));
        String message = String.valueOf(e.getMessage());
        for (String fragment : mustMention) {
            assertTrue(message.contains(fragment),
                    "the message should name '" + fragment + "': " + message);
        }
    }

    @Test
    @DisplayName("a duplicate tower id is rejected")
    void duplicateTowerId() {
        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH,
                        "{ \"towers\": [" + tower("bowman", null, null) + ","
                                + tower("bowman", null, null) + "],"
                                + " \"castle\": " + castle() + " }"),
                "duplicate tower id", "bowman");
    }

    @Test
    @DisplayName("a negative or zero cooldown is rejected")
    void badCooldown() {
        expectFailure(brokenTower("cooldown", "-1"), "cooldown must be > 0");
        expectFailure(brokenTower("cooldown", "0"), "cooldown must be > 0");
    }

    @Test
    @DisplayName("an invalid range, damage or health is rejected")
    void badStats() {
        expectFailure(brokenTower("range", "0"), "range must be > 0");
        expectFailure(brokenTower("range", "-40"), "range must be > 0");
        expectFailure(brokenTower("damage", "0"), "damage must be > 0");
        expectFailure(brokenTower("maxHp", "-1"), "maxHp must be > 0");
        expectFailure(brokenTower("splash", "-5"), "splash must not be negative");
        expectFailure(brokenTower("projectileSpeed", "0"), "projectileSpeed must be > 0");
        expectFailure(brokenTower("regen", "2"), "regen");
        expectFailure(brokenTower("rebuildTime", "0"), "rebuildTime must be > 0");
    }

    @Test
    @DisplayName("a counter multiplier below 1.0 is rejected as a typo")
    void badCounterMultiplier() {
        //  0.5 would be a PENALTY, which no tower has and which almost certainly
        //  means someone wrote a percentage where a multiplier belongs.
        expectFailure(brokenTower("bonusVsAir", "0.5"), "bonusVsAir", "at least 1.0");
        expectFailure(brokenTower("bonusVsHeavy", "0"), "bonusVsHeavy");
        expectFailure(brokenTower("airRangeMult", "0.9"), "airRangeMult");
    }

    @Test
    @DisplayName("an unknown projectile type is rejected")
    void unknownProjectileType() {
        expectFailure(brokenTower("projectile", "\"plasma\""),
                "unknown projectile type", "plasma");
    }

    @Test
    @DisplayName("an unknown tower id is rejected, and lists what is valid")
    void unknownTowerId() {
        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH,
                        "{ \"towers\": [" + tower("trebuchet", null, null) + "],"
                                + " \"castle\": " + castle() + " }"),
                "unknown tower id", "trebuchet", "bowman");
    }

    @Test
    @DisplayName("a missing tower stat block is rejected")
    void missingTower() {
        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH,
                        "{ \"towers\": [" + tower("bowman", null, null) + "],"
                                + " \"castle\": " + castle() + " }"),
                "no stat block for tower");
    }

    @Test
    @DisplayName("an overchargeable tower with no overcharge speed is rejected")
    void overchargeableNeedsASpeed() {
        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH,
                        "{ \"towers\": [{ \"id\": \"bowman\", \"name\": \"T\","
                                + " \"range\": 400, \"cooldown\": 1, \"damage\": 10,"
                                + " \"hitsAir\": true, \"maxHp\": 50, \"width\": 20,"
                                + " \"height\": 20, \"regen\": 0.05, \"rebuildTime\": 9,"
                                + " \"projectile\": \"arrow\", \"projectileSpeed\": 800,"
                                + " \"overchargeable\": true, \"overchargeSpeed\": 0 }],"
                                + " \"castle\": " + castle() + " }"),
                "overchargeSpeed");
    }

    @Test
    @DisplayName("castle tiers that do not strengthen are rejected")
    void nonMonotonicTiers() {
        //  upgradeWall() gains the DIFFERENCE between consecutive maxima, so a
        //  tier that went backwards would reduce the castle's health on purchase.
        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH,
                        "{ \"towers\": [" + tower("bowman", null, null) + ","
                                + tower("ballista", null, null) + ","
                                + tower("cannon", null, null) + "],"
                                + " \"castle\": { \"tiers\": ["
                                + " { \"id\": \"a\", \"name\": \"A\", \"maxHp\": 400 },"
                                + " { \"id\": \"b\", \"name\": \"B\", \"maxHp\": 300 } ],"
                                + " \"slots\": [ { \"id\": \"s\", \"x\": 1, \"y\": 1 } ] } }"),
                "must increase in maxHp");
    }

    @Test
    @DisplayName("a duplicate castle tier or slot id is rejected")
    void duplicateCastleIds() {
        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH,
                        "{ \"towers\": [" + tower("bowman", null, null) + ","
                                + tower("ballista", null, null) + ","
                                + tower("cannon", null, null) + "],"
                                + " \"castle\": { \"tiers\": ["
                                + " { \"id\": \"a\", \"name\": \"A\", \"maxHp\": 100 },"
                                + " { \"id\": \"a\", \"name\": \"A\", \"maxHp\": 200 } ],"
                                + " \"slots\": [ { \"id\": \"s\", \"x\": 1, \"y\": 1 } ] } }"),
                "duplicate castle tier id");

        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH,
                        "{ \"towers\": [" + tower("bowman", null, null) + ","
                                + tower("ballista", null, null) + ","
                                + tower("cannon", null, null) + "],"
                                + " \"castle\": { \"tiers\": ["
                                + " { \"id\": \"a\", \"name\": \"A\", \"maxHp\": 100 } ],"
                                + " \"slots\": [ { \"id\": \"s\", \"x\": 1, \"y\": 1 },"
                                + " { \"id\": \"s\", \"x\": 2, \"y\": 2 } ] } }"),
                "duplicate slot id");
    }

    @Test
    @DisplayName("two slots at the same point are rejected")
    void overlappingSlots() {
        //  Occupancy is tested by position, so a duplicated point would leave a
        //  slot permanently "occupied" by the tower standing on its twin.
        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH,
                        "{ \"towers\": [" + tower("bowman", null, null) + ","
                                + tower("ballista", null, null) + ","
                                + tower("cannon", null, null) + "],"
                                + " \"castle\": { \"tiers\": ["
                                + " { \"id\": \"a\", \"name\": \"A\", \"maxHp\": 100 } ],"
                                + " \"slots\": [ { \"id\": \"s1\", \"x\": 5, \"y\": 5 },"
                                + " { \"id\": \"s2\", \"x\": 5, \"y\": 5 } ] } }"),
                "shares its position");
    }

    @Test
    @DisplayName("a slot outside the world is rejected")
    void slotOutsideTheWorld() {
        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH,
                        "{ \"towers\": [" + tower("bowman", null, null) + ","
                                + tower("ballista", null, null) + ","
                                + tower("cannon", null, null) + "],"
                                + " \"castle\": { \"tiers\": ["
                                + " { \"id\": \"a\", \"name\": \"A\", \"maxHp\": 100 } ],"
                                + " \"slots\": [ { \"id\": \"s\", \"x\": 9999, \"y\": 5 } ] } }"),
                "outside the world");
    }

    @Test
    @DisplayName("missing or empty sections are rejected")
    void missingSections() {
        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH, "{ }"),
                "'towers' must be a non-empty array");
        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH,
                "{ \"towers\": [] }"), "'towers' must be a non-empty array");
        expectFailure(new InMemoryJsonSource().put(DefenceTable.PATH,
                        "{ \"towers\": [" + tower("bowman", null, null) + ","
                                + tower("ballista", null, null) + ","
                                + tower("cannon", null, null) + "] }"),
                "castle");
    }

    @Test
    @DisplayName("projectile kinds round-trip by their stable ids")
    void projectileKindIds() {
        for (ProjectileKind k : ProjectileKind.values()) {
            assertEquals(k, ProjectileKind.byId(k.id(), null), k.id());
        }
        assertNotNull(ProjectileKind.byId("nope", ProjectileKind.ARROW));
        assertEquals(ProjectileKind.ARROW, ProjectileKind.byId(null, ProjectileKind.ARROW));
        assertTrue(ProjectileKind.CANNON.explodesOnGround());
        assertTrue(ProjectileKind.FIRE.explodesOnGround());
        assertFalse(ProjectileKind.ARROW.explodesOnGround());
    }
}
