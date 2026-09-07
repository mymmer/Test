# TEXT_LOCALIZATION.md — strings and how they fit

Phase 10. Where user-facing text comes from, and what stops a longer translation
from bursting a screen.

---

## 1. One place for text

Every user-facing string is a key in `assets/i18n/strings.properties`, reached
through `Strings.get(key)` or `Strings.format(key, args...)` — a thin wrapper over
libGDX's `I18NBundle`, loaded once in `Services`. There are no string literals in
a screen and none in the renderer.

A missing key returns `!key!` rather than throwing. A screen that crashes because
a translator has not finished is worse than a screen with one visibly wrong label,
and the marker is loud enough to be caught in a screenshot — which is how nine
missing keys were in fact caught, including the price on every shop card.

The game ships in English. The point of the indirection is not that a translation
exists; it is that **adding one requires no code change**, and that the layout is
already proven to survive one.

## 2. Keys

```
   menu.*         mode.*          the front screen
   settings.*     common.*        settings, and text shared between screens
   shop.title     shop.cost       shop chrome
   shop.<id>.name  .desc          one pair per shop item        (11)
   talent.<id>.name  .desc        one pair per talent           (38)
   talent.branch.<id>             branch headings                (6)
   skill.<id>.name  .desc  .short one set per skill              (3)
   difficulty.<id>.name  .blurb   one pair per difficulty
   boss.<id>                      boss names
   hud.*  pause.*  gameover.*     in-run chrome
   banner.*                       announcements
```

The per-entity keys are derived from the **stable data id**, so a new entry in
`talents.json` or `shop.json` appears on screen with no code written. The one
thing that does not follow automatically is its name, which is exactly why the
completeness check exists.

Where a string already said a thing, the new screens reuse it rather than adding a
synonym: the mode buttons use `mode.classic`/`mode.endless`, the back buttons use
`common.back`. A translator should see one string per idea, not two that must be
kept in step.

## 3. Completeness is a test, not a review

`UiTextTest` checks three different ways, because they fail differently:

**Against the data tables.** For all 11 shop items, 38 talents, 6 branches, 3
skills and every difficulty, the name and description keys must exist and be
non-empty. Driven by the tables, so adding a talent breaks the test until it is
named.

**Against the source.** `literalKeysAllExist` scans every `.java` file in `ui` and
`render` for literal `Strings.get("…")` / `Strings.format("…")` calls and requires
each key. Keys assembled at runtime are not literals and are covered by the check
above. This reads the source rather than a list kept beside it, because a list kept
beside it goes stale — this is the check that found the missing card prices.

**Against itself.** No value in the bundle may be blank, and every value must
survive `MessageFormat` with generous arguments — an unbalanced brace in a
`.properties` file is otherwise a crash at the moment a player opens that screen.

## 4. TextLayout

`ui/TextLayout` is measurement and fitting, with the font injected as a
`Measurer` interface — which is what lets every text test run headless. In
production the measurer is backed by the real `BitmapFont` through a shared
`GlyphLayout`, so the layout is measured with the font that will draw it.

```
   fitToWidth(text, maxWidth, maxSize, minSize)   step the size down until it fits
   ellipsize(text, maxWidth, size)                cut and add "…" INSIDE the width
   wrap(text, maxWidth, size, lineGap)            break lines; break long words too
   fitInBox(text, w, h, maxSize, minSize, gap)    shrink, then wrap, then clip
```

Two details that matter:

- `fitToWidth` steps down one unit at a time, as Python does, rather than solving
  for a ratio. A bitmap font's advance widths are not linear in the scale, so the
  ratio answer is sometimes a unit too large — and one unit too large is a label
  touching its own border on every button at once.
- `fitInBox` reports `clipped` when the text could not fit even at the minimum
  size. Pretending otherwise is how text ends up drawn across a neighbouring
  control.

## 5. Long strings

German and Finnish routinely run half again as long as English. The layout tests
run at **three times** English width — deliberately past anything real — across
seven screen shapes and every screen, and assert that nothing escapes the safe
rectangle and no control drops below the touch minimum.

The same test set stretches the *line height*, which is the other failure mode: a
taller line makes the measured HUD stack taller, and it must grow downward inside
the panel rather than off the screen.

Between them these cover the three ways long text breaks a layout: it overflows
sideways, it pushes rows off the bottom, or it forces a control to shrink below
the size of a thumb.

## 6. The font, and what it can draw

**Decision (verified before Phase 11): the bundled libGDX font stays.**

It is Liberation Sans at 15px, shipped inside the gdx jar as `lsans-15.fnt`. It
carries 168 glyphs across codepoints 0..255 — the whole of Latin-1 — which covers
every character a European localisation of this game realistically needs:

```
   a-z A-Z 0-9        the Nordic set   æ ø å  Æ Ø Å
   é è ê ç à ù î ï    umlauts          ü ö ä  Ü Ö Ä
   ß                  punctuation      . , : ; ! ? ' " ( ) [ ] { } - _ / \ + = * % & # @ < > | ~ ^ `
   $ £ ¥ ¢
```

It needs no asset of ours, no FreeType dependency, no packing step and — the
point that decides it — **no device-installed font**, so it renders identically on
every device.

What it lacks is a handful of typographic characters outside Latin-1: `€`, `…`,
`–`, `—`, `°`, `×`. None is used. The game counts gold in "G", and
`TextLayout.ellipsize` appends three ASCII dots rather than an ellipsis glyph —
which `FontCoverageTest.ellipsisIsAscii` pins deliberately, because switching to
`…` would put a missing-glyph box on the end of every truncated label and no
other test would notice, the layout arithmetic being identical either way.

Replacing it would mean bundling a font asset — a redistribution licence to
verify and an atlas to pack — for characters the game does not use. If a
translation ever needs the euro sign, `FontCoverageTest` is what fails first, and
that is the moment to do it.

`FontCoverageTest` checks libGDX's own parse of the real `.fnt`, not a
transcription of it: the required set, **every character actually present in the
shipped bundle**, and the metrics — that accented glyphs are not zero-width, that
`ä` advances like `a` so an accented translation widens rather than reflows, that
accents do not grow the line box (which would make the measured HUD stack taller),
and that the smallest size the interface uses stays legible.

## 7. What is not here

Right-to-left scripts, CJK glyph coverage and pluralisation rules. The layout is
direction-agnostic in that it positions boxes, but nothing has been mirrored, and
no font in the project has CJK glyphs. If such a translation is ever commissioned
these become work alongside a real typeface — a font pipeline, not a string
file.

See also [`UI.md`](UI.md).

## Phase 11.5 polish — pygame sizes are not libGDX sizes

Every font size in this port is a number copied from the source, where it is an
argument to `pygame.font.Font(None, size)`. The port originally assumed pygame
and libGDX meant the same thing by that number. They do not, and the result was
that every string on screen was noticeably larger than the original — the
announcements sprawling across the middle of the scene was the visible symptom.

Measured rather than guessed. Nine representative strings at their real sizes,
rendered by pygame and measured against the same strings through libGDX's
built-in font:

```
   libGDX advance width / pygame advance width  =  1.364   (mean of 9 strings)
   pygame get_height()  / nominal size          =  0.6676  (mean of 12 sizes)
   libGDX lineHeight    / nominal size          =  1.2
```

So `TextLayout` carries two constants:

```java
   GLYPH = 0.7333f   // a source size, as a libGDX size
   LINE  = 0.7587f   // extra line-advance factor for a measured row stack
```

Two rather than one because the fonts disagree about the glyph size **and**
about how much air to leave around it; folding them together would fix the
widths and leave the HUD's measured row stack a third too tall.

**This is a unit conversion, not a design decision.** It scales every size by the
same factor, so the source's relative hierarchy — a 34-point banner over a
24-point boss name over an 18-point hint — is preserved exactly. Nothing should
ever be tuned per call site: if one label looks wrong, its source size is wrong.

### Bold, without a second font

The source marks almost every HUD value and every banner `bold=True`, and pygame
synthesises that for a face with no bold weight. The port does the same: a second
draw pass 0.7 units across. No font to bundle, no licence to verify, and the same
thickening the original gets. Without it the interface reads noticeably lighter
than the source's.

The built-in font's coverage decision in §6 is unchanged — no new font was needed.
