package com.mymmer.castledefense.enemy;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.defence.Outpost;
import com.mymmer.castledefense.defence.Projectile;
import com.mymmer.castledefense.defence.ProjectileKind;
import com.mymmer.castledefense.defence.Trappable;

/**
 * Hangs back and raises skeletons. Fling him into the Outpost.
 *
 * <p>He never closes with the castle: he halts at a stand-off point and works
 * from there, which is what makes him a problem the player has to go and solve
 * rather than one the towers eventually chew through.
 *
 * <h2>The betrayal, and the dependency direction</h2>
 *
 * <p>He is the only {@link Trappable} in the game. Note which way that points:
 * <b>this</b> class implements an interface declared in {@code defence}, and the
 * Outpost knows nothing about Necromancers. That is the Python arrangement —
 * {@code castle.py} never imports {@code enemies.py} — preserved literally.
 *
 * <h2>Rivals hunt the prisoner</h2>
 *
 * <p>Once one of them is in the cage, every other Necromancer switches targets.
 * The stand-off point moves out to the Outpost the moment there is a traitor to
 * punish: he used to march all the way to the barricade before noticing the cage
 * behind him, which put the prisoner out of the fight for the whole approach.
 */
public final class Necromancer extends Enemy implements Trappable {

    /** How far from the castle front he halts, before jitter. */
    public static final float STANDOFF = 400f;
    public static final double SUMMON_RATE = 3.4;
    public static final int MAX_MINIONS = 4;

    private double summonTimer = 2.0;
    private double castTimer;
    private final float standoffX;
    /** Live minions only; pruned each step so dead ones are not pinned. */
    private final Array<Enemy> minions = new Array<>(false, MAX_MINIONS);
    /** Visual only: the staff orb flare. */
    private float glow;

    public Necromancer(EnemyContext ctx, EnemyConfig config, int wave, Float x, Float y) {
        super(ctx, config, wave, x, y);
        this.castTimer = ctx.rng().uniformSeconds(1.5, 3.0);
        this.standoffX = GameConfig.CASTLE_FRONT + STANDOFF + ctx.rng().uniform(-40f, 60f);
    }

    public int minionCount() {
        return minions.size;
    }

    /** Where he would halt if there were no prisoner. */
    public float standoffX() {
        return standoffX;
    }

    /** Visual only. */
    public float glow() {
        return glow;
    }

    /** True while there is a turncoat in the Outpost for him to punish. */
    public boolean huntingPrisoner() {
        Outpost post = ctx.outpost();
        return post != null && post.hasPrisoner() && post.prisoner() != this;
    }

    /**
     * Where he halts.
     *
     * <p>A traitor in the Outpost outranks the castle, so the stand-off moves out
     * to whichever is further from the castle: his own point, or the tower.
     */
    public float currentStandoff() {
        if (huntingPrisoner()) {
            return Math.max(standoffX, ctx.outpost().x());
        }
        return standoffX;
    }

    @Override
    protected void think(double dt) {
        //  Deliberately does NOT call super: he has no interest in the
        //  barricade, the castle front or the blocked queue.  He walks to his
        //  line and then stands there casting.
        float fdt = (float) dt;
        anim += fdt * 3f;
        glow = Math.max(0f, glow - fdt * 2f);
        for (int i = minions.size - 1; i >= 0; i--) {
            if (!minions.get(i).alive()) {
                minions.removeIndex(i);
            }
        }

        float standoff = currentStandoff();
        if (x > standoff) {
            x -= speed * fdt;
            setVxEstimate(-speed);
            setState(EnemyState.WALK);
            return;
        }
        setState(EnemyState.ATTACK);

        summonTimer -= dt;
        if (summonTimer <= 0d && minions.size < MAX_MINIONS) {
            summonTimer = SUMMON_RATE;
            summon();
        }

        castTimer -= dt;
        if (castTimer <= 0d) {
            //  Halted at the Outpost rather than the wall means he is here for
            //  the prisoner and nothing else, so every bolt goes into the cage.
            //  Otherwise it is a coin weighted 65% toward the cage.
            boolean atPost = x > standoffX;
            if (huntingPrisoner() && (atPost || ctx.rng().game().nextFloat() < 0.65f)) {
                castTimer = GameConfig.RIVAL_BOLT_RATE;
                castAtPrisoner();
            } else {
                castTimer = ctx.rng().uniformSeconds(2.6, 4.0);
                castBolt();
            }
        }
    }

    /** Raises a skeleton just behind him and files it with the horde. */
    public void summon() {
        Enemy sk = ctx.createEnemy(EnemyType.SKELETON, wave,
                x - ctx.rng().uniform(20f, 60f), null);
        sk.setY(sk.groundY());
        minions.add(sk);
        ctx.spawnEnemy(sk);
        glow = 1f;
    }

    /** An ordinary bolt at the wall. */
    public void castBolt() {
        float tx = ctx.castle().frontX() - 20f;
        float ty = GameConfig.WALL_TOP + 60f;
        float a = (float) Math.atan2(ty - y, tx - x);
        float speedOf = 430f;
        ctx.addProjectile(new Projectile(ctx, x, y - 8f,
                (float) Math.cos(a) * speedOf, (float) Math.sin(a) * speedOf,
                ProjectileKind.MAGIC, damage(),
                0f, 0, 0f, true, 5f, 0f, 1f, 1f, false, uid()));
        glow = 1f;
    }

    /**
     * Punishes the turncoat: a bolt aimed at the Outpost cage.
     *
     * <p>Flagged {@code atPrisoner}, which makes it ignore the barricade, the
     * towers and the walls entirely — it only ever resolves against the cage.
     */
    public void castAtPrisoner() {
        Outpost post = ctx.outpost();
        float a = (float) Math.atan2(post.aimPointY() - y, post.aimPointX() - x);
        float speedOf = 470f;
        ctx.addProjectile(new Projectile(ctx, x, y - 8f,
                (float) Math.cos(a) * speedOf, (float) Math.sin(a) * speedOf,
                ProjectileKind.MAGIC, GameConfig.RIVAL_BOLT_DAMAGE,
                0f, 0, 0f, true, 5f, 0f, 1f, 1f, true, uid()));
        glow = 1f;
    }

    // --- Trappable ----------------------------------------------------------

    @Override
    public float mass() {
        return config.mass;
    }

    /**
     * Caged.
     *
     * <p>The minion list is dropped now rather than being left to pin dead
     * Skeletons for as long as he lives — he stops thinking in there, so nothing
     * would ever prune it.
     */
    @Override
    public void onTrapped() {
        minions.clear();
        markTrapped();
    }

    @Override
    public void moveTo(float nx, float ny) {
        setX(nx);
        setY(ny);
    }
}
