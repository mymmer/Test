package com.mymmer.castledefense.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.render.ViewportSet;
import com.mymmer.castledefense.testsupport.GlStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pointer ownership and consumption, proven with generic fixtures — the real
 * grab/strip/regalia mechanics arrive in Phases 5-7 and change none of this.
 */
class InputRouterTest {

    /** Records what the world was asked to do. */
    private static final class WorldFixture implements WorldInteractionHandler {
        final Array<String> log = new Array<>();
        boolean accept = true;
        int owningPointer = -1;

        @Override
        public boolean onWorldPress(Pointer pointer) {
            log.add("press:" + pointer.id());
            if (accept) {
                owningPointer = pointer.id();
            }
            return accept;
        }

        @Override
        public void onWorldDrag(Pointer pointer) {
            log.add("drag:" + pointer.id());
        }

        @Override
        public void onWorldRelease(Pointer pointer) {
            log.add("release:" + pointer.id());
            owningPointer = -1;
        }

        @Override
        public void onWorldCancel(Pointer pointer) {
            log.add("cancel:" + pointer.id());
            owningPointer = -1;
        }
    }

    /** A button that claims presses inside a rectangle of UI space. */
    private static final class ButtonFixture implements UiConsumer {
        final Array<String> log = new Array<>();
        final float x;
        final float y;
        final float w;
        final float h;
        boolean claim = true;

        ButtonFixture(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        @Override
        public boolean onPress(Pointer pointer) {
            boolean inside = pointer.uiX() >= x && pointer.uiX() <= x + w
                    && pointer.uiY() >= y && pointer.uiY() <= y + h;
            if (inside && claim) {
                log.add("press:" + pointer.id());
                return true;
            }
            return false;
        }

        @Override
        public void onDrag(Pointer pointer) {
            log.add("drag:" + pointer.id());
        }

        @Override
        public void onRelease(Pointer pointer) {
            log.add("release:" + pointer.id());
        }
    }

    private ViewportSet viewports;
    private GameInput input;
    private InputRouter router;
    private WorldFixture world;

    @BeforeEach
    void setUp() {
        GlStub.install();
        viewports = new ViewportSet();
        viewports.resize(1280, 720);        // 1:1 with the world, so screen==world
        input = new GameInput(viewports);
        router = new InputRouter(input);
        world = new WorldFixture();
        router.setWorldHandler(world);
    }

    @AfterEach
    void tearDown() {
        GlStub.uninstall();
    }

    /** Screen y is measured from the top; world y from the bottom. */
    private void press(int pointerId, int screenX, int screenY) {
        input.touchDown(screenX, screenY, pointerId, 0);
    }

    private void drag(int pointerId, int screenX, int screenY) {
        input.touchDragged(screenX, screenY, pointerId);
    }

    private void release(int pointerId, int screenX, int screenY) {
        input.touchUp(screenX, screenY, pointerId, 0);
    }

    private void step() {
        router.route();
        input.endStep();
    }

    @Test
    @DisplayName("a pointer acquires the world interaction")
    void acquireInteraction() {
        press(0, 600, 400);
        step();
        assertEquals("press:0", world.log.first());
        assertTrue(input.ownsInteraction(0));
        assertEquals(0, input.interactionOwner());
    }

    @Test
    @DisplayName("a second pointer cannot steal an active interaction")
    void secondPointerCannotSteal() {
        press(0, 600, 400);
        step();
        assertTrue(input.ownsInteraction(0));

        press(1, 700, 300);
        step();
        assertFalse(input.ownsInteraction(1), "pointer 1 must not take over");
        assertTrue(input.ownsInteraction(0), "pointer 0 must keep it");
        assertFalse(world.log.contains("press:1", false),
                "and the world must never see a second press: " + world.log);
    }

    @Test
    @DisplayName("a non-owner's release does not end the owner's interaction")
    void nonOwnerReleaseIsIgnored() {
        press(0, 600, 400);
        step();
        press(1, 700, 300);
        step();

        release(1, 700, 300);
        step();
        assertTrue(input.ownsInteraction(0), "pointer 1 lifting must change nothing");
        assertFalse(world.log.contains("release:1", false));
    }

    @Test
    @DisplayName("the owner's release ends the interaction")
    void ownerReleaseEndsIt() {
        press(0, 600, 400);
        step();
        release(0, 800, 200);
        step();
        assertTrue(world.log.contains("release:0", false));
        assertFalse(input.hasInteraction());
        assertEquals(Pointer.NO_OWNER, input.interactionOwner());
    }

    @Test
    @DisplayName("cancellation ends the interaction distinctly from a release")
    void cancellationEndsItSafely() {
        press(0, 600, 400);
        step();
        input.cancelPointer(0);         // Android stole the gesture
        step();
        assertTrue(world.log.contains("cancel:0", false),
                "a cancel must not be reported as a normal release: " + world.log);
        assertFalse(world.log.contains("release:0", false));
        assertFalse(input.hasInteraction());
    }

    @Test
    @DisplayName("only the owner receives drags")
    void onlyOwnerDrags() {
        press(0, 600, 400);
        step();
        press(1, 700, 300);
        step();
        world.log.clear();

        drag(0, 620, 380);
        drag(1, 720, 280);
        step();
        assertTrue(world.log.contains("drag:0", false));
        assertFalse(world.log.contains("drag:1", false), "a non-owner must not drag the world");
    }

    @Test
    @DisplayName("a UI-consumed pointer never reaches the world")
    void uiConsumesPointer() {
        ButtonFixture button = new ButtonFixture(0f, 0f, 200f, 100f);
        router.addConsumer(button);

        // press inside the button: UI space y is measured from the bottom, so a
        // screen y near the bottom of the window lands in the button
        press(0, 50, 700);
        step();
        assertEquals("press:0", button.log.first());
        assertEquals(0, world.log.size, "the world must not see a consumed press");
        assertFalse(input.hasInteraction());
        assertSame(button, input.pointer(0).consumedBy());
    }

    @Test
    @DisplayName("a consumed pointer stays consumed after dragging off the control")
    void consumptionIsStickyForTheWholeGesture() {
        ButtonFixture button = new ButtonFixture(0f, 0f, 200f, 100f);
        router.addConsumer(button);

        press(0, 50, 700);
        step();
        // slide the finger far away from the button, over the battlefield
        drag(0, 900, 300);
        step();
        release(0, 900, 300);
        step();

        assertTrue(button.log.contains("drag:0", false),
                "the button keeps the finger: " + button.log);
        assertTrue(button.log.contains("release:0", false));
        assertEquals(0, world.log.size,
                "sliding off a button must never turn into a world grab");
    }

    @Test
    @DisplayName("a consumer that declines lets the press through")
    void decliningConsumerPassesThrough() {
        ButtonFixture button = new ButtonFixture(0f, 0f, 200f, 100f);
        button.claim = false;
        router.addConsumer(button);

        press(0, 50, 700);
        step();
        assertEquals("press:0", world.log.first());
        assertNull(input.pointer(0).consumedBy());
    }

    @Test
    @DisplayName("consumers are offered a press in registration order")
    void consumerPriority() {
        ButtonFixture first = new ButtonFixture(0f, 0f, 1280f, 720f);
        ButtonFixture second = new ButtonFixture(0f, 0f, 1280f, 720f);
        router.addConsumer(first);
        router.addConsumer(second);

        press(0, 100, 100);
        step();
        assertEquals(1, first.log.size, "the first registered consumer wins");
        assertEquals(0, second.log.size);
    }

    @Test
    @DisplayName("a world handler that declines does not hold the interaction")
    void decliningWorldHandlerReleasesOwnership() {
        world.accept = false;
        press(0, 600, 400);
        step();
        assertFalse(input.hasInteraction(),
                "refusing a press must not leave the pointer owning nothing");
    }

    @Test
    @DisplayName("pointer positions reach gameplay in world units, not pixels")
    void coordinatesAreWorldSpace() {
        // 1280x720 screen == 1280x720 world, y flipped
        press(0, 640, 360);
        step();
        Pointer p = input.pointer(0);
        assertEquals(640f, p.worldX(), 0.5f);
        assertEquals(360f, p.worldY(), 0.5f);

        // and on a phone-shaped screen the same physical centre is still (640,360)
        viewports.resize(2400, 1080);
        press(1, 1200, 540);
        step();
        Pointer q = input.pointer(1);
        assertEquals(640f, q.worldX(), 0.5f,
                "world coordinates must not depend on the device");
        assertEquals(360f, q.worldY(), 0.5f);
    }

    @Test
    @DisplayName("cancelling everything ends any interaction, as on backgrounding")
    void cancelAll() {
        press(0, 600, 400);
        step();
        input.cancelAll();
        step();
        assertFalse(input.hasInteraction());
        assertTrue(world.log.contains("cancel:0", false));
    }
}
