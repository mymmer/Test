package com.mymmer.castledefense.render;

/**
 * Builds the renderer once a GL context exists.
 *
 * <p>Deliberately a hand-written interface rather than {@code java.util.function
 * .Supplier}: {@code java.util.function} is API 24+ on Android and this project
 * targets minSdk 21 without core-library desugaring, so {@code core} avoids it.
 */
public interface GameRendererFactory {
    GameRenderer create();
}
