# VISUAL_EFFECTS.md — particles, text, weather and skill visuals

Phase 11. Everything that is drawn but is not a unit or a building. The world
renderer, draw order and skin path are [`RENDERING.md`](RENDERING.md).

---

## 1. State where possible, events where necessary

Almost everything here is read from state and needs no notification at all:

| Drawn from | Because it is |
|---|---|
| `enemy.hurtFlash()` | a gameplay timer |
| `castle.flash()` | a gameplay timer |
| `boss.aura()`, `venting()`, `wardStrength()`, `orbCharge()` | gameplay state |
| `fireZone.x()/radius()/fraction()` | the zone that is doing the damage |
| `tornado.x()/radius()/phase()` | the tornado doing the pulling |
| `weather.wind()/storm()/stormFlash()` | the weather doing the slowing |

Reading state is simpler than an event and cannot go stale. What state cannot
express is a **moment**: a Volatile detonating throws sparks once and by the next
frame is gone from the roster, so there is nothing left to read.

## 2. VisualEvents: the presentation seam

Three methods, all `void`:

```java
burst(x, y, count, rgb, speed, life, size, grav, shape)
ring (x, y, count, rgb, speed, life, size)
text (x, y, message, rgb, size, life)
```

**It is not `SimulationTrace.`** That is a diagnostic interface with a recording
implementation used by tests. Hanging production visuals off it would make a
debug facility load-bearing — turn the trace off and the game stops sparkling.
The two never meet, and `ArchitectureTest` allows gameplay to name exactly one
thing in the render package: `VisualEvents`.

**It is not an event bus.** One sink, set once, no subscribers, no query, no
return value. `RenderPurityTest.theSinkIsOneWay` asserts by reflection that every
method returns `void`, because a sink that could report failure or a count is
something a simulation could branch on — and then whether anything was drawing
would be a gameplay input.

The default is `VisualEvents.NONE`. `RenderPurityTest.noSinkChangesNothing` runs
thirty seconds of identically seeded Endless with and without a sink attached and
asserts gold, score, roster, castle health and both halves of the generator's
state are identical. That property is why 893 headless tests run against
production gameplay code that emits visual events.

### Where it is emitted

Matching the source's own `effects.burst` / `effects.text` calls: an enemy death
(spray and the gold number), the castle taking damage (dust off the wall, scaled
to the blow), a Siege Ram's `SMASH!`, a Volatile's detonation (a burst plus a
ring at the blast radius the damage actually used).

## 3. Particles

`EffectsSystem` is the source's `Effects`, pooled.

```
   MAX_PARTICLES = 900        the source's own cap
   MAX_TEXTS     = 90         Effects.text's own cap
```

Both arrays are allocated once at their cap and never grow. A particle is taken
from the free list, **every field written**, and returned on death. "Every field"
is the whole contract: a pooled object keeping one stale value from its previous
life is the classic pooling bug, and here it shows as a spark inheriting the
wrong colour or an immortal one that never fades.
`EffectsAndQualityTest.recycledParticlesAreFullyReset` fires a short-lived batch,
lets it die, fires a long-lived one and asserts the second batch does not die on
the first batch's timer.

The source's randomisation is reproduced exactly — angle uniform over the circle,
speed 25–100% of nominal, an upward bias of a quarter, life ×0.6–1.25, size
×0.6–1.4 — and every draw is from the **decoration** stream.

A particle both darkens and shrinks as it dies (`shade(color, 0.45 + 0.55a)`,
`size * (0.35 + 0.65a)`), which is what makes a burst read as embers cooling
rather than dots vanishing.

### They run on frame time

Particles affect nothing — no damage, no collision, nothing reads them — so tying
them to the fixed step would only make them stutter at 144 Hz for no benefit.
They are still frozen when the world is, because the loop simply does not call
`update` outside `PLAYING`: the same rule that freezes the Endless armoury.

## 4. Floating text

Rises at 46 px/s decaying by 62/s, fading with its life, centred, bold — the
source's `FloatingText`, pooled the same way.

**It floats a value; it never computes one.** The damage number, the gold gain
and the score are passed in by whoever already knew them. A renderer recomputing
a reward is a second implementation of the payout rule, and the two drift.

## 5. Weather

Rendering reads `Weather` and draws what it finds. It cannot create a strike.

- **Wind streaks** when `|wind| > 40`, positioned by a hash of index and time
  exactly as the source's `seed` expression does — not by a random draw, so they
  stream smoothly rather than flickering. LOW quality shows fewer streaks; the
  wind is unchanged.
- **The storm veil**, a full-screen tint at `stormFlash` strength.
- **Lightning bolts** as a jagged path down from the clouds. The path is random
  and the randomness is keyed to the bolt, so it keeps its shape for its whole
  life. In the source this draw perturbs the gameplay generator; here it cannot.

**Gameplay lightning and visual lightning are separate things.** `Weather` decides
the target, the damage and the timing; this draws the result. Dropping to LOW may
simplify the picture and never removes a strike.

## 6. Skill visuals

Gameplay is Phase 9's and is not duplicated here — no damage, no radius, no
lifetime, no target selection.

**FireZone** draws seven flames along the ground, fading with `fraction()` — the
zone's own remaining life. There is deliberately no independent visual lifetime
that could outlive the damage or burn a wider patch than it hurts. Individual
flame heights wobble on the zone's `phase`, which the simulation advances, so
they stop when the world does.

**Tornado** draws thirteen stacked ellipse *outlines* (filled would hide the mobs
caught inside) and a core line, at the tornado's own `x`. The pull on caught mobs
is computed by the `Tornado` from that same value — the rendered funnel is never
consulted by the physics, and could not be, since it does not exist outside the
draw method.

**Lightning** and **Meteor** are bursts and bolts at the positions gameplay chose.

**The aiming reticle** shows the skill's real radius including talent scaling,
asked of gameplay — so the ring shows what the cast will actually cover.

## 7. Projectile trails

The source keeps `self.trail` on the `Projectile`. Here it lives in
`ProjectileTrails`, in the renderer, and the projectile does not know the class
exists — which makes it structurally impossible for a shot's motion to depend on
its own trail.

Bounded in every direction: 8 samples per shot in a fixed ring buffer; entries
for projectiles that no longer exist are dropped every frame, so a long Endless
run cannot accumulate them; and `clear()` on a new run. LOW quality switches them
off entirely.

## 8. Damage flashes

Ported where the source has them, from gameplay timers — `enemy.hurtFlash()`,
`castle.flash()`, `barricade.flash()`. **No flash timer is gameplay-authoritative**;
they are values gameplay already keeps for its own reasons.

The castle's is a **documented visual approximation**. Pygame composites
`BLEND_RGBA_MULT` then `BLEND_RGBA_ADD`, which has no direct batch equivalent;
the port draws the closest clean thing, an additive tinted pass. Recognisably the
same red pulse, not bit-identical, and it adds no gameplay effect.

## 9. Glows and auras

Volatile's pulse, the Dragon's aura, the Lich's ward, magic and fire projectiles,
the dropped crown and staff. Drawn as concentric translucent discs rather than the
source's per-frame `SRCALPHA` surface — **no texture is allocated during a frame**,
by anything, anywhere in the renderer. Quality scales their intensity.

## 10. Quality

| | LOW | MEDIUM | HIGH |
|---|---|---|---|
| particles | 220 | 520 | 900 |
| glow intensity | 0 | 0.6 | 1.0 |
| shadows | off | on | on |
| trails | off | off | on |
| wind streaks | capped at 8 | full | full |

And nothing else. See [`RENDERING.md`](RENDERING.md) §12 for the equivalence
test that locks it down.
