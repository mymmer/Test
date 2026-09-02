package com.mymmer.castledefense.testsupport;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.mock.graphics.MockGraphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.GdxNativesLoader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * The minimum graphics environment a headless test needs to lay out a viewport.
 *
 * <p>Applying a viewport goes {@code Viewport.apply} → {@code HdpiUtils
 * .glViewport} → {@code Gdx.gl.glViewport}, and {@code HdpiUtils} also reads
 * {@code Gdx.graphics} on the way. So both must exist, and the headless backend
 * ships neither: it installs a {@code MockGraphics} only when a full
 * {@code HeadlessApplication} is started, and no GL binding at all.
 *
 * <p>Installing both here keeps every test that needs viewport maths
 * self-contained, rather than depending on whichever test class happened to
 * start an application first — which is exactly the sort of ordering dependency
 * that makes a suite flaky.
 *
 * <p>This is the one place the project uses reflection, and the reason is
 * narrow: {@code GL20} declares ~350 methods and a hand-written stub would be
 * hundreds of lines of noise nobody will read. Test scope only; it never reaches
 * a shipped artifact.
 */
public final class GlStub {

    private GlStub() {
    }

    private static boolean installedGraphics;

    /**
     * Installs a GL binding and, if none exists, a mock {@code Gdx.graphics}.
     * Idempotent, and it never replaces a real backend's objects.
     */
    public static void install() {
        //  Camera maths goes through Matrix4.prj, which is a native method.
        //  A HeadlessApplication would load the natives for us; a test that
        //  does not start one has to ask. Idempotent by design in libGDX.
        GdxNativesLoader.load();
        if (Gdx.graphics == null) {
            Gdx.graphics = new MockGraphics();
            installedGraphics = true;
        }
        if (Gdx.gl != null) {
            return;
        }
        GL20 stub = (GL20) Proxy.newProxyInstance(
                GL20.class.getClassLoader(),
                new Class<?>[]{GL20.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return defaultValue(method.getReturnType());
                    }
                });
        Gdx.gl = stub;
        Gdx.gl20 = stub;
    }

    public static void uninstall() {
        Gdx.gl = null;
        Gdx.gl20 = null;
        if (installedGraphics) {
            Gdx.graphics = null;
            installedGraphics = false;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == void.class) {
            return null;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}
