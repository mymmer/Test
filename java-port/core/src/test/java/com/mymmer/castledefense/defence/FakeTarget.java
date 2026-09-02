package com.mymmer.castledefense.defence;

import com.badlogic.gdx.utils.Array;

/**
 * A stand-in for a Phase 6 {@code Enemy}.
 *
 * <p>Deliberately dumb: fields, a damage log, and nothing else. Phase 5 is
 * allowed to know that something shootable exists, not what it is — so rather
 * than inventing a Scout to test towers with, the tests use this, and when the
 * real {@code Enemy} arrives it implements the same {@link Target} contract and
 * these tests keep meaning what they mean.
 */
final class FakeTarget implements Trappable {

    private static long nextUid = 1;

    private final long uid = nextUid++;

    boolean alive = true;
    boolean targetable = true;
    boolean flying;
    boolean heavy;
    float x;
    float y;
    float width = 30f;
    float height = 40f;
    float hp = 100f;
    float vx;
    float vy;
    float mass = 3f;
    boolean trapped;

    /** Every hit, in order: useful for asserting counters and falloff. */
    final Array<Float> damageTaken = new Array<>();
    final Array<String> damageSources = new Array<>();

    FakeTarget(float x, float y) {
        this.x = x;
        this.y = y;
    }

    static FakeTarget ground(float x) {
        return new FakeTarget(x, com.mymmer.castledefense.config.GameConfig.GROUND_Y - 20f);
    }

    static FakeTarget flyer(float x, float y) {
        FakeTarget t = new FakeTarget(x, y);
        t.flying = true;
        return t;
    }

    static FakeTarget heavy(float x) {
        FakeTarget t = ground(x);
        t.heavy = true;
        return t;
    }

    float totalDamage() {
        float sum = 0f;
        for (Float f : damageTaken) {
            sum += f;
        }
        return sum;
    }

    @Override
    public long uid() {
        return uid;
    }

    @Override
    public boolean alive() {
        return alive;
    }

    @Override
    public boolean targetable() {
        return targetable;
    }

    @Override
    public float x() {
        return x;
    }

    @Override
    public float y() {
        return y;
    }

    @Override
    public float width() {
        return width;
    }

    @Override
    public float height() {
        return height;
    }

    @Override
    public boolean flying() {
        return flying;
    }

    @Override
    public boolean heavy() {
        return heavy;
    }

    @Override
    public float hp() {
        return hp;
    }

    @Override
    public float vxEstimate() {
        return vx;
    }

    @Override
    public float vyEstimate() {
        return vy;
    }

    @Override
    public void takeDamage(float amount, String source) {
        damageTaken.add(amount);
        damageSources.add(source);
        hp -= amount;
        if (hp <= 0f) {
            hp = 0f;
            alive = false;
        }
    }

    @Override
    public float mass() {
        return mass;
    }

    @Override
    public void onTrapped() {
        trapped = true;
    }

    @Override
    public void moveTo(float nx, float ny) {
        this.x = nx;
        this.y = ny;
    }

    @Override
    public String toString() {
        return "FakeTarget#" + uid + "@" + x + "," + y + (alive ? "" : " DEAD");
    }
}
