package com.mymmer.castledefense.enemy;

/**
 * One entry in a composed wave: either an enemy type, or a boss that does not
 * exist yet.
 *
 * <p>The seam that lets Phase 6 port wave composition in full without pulling
 * Phase 7 forward. Composition places bosses at waves 5, 10, 15 and every fifth
 * after — that logic is complete and tested here — but a boss entry carries a
 * <b>stable boss id string</b> rather than a class. Phase 7 supplies a factory
 * that resolves those ids; until then a spec whose boss is unresolvable is a
 * clean, visible gap rather than a fake TrollKing that has to be deleted later.
 */
public final class SpawnSpec {

    /** Non-null for an ordinary enemy; null for a boss. */
    public final EnemyType type;
    /** Non-null for a boss; null otherwise. A stable id, never a class name. */
    public final String bossId;

    private SpawnSpec(EnemyType type, String bossId) {
        this.type = type;
        this.bossId = bossId;
    }

    public static SpawnSpec of(EnemyType type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        return new SpawnSpec(type, null);
    }

    public static SpawnSpec boss(String bossId) {
        if (bossId == null || bossId.isEmpty()) {
            throw new IllegalArgumentException("bossId must not be empty");
        }
        return new SpawnSpec(null, bossId);
    }

    public boolean isBoss() {
        return bossId != null;
    }

    /** Stable id either way, for traces and tests. */
    public String id() {
        return isBoss() ? bossId : type.id();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpawnSpec)) {
            return false;
        }
        SpawnSpec other = (SpawnSpec) o;
        return type == other.type
                && (bossId == null ? other.bossId == null : bossId.equals(other.bossId));
    }

    @Override
    public int hashCode() {
        return (type == null ? 0 : type.hashCode()) * 31
                + (bossId == null ? 0 : bossId.hashCode());
    }

    @Override
    public String toString() {
        return id();
    }
}
