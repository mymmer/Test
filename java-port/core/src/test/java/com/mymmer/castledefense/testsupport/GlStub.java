package com.mymmer.castledefense.testsupport;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * A do-nothing {@link GL20} binding for headless tests.
 *
 * <p>libGDX applies a viewport by calling {@code glViewport}, so viewport layout
 * — the letterboxing this port depends on — cannot be asserted without *some*
 * GL binding present. The headless backend does not ship one.
 *
 * <p>This is the one place the project uses reflection, and the reason is
 * narrow: {@code GL20} declares ~350 methods and a hand-written stub would be
 * hundreds of lines of noise that no one will ever read. It is test scope only
 * and never reaches a shipped artifact.
 */
public final class GlStub {

    private GlStub() {
    }

    /** Installs the stub into {@code Gdx.gl}/{@code Gdx.gl20}. Idempotent. */
    public static void install() {
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
