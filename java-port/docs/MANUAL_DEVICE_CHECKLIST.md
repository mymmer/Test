# Manual device checklist — Phase 13.1

Short enough to run while holding the phone. Everything here is something
`adb` **cannot** do: two fingers at once, a fast flick, a real palm on real
glass. Everything that could be automated already has been.

**Build:** `android-debug.apk`, commit `86984a9` or later.
**Install:** `adb install -r android-debug.apk`, or copy it to the phone and open it.

Tick each line. If one fails, note what you did and what happened — that is
enough for me to reproduce it.

---

## A. Multi-touch — the only genuinely unverified area

`adb shell input` injects one pointer. Nothing below has been exercised with two
simultaneous contacts on hardware; the ownership rules are covered by tests, but
tests cannot press two places at once on a real digitiser.

- [ ] **A1.** Start Endless. Put **two fingers on two different mobs at the same
      time.** Expect: **one** mob is lifted, not two. The second finger does
      nothing.
- [ ] **A2.** Still holding with finger one, **lift finger two.** Expect: finger
      one keeps its mob. It must not be dropped or thrown.
- [ ] **A3.** Holding a mob with finger one, **tap the Challenge Horn with finger
      two.** Expect: the horn fires (or refuses if spent) and the held mob stays
      held.
- [ ] **A4.** Buy **Magnetic Gloves** (340 G, multi-grab), then grab a mob in a
      dense crowd. Expect: several mobs come up together, and a throw scatters
      them rather than launching a rigid block.
- [ ] **A5.** With two fingers down, **background the app** (Home). Reopen.
      Expect: nothing is held, nothing is thrown, no mob is stuck to your finger.

## B. Fast flicks and edges

- [ ] **B1.** Grab a mob and **flick it hard and quickly**. Expect: it travels
      much further than a slow drag released at the same point.
- [ ] **B2.** Grab a mob and drag your finger **off the top/bottom edge of the
      screen**, then let go outside. Expect: it is released, not stuck.
- [ ] **B3.** Drag starting **on the HUD panel** (top-left). Expect: no mob is
      grabbed behind it.
- [ ] **B4.** Press **SETTINGS** on the menu (bottom-right). Expect: it opens
      first time. This is the button that was 45% inside the navigation strip.
- [ ] **B5.** Swipe **in from the right edge** during play. Expect: the system
      navigation bar appears; the game does not grab anything.

## C. The Dragon

Automated taps could not land on it — it flies and injected taps coalesce.

- [ ] **C1.** Reach a Dragon (or launch it: see below). **Tap its claws**
      repeatedly. Expect: a smack effect and the Dragon reels; nothing is
      detached and carried.
- [ ] **C2.** While it reels, tap again. Expect: no smack until it recovers.

## D. Feel

Not pass/fail — tell me if anything is uncomfortable.

- [ ] **D1.** Are the **skill slots** (bottom centre) easy to hit with a thumb?
- [ ] **D2.** Is the **bottom instruction line** readable at arm's length?
- [ ] **D3.** Are **shop prices** and **talent ranks** readable without squinting?
      They are the smallest text in the game — see the size table in
      `ANDROID_DEVICE.md` §7.
- [ ] **D4.** Holding the phone normally, does your palm ever trigger something?

---

## Launching a specific scene

Any of these can be started directly, so you do not have to play to them:

```bash
adb shell am start -n com.mymmer.castledefense/.android.AndroidLauncher \
    -e scenario dragon-breath --ez keepAlive true
```

Useful scenarios: `dragon-breath`, `all-bosses`, `armour` (Siege Rams),
`all-towers` (overcharge), `tornado` (skills unlocked), `mixed-wave`,
`endless-late`.

`--ez keepAlive true` keeps the castle standing so the scene does not end while
you are testing. Leave it off to test Game Over.

Add `--ez dumpUi true` and every control's exact hit rectangle is printed to
logcat:

```bash
adb logcat -s CastleDefensePerf
```

## If you want to change navigation mode

The phone is currently on **3-button navigation**, so gesture-navigation edges
are untested. I have not changed this setting and will not. If you want to test
it: **Settings → Display → Navigation bar → Swipe gestures**. Then B4 and B5
above are worth repeating, since that mode reserves different edges.
