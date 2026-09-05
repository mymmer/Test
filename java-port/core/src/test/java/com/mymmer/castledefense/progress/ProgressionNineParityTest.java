package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.mymmer.castledefense.config.Tuning;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.skill.SkillId;
import com.mymmer.castledefense.skill.SkillPanel;
import com.mymmer.castledefense.talent.TalentDef;
import com.mymmer.castledefense.talent.TalentTree;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 9 numbers, checked against values generated from the Python source.
 *
 * <p>The talent definitions are <b>parsed out of {@code main.py}</b> by the
 * generator rather than retyped, so the fixture cannot drift from the source
 * even by a typo. The shop curves and skill constants are transcribed with the
 * line each mirrors.
 */
class ProgressionNineParityTest {

    private static JsonValue fx;

    @BeforeAll
    static void load() throws IOException {
        File f = new File("../core/src/test/resources/parity/fixtures.json");
        assertTrue(f.isFile(), "run tools/parity/generate_fixtures.py first: " + f);
        fx = new JsonReader().parse(new String(
                Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    private static void close(double expected, double actual, String what) {
        assertEquals(expected, actual, Math.max(1e-6, Math.abs(expected) * 1e-6), what);
    }

    // ========================================================================
    //  Talents
    // ========================================================================

    @Test
    @DisplayName("all 38 talent definitions match the ones parsed out of main.py")
    void talentDefinitions() {
        TestRun r = new TestRun();
        int checked = 0;
        for (JsonValue c = fx.get("talentDefs").child; c != null; c = c.next) {
            String id = c.getString("id");
            TalentDef d = r.talentTable.get(id);
            assertNotNull(d, "the port has no talent '" + id + "'");
            assertEquals(c.getString("branch"), d.branch.id(), id + ": branch");
            assertEquals(c.getInt("tier"), d.tier, id + ": tier");
            assertEquals(c.getInt("maxRank"), d.maxRank, id + ": maxRank");
            close(c.getDouble("perRank"), d.perRank, id + ": perRank");
            checked++;
        }
        assertEquals(38, checked);
        assertEquals(38, r.talentTable.size(), "and the port has no extras");
    }

    @Test
    @DisplayName("every talent effect matches Python at every rank, including the caps")
    void talentEffectsAtEveryRank() {
        int checked = 0;
        for (JsonValue c = fx.get("talentEffects").child; c != null; c = c.next) {
            String id = c.getString("id");
            int rank = c.getInt("rank");
            double expectedValue = c.getDouble("value");
            double expectedEffect = c.getDouble("effect");

            TestRun r = new TestRun().beginClassic();
            if (rank > 0) {
                r.grantPoints(500);
                r.buyTalentDeep(id, rank);
            }
            TalentTree t = r.talents();
            assertEquals(rank, t.rank(id), id + " at rank " + rank);
            close(expectedValue, t.value(id), id + " value at rank " + rank);
            close(expectedEffect, effectOf(t, id), id + " effect at rank " + rank);
            checked++;
        }
        assertEquals(175, checked, "every rank of every talent");
    }

    /** Reads the one modifier this talent drives. */
    private static double effectOf(TalentTree t, String id) {
        switch (id) {
            case "rate": return t.towerRate();
            case "power": return t.towerDamage();
            case "crit": return t.critChance();
            case "pierce": return t.extraPierce();
            case "splash": return t.splashMult();
            case "overcharge": return t.overchargeCd();
            case "maxhp": return t.castleHpClaim();
            case "regen": return t.barricadeRegen();
            case "spikedot": return t.spikeDot();
            case "towerhp": return t.towerHp();
            case "rebuild": return t.rebuildMult();
            case "thorns": return t.damageTaken();
            case "greed": return t.goldPop();
            case "lighthands": return t.grabBonus();
            case "haggle": return t.shopDiscount();
            case "purse": return t.wavePurse();
            case "lightfingers": return t.grabCdScale();
            case "showman": return t.scoreMult();
            case "scavenge": return t.killGold();
            case "sentinels": return t.allySentinels() ? 1d : 0d;
            case "stormwinds": return t.stormWindSlow();
            case "throwarm": return t.throwPower();
            case "updraft": return t.fallDamage();
            case "conductor": return t.lightningMult();
            case "gale": return t.windMult();
            case "tempest": return t.stormChance();
            case "bonecraft": return t.allyPower();
            case "hostmaster": return t.allyCapBonus();
            case "quickraise": return t.allyRate();
            case "gravechill": return t.graveChill();
            case "secondwind": return t.allyLife();
            case "bonewall": return t.allyTough();
            case "focus": return t.skillCd();
            case "amplify": return t.skillPower();
            case "widecast": return t.skillArea();
            case "emberfall": return t.fireTime();
            case "eyeofstorm": return t.tornadoMult();
            case "twincast": return t.meteorCount();
            default: throw new IllegalArgumentException("no effect mapped for " + id);
        }
    }

    // ========================================================================
    //  Shop
    // ========================================================================

    @Test
    @DisplayName("every shop curve matches Python at every representative level")
    void shopCurves() {
        TestRun r = new TestRun().beginClassic();
        int checked = 0;
        for (JsonValue c = fx.get("shopCosts").child; c != null; c = c.next) {
            String id = c.getString("id");
            int level = c.getInt("level");
            double raw = c.getDouble("raw");
            int cost = c.getInt("cost");

            com.mymmer.castledefense.shop.ShopItemDef d = r.shop().table().require(id);
            double javaRaw = d.base * Math.pow(d.growth, level);
            close(raw, javaRaw, id + " raw at level " + level);
            assertEquals(cost, (int) javaRaw, id + " cost at level " + level);
            checked++;
        }
        assertEquals(63, checked);
    }

    @Test
    @DisplayName("the discount ordering matches Python, and the other order would not")
    void shopDiscountOrdering() {
        TestRun r = new TestRun().beginClassic();
        int differing = 0;
        for (JsonValue c = fx.get("shopDiscount").child; c != null; c = c.next) {
            String id = c.getString("id");
            int level = c.getInt("level");
            double discount = c.getDouble("discount");

            com.mymmer.castledefense.shop.ShopItemDef d = r.shop().table().require(id);
            double raw = d.base * Math.pow(d.growth, level);

            assertEquals(c.getInt("cost"), (int) (raw * discount),
                    id + " at level " + level + " with discount " + discount);
            if (c.getInt("cost") != c.getInt("costIfTruncatedFirst")) {
                differing++;
            }
        }
        assertTrue(differing > 0,
                "the fixture must contain cases where the two orderings differ, "
                        + "or it proves nothing about the ordering");
    }

    @Test
    @DisplayName("the barricade and repair curves match Python in every case")
    void bespokeCurves() {
        TestRun r = new TestRun().beginClassic();
        com.mymmer.castledefense.shop.ShopItemDef bar =
                r.shop().table().require("barricade");
        com.mymmer.castledefense.shop.ShopItemDef rep =
                r.shop().table().require("repair");

        for (JsonValue c = fx.get("shopBespokeCosts").child; c != null; c = c.next) {
            if ("barricade".equals(c.getString("id"))) {
                int level = c.getInt("level");
                boolean alive = c.getBoolean("alive");
                double hp = c.getDouble("hp");
                double maxHp = c.getDouble("maxHp");
                double java;
                if (level == 0) {
                    java = bar.firstCost;
                } else if (!alive) {
                    java = (int) (bar.rebuildBase * Math.pow(bar.rebuildGrowth, level));
                } else if (hp < maxHp && level >= 5) {
                    java = (int) (bar.topUpFlat + (maxHp - hp) * bar.topUpPerMissingHp);
                } else {
                    java = (int) (bar.base * Math.pow(bar.growth, level));
                }
                close(c.getDouble("cost"), java,
                        "barricade level " + level + " alive=" + alive);
            } else {
                double missing = c.getDouble("missing");
                double java = Math.max(rep.base, (int) (missing * rep.perMissingHp));
                close(c.getDouble("cost"), java, "repair with " + missing + " missing");
            }
        }
    }

    // ========================================================================
    //  Skills
    // ========================================================================

    @Test
    @DisplayName("the skill constants match the source's tuning block")
    void skillConstants() {
        JsonValue c = fx.get("skillConstants").child;
        assertNotNull(c);
        close(c.getDouble("lightningRadius"), Tuning.LIGHTNING_RADIUS, "lightning radius");
        close(c.getDouble("lightningDamage"), Tuning.LIGHTNING_DAMAGE, "lightning damage");
        assertEquals(c.getDouble("lightningCooldown"), Tuning.LIGHTNING_COOLDOWN, 0d);
        assertEquals(c.getInt("meteorCount"), Tuning.METEOR_COUNT);
        close(c.getDouble("meteorRadius"), Tuning.METEOR_RADIUS, "meteor radius");
        close(c.getDouble("meteorDamage"), Tuning.METEOR_DAMAGE, "meteor damage");
        assertEquals(c.getDouble("meteorCooldown"), Tuning.METEOR_COOLDOWN, 0d);
        assertEquals(c.getDouble("fireZoneTime"), Tuning.FIRE_ZONE_TIME, 0d);
        close(c.getDouble("fireZoneDps"), Tuning.FIRE_ZONE_DPS, "fire dps");
        assertEquals(c.getDouble("tornadoCooldown"), Tuning.TORNADO_COOLDOWN, 0d);
        assertEquals(c.getDouble("tornadoLife"), Tuning.TORNADO_LIFE, 0d);
        close(c.getDouble("tornadoSpeed"), Tuning.TORNADO_SPEED, "tornado speed");
        close(c.getDouble("tornadoRadius"), Tuning.TORNADO_RADIUS, "tornado radius");
        close(c.getDouble("tornadoLift"), Tuning.TORNADO_LIFT, "tornado lift");
        close(c.getDouble("tornadoSwirl"), Tuning.TORNADO_SWIRL, "tornado swirl");
    }

    @Test
    @DisplayName("every talent that scales a skill matches Python at every rank")
    void skillScaling() {
        int checked = 0;
        for (JsonValue c = fx.get("skillScaling").child; c != null; c = c.next) {
            String talent = c.getString("talent");
            if ("lightning-damage".equals(talent)) {
                checkLightningDamage(c);
                checked++;
                continue;
            }
            int rank = c.getInt("rank");
            TestRun r = new TestRun().beginClassic();
            if (rank > 0) {
                r.grantPoints(500);
                r.buyTalentDeep(talent, rank);
            }
            switch (talent) {
                case "focus":
                    close(c.getDouble("lightningCooldown"),
                            r.skills().fullCooldown(SkillId.LIGHTNING),
                            "lightning cooldown at focus " + rank);
                    close(c.getDouble("meteorCooldown"),
                            r.skills().fullCooldown(SkillId.METEOR),
                            "meteor cooldown at focus " + rank);
                    close(c.getDouble("tornadoCooldown"),
                            r.skills().fullCooldown(SkillId.TORNADO),
                            "tornado cooldown at focus " + rank);
                    break;
                case "widecast": {
                    float area = r.run.modifiers().skillArea();
                    close(c.getDouble("lightningRadius"), Tuning.LIGHTNING_RADIUS * area,
                            "lightning radius at widecast " + rank);
                    close(c.getDouble("meteorRadius"), Tuning.METEOR_RADIUS * area,
                            "meteor radius at widecast " + rank);
                    close(c.getDouble("tornadoRadius"), Tuning.TORNADO_RADIUS * area,
                            "tornado radius at widecast " + rank);
                    break;
                }
                case "twincast":
                    assertEquals(c.getInt("meteorCount"),
                            (int) (Tuning.METEOR_COUNT * r.run.modifiers().meteorCount()),
                            "meteor count at twincast " + rank);
                    break;
                case "emberfall":
                    close(c.getDouble("fireZoneLife"),
                            Tuning.FIRE_ZONE_TIME * r.run.modifiers().fireTime(),
                            "fire life at emberfall " + rank);
                    break;
                case "eyeofstorm":
                    close(c.getDouble("tornadoLife"),
                            Tuning.TORNADO_LIFE * r.run.modifiers().tornadoMult(),
                            "tornado life at eyeofstorm " + rank);
                    close(c.getDouble("tornadoPower"), r.run.modifiers().tornadoMult(),
                            "tornado power at eyeofstorm " + rank);
                    break;
                default:
                    throw new IllegalStateException("unhandled scaling row: " + talent);
            }
            checked++;
        }
        assertEquals(33, checked);
    }

    private static void checkLightningDamage(JsonValue c) {
        TestRun r = new TestRun().beginClassic();
        r.grantPoints(500);
        int amp = c.getInt("amplifyRank");
        int cond = c.getInt("conductorRank");
        if (amp > 0) {
            r.buyTalentDeep("amplify", amp);
        }
        if (cond > 0) {
            r.buyTalentDeep("conductor", cond);
        }
        double maxHp = c.getDouble("maxHp");
        double dmg = maxHp * Tuning.LIGHTNING_DAMAGE
                * r.run.modifiers().skillPower() * r.run.modifiers().lightningMult();
        close(c.getDouble("damage"), dmg,
                "lightning on " + maxHp + " hp, amplify " + amp + " conductor " + cond);
        close(c.getDouble("bossDamage"), dmg * 0.25d, "the boss share");
    }

    @Test
    @DisplayName("the live SkillPanel reproduces the fixtured cooldowns")
    void liveCooldownsMatch() {
        for (JsonValue c = fx.get("skillScaling").child; c != null; c = c.next) {
            if (!"focus".equals(c.getString("talent"))) {
                continue;
            }
            int rank = c.getInt("rank");
            TestRun r = new TestRun();
            r.begin(GameMode.ENDLESS, "normal");
            if (rank > 0) {
                r.grantPoints(500).buyTalentDeep("focus", rank);
            }
            r.skills().unlockNext();
            assertTrue(r.skills().castAt(SkillId.LIGHTNING, 800f, 500f));
            close(c.getDouble("lightningCooldown"),
                    r.skills().cooldownRemaining(SkillId.LIGHTNING),
                    "a live cast at focus rank " + rank);
            assertEquals(SkillPanel.baseCooldown(SkillId.LIGHTNING),
                    Tuning.LIGHTNING_COOLDOWN, 0d);
        }
    }
}
