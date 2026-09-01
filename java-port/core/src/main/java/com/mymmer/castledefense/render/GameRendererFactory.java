package com.mymmer.castledefense.render;

/**
 * Builds the renderer once a GL context exists.
 *
 * <p>A named interface rather than {@code Supplier<GameRenderer>} purely for
 * readability — "renderer factory" says what this is at every call site, and a
 * single-method interface costs nothing. It is <em>not</em> an API-level
 * workaround: the Android module enables core-library desugaring, so
 * {@code java.util.function}, {@code Optional}, {@code java.time} and the
 * streams API are all available down to minSdk 21. Where those are avoided in
 * this codebase it is a performance decision about per-frame allocation, not a
 * compatibility one (see {@code java-port/README.md}).
 */
public interface GameRendererFactory {
    GameRenderer create();
}
