package com.mymmer.castledefense.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.game.Simulation;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Boss formulas against reference values generated from the Python source.
 *
 * <p>Same arrangement as {@code PythonParityTest}: the values come from
 * {@code tools/parity/generate_fixtures.py}, which reads the constants out of
 * {@code sprites.py} by parsing and transcribes each formula in one marked block
 * naming the source line it mirrors. The four authoritative files are read-only.
 *
 * <p>Only deterministic, isolatable formulas are fixtured. A boss's full
 * behaviour depends on the RNG, the enemy list and the structures, and belongs
 * to the Phase 13 parity work rather than to a cross-language simulator.
 */
class BossParityTest {

    private static JsonValue fx;
    private static final float REL = 1e-5f;

    @BeforeAll
    static void loadFixtures() throws Exception {
        InputStream in = BossParityTest.class.getResourceAsStream("/parity/fixtures.json");
        assertNotNull(in, "fixtures.json is missing -- run tools/parity/generate_fixtures.py");
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            fx = new JsonReader().parse(r);
        }
    }

    private static void close(double expected, double actual, String what) {
        assertEquals(expected, actual, Math.max(1e-4, Math.abs(expected) * REL), what);
    }

    @Test
    @DisplayName("the boss constants match the Python ones")
    void constants() {
        JsonValue c = fx.get("constants");
        //  The time-domain constants are read as doubles on BOTH sides: the
        //  Python source states 0.6, and a float round trip would turn that into
        //  0.6000000238418579 and quietly pass a looser assertion.
        assertEquals(GameConfig.REGALIA_COOLDOWN, c.getDouble("REGALIA_COOLDOWN"), 0d);
        assertEquals(GameConfig.REGALIA_CD_GROWTH, c.getDouble("REGALIA_CD_GROWTH"), 0d);
        assertEquals(GameConfig.STAFF_DISARM_TIME, c.getDouble("STAFF_DISARM_TIME"), 0d);
        assertEquals(GameConfig.CLAW_STAGGER, c.getDouble("CLAW_STAGGER"), 0d);
        //  ...and the spatial ones stay floats.
        assertEquals(GameConfig.CROWN_RETRIEVE_SPEED, c.getFloat("CROWN_RETRIEVE_SPEED"), 0f);
        assertEquals(GameConfig.CLAW_SMACK_DISTANCE, c.getFloat("CLAW_SMACK_DISTANCE"), 0f);
        assertEquals(GameConfig.WALL_TOP, c.getFloat("WALL_TOP"), 0f);
    }

    @Test
    @DisplayName("the disruption guard ladder matches Python")
    void regaliaGuardLadder() {
        TestBossWorld w = new TestBossWorld();
        for (JsonValue c = fx.get("regaliaGuard").child; c != null; c = c.next) {
            int taken = c.getInt("taken");
            //  Drive the real object rather than recomputing the formula here:
            //  a test that reimplemented guard_regalia() would pass even if the
            //  production code stopped calling it.
            TrollKing troll = (TrollKing) w.summon(BossType.TROLL_KING, 900f);
            for (int i = 0; i < taken; i++) {
                assertTrue(troll.hasCrown());
                troll.detachRegalia();
                troll.crownItem().markDead();
                troll.retrieveCrown(Simulation.FIXED_DT);
                //  clear the guard so the next detach is allowed
                int windDown = (int) (troll.regaliaCd() / Simulation.FIXED_DT) + 2;
                if (i < taken - 1) {
                    for (int s = 0; s < windDown; s++) {
                        troll.update(Simulation.FIXED_DT);
                    }
                }
            }
            close(c.getDouble("guard"), troll.regaliaCd(),
                    "guard after " + taken + " disruptions");
            troll.markDead();
        }
    }

    @Test
    @DisplayName("crown retrieval speed matches Python")
    void crownRetrieveSpeed() {
        for (JsonValue c = fx.get("crownRetrieve").child; c != null; c = c.next) {
            float speed = c.getFloat("speed");
            close(c.getDouble("retrieveSpeedMult"), GameConfig.CROWN_RETRIEVE_SPEED,
                    "CROWN_RETRIEVE_SPEED");
            close(c.getDouble("stepDistance"),
                    speed * GameConfig.CROWN_RETRIEVE_SPEED * Simulation.PHYSICS_DT,
                    "one retrieval step at speed " + speed);
        }
    }

    @Test
    @DisplayName("the Dragon's breath cadence matches Python exactly, at every configuration")
    void breathCadence() {
        //  ------------------------------------------------------------------
        //  PRODUCTION BEHAVIOUR.  The Dragon's timers are doubles, like every
        //  gameplay clock in the port, so this loop -- the same one as
        //  Dragon.think -- is asserted against Python's OWN count, `shots`.
        //  Not against `shotsFloat32`, which is now only a historical record of
        //  what the float implementation used to do.  See
        //  floatTimersAreWhyThisRuleExists() below.
        //  ------------------------------------------------------------------
        for (JsonValue c = fx.get("dragonBreath").child; c != null; c = c.next) {
            double breathTime = c.getDouble("breathTime");
            double interval = c.getDouble("shotInterval");
            double fireScale = c.getDouble("fireScale");

            double breathing = breathTime;
            double shotTimer = 0d;
            double scaled = interval * fireScale;
            int shots = 0;
            int guard = 0;
            while (breathing > 0d && guard < 100000) {
                guard++;
                breathing -= Simulation.FIXED_DT;
                shotTimer -= Simulation.FIXED_DT;
                if (shotTimer <= 0d) {
                    shotTimer = scaled;
                    shots++;
                }
            }
            assertEquals(c.getInt("shots"), shots,
                    "breath " + breathTime + "s at " + interval + "s x" + fireScale);
        }
    }

    @Test
    @DisplayName("the shipped Hard breath is fifteen fireballs, as in Python")
    void shippedHardBreathIsFifteen() {
        //  THE case this whole timing rule was built for.  On Hard the
        //  difficulty halves the shot interval to 0.075 s, and 0.075 is a
        //  boundary: nine subtractions of an exact sixtieth land on it, nine
        //  subtractions of 1f/60f overshoot.  Python fires fifteen.  The port
        //  used to fire sixteen; it now fires fifteen.
        boolean checked = false;
        for (JsonValue c = fx.get("dragonBreath").child; c != null; c = c.next) {
            if (!c.getBoolean("shipped")) {
                continue;
            }
            if (c.getDouble("fireScale") == 0.5d) {
                assertEquals(15, c.getInt("shots"), "Python fires fifteen on Hard");
                assertEquals(16, c.getInt("shotsFloat32"),
                        "and a float implementation fired sixteen -- the bug");
                assertEquals(15, liveBreathCount(0.5d), "and the live Dragon now fires fifteen");
                checked = true;
            } else if (c.getDouble("fireScale") == 1d) {
                assertEquals(8, c.getInt("shots"), "Normal is eight fireballs");
                assertEquals(8, liveBreathCount(1d), "and the live Dragon fires eight");
            }
        }
        assertTrue(checked, "the fixture should cover the shipped Hard breath");
    }

    @Test
    @DisplayName("float timers are why the rule exists: three of six cases diverge")
    void floatTimersAreWhyThisRuleExists() {
        //  Kept deliberately.  It does NOT describe production any more -- it
        //  reproduces what the float implementation computed, and proves the
        //  divergence was real, went BOTH ways, and was not a single unlucky
        //  case that could have been tuned away.
        int divergent = 0;
        for (JsonValue c = fx.get("dragonBreath").child; c != null; c = c.next) {
            float breathing = c.getFloat("breathTime");
            float shotTimer = 0f;
            float scaled = c.getFloat("shotInterval") * c.getFloat("fireScale");
            int shots = 0;
            int guard = 0;
            while (breathing > 0f && guard < 100000) {
                guard++;
                breathing -= Simulation.PHYSICS_DT;
                shotTimer -= Simulation.PHYSICS_DT;
                if (shotTimer <= 0f) {
                    shotTimer = scaled;
                    shots++;
                }
            }
            assertEquals(c.getInt("shotsFloat32"), shots,
                    "the historical single-precision count must still reproduce");
            if (c.getInt("shots") != shots) {
                divergent++;
            }
        }
        assertEquals(3, divergent,
                "three of the six fixtured configurations diverged under float timers");
    }

    /** Runs a real Dragon through one whole breath and counts the fireballs. */
    private int liveBreathCount(double fireScale) {
        TestBossWorld w = new TestBossWorld();
        w.bossFireScale = fireScale;
        com.mymmer.castledefense.debug.RecordingSimulationTrace trace =
                new com.mymmer.castledefense.debug.RecordingSimulationTrace(4096);
        Dragon dragon = (Dragon) w.summon(BossType.DRAGON,
                w.bosses.config(BossType.DRAGON).standoffX);
        //  Trace from before the breath starts, so the first fireball -- which
        //  leaves on the same step the breath begins -- is counted too.
        trace.setEnabled(true);
        w.world.trace = trace;
        for (int i = 0; i < 60 * 20 && !dragon.breathing(); i++) {
            w.step(Simulation.FIXED_DT);
        }
        assertTrue(dragon.breathing(), "the Dragon should have started breathing");
        while (dragon.breathing()) {
            w.step(Simulation.FIXED_DT);
        }
        return trace.countOf(com.mymmer.castledefense.debug.TraceEvent.BOSS_ATTACK);
    }

    @Test
    @DisplayName("the live Dragon runs the cadence the fixture predicts, exactly")
    void breathCadenceInTheRealDragon() {
        //  The standalone loops above prove the arithmetic; this proves the
        //  Dragon actually runs it, by counting real trace events -- and it is
        //  an EXACT count now, not a tolerance, because the boundary is no
        //  longer precision-dependent.
        for (JsonValue c = fx.get("dragonBreath").child; c != null; c = c.next) {
            if (!c.getBoolean("shipped")) {
                continue;
            }
            double fireScale = c.getDouble("fireScale");
            assertEquals(c.getInt("shots"), liveBreathCount(fireScale),
                    "live Dragon at fireScale " + fireScale);
        }
    }

    @Test
    @DisplayName("claw smack progress matches Python")
    void clawSmackProgress() {
        for (JsonValue c = fx.get("dragonSmack").child; c != null; c = c.next) {
            float amount = c.getFloat("amount");
            close(c.getDouble("smackDistance"), GameConfig.CLAW_SMACK_DISTANCE,
                    "CLAW_SMACK_DISTANCE");
            close(c.getDouble("progress"), amount / GameConfig.CLAW_SMACK_DISTANCE,
                    "progress from " + amount);

            TestBossWorld w = new TestBossWorld();
            Dragon dragon = (Dragon) w.summon(BossType.DRAGON);
            assertEquals(c.getBoolean("completesAlone"), dragon.applySmack(amount),
                    amount + " px of drag completing a battering");
        }
    }

    @Test
    @DisplayName("the Lich ward matches Python, in the source's order")
    void wardOrdering() {
        //  See the note on BossMechanicsTest.wardOrdering: with the current
        //  formulas the two orders are numerically IDENTICAL, because both steps
        //  are pure multiplies and x0.25 is an exact power of two.  The fixture
        //  generates both so the test can say so plainly rather than claim to
        //  detect a difference that does not exist.
        int differing = 0;
        for (JsonValue c = fx.get("lichWard").child; c != null; c = c.next) {
            float amount = c.getFloat("amount");
            boolean warded = c.getBoolean("warded");
            float armor = c.getFloat("armor");
            float vulnerable = c.getFloat("vulnerable");

            float ward = warded ? amount * 0.25f : amount;
            float java = Math.max(0f, ward * (1f - armor) * vulnerable);
            close(c.getDouble("correct"), java,
                    "warded=" + warded + " armor=" + armor + " vuln=" + vulnerable);

            if (c.getDouble("correct") != c.getDouble("reversed")) {
                differing++;
            }
        }
        assertEquals(0, differing,
                "documented finding: the two orders coincide for every fixtured "
                        + "case. The source order is preserved regardless, because "
                        + "it stops being equivalent the moment armour gains a "
                        + "floor, a cap or a flat subtraction.");
    }

    @Test
    @DisplayName("dropped-item flight matches Python step for step")
    void droppedItemPhysics() {
        for (JsonValue c = fx.get("droppedItem").child; c != null; c = c.next) {
            RegaliaKind kind = RegaliaKind.byId(c.getString("kind"), null);
            assertNotNull(kind);
            int steps = c.getInt("steps");
            close(c.getDouble("restY"),
                    GameConfig.GROUND_Y - kind.height() / 2f + 2f, "restY " + kind.id());

            //  The same integration as DroppedItem.update, standalone.
            float x = 800f;
            float y = 300f;
            float vx = 400f;
            float vy = -300f;
            boolean grounded = false;
            for (int i = 0; i < steps; i++) {
                vy += GameConfig.GRAVITY * Simulation.PHYSICS_DT;
                x += vx * Simulation.PHYSICS_DT;
                y += vy * Simulation.PHYSICS_DT;
                float left = GameConfig.CASTLE_FRONT + kind.width();
                float right = GameConfig.WORLD_WIDTH - kind.width();
                if (x < left) {
                    x = left;
                    vx = Math.abs(vx) * 0.4f;
                }
                if (x > right) {
                    x = right;
                    vx = -Math.abs(vx) * 0.4f;
                }
                float rest = GameConfig.GROUND_Y - kind.height() / 2f + 2f;
                if (y >= rest) {
                    y = rest;
                    vy = -Math.abs(vy) * 0.34f;
                    vx *= 0.6f;
                    if (Math.abs(vy) < 90f) {
                        vy = 0f;
                        grounded = true;
                    }
                }
            }
            String at = kind.id() + " after " + steps + " steps";
            close(c.getDouble("x"), x, "x " + at);
            close(c.getDouble("y"), y, "y " + at);
            close(c.getDouble("vx"), vx, "vx " + at);
            close(c.getDouble("vy"), vy, "vy " + at);
            assertEquals(c.getBoolean("grounded"), grounded, "grounded " + at);
        }
    }

    @Test
    @DisplayName("the regalia throw cap matches Python, by kind")
    void throwCap() {
        for (JsonValue c = fx.get("regaliaThrowCap").child; c != null; c = c.next) {
            RegaliaKind kind = RegaliaKind.byId(c.getString("kind"), null);
            TestBossWorld w = new TestBossWorld();
            Boss owner = w.summon(kind == RegaliaKind.CROWN
                    ? BossType.TROLL_KING : BossType.LICH_LORD, 900f);
            DroppedItem item = new DroppedItem(w, owner, kind, 800f, 300f);
            item.throwIt(c.getFloat("vx"), c.getFloat("vy"));

            String at = kind.id() + " thrown at (" + c.getFloat("vx") + ", "
                    + c.getFloat("vy") + ")";
            close(c.getDouble("outVx"), item.vx(), "vx " + at);
            close(c.getDouble("outVy"), item.vy(), "vy " + at);
        }
    }

    @Test
    @DisplayName("the Lich's summon cadence and count match Python")
    void lichSummon() {
        TestBossWorld w = new TestBossWorld();
        var cfg = w.bosses.config(BossType.LICH_LORD);
        for (JsonValue c = fx.get("lichSummon").child; c != null; c = c.next) {
            int wave = c.getInt("wave");
            double interval = Math.max(cfg.summonIntervalFloor,
                    cfg.summonIntervalBase - wave * cfg.summonIntervalPerWave);
            int count = cfg.summonBaseCount + Math.min(cfg.summonMaxBonus, wave / 8);
            close(c.getDouble("interval"), interval, "summon interval at wave " + wave);
            assertEquals(c.getInt("count"), count, "summon count at wave " + wave);
        }
    }
}
