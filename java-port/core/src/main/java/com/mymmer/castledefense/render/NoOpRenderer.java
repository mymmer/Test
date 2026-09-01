package com.mymmer.castledefense.render;

/**
 * A renderer that draws nothing.
 *
 * <p>Used by the headless test backend, where there is no GL context to create
 * a batch or a font with. It exists so tests exercise the real application
 * class and the real lifecycle rather than a parallel implementation.
 */
public final class NoOpRenderer implements GameRenderer {

    private volatile int createCount;
    private volatile int resizeCount;
    private volatile int renderCount;
    private volatile int disposeCount;

    @Override
    public void create(ViewportSet viewports) {
        createCount++;
    }

    @Override
    public void resize(ViewportSet viewports, int width, int height) {
        resizeCount++;
    }

    @Override
    public void render(ViewportSet viewports, float alpha) {
        renderCount++;
    }

    @Override
    public void dispose() {
        disposeCount++;
    }

    public int getCreateCount() {
        return createCount;
    }

    public int getResizeCount() {
        return resizeCount;
    }

    public int getRenderCount() {
        return renderCount;
    }

    public int getDisposeCount() {
        return disposeCount;
    }
}
