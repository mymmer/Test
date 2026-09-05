package com.mymmer.castledefense;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.badlogic.gdx.utils.Array;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dependency rules that are easy to state and easy to break silently.
 *
 * <p>Each of these is an architectural invariant the whole port rests on, and
 * each would be violated by a single well-meaning import that compiles fine and
 * passes every behavioural test. So they are checked against the source itself.
 *
 * <p>The check is a plain source scan, not a bytecode analyser: the rules are
 * about what a package is <em>allowed to name</em>, and an import is exactly
 * that. Reflection could evade it; nothing in this codebase uses reflection.
 *
 * <p>Comments are stripped before scanning. Several of these files <em>discuss</em>
 * the banned thing in a javadoc — {@code DefenceContext} says "never
 * {@code new Random()}" — and a scan that flagged the documentation for a rule
 * as a violation of it would be worse than no scan.
 */
class ArchitectureTest {

    private static final Path SOURCE_ROOT =
            new File("../core/src/main/java/com/mymmer/castledefense").toPath();

    /**
     * Gameplay may not name the asset system.
     *
     * <p><b>This is the "a skin cannot change gameplay" invariant, enforced
     * structurally.</b> If gameplay cannot import {@code assets}, it cannot read
     * an {@code AttachmentPoint}, so a skin's coordinates can never reach a
     * decision the player feels. Positions gameplay does need are
     * {@link com.mymmer.castledefense.config.GameplayAnchor}s, from gameplay data.
     */
    @Test
    @DisplayName("no gameplay package may import the assets package")
    void gameplayNeverImportsAssets() {
        String[] gameplayPackages =
                {"enemy", "boss", "defence", "interaction", "game", "entity", "progress"};
        Array<String> violations = new Array<>();
        for (String pkg : gameplayPackages) {
            scan(SOURCE_ROOT.resolve(pkg), file -> {
                String text = read(file);
                if (text.contains("import com.mymmer.castledefense.assets.")) {
                    violations.add(SOURCE_ROOT.relativize(file).toString()
                            + " imports the assets package");
                }
            });
        }
        if (violations.size > 0) {
            fail("a skin must never be able to change gameplay, so gameplay must not "
                    + "name the asset system:\n  " + violations.toString("\n  ")
                    + "\n\nIf you need a position, use config.GameplayAnchor.");
        }
    }

    @Test
    @DisplayName("the enemy package may not name a concrete boss")
    void enemyNeverImportsBoss() {
        //  boss extends enemy, so the arrow runs boss -> enemy.  An enemy that
        //  named a boss class would close the loop, and it is exactly the kind
        //  of import an "is this a Troll King?" check would add.  Boss identity
        //  is Enemy.isBoss(), a flag on the base.
        Array<String> violations = new Array<>();
        scan(SOURCE_ROOT.resolve("enemy"), file -> {
            String text = read(file);
            if (text.contains("import com.mymmer.castledefense.boss.")) {
                violations.add(SOURCE_ROOT.relativize(file).toString());
            }
        });
        if (violations.size > 0) {
            fail("enemy must not depend on boss; use Enemy.isBoss():\n  "
                    + violations.toString("\n  "));
        }
    }

    @Test
    @DisplayName("nothing switches on a concrete boss class")
    void noInstanceofOnConcreteBosses() {
        //  Boss immunity and boss behaviour are flags and overrides, never
        //  "instanceof TrollKing" scattered through unrelated systems.  The
        //  interaction layer may name the Boss BASE (it has to, to offer a
        //  disruption); naming a specific one is the thing being prevented.
        Array<String> violations = new Array<>();
        scan(SOURCE_ROOT, file -> {
            String text = read(file);
            for (String concrete : new String[]{"TrollKing", "Dragon", "LichLord"}) {
                if (text.contains("instanceof " + concrete)) {
                    violations.add(SOURCE_ROOT.relativize(file) + " -> instanceof " + concrete);
                }
            }
        });
        if (violations.size > 0) {
            fail("expose a flag or override instead:\n  " + violations.toString("\n  "));
        }
    }

    @Test
    @DisplayName("the defence package may not name a concrete enemy")
    void defenceNeverImportsEnemy() {
        //  castle.py never imports enemies.py.  Towers and projectiles talk to
        //  the narrow Target/Trappable contracts declared in `defence` itself.
        Array<String> violations = new Array<>();
        scan(SOURCE_ROOT.resolve("defence"), file -> {
            String text = read(file);
            if (text.contains("import com.mymmer.castledefense.enemy.")) {
                violations.add(SOURCE_ROOT.relativize(file).toString());
            }
        });
        if (violations.size > 0) {
            fail("defence must not depend on enemy (castle.py never imports "
                    + "enemies.py):\n  " + violations.toString("\n  "));
        }
    }

    @Test
    @DisplayName("gameplay packages may not name a rendering type")
    void gameplayNeverImportsRendering() {
        String[] gameplayPackages =
                {"enemy", "boss", "defence", "interaction", "entity", "progress"};
        Array<String> violations = new Array<>();
        for (String pkg : gameplayPackages) {
            scan(SOURCE_ROOT.resolve(pkg), file -> {
                String text = read(file);
                for (String banned : new String[]{
                        "import com.mymmer.castledefense.render.",
                        "import com.badlogic.gdx.graphics.",
                        "import com.badlogic.gdx.scenes.",
                }) {
                    if (text.contains(banned)) {
                        violations.add(SOURCE_ROOT.relativize(file) + " -> " + banned);
                    }
                }
            });
        }
        if (violations.size > 0) {
            fail("gameplay classes hold no rendering code:\n  "
                    + violations.toString("\n  "));
        }
    }

    @Test
    @DisplayName("core may not name a platform backend")
    void coreNeverImportsABackend() {
        Array<String> violations = new Array<>();
        scan(SOURCE_ROOT, file -> {
            String text = read(file);
            for (String banned : new String[]{
                    "import android.", "import androidx.",
                    "import com.badlogic.gdx.backends.",
                    "import org.lwjgl.",
            }) {
                if (text.contains(banned)) {
                    violations.add(SOURCE_ROOT.relativize(file) + " -> " + banned);
                }
            }
        });
        if (violations.size > 0) {
            fail("core is platform-independent:\n  " + violations.toString("\n  "));
        }
    }

    @Test
    @DisplayName("gameplay may not use java.util.Random")
    void gameplayNeverUsesJavaRandom() {
        //  All randomness comes from the seeded Rng, or a bug report cannot be
        //  reproduced from its seed.
        Array<String> violations = new Array<>();
        scan(SOURCE_ROOT, file -> {
            String text = read(file);
            if (text.contains("new Random(") || text.contains("Math.random(")) {
                violations.add(SOURCE_ROOT.relativize(file).toString());
            }
        });
        if (violations.size > 0) {
            fail("use the seeded Rng:\n  " + violations.toString("\n  "));
        }
    }

    // ========================================================================
    //  Phase 10: the interface is downstream of everything
    // ========================================================================

    /**
     * Gameplay may not name the interface.
     *
     * <p>The dependency runs one way: screens read subsystems, subsystems have
     * never heard of screens. This is what makes the whole gameplay suite
     * runnable without a UI, and what stops a rule like "the shop is closed"
     * from being expressed as "the shop screen is not showing".
     */
    @Test
    @DisplayName("no gameplay package may import the ui package")
    void gameplayNeverImportsUi() {
        String[] gameplayPackages = {"enemy", "boss", "defence", "interaction",
            "entity", "progress", "talent", "shop", "skill", "config", "util"};
        Array<String> violations = new Array<>();
        for (String pkg : gameplayPackages) {
            scan(SOURCE_ROOT.resolve(pkg), file -> {
                if (read(file).contains("import com.mymmer.castledefense.ui.")) {
                    violations.add(SOURCE_ROOT.relativize(file).toString());
                }
            });
        }
        if (violations.size > 0) {
            fail("gameplay must not know the interface exists:\n  "
                    + violations.toString("\n  "));
        }
    }

    /**
     * The interface may not draw, and may not be Scene2D.
     *
     * <p>Two rules that happen to share a scan. Layout code holding a
     * {@code SpriteBatch} is how a screen ends up impossible to test headlessly;
     * every one of the UI tests runs because these classes compute rectangles and
     * nothing else, and the renderer reads those rectangles from the outside.
     *
     * <p>The {@code scenes.scene2d} half is the Phase 10 framework decision made
     * permanent. Scene2D brings its own actor tree, its own hit detection and its
     * own input multiplexer — a second interaction model beside
     * {@code InputRouter}'s pointer ownership, which is the one thing the port
     * cannot afford two of. See {@code UI.md}, "why not Scene2D".
     */
    @Test
    @DisplayName("the ui package neither draws nor uses Scene2D")
    void uiHoldsNoGraphics() {
        Array<String> violations = new Array<>();
        scan(SOURCE_ROOT.resolve("ui"), file -> {
            String text = read(file);
            for (String banned : new String[]{
                    "import com.badlogic.gdx.graphics.",
                    "import com.badlogic.gdx.scenes.",
            }) {
                if (text.contains(banned)) {
                    violations.add(SOURCE_ROOT.relativize(file) + " -> " + banned);
                }
            }
        });
        if (violations.size > 0) {
            fail("the interface computes layout; it does not paint:\n  "
                    + violations.toString("\n  "));
        }
    }

    @Test
    @DisplayName("Scene2D is not used anywhere in core")
    void noScene2dAtAll() {
        Array<String> violations = new Array<>();
        scan(SOURCE_ROOT, file -> {
            if (read(file).contains("com.badlogic.gdx.scenes.scene2d")) {
                violations.add(SOURCE_ROOT.relativize(file).toString());
            }
        });
        if (violations.size > 0) {
            fail("a second input and layout model has appeared:\n  "
                    + violations.toString("\n  "));
        }
    }

    /**
     * The interface has no randomness and no clock of its own.
     *
     * <p>Both would desynchronise the same way. A screen drawing from the run's
     * {@code Rng} makes the simulation depend on how many buttons were pressed;
     * a screen reading a wall clock makes a cooldown ring finish at a different
     * moment from the cooldown. Everything the interface shows about time comes
     * from the subsystem that owns it — {@code SkillPanel.cooldownRemaining},
     * {@code RunSession.playTime} — which is frozen exactly when the world is.
     */
    @Test
    @DisplayName("the ui package has neither an Rng nor a clock")
    void uiHasNoRngAndNoClock() {
        Array<String> violations = new Array<>();
        scan(SOURCE_ROOT.resolve("ui"), file -> {
            String text = read(file);
            for (String banned : new String[]{
                    "new Rng(", "new RandomXS128(", "Math.random(",
                    "import java.util.Random",
                    "System.currentTimeMillis(", "System.nanoTime(",
                    "Gdx.graphics.getDeltaTime(", "Gdx.graphics.getRawDeltaTime(",
                    "TimeUtils.",
            }) {
                if (text.contains(banned)) {
                    violations.add(SOURCE_ROOT.relativize(file) + " -> " + banned);
                }
            }
        });
        if (violations.size > 0) {
            fail("the interface must not have its own randomness or its own time:\n  "
                    + violations.toString("\n  "));
        }
    }

    /**
     * No screen decides where the game goes next by itself.
     *
     * <p>Every transition is a {@code Navigation} call. If a screen could set the
     * state directly, the graph in {@code Navigation} would stop being the whole
     * truth about routing — and the run-difficulty guarantee, which is enforced
     * as a routing rule, would stop being enforceable.
     */
    @Test
    @DisplayName("no screen sets the game state directly")
    void screensRouteThroughNavigation() {
        Array<String> violations = new Array<>();
        scan(SOURCE_ROOT.resolve("ui"), file -> {
            if (file.getFileName().toString().equals("Navigation.java")) {
                return;             // the graph is allowed to be the graph
            }
            String text = read(file);
            //  Narrowly the ROUTING calls.  UiRect.setState is a control's own
            //  look -- normal, selected -- and has nothing to do with the game
            //  state; a rule that banned the word would ban the wrong thing.
            for (String banned : new String[]{
                    "world().setState(", ".setState(GameState.",
                    ".startRun(", ".resetToMenu("}) {
                if (text.contains(banned)) {
                    violations.add(SOURCE_ROOT.relativize(file) + " -> " + banned);
                }
            }
        });
        if (violations.size > 0) {
            fail("routing belongs to Navigation alone:\n  "
                    + violations.toString("\n  "));
        }
    }

    @Test
    @DisplayName("comment stripping does not hide real code")
    void stripCommentsKeepsCode() {
        String src = "import a.B;\n// import c.D;\n/* import e.F; */\nimport g.H;";
        String stripped = stripComments(src);
        assertTrue(stripped.contains("import a.B;"));
        assertTrue(stripped.contains("import g.H;"));
        assertTrue(!stripped.contains("c.D"), "line comment removed");
        assertTrue(!stripped.contains("e.F"), "block comment removed");
    }

    @Test
    @DisplayName("the scan actually found the source tree")
    void scanIsNotVacuous() {
        //  Every test above passes trivially if the path is wrong.  This one
        //  fails loudly instead.
        final int[] count = {0};
        scan(SOURCE_ROOT, file -> count[0]++);
        assertTrue(count[0] > 50,
                "expected the whole core source tree, found " + count[0]
                        + " files under " + SOURCE_ROOT.toAbsolutePath());
    }

    // --- helpers ------------------------------------------------------------

    private interface FileCheck {
        void check(Path file);
    }

    private static void scan(Path root, FileCheck check) {
        if (!Files.isDirectory(root)) {
            fail("not a directory: " + root.toAbsolutePath());
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(check::check);
        } catch (IOException e) {
            fail("could not walk " + root + ": " + e);
        }
    }

    /** File contents with comments removed, so documentation cannot trip a rule. */
    private static String read(Path file) {
        try {
            return stripComments(new String(
                    Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("could not read " + file + ": " + e);
            return "";
        }
    }

    /**
     * Removes block and line comments.
     *
     * <p>Deliberately simple: it does not track string literals, because no rule
     * here looks for something that appears inside one. Erring toward removing
     * too much would only ever hide a violation from a scan that is a safety net
     * rather than the primary defence.
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else {
                out.append(source.charAt(i++));
            }
        }
        return out.toString();
    }
}
