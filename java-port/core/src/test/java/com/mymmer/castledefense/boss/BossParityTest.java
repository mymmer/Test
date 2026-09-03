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
        assertEquals(GameConfig.REGALIA_COOLDOWN, c.getFloat("REGALIA_COOLDOWN"), 0f);
        assertEquals(GameConfig.REGALIA_CD_GROWTH, c.getFloat("REGALIA_CD_GROWTH"), 0f);
        assertEquals(GameConfig.CROWN_RETRIEVE_SPEED, c.getFloat("CROWN_RETRIEVE_SPEED"), 0f);
        assertEquals(GameConfig.STAFF_DISARM_TIME, c.getFloat("STAFF_DISARM_TIME"), 0f);
        assertEquals(GameConfig.CLAW_SMACK_DISTANCE, c.getFloat("CLAW_SMACK_DISTANCE"), 0f);
        assertEquals(GameConfig.CLAW_STAGGER, c.getFloat("CLAW_STAGGER"), 0f);
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
                troll.retrieveCrown(Simulation.DT);
                //  clear the guard so the next detach is allowed
                int windDown = (int) (troll.regaliaCd() / Simulation.DT) + 2;
                if (i < taken - 1) {
                    for (int s = 0; s < windDown; s++) {
                        troll.update(Simulation.DT);
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
                    speed * GameConfig.CROWN_RETRIEVE_SPEED * Simulation.DT,
                    "one retrieval step at speed " + speed);
        }
    }

    @Test
    @DisplayName("the Dragon's breath cadence matches Python at single precision")
    void breathCadence() {
        //  ------------------------------------------------------------------
        //  A REAL, MEASURED PRECISION BOUNDARY -- reported, not papered over.
        //
        //  Python's timers are doubles; the port's are floats, like all its
        //  gameplay state.  The fixture therefore carries BOTH counts: `shots`
        //  from Python's own arithmetic, and `shotsFloat32` from the identical
        //  loop re-run in single precision, which is what Java necessarily
        //  computes.  Java is asserted against shotsFloat32.
        //
        //  They agree for the shipped Normal configuration.  They differ by one
        //  for the shipped HARD one (fireScale 0.5): Python fires 15 fireballs
        //  per breath, Java 16.  That is a genuine port difference, recorded in
        //  PORT_ANALYSIS.md section 14.  It is NOT fixed by tweaking a constant:
        //  the cause is the precision of the timer type, and changing that is an
        //  architectural decision, not a balance patch.
        //  ------------------------------------------------------------------
        int divergent = 0;
        for (JsonValue c = fx.get("dragonBreath").child; c != null; c = c.next) {
            float breathTime = c.getFloat("breathTime");
            float interval = c.getFloat("shotInterval");
            float fireScale = c.getFloat("fireScale");

            //  The same loop as Dragon.think, run standalone so the assertion is
            //  about the cadence rather than the surrounding state machine.
            float breathing = breathTime;
            float shotTimer = 0f;
            float scaled = interval * fireScale;
            int shots = 0;
            int guard = 0;
            while (breathing > 0f && guard < 100000) {
                guard++;
                breathing -= Simulation.DT;
                shotTimer -= Simulation.DT;
                if (shotTimer <= 0f) {
                    shotTimer = scaled;
                    shots++;
                }
            }
            assertEquals(c.getInt("shotsFloat32"), shots,
                    "breath " + breathTime + "s at " + interval + "s x" + fireScale
                            + " (single precision)");
            if (c.getInt("shots") != c.getInt("shotsFloat32")) {
                divergent++;
            }
        }
        assertTrue(divergent > 0,
                "the fixture is expected to contain at least one boundary case "
                        + "where the precisions differ; if it no longer does, the "
                        + "finding in PORT_ANALYSIS section 14 needs revisiting");
    }

    @Test
    @DisplayName("the shipped Normal breath has no precision divergence at all")
    void shippedNormalBreathAgreesExactly() {
        //  The case that actually ships on Normal: Python and the port produce
        //  the same eight fireballs.  Pinned separately so a future tuning change
        //  that moved it into the divergent set would fail loudly here.
        boolean checked = false;
        for (JsonValue c = fx.get("dragonBreath").child; c != null; c = c.next) {
            if (c.getBoolean("shipped") && c.getFloat("fireScale") == 1f) {
                assertEquals(c.getInt("shots"), c.getInt("shotsFloat32"),
                        "the shipped Normal breath must agree in both precisions");
                assertEquals(8, c.getInt("shots"), "and it is eight fireballs");
                checked = true;
            }
        }
        assertTrue(checked, "the fixture should cover the shipped breath");
    }

    @Test
    @DisplayName("the Dragon's live breath fires the fixtured number of times")
    void breathCadenceInTheRealDragon() {
        //  The standalone loop above proves the arithmetic; this proves the
        //  Dragon actually runs it, by counting real trace events.
        TestBossWorld w = new TestBossWorld();
        com.mymmer.castledefense.debug.RecordingSimulationTrace trace =
                new com.mymmer.castledefense.debug.RecordingSimulationTrace(4096);
        Dragon dragon = (Dragon) w.summon(BossType.DRAGON,
                w.bosses.config(BossType.DRAGON).standoffX);

        for (int i = 0; i < 60 * 20 && !dragon.breathing(); i++) {
            w.step(Simulation.DT);
        }
        assertTrue(dragon.breathing());

        trace.setEnabled(true);
        w.world.trace = trace;
        while (dragon.breathing()) {
            w.step(Simulation.DT);
        }
        int fired = trace.countOf(com.mymmer.castledefense.debug.TraceEvent.BOSS_ATTACK);

        int expected = -1;
        for (JsonValue c = fx.get("dragonBreath").child; c != null; c = c.next) {
            if (c.getFloat("breathTime") == w.bosses.config(BossType.DRAGON).breathTime
                    && c.getFloat("fireScale") == 1f) {
                expected = c.getInt("shotsFloat32");
            }
        }
        assertTrue(expected > 0, "the fixture should cover the shipped breath");
        //  One fireball can already have left before tracing was switched on, so
        //  the live count is allowed to be one short of the standalone total.
        assertTrue(fired == expected || fired == expected - 1,
                "expected " + expected + " (or one fewer), got " + fired);
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
                vy += GameConfig.GRAVITY * Simulation.DT;
                x += vx * Simulation.DT;
                y += vy * Simulation.DT;
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
            float interval = Math.max(cfg.summonIntervalFloor,
                    cfg.summonIntervalBase - wave * cfg.summonIntervalPerWave);
            int count = cfg.summonBaseCount + Math.min(cfg.summonMaxBonus, wave / 8);
            close(c.getDouble("interval"), interval, "summon interval at wave " + wave);
            assertEquals(c.getInt("count"), count, "summon count at wave " + wave);
        }
    }
}
