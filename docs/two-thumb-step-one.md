# Tap + Gesture Combining (Step One)

LeanType can optionally accept a tap from one finger and a simultaneous gesture from another finger as part of the same current word. This is a small first step toward two-thumb typing support.

The feature is intentionally conservative: it does not keep a word open after all fingers leave the keyboard, and it does not add spacing, grace timers, or delayed commit behavior.

## Enable it

Open **Settings → Gesture Typing** and enable:

1. **Combine simultaneous tapping and additional gestures**
2. Optional: **Draw taps and gestures**

Both settings default to off.

## How it works

With the combine setting enabled:

- A gesture may bypass the rapid-typing suppression only when another pointer is still active.
- The previous input must have been a letter.
- The normal upstream word boundary remains unchanged: once all fingers are lifted, the next gesture starts a new word.

This means overlapping multi-pointer input is allowed, but a later single-finger tap → swipe sequence is not treated as part of the same word.

## Example

A supported sequence is:

1. Press or tap a letter with one thumb.
2. While at least one finger is still active, start a gesture with another finger.
3. Lift normally to finish the gesture.

An out-of-scope sequence is:

1. Tap a word.
2. Lift all fingers.
3. Tap space.
4. Start a new single-finger gesture.

That remains normal gesture typing for the next word and keeps the usual rapid-typing guard.

## Debug drawing

The optional **Draw taps and gestures** setting draws a lightweight diagnostic overlay:

- tap points are shown as points
- gesture samples are drawn as a simple path

The overlay is for manual testing and debugging only. It does not affect committed text or suggestions.

The overlay clears when a new pointer sequence starts, when gesture input is cancelled, and when debug drawing is disabled.

## Scope limits

This step deliberately avoids the larger two-thumb model from downstream forks:

- no phantom spaces
- no grace timers
- no delayed commit after all fingers lift
- no re-recognition of prior taps
- no custom spacing behavior
- no synthetic point insertion

Those can be evaluated separately after the minimal mixed-input gate has proven useful.

## Implementation notes

The feature adds two cached settings in `SettingsValues` and a small `TapGestureDrawingPreview`. `PointerTracker` passes a new `ignoreFastTypingCooldown` flag to `BatchInputArbiter` only when overlapping multi-pointer tap+gesture input is active.

`BatchInputArbiter` keeps the original five-argument `addDownEventPoint` as the default path, so existing callers preserve upstream behavior.
