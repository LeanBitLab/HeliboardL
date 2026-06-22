/*
 * Copyright (C) 2012 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.event

import android.view.KeyEvent

/**
 * An event decoder for hardware events such as physical keyboard key presses.
 *
 * This interface extends [EventDecoder] to provide hardware-specific event decoding.
 * Implementations should decode hardware key events into [Event] objects that can be processed by the keyboard system.
 *
 * Usage guidelines:
 * - Implement [decodeHardwareKey] to handle [KeyEvent] from hardware input devices.
 * - Ensure decoded events are compatible with the keyboard's event handling pipeline.
 */
interface HardwareEventDecoder : EventDecoder {
    fun decodeHardwareKey(keyEvent: KeyEvent): Event
}