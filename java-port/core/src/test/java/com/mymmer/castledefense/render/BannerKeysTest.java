package com.mymmer.castledefense.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessFiles;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.progress.Announcements;
import com.mymmer.castledefense.skill.SkillId;
import com.mymmer.castledefense.text.Strings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The key a banner really builds resolves to a real string.
 *
 * <h2>Asserting the mapping, not the bundle</h2>
 *
 * <p>{@code ComposedKeysTest} asserts the bundle contains {@code enemy.<id>}.
 * That passed throughout the defect, because the bundle did contain it — the
 * <em>renderer</em> was asking for {@code enemy.<id>.name}, a key nobody had
 * ever written. A test that picks its own key shape and finds it present has
 * proved something true and irrelevant.
 *
 * <p>So this drives the production mapping, {@code UiRenderer.subjectKey}, with
 * real banners built from the real enumerations, and looks up whatever it
 * returns. It fails if the mapping and the bundle disagree, which is the only
 * thing that matters to a player reading a banner.
 */
class BannerKeysTest {

    @BeforeAll
    static void load() {
        Gdx.files = new HeadlessFiles();
        Strings.loadFrom(Gdx.files.internal("i18n/strings"), Locale.ENGLISH);
        assertTrue(Strings.isLoaded(), "the bundle did not load");
    }

    @AfterAll
    static void unload() {
        Gdx.files = null;
    }

    /** The one banner of each kind, built the way the game builds it. */
    private static Announcements.Banner banner(Announcements.Id id, String subject) {
        Announcements a = new Announcements();
        a.post(id, 0, subject, 1d);
        return a.get(0);
    }

    private static void requireResolved(Announcements.Banner b, List<String> bad) {
        String key = UiRenderer.subjectKey(b);
        String value = Strings.get(key);
        if (value == null || value.isEmpty() || value.equals("!" + key + "!")) {
            bad.add(b.id + " -> " + key);
        }
    }

    @Test
    @DisplayName("NEW FOE names every enemy, through the renderer's own mapping")
    void newFoeResolvesForEveryEnemy() {
        List<String> bad = new ArrayList<>();
        for (EnemyType t : EnemyType.values()) {
            requireResolved(banner(Announcements.Id.NEW_FOE, t.id()), bad);
        }
        assertTrue(bad.isEmpty(),
                "the renderer builds a key the bundle does not have, so the "
                        + "banner shows the raw key: " + bad);
    }

    @Test
    @DisplayName("SKILL UNLOCKED names every skill")
    void skillUnlockedResolvesForEverySkill() {
        List<String> bad = new ArrayList<>();
        for (SkillId id : SkillId.values()) {
            requireResolved(banner(Announcements.Id.SKILL_UNLOCKED, id.id()), bad);
        }
        assertTrue(bad.isEmpty(), "unresolved: " + bad);
    }

    @Test
    @DisplayName("BOSS banners name every boss")
    void bossBannersResolve() {
        List<String> bad = new ArrayList<>();
        for (String id : new String[] {"troll_king", "dragon", "lich_lord"}) {
            requireResolved(banner(Announcements.Id.BOSS_APPROACHES, id), bad);
            requireResolved(banner(Announcements.Id.BOSS_ARRIVES, id), bad);
        }
        assertTrue(bad.isEmpty(), "unresolved: " + bad);
    }

    @Test
    @DisplayName("the enemy key carries no .name suffix -- the shape that broke")
    void theEnemyKeyShapeIsPinned() {
        assertEquals("enemy.foot_soldier",
                UiRenderer.subjectKey(
                        banner(Announcements.Id.NEW_FOE, "foot_soldier")),
                "the NEW_FOE key grew a '.name' suffix again; the bundle uses "
                        + "the bare id, exactly as the boss case does");
    }
}
