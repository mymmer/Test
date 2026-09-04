package com.mymmer.castledefense.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.game.Simulation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The banner queue: stable ids, arguments and lifetimes. */
class AnnouncementsTest {

    @Test
    @DisplayName("a banner ages on the gameplay clock and expires when its life runs out")
    void lifecycle() {
        Announcements a = new Announcements();
        a.post(Announcements.Id.WAVE_START, 3, 1.0);
        assertEquals(1, a.size());
        assertEquals(1.0, a.latest(Announcements.Id.WAVE_START).remaining(), 0d);

        for (int i = 0; i < 59; i++) {
            a.age(Simulation.FIXED_DT);
        }
        assertEquals(1, a.size(), "still up at 59 steps");

        a.age(Simulation.FIXED_DT);
        assertEquals(0, a.size(), "gone on the 60th");
        assertNull(a.latest(Announcements.Id.WAVE_START));
    }

    @Test
    @DisplayName("banners carry stable ids and arguments, never rendered sentences")
    void carriesIdsNotStrings() {
        Announcements a = new Announcements();
        a.post(Announcements.Id.NEW_FOE, 7, EnemyType.NECROMANCER.id(), 4.0);

        Announcements.Banner b = a.latest(Announcements.Id.NEW_FOE);
        assertNotNull(b);
        assertEquals(Announcements.Id.NEW_FOE, b.id);
        assertEquals(7, b.amount);
        assertEquals("necromancer", b.subject,
                "the subject is the stable id the i18n table keys off");
    }

    @Test
    @DisplayName("several banners can be up at once, in the order they were posted")
    void ordering() {
        Announcements a = new Announcements();
        a.post(Announcements.Id.WAVE_START, 1, 3.0);
        a.post(Announcements.Id.WEATHER_STORM, 3.0);
        a.post(Announcements.Id.BOSS_APPROACHES, 1, "dragon", 3.0);

        assertEquals(3, a.size());
        assertEquals(Announcements.Id.WAVE_START, a.get(0).id);
        assertEquals(Announcements.Id.WEATHER_STORM, a.get(1).id);
        assertEquals(Announcements.Id.BOSS_APPROACHES, a.get(2).id);
    }

    @Test
    @DisplayName("the shorter banner expires first and the longer one survives")
    void independentLifetimes() {
        Announcements a = new Announcements();
        a.post(Announcements.Id.WAVE_START, 1, 0.5);
        a.post(Announcements.Id.WEATHER_STORM, 3.0);

        for (int i = 0; i < 40; i++) {
            a.age(Simulation.FIXED_DT);
        }
        assertEquals(1, a.size());
        assertTrue(a.has(Announcements.Id.WEATHER_STORM));
        assertFalse(a.has(Announcements.Id.WAVE_START));
    }

    @Test
    @DisplayName("a zero or negative lifetime posts nothing")
    void refusesNonsense() {
        Announcements a = new Announcements();
        a.post(Announcements.Id.WAVE_START, 1, 0.0);
        a.post(Announcements.Id.WAVE_START, 1, -2.0);
        a.post(null, 1, "", 3.0);
        assertEquals(0, a.size());
    }

    @Test
    @DisplayName("starting a run clears whatever the last one left up")
    void newRunClearsBanners() {
        TestRun r = new TestRun().beginClassic();
        assertTrue(r.run.announcements().size() > 0, "wave 1 announces itself");

        r.run.beginRun(com.mymmer.castledefense.game.GameMode.ENDLESS,
                r.difficulty("normal"), 7L);
        assertFalse(r.run.announcements().has(Announcements.Id.WAVE_START),
                "no Classic banner survives into an Endless run");
        assertTrue(r.run.announcements().has(Announcements.Id.ENDLESS_BEGIN));
    }

    @Test
    @DisplayName("a Classic wave announces itself, its new foes and its boss")
    void classicWaveBanners() {
        TestRun r = new TestRun().beginClassic();
        assertTrue(r.run.announcements().has(Announcements.Id.WAVE_START));

        //  wave 5 is a boss wave in the shipped composition
        for (int w = 1; w < 5; w++) {
            for (int guard = 0; guard < 400 && r.waves().pendingSpawns() > 0; guard++) {
                r.seconds(1.0);
                r.killEveryEnemy();
            }
            r.killEveryEnemy();
            r.seconds(1.5);
            r.run.startNextWave();
        }
        assertEquals(5, r.session().wave());
        assertTrue(r.run.announcements().has(Announcements.Id.BOSS_APPROACHES),
                "a boss wave warns the player before the boss walks in");
    }
}
