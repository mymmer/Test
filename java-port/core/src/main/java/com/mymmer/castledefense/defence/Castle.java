package com.mymmer.castledefense.defence;

import com.badlogic.gdx.utils.Array;
import com.mymmer.castledefense.config.GameConfig;
import com.mymmer.castledefense.debug.TraceEvent;
import com.mymmer.castledefense.util.Collisions;

/**
 * The player's keep: hit points, wall tiers, emplacements and the towers on them.
 *
 * <p>Gameplay only. The Python class also renders six visual wall tiers into a
 * cached surface; none of that is here (Phase 11). What survives from the tier
 * system is what the tier <em>means</em>: maximum health, how many tower slots
 * exist, and the label the HUD shows.
 *
 * <h2>Reinforcement never refuses</h2>
 *
 * <p>Past the last visual tier the walls keep getting stronger — each further
 * level adds {@code ENDLESS_WALL_STEP * ENDLESS_WALL_GROWTH^(n-1)} health and
 * the appearance simply stops changing. This is deliberate in the Python (the
 * self-test asserts "Reinforce Walls must never refuse" and that the card stays
 * buyable for ever), so Endless has something to spend on at any wave.
 */
public final class Castle {

    /** Health added by the first level past the last visual tier. */
    public static final float ENDLESS_WALL_STEP = 520f;
    /** Compounding growth of that step for each level after it. */
    public static final float ENDLESS_WALL_GROWTH = 1.16f;

    private final DefenceContext ctx;
    private final DefenceTable table;

    private final float frontX = GameConfig.CASTLE_FRONT;
    private final float keepRight = 132f;

    private int wallLevel = 1;
    private float maxHp;
    private float hp;
    private final Array<DefenceTower> towers = new Array<>(false, 9);

    /** Visual only: damage flash, decays at 5/s. */
    private float flash;

    public Castle(DefenceContext ctx, DefenceTable table) {
        if (ctx == null || table == null) {
            throw new IllegalArgumentException("ctx and table must not be null");
        }
        this.ctx = ctx;
        this.table = table;
        this.maxHp = table.tier(0).maxHp;
        this.hp = maxHp;
    }

    // --- geometry -----------------------------------------------------------

    /** x of the castle's front face. Enemies stop here; shots resolve against it. */
    public float frontX() {
        return frontX;
    }

    /** x of the keep's right edge, behind the curtain wall. */
    public float keepRight() {
        return keepRight;
    }

    // --- tiers --------------------------------------------------------------

    public int wallLevel() {
        return wallLevel;
    }

    /** The tier whose <em>look</em> applies: clamped at the last one. */
    public int tierIndex() {
        return Collisions.clamp(wallLevel - 1, 0, table.tierCount() - 1);
    }

    /** True once reinforcement has passed the last visual tier. */
    public boolean visualCapped() {
        return wallLevel > table.tierCount();
    }

    public CastleTier tier() {
        return table.tier(tierIndex());
    }

    /** The last visual tier's level number. */
    public int maxVisualLevel() {
        return table.tierCount();
    }

    /**
     * What the HUD shows: the tier name, plus the reinforcement count once the
     * appearance has topped out ({@code "Runed Obsidian +6"}).
     */
    public String tierLabel() {
        if (visualCapped()) {
            return table.tier(table.tierCount() - 1).name + " +"
                    + (wallLevel - table.tierCount());
        }
        return tier().name;
    }

    public float hp() {
        return hp;
    }

    public float maxHp() {
        return maxHp;
    }

    /** Visual only. */
    public float flash() {
        return flash;
    }

    /**
     * Reinforces the walls. Never refuses.
     *
     * <p>Up to the last tier the gain is the difference between tier maxima;
     * beyond it the gain compounds. Either way the current health rises by the
     * same amount, so reinforcing mid-fight is a real heal, and every existing
     * platform gets 22% tougher with it.
     */
    public boolean upgradeWall() {
        wallLevel++;
        float gained;
        if (wallLevel <= table.tierCount()) {
            gained = table.tier(tierIndex()).maxHp - maxHp;
        } else {
            int over = wallLevel - table.tierCount();
            gained = (float) (ENDLESS_WALL_STEP * Math.pow(ENDLESS_WALL_GROWTH, over - 1));
        }
        maxHp += gained;
        hp = Math.min(maxHp, hp + gained);
        for (int i = 0; i < towers.size; i++) {
            towers.get(i).scaleMaxHp(1.22f);
        }
        return true;
    }

    /** Repairs a fraction of the maximum. Returns how much was actually healed. */
    public float repair(float frac) {
        float healed = Math.min(maxHp - hp, maxHp * frac);
        hp += healed;
        return healed;
    }

    /** Python's default repair fraction. */
    public float repair() {
        return repair(0.35f);
    }

    // --- tower slots --------------------------------------------------------

    /** How many emplacements are unlocked: {@code min(all, 4 + wallLevel)}. */
    public int slotCapacity() {
        return Math.min(table.slotCount(), 4 + wallLevel);
    }

    /** Slots that are unlocked and unoccupied, in declaration order. */
    public Array<TowerSlot> freeSlots() {
        Array<TowerSlot> free = new Array<>(false, table.slotCount());
        int capacity = slotCapacity();
        for (int i = 0; i < capacity; i++) {
            TowerSlot s = table.slot(i);
            boolean used = false;
            for (int t = 0; t < towers.size; t++) {
                DefenceTower tower = towers.get(t);
                if (tower.x() == s.x && tower.y() == s.y) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                free.add(s);
            }
        }
        return free;
    }

    /**
     * Stations a new tower, or returns null when there is no space.
     *
     * <p>Wall (front) slots are filled first so the defences look deliberate:
     * Python sorts the free slots by descending x. The sort is <b>stable</b>
     * there, so slots at the same x keep their declaration order, and this
     * insertion sort preserves that.
     *
     * <p>The new tower's health is scaled by the wall level it is being built on
     * ({@code 1.22^(level-1)}) and the tower-HP talent — an absolute set, not a
     * multiply, because the tower starts at its config maximum.
     */
    public DefenceTower addTower(TowerType type) {
        Array<TowerSlot> free = freeSlots();
        if (free.size == 0) {
            return null;
        }
        stableSortByDescendingX(free);
        TowerSlot slot = free.first();
        DefenceTower t = table.createTower(ctx, type, slot.x, slot.y);
        float boost = (float) Math.pow(1.22, wallLevel - 1) * ctx.modifiers().towerHp();
        t.setMaxHpAndHeal(t.maxHp() * boost);
        towers.add(t);
        return t;
    }

    /** Insertion sort: stable, and the list is at most nine long. */
    private static void stableSortByDescendingX(Array<TowerSlot> slots) {
        for (int i = 1; i < slots.size; i++) {
            TowerSlot key = slots.get(i);
            int j = i - 1;
            while (j >= 0 && slots.get(j).x < key.x) {
                slots.set(j + 1, slots.get(j));
                j--;
            }
            slots.set(j + 1, key);
        }
    }

    /** Every tower, in the order they were stationed. */
    public Array<DefenceTower> towers() {
        return towers;
    }

    public int towerCount() {
        return towers.size;
    }

    /** How many towers of one type are stationed. */
    public int countOf(TowerType type) {
        int n = 0;
        for (int i = 0; i < towers.size; i++) {
            if (towers.get(i).type() == type) {
                n++;
            }
        }
        return n;
    }

    /**
     * The first live tower under a point, or null.
     *
     * <p>Disabled towers are transparent: a hostile shell passes through the
     * rubble and carries on to the wall behind it.
     */
    public DefenceTower towerAt(float x, float y) {
        for (int i = 0; i < towers.size; i++) {
            DefenceTower t = towers.get(i);
            if (!t.disabled() && t.contains(x, y)) {
                return t;
            }
        }
        return null;
    }

    // --- damage -------------------------------------------------------------

    /**
     * Takes a hit on the walls.
     *
     * <p>Already-dead castles absorb nothing, so a volley landing on the same
     * frame as the killing blow cannot trigger defeat twice.
     */
    public void takeDamage(float amount) {
        if (hp <= 0f) {
            return;
        }
        amount *= ctx.modifiers().damageTaken();
        hp -= amount;
        //  proportional to how hard the blow was, so a stream of small hits never
        //  leaves the castle permanently tinted red
        flash = Math.min(1f, flash + amount / Math.max(1f, maxHp * 0.10f));
        ctx.trace().event(TraceEvent.CASTLE_DAMAGE, ctx.step(), 0L, amount, hp, null);
        if (hp <= 0f) {
            hp = 0f;
            ctx.onCastleDestroyed();
        }
    }

    /**
     * A blast near the wall.
     *
     * <p>Only the horizontal gap to the front face counts for the wall itself —
     * a shell landing behind the wall is at gap zero and hits full. Towers take
     * <b>half</b> damage and are measured from their mid-height, and a stunning
     * blast stuns them for the longer of the two durations.
     */
    public void splashHit(float x, float y, float radius, float damage, double stun) {
        float gap = Math.max(0f, x - frontX);
        if (gap <= radius) {
            takeDamage(damage * (1f - 0.5f * gap / Math.max(1f, radius)));
        }
        for (int i = 0; i < towers.size; i++) {
            DefenceTower t = towers.get(i);
            if (t.disabled()) {
                continue;
            }
            float dy = (t.y() - t.height() / 2f) - y;
            float dx = t.x() - x;
            if (Math.sqrt(dx * dx + dy * dy) <= radius) {
                t.takeDamage(damage * 0.5f);
                t.applyStun(stun);
            }
        }
    }

    /**
     * A boss picks a live tower at random and hits it.
     *
     * <p>The entry point bosses use in Phase 7. It is here rather than there
     * because the choice is made over the castle's own list, and the RNG draw is
     * from the seeded gameplay stream so a replay smashes the same tower.
     */
    public DefenceTower smashRandomTower(float damage, double stun) {
        int live = 0;
        for (int i = 0; i < towers.size; i++) {
            if (!towers.get(i).disabled()) {
                live++;
            }
        }
        if (live == 0) {
            return null;
        }
        int pick = ctx.rng().game().nextInt(live);
        for (int i = 0; i < towers.size; i++) {
            DefenceTower t = towers.get(i);
            if (t.disabled()) {
                continue;
            }
            if (pick-- == 0) {
                t.takeDamage(damage);
                t.applyStun(stun);
                return t;
            }
        }
        return null;
    }

    /** Brings every tower back to full. */
    public void restoreTowers() {
        for (int i = 0; i < towers.size; i++) {
            towers.get(i).restore();
        }
    }

    // --- step ---------------------------------------------------------------

    public void update(double dt) {
        flash = Math.max(0f, flash - (float) dt * 5f);      // visual
        for (int i = 0; i < towers.size; i++) {
            towers.get(i).update(dt);
        }
    }

    @Override
    public String toString() {
        return "Castle[" + tierLabel() + " hp=" + (int) hp + "/" + (int) maxHp
                + " towers=" + towers.size + "/" + slotCapacity() + "]";
    }
}
