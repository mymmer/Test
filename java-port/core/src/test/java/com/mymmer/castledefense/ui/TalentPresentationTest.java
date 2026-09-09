package com.mymmer.castledefense.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.game.GameMode;
import com.mymmer.castledefense.talent.TalentBranch;
import com.mymmer.castledefense.talent.TalentDef;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The talent tree stays complete, and looking is not buying.
 *
 * <h2>What changed and what it risked</h2>
 *
 * <p>The nodes were enlarged for a phone — a 13-unit name is 7.4 dp on a
 * 3040x1440 panel — and a detail panel was added for the selected talent, which
 * is the touch equivalent of the source's hover tooltip. Both take vertical
 * space, and the deepest branch has eight talents.
 *
 * <p>{@code scrollBy} already existed and <b>nothing called it</b>, so before
 * this the tree could not scroll at all: any talent below the visible rows was
 * simply unreachable. Growing the nodes without noticing that would have hidden
 * three of them for good.
 */
class TalentPresentationTest {

    private static final int[][] DEVICES = {
        {1280, 720}, {2340, 1080}, {3040, 1440},
    };

    private static TestUi talents(int w, int h) {
        TestUi t = new TestUi(w, h).withInsets(142, 0, 0, 0, 0, 168, 84, 0);
        t.startRun(GameMode.CLASSIC, "normal");
        t.ui.navigation().openTalents();
        t.ui.layout();
        return t;
    }

    @Test
    @DisplayName("every talent can be reached by scrolling, on every shape")
    void everyTalentIsReachable() {
        for (int[] device : DEVICES) {
            TestUi t = talents(device[0], device[1]);
            TalentScreen screen = t.ui.talents();

            int total = 0;
            for (TalentBranch b : TalentBranch.values()) {
                total += t.run.run.talentTree().table().branch(b).size;
            }
            assertTrue(total >= 38, "precondition: the tree still has "
                    + total + " talents");

            //  Walk the scroll from top to bottom and collect what is visible.
            Set<String> seen = new HashSet<>();
            for (int pass = 0; pass <= screen.maxScroll(); pass++) {
                t.ui.layout();
                Array<UiRect> nodes = screen.nodes();
                for (int i = 0; i < nodes.size; i++) {
                    if (nodes.get(i).visible()) {
                        seen.add(screen.order().get(i).id);
                    }
                }
                screen.scrollBy(1);
            }

            assertEquals(total, seen.size(),
                    device[0] + "x" + device[1] + ": only " + seen.size()
                            + " of " + total + " talents can ever be seen -- the "
                            + "rest are laid out below the panel with no way to "
                            + "scroll to them");
        }
    }

    @Test
    @DisplayName("scroll controls appear only when there is somewhere to go")
    void scrollControlsAppearOnlyWhenUseful() {
        TestUi t = talents(3040, 1440);
        TalentScreen screen = t.ui.talents();
        if (screen.maxScroll() == 0) {
            assertFalse(screen.scrollUpButton().visible());
            assertFalse(screen.scrollDownButton().visible());
            return;
        }
        //  At the top: down only.
        assertFalse(screen.scrollUpButton().visible(), "nothing above the top");
        assertTrue(screen.scrollDownButton().visible(), "more below");

        screen.scrollBy(screen.maxScroll());
        t.ui.layout();
        assertTrue(screen.scrollUpButton().visible(), "more above");
        assertFalse(screen.scrollDownButton().visible(), "nothing below the end");
    }

    @Test
    @DisplayName("the first tap inspects and does not spend a point")
    void inspectingDoesNotBuy() {
        TestUi t = talents(3040, 1440);
        t.run.run.talentTree().award(3, "test");
        int before = t.run.run.talentTree().availablePoints();
        assertTrue(before > 0, "precondition: there are points to spend");

        TalentScreen screen = t.ui.talents();
        UiRect node = screen.nodes().get(0);
        TalentDef def = screen.order().get(0);
        int rankBefore = t.run.run.talentTree().rank(def.id);

        t.press(node);

        assertEquals(def.id, screen.selected(),
                "the first tap must select, so the player can read it");
        assertEquals(rankBefore, t.run.run.talentTree().rank(def.id),
                "the first tap spent a point -- a player cannot look without "
                        + "buying");
        assertEquals(before, t.run.run.talentTree().availablePoints());
    }

    @Test
    @DisplayName("a second tap on the same node does buy, through TalentTree")
    void theSecondTapBuys() {
        TestUi t = talents(3040, 1440);
        t.run.run.talentTree().award(3, "test");
        TalentScreen screen = t.ui.talents();

        //  The first talent of a branch is the one with no prerequisite.
        UiRect node = null;
        TalentDef def = null;
        for (int i = 0; i < screen.nodes().size; i++) {
            TalentDef d = screen.order().get(i);
            if (t.run.run.talentTree().canPurchase(d.id)) {
                node = screen.nodes().get(i);
                def = d;
                break;
            }
        }
        assertNotNull(def, "precondition: something is purchasable");
        int rankBefore = t.run.run.talentTree().rank(def.id);

        t.press(node);
        t.press(node);

        assertEquals(rankBefore + 1, t.run.run.talentTree().rank(def.id),
                "the second tap must spend the point, through the tree");
    }

    @Test
    @DisplayName("the value shown is the source's, not a guess from perRank")
    void valuesAreFormattedAsTheSourceWrites() {
        TestUi t = talents(1280, 720);
        //  spikedot is the one that breaks a "below 1.0 means a percentage"
        //  rule: perRank 0.9, and main.py writes it as {v:.0f}.
        TalentDef spike = t.run.run.talentTree().table().get("spikedot");
        assertNotNull(spike, "precondition: the talent exists");
        assertEquals(TalentDef.ValueFormat.DECIMAL, spike.format,
                "spikedot is written as a number in the source");
        assertFalse(spike.formatValue(1).contains("%"),
                "a percentage here would tell the player 90% where the game "
                        + "means 0.9: " + spike.formatValue(1));

        TalentDef crit = t.run.run.talentTree().table().get("crit");
        assertTrue(crit.formatValue(1).endsWith("%"),
                "crit chance is a percentage in the source");
        assertEquals("5%", crit.formatValue(1), "perRank 0.05 at rank 1");
    }

    @Test
    @DisplayName("no talent silently falls back to printing no value")
    void everyTalentCarriesTheSourcesFormat() {
        //  Counted out of main.py's TALENTS list, not out of talents.json:
        //  30 write {v:.0%}, four {v:.0f}, one {v:.1%}, one {v:.1f}, and
        //  exactly two -- Sentinels and Tempest -- have no {v} at all.
        //
        //  The loader defaults a missing "format" to NONE, which prints
        //  nothing. That is the right default for the two that want it and
        //  silent data loss for the other 36, and it had already swallowed
        //  lightfingers and stormwinds before this test existed.
        TestUi t = talents(1280, 720);
        int percent = 0, percent1 = 0, number = 0, decimal = 0;
        Set<String> valueless = new HashSet<>();
        for (TalentBranch b : TalentBranch.values()) {
            Array<TalentDef> branch = t.run.run.talentTree().table().branch(b);
            for (int i = 0; i < branch.size; i++) {
                TalentDef d = branch.get(i);
                switch (d.format) {
                    case PERCENT:  percent++;  break;
                    case PERCENT1: percent1++; break;
                    case NUMBER:   number++;   break;
                    case DECIMAL:  decimal++;  break;
                    default:       valueless.add(d.id); break;
                }
            }
        }
        assertEquals(30, percent, "main.py writes {v:.0%} thirty times");
        assertEquals(1, percent1, "and {v:.1%} once");
        assertEquals(4, number, "and {v:.0f} four times");
        assertEquals(1, decimal, "and {v:.1f} once");
        assertEquals(new HashSet<>(java.util.Arrays.asList("sentinels", "tempest")),
                valueless,
                "only Sentinels and Tempest have no {v} in the source; anything "
                        + "else here is a talent whose value the detail panel "
                        + "will not print: " + valueless);
    }
}
