package com.mymmer.castledefense.text;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.backends.headless.HeadlessFiles;
import com.badlogic.gdx.Gdx;
import com.mymmer.castledefense.enemy.EnemyType;
import com.mymmer.castledefense.skill.SkillId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keys the game builds at runtime, which no scan of the source can see.
 *
 * <h2>Why a third localisation test</h2>
 *
 * <p>There are now three classes of key and each hid a live defect from the one
 * before it:
 *
 * <ol>
 *   <li><b>Literal</b> — {@code Strings.get("hud.horn")}. Scanned since Phase 12,
 *       after an emulator drew {@code !difficulty.normal.name!}.</li>
 *   <li><b>Conditional</b> — {@code Strings.get(spent ? "a" : "b")}. The Phase 12
 *       pattern required the literal to sit immediately after the bracket, so it
 *       walked past every one of these; a phone drew
 *       {@code !hud.hornSpent!} while that test passed. Fixed in Phase 13.</li>
 *   <li><b>Composed</b> — {@code Strings.get("enemy." + id + ".name")}. No
 *       pattern over the source can resolve these, and they were explicitly out
 *       of scope. So a phone announced {@code New foe:
 *       !enemy.foot_soldier.name!} and drew {@code !skill.lightning.short!}
 *       under every skill slot.</li>
 * </ol>
 *
 * <p>This test takes the third class the only way it can be taken: by walking
 * the actual enumerations the game composes keys from, and asking the bundle for
 * each one. It cannot be fooled by a key shape nobody uses, and it fails the day
 * a new enemy or skill is added without its strings.
 */
class ComposedKeysTest {

    @BeforeAll
    static void load() {
        //  Strings.load needs Gdx.files, which a headless test does not have --
        //  the trap Phase 12 found, where the bundle silently never loaded and
        //  every key "passed". loadFrom takes an explicit handle instead.
        Gdx.files = new HeadlessFiles();
        Strings.loadFrom(Gdx.files.internal("i18n/strings"), Locale.ENGLISH);
        assertTrue(Strings.isLoaded(),
                "the bundle did not load, so every assertion below would be "
                        + "vacuous -- which is exactly how this went unnoticed");
    }

    @AfterAll
    static void unload() {
        Gdx.files = null;
    }

    /** Collects missing keys rather than failing on the first, so one run names them all. */
    private static void require(String key, List<String> missing) {
        String value = Strings.get(key);
        if (value == null || value.isEmpty() || value.equals("!" + key + "!")) {
            missing.add(key);
        }
    }

    private static void assertNoneMissing(List<String> missing) {
        assertTrue(missing.isEmpty(),
                "the game builds these keys at runtime and the bundle has no "
                        + "entry, so a player sees the raw key on screen: "
                        + missing);
    }

    @Test
    @DisplayName("every enemy has the name the NEW FOE banner asks for")
    void everyEnemyHasABannerName() {
        List<String> missing = new ArrayList<>();
        for (EnemyType t : EnemyType.values()) {
            //  UiRenderer.subjectKey builds "enemy.<id>" for NEW_FOE -- with no
            //  ".name" suffix, which is what it used to add.
            require("enemy." + t.id(), missing);
        }
        assertFalse(EnemyType.values().length == 0, "no enemies to check");
        assertNoneMissing(missing);
    }

    @Test
    @DisplayName("every skill has a name, a description and a slot caption")
    void everySkillHasItsThreeStrings() {
        List<String> missing = new ArrayList<>();
        for (SkillId id : SkillId.values()) {
            require("skill." + id.id() + ".name", missing);
            require("skill." + id.id() + ".desc", missing);
            //  The three-letter caption under the slot. It had no keys at all.
            require("skill." + id.id() + ".short", missing);
        }
        assertNoneMissing(missing);
    }

    @Test
    @DisplayName("every boss the banner can name has a name")
    void everyBossHasABannerName() {
        List<String> missing = new ArrayList<>();
        for (String id : new String[] {"troll_king", "dragon", "lich_lord"}) {
            require("boss." + id, missing);
        }
        assertNoneMissing(missing);
    }

    @Test
    @DisplayName("a key the bundle really lacks is still reported as missing")
    void theCheckItselfCanFail() {
        //  Guards the guard. If require() ever stopped detecting an absent key
        //  -- a marker change, a fallback that returns the key itself -- every
        //  test above would pass while proving nothing, which is the failure
        //  mode this whole file exists because of.
        List<String> missing = new ArrayList<>();
        require("enemy.no_such_creature", missing);
        assertTrue(missing.contains("enemy.no_such_creature"),
                "a missing key was not detected, so these tests cannot fail");
    }
}
