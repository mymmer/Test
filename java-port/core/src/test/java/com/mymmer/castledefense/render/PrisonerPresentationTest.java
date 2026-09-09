package com.mymmer.castledefense.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.defence.Outpost;
import com.mymmer.castledefense.defence.Trappable;
import com.mymmer.castledefense.enemy.Enemy;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.progress.TestRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The caged Necromancer, and the health the bar above him reads.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code castle.py Outpost.draw_prisoner} draws a cage, the hunched prisoner
 * inside it, four bars, a health bar and two captions. None of it was ported.
 * The gameplay was complete — he was trapped, drained, shot at by rival
 * Necromancers, regenerated and released on death — and the outpost on screen
 * looked exactly as it did when empty.
 *
 * <h2>What is asserted</h2>
 *
 * <p>The state the renderer reads, through its whole lifecycle. The painter
 * itself needs a GL context; what can be pinned headlessly is that the values
 * it draws from are the prisoner's own and behave as the source says — which is
 * where the real risk lies, because the easy mistake is to give the
 * <em>Outpost</em> a health pool so the bar has something to show.
 */
class PrisonerPresentationTest {

    private static Outpost withPrisoner(TestRun t) {
        t.run.session().addGold(100000);
        t.run.shop().buy("outpost");
        Enemy necro = t.run.spawnEnemy(EnemyType.NECROMANCER, 6);
        assertTrue(necro instanceof Trappable, "precondition: a Necromancer traps");
        assertTrue(t.run.outpost().trap((Trappable) necro),
                "precondition: the outpost took him");
        return t.run.outpost();
    }

    private static TestRun run() {
        TestRun t = new TestRun(77L);
        t.begin(GameMode.ENDLESS, "normal");
        return t;
    }

    @Test
    @DisplayName("an empty outpost has no prisoner to draw")
    void emptyDrawsNothing() {
        TestRun t = run();
        t.run.session().addGold(100000);
        t.run.shop().buy("outpost");

        assertFalse(t.run.outpost().hasPrisoner(),
                "the cage is drawn from hasPrisoner, so an empty outpost must "
                        + "report empty");
    }

    @Test
    @DisplayName("a fresh capture shows a full bar")
    void freshCaptureIsFull() {
        TestRun t = run();
        Outpost post = withPrisoner(t);

        assertTrue(post.hasPrisoner());
        assertNotNull(post.prisoner());
        assertEquals(GameConfig.PRISONER_HP, post.prisonerMax(), 0.01f);
        assertEquals(1f, post.prisonerHp() / post.prisonerMax(), 0.001f,
                "a new prisoner starts on a full pool");
    }

    @Test
    @DisplayName("the bar reads the prisoner's pool, not the outpost's")
    void theBarIsThePrisoners() {
        TestRun t = run();
        Outpost post = withPrisoner(t);
        float full = post.prisonerMax();

        post.hurtPrisoner(full * 0.45f);

        assertEquals(0.55f, post.prisonerHp() / post.prisonerMax(), 0.005f,
                "the fraction the bar draws must follow the damage dealt");
        //  The Outpost itself is healthless in the source and must stay so: a
        //  bar fed from a pool invented for it would look right and mean
        //  nothing.
        assertEquals(GameConfig.PRISONER_HP, post.prisonerMax(), 0.01f,
                "the prisoner's maximum is the shared constant, not a value "
                        + "the outpost keeps for itself");
    }

    @Test
    @DisplayName("being shot raises the UNDER FIRE flag, which then lapses")
    void underFireLapses() {
        TestRun t = run();
        Outpost post = withPrisoner(t);

        post.hurtPrisoner(10f);
        assertTrue(post.prisonerHit() > 0d,
                "the second caption is drawn while this is positive");

        for (int i = 0; i < 120; i++) {
            post.updatePrisoner(1d / 60d);
        }
        assertEquals(0d, post.prisonerHit(), 1e-6,
                "it must lapse, or UNDER FIRE never comes off the screen");
    }

    @Test
    @DisplayName("he regenerates once the fire stops, up to his cap")
    void regenerationRefillsTheBar() {
        TestRun t = run();
        Outpost post = withPrisoner(t);
        post.hurtPrisoner(post.prisonerMax() * 0.5f);
        float wounded = post.prisonerHp();

        for (int i = 0; i < 180; i++) {
            post.updatePrisoner(1d / 60d);
        }

        assertTrue(post.prisonerHp() > wounded,
                "the bar should climb again once nobody is shooting");
        assertTrue(post.prisonerHp() <= post.prisonerMax() + 0.001f,
                "and must not overfill past the cap the bar is drawn against");
    }

    @Test
    @DisplayName("killing him empties the cage, so nothing is left drawn")
    void deathClearsTheCage() {
        TestRun t = run();
        Outpost post = withPrisoner(t);

        post.hurtPrisoner(post.prisonerMax() * 2f);

        assertFalse(post.hasPrisoner(),
                "a dead prisoner must leave no cage behind");
        assertEquals(0f, post.prisonerHp(), 0.001f,
                "and no residual health for a bar to draw");
    }

    @Test
    @DisplayName("the cage can be filled again after a death")
    void theOutpostTakesAnother() {
        TestRun t = run();
        Outpost post = withPrisoner(t);
        post.hurtPrisoner(post.prisonerMax() * 2f);
        assertFalse(post.hasPrisoner(), "precondition: empty again");

        Enemy second = t.run.spawnEnemy(EnemyType.NECROMANCER, 6);
        assertTrue(post.trap((Trappable) second), "a second capture is allowed");
        assertEquals(1f, post.prisonerHp() / post.prisonerMax(), 0.001f,
                "and starts full rather than inheriting the last one's pool");
    }
}
