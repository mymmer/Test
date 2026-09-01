package com.mymmer.castledefense.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.testsupport.GlStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The world must be 1280x720 on every device, and it must letterbox rather than
 * stretch. These are the numbers a 20:9 phone will actually produce.
 */
class ViewportSetTest {

    @BeforeAll
    static void installGl() {
        GlStub.install();
    }

    @AfterAll
    static void removeGl() {
        GlStub.uninstall();
    }

    @Test
    @DisplayName("world viewport is exactly 1280x720 world units whatever the screen")
    void worldSizeIsConstant() {
        ViewportSet v = new ViewportSet();
        int[][] screens = {
                {1280, 720},    // 16:9 reference
                {1920, 1080},   // 16:9 FHD
                {2160, 1080},   // 18:9
                {2340, 1080},   // 19.5:9
                {2400, 1080},   // 20:9
                {2560, 1440},   // 16:9 QHD
                {1080, 2400},   // portrait, the pathological case
        };
        for (int[] s : screens) {
            v.resize(s[0], s[1]);
            assertEquals(GameConfig.WORLD_WIDTH, v.getWorld().getWorldWidth(), 0.001f,
                    "world width changed on " + s[0] + "x" + s[1]);
            assertEquals(GameConfig.WORLD_HEIGHT, v.getWorld().getWorldHeight(), 0.001f,
                    "world height changed on " + s[0] + "x" + s[1]);
        }
    }

    @Test
    @DisplayName("a 20:9 phone gets pillarboxes, not a wider battlefield")
    void ultrawidePillarboxes() {
        ViewportSet v = new ViewportSet();
        v.resize(2400, 1080);
        // fit by height: 1080/720 = 1.5 -> 1920x1080 inside a 2400 wide screen
        assertEquals(1920, v.getWorld().getScreenWidth());
        assertEquals(1080, v.getWorld().getScreenHeight());
        assertEquals(240, v.getLetterboxX(), "expected 240px bars either side");
        assertEquals(0, v.getLetterboxY());
        assertEquals(1.5f, v.getWorldScale(), 0.001f);
    }

    @Test
    @DisplayName("a 4:3 screen letterboxes top and bottom")
    void narrowLetterboxes() {
        ViewportSet v = new ViewportSet();
        v.resize(1024, 768);
        assertEquals(1024, v.getWorld().getScreenWidth());
        assertEquals(576, v.getWorld().getScreenHeight());
        assertEquals(0, v.getLetterboxX());
        assertEquals(96, v.getLetterboxY());
    }

    @Test
    @DisplayName("the UI viewport keeps 720 units of height and gains width")
    void uiExtendsOnTallScreens() {
        ViewportSet v = new ViewportSet();
        v.resize(2400, 1080);
        assertEquals(720f, v.getUi().getWorldHeight(), 0.001f);
        assertEquals(1600f, v.getUi().getWorldWidth(), 0.5f,
                "20:9 should hand the HUD 1600x720 of UI space");
        // and the world is untouched by that
        assertEquals(1280f, v.getWorld().getWorldWidth(), 0.001f);
    }

    @Test
    @DisplayName("screen coordinates convert back to world units")
    void screenToWorldRoundTrip() {
        ViewportSet v = new ViewportSet();
        v.resize(2400, 1080);
        // centre of the physical screen is the centre of the world
        var world = v.screenToWorld(1200, 540);
        assertEquals(640f, world.x, 0.5f);
        assertEquals(360f, world.y, 0.5f);
        // inside the left pillarbox: outside the world, negative x
        var bar = v.screenToWorld(60, 540);
        assertTrue(bar.x < 0f, "a touch in the pillarbox must land outside the world");
    }
}
