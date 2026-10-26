# Graphite Signal design system

Source of truth: `app/src/main/java/com/nefeon/akzsa/design/GraphiteSignal.kt` (tokens) mirrored in `res/values/colors.xml` (a test fails on drift).

| Role | Light | Dark |
|---|---|---|
| background | #F7F7F0 Soft Ivory | #151715 Graphite |
| surface | #FFFFFF | #20231F |
| secondarySurface | #E5E9DF Sage Mist | #262A24 |
| textPrimary / textSecondary | #252923 / #5E635A | #F2F4EC / #A7AEA0 |
| accent (Electric Lime) / onAccent | #C8F169 / #151715 | #C8F169 / #151715 |
| focus | #3F5F00 (dark olive) | #C8F169 |
| borderStrong (controls) | #7A8172 | #7A8274 |
| error / success | #B3261E / #2E6B1F | #FFB4AB / #9BD67A |

Rules: **lime is a fill**, always with `onAccent`. On Ivory lime is only 1.20:1, so it never carries meaning alone; active states add a `focus` outline, icons on the page use `colorScheme.tertiary`. Tests enforce >=4.5:1 for text and >=3:1 for control boundaries/focus on every surface, both themes.
Layout: 48 dp toolbar/actions, 44-56 dp key rows (`KeyboardLayoutPolicy`), 12 sp minimum text, gaps 3-4 dp, radii 8/12/16.
Profiles come only from measured width/height/font-scale: COMPACT (<360 dp), STANDARD, WIDE (>=600 dp, content <=720 dp), LOW_HEIGHT (<420 dp or short landscape; header collapses first).
Icon: lime disc + graphite microphone inside the adaptive safe zone, with a monochrome layer for themed icons.
