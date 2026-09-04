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
