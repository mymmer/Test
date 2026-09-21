package com.mymmer.castledefense.enemy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.defence.Trappable;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.progress.TestRun;
import com.mymmer.castledefense.render.WorldGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A raised skeleton stands on the ground, like everything else that walks.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code Outpost.raise} called {@code allies.spawnAlly(x - 30f, y)} and
 * passed its <b>own</b> y -- {@code OUTPOST_BASE_Y}, 505, the centre of a
 * structure that stands clear of the ground. {@code FriendlySkeleton} takes a
 * non-null y as authoritative, so the ally's centre was 505 and its feet landed
 * at 519 where the ground under it is 607.6: floating 88 units, 177 px on a
 * 3040x1440 phone. Nothing in {@code update} touches y, so it never came down.
 *
 * <p>Python's {@code Game.make_ally(x)} takes only an x, and
 * {@code FriendlySkeleton.__init__} defaults y to its own {@code ground_y}.
 * The seam now takes only an x too, so the mistake cannot be made again.
 *
 * <h2>Why every path is exercised</h2>
 *
 * <p>Every existing ally test built one through a fake that passed {@code null}
 * -- which is the ground -- so all of them agreed with each other and none of
 * them agreed with the game. The production path is the Outpost with a trapped
 * Necromancer in it, and that is what these drive.
 *
 * <h2>The number that is asserted</h2>
 *
 * <p>{@code GROUND_Y}, 620, from {@code main.py}. Each unit draws a random
 * {@code depth} within a band of the ground line, so the invariant that holds
 * for every ground unit is <b>feet minus depth == 620</b> -- a constant from
 * the source, not a value read back from the thing being tested.
 */
class AllyGroundAlignmentTest {

    /** {@code main.py: GROUND_Y = 620}. */
    private static final float GROUND = 620f;

    private static TestRun withTrappedNecromancer(long seed) {
        TestRun t = new TestRun(seed);
        t.begin(GameMode.ENDLESS, "normal");
        t.run.session().addGold(100000);
        t.run.shop().buy("outpost");
        Enemy necro = t.run.spawnEnemy(EnemyType.NECROMANCER, 6);
        assertTrue(t.run.outpost().trap((Trappable) necro),
                "precondition: the outpost took a prisoner");
        return t;
    }

    /** Steps the world until the prisoner's timer raises one. */
    private static FriendlySkeleton raised(TestRun t) {
        for (int i = 0; i < 4000 && t.run.allyCount() == 0; i++) {
            t.step();
        }
        assertTrue(t.run.allyCount() > 0,
                "precondition: the trapped Necromancer raised an ally");
        FriendlySkeleton a = t.run.ally(0);
        assertNotNull(a);
        return a;
    }

    private static float feet(FriendlySkeleton a) {
        return a.y() + a.height() / 2f;
    }

    private static float feet(Enemy e) {
        return e.y() + e.height() / 2f;
    }

    @Test
    @DisplayName("an ally raised by the Outpost shares the mob ground baseline")
    void theRaisedAllyStandsWhereMobsStand() {
        TestRun t = withTrappedNecromancer(101L);
        FriendlySkeleton ally = raised(t);
        Enemy scout = t.run.spawnEnemy(EnemyType.SCOUT, 6);

        //  620 is main.py's GROUND_Y.  Each unit is offset from it by its own
        //  random depth, so the baseline is what has to agree -- and it has to
        //  agree with the constant, not merely with itself.
        assertEquals(GROUND, feet(ally) - ally.depth(), 0.01f,
                "the ally's feet are at " + feet(ally) + " with depth "
                        + ally.depth() + ", so it is standing on "
                        + (feet(ally) - ally.depth()) + " rather than the ground "
                        + "line at " + GROUND);
        assertEquals(GROUND, feet(scout) - scout.depth(), 0.01f,
                "precondition: an ordinary ground mob stands on GROUND_Y");
        assertEquals(feet(scout) - scout.depth(), feet(ally) - ally.depth(), 0.01f,
                "an ally and a mob must walk the same ground plane");
    }

    @Test
    @DisplayName("at equal depth an ally's feet land exactly where a mob's do")
    void atEqualDepthTheFeetCoincide() {
        TestRun t = withTrappedNecromancer(202L);
        FriendlySkeleton ally = raised(t);

        //  Depth is drawn per unit, so find a mob that happened to draw one
        //  close to the ally's and compare the feet directly.
        Enemy match = null;
        for (int i = 0; i < 400; i++) {
            Enemy e = t.run.spawnEnemy(EnemyType.SCOUT, 6);
            if (Math.abs(e.depth() - ally.depth()) <= 0.5f) {
                match = e;
                break;
            }
        }
        assertNotNull(match, "precondition: a mob drew a depth near the ally's");

        assertEquals(feet(match), feet(ally),
                Math.abs(match.depth() - ally.depth()) + 0.01f,
                "same depth, different ground: mob feet " + feet(match)
                        + " vs ally feet " + feet(ally));
    }

    @Test
    @DisplayName("it stays on the ground while it walks, with no drift")
    void walkingDoesNotLiftIt() {
        TestRun t = withTrappedNecromancer(303L);
        FriendlySkeleton ally = raised(t);
        float startY = ally.y();
        float startX = ally.x();

        int stepped = 0;
        for (int i = 0; i < 600 && ally.alive(); i++) {
            t.step();
            stepped++;
            assertEquals(GROUND, feet(ally) - ally.depth(), 0.01f,
                    "the ally left the ground plane after " + stepped
                            + " steps -- feet " + feet(ally));
        }
        assertTrue(stepped > 60, "precondition: it survived long enough to walk");
        assertEquals(startY, ally.y(), 0.0001f,
                "y moved during a walk; an ally advances in x only");
        assertTrue(ally.x() != startX || !ally.alive(),
                "precondition: it actually walked");
    }

    @Test
    @DisplayName("every creation path puts it on the same ground line")
    void allCreationPathsAgree() {
        //  Three ways one can come into being: the Outpost's raise timer, the
        //  world's own factory method, and the constructor a test reaches for.
        //  Before the fix only the first was wrong, and only the first is the
        //  one players see.
        TestRun t = withTrappedNecromancer(404L);
        FriendlySkeleton viaOutpost = raised(t);

        assertTrue(t.run.spawnAlly(700f), "the world raises one directly");
        FriendlySkeleton viaWorld = t.run.ally(t.run.allyCount() - 1);

        FriendlySkeleton viaConstructor =
                new FriendlySkeleton(t.run, t.run.session().wave(), 700f, null);

        for (FriendlySkeleton a : new FriendlySkeleton[] {
                viaOutpost, viaWorld, viaConstructor}) {
            assertEquals(GROUND, feet(a) - a.depth(), 0.01f,
                    "one creation path puts an ally at " + (feet(a) - a.depth())
                            + " instead of the ground line " + GROUND);
            assertEquals(a.groundY(), a.y(), 0.01f,
                    "and its y must be the ground y it computes for itself");
        }
    }

    @Test
    @DisplayName("the drawn feet land on the drawn ground line")
    void theRenderedFeetTouchTheGround() {
        //  The painter's own anchor: paintAlly takes the draw-space centre and
        //  works down to `bottom = y - h/2`, the same arithmetic EnemyPainter
        //  uses for frame[1].  The ground line in draw space is toDrawY(620 +
        //  depth) -- converted through the one boundary, never by hand, so this
        //  also fails if the y-down inversion is ever reintroduced.
        TestRun t = withTrappedNecromancer(505L);
        FriendlySkeleton ally = raised(t);

        float drawnBottom = WorldGeometry.toDrawY(ally.y()) - ally.height() / 2f;
        float groundLine = WorldGeometry.toDrawY(GROUND + ally.depth());

        assertEquals(groundLine, drawnBottom, 0.01f,
                "the ally is drawn with its feet " + (drawnBottom - groundLine)
                        + " units off the ground line");

        //  And the conversion really is the inverting one, not an identity that
        //  would make the assertion above vacuous.
        assertTrue(WorldGeometry.toDrawY(GROUND) < WorldGeometry.toDrawY(GROUND - 100f),
                "precondition: draw space is y-up while gameplay is y-down");
    }
}
