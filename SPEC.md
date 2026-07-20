# Step-One Tap + Gesture Combining

## Goal

Add the smallest upstreamable foundation for two-thumb typing: allow tap input from one pointer and a simultaneous/overlapping gesture from another pointer to coexist during the current word, and optionally draw a visual trail of taps and gestures for debugging.

This is intentionally not the full fork two-thumb system. It is a first slice that makes the input pipeline tolerate overlapping multi-pointer mixed tap/gesture word construction while preserving upstream's current word-boundary behavior. A single-finger tap followed later by a single-finger swipe after all fingers have left the keyboard is out of scope for this step.

## Non-goals

- No spacing changes.
- No phantom-space behavior.
- No grace timers.
- No auto-finish/auto-commit delay after all fingers leave the keyboard.
- No re-recognize experiments.
- No toolbar, clipboard, dictionary, or shortcut-row changes.
- No local test identity changes (`applicationId`, package name, app label, version).
- No Java/Kotlin package or namespace refactor.

## User-visible behavior

Add exactly two toggles under Gesture Typing, in a new category:

1. `Combine simultaneous tapping and additional gestures`
   - Allows mixed tap and gesture input only when the subsequent gesture starts while at least one other pointer is still active (overlapping multi-pointer input).
   - Does not keep the word open after all fingers leave the keyboard; a later single-finger swipe starts a new word and keeps the normal fast-typing cooldown.
2. `Draw taps and gestures`
   - Draws visual debug points/trails for tap and gesture input when the first toggle is enabled.
   - Has no effect on committed text.

Both default off for upstream safety.

## Design

### Settings

Follow the mandatory settings five-file pattern:

1. `Settings.java` — add two `PREF_*` constants.
2. `Defaults.kt` — add default values.
3. `SettingsValues.java` — cache the two booleans.
4. `res/values/strings.xml` — add category/title/summary strings.
5. Gesture settings screen — add the new category and toggles.

Update `SettingsContainerTest` if the settings screens are validated there.

### Input behavior

The first toggle should relax the input pipeline only enough to avoid rejecting a gesture when another pointer is still active and tap input has just contributed to the composing word. The cooldown bypass must require overlapping multi-pointer input, so a rapid tap-space-gesture sequence across a no-fingers word boundary remains a new word and keeps upstream fast-typing suppression. This branch must not add timers, deferred spacing, or no-finger continuation semantics.

### Visual trail

Reuse existing drawing surfaces/preview plumbing where possible. The debug trail should be cheap when disabled and must not allocate avoidably in hot paths.

The debug view should show enough information to verify mixed input ordering: tap points and gesture path points. It is diagnostic only and may be visually simple.

### Tests

Add focused tests for:

- settings defaults/wiring;
- any unit-testable gate or state object introduced for mixed tap/gesture behavior.

## Verification

Run targeted tests only:

- `:app:testOfflineDebugUnitTest --tests "*SettingsContainerTest*"`
- any new focused tests for the mixed input gate/state

## Commit plan

1. Commit this `SPEC.md` design.
2. Commit the upstreamable implementation.
3. Add a separate local-only test identity commit after the upstreamable implementation is complete and verified.
