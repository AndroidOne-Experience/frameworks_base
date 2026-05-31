/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.server.audio;

import android.media.audiofx.AudioEffect;
import android.util.Log;

import java.util.UUID;

final class SstAudioEffect extends AudioEffect {
    private static final String TAG = "SstAudioEffect";

    private static final UUID EFFECT_TYPE_UUID =
            UUID.fromString("80c18fc2-44f4-4ea0-875b-0002a5d5c51b");
    private static final UUID EFFECT_SST_UUID =
            UUID.fromString("01325f27-2882-44f7-bdec-e7ce4ea3a581");

    SstAudioEffect(int priority, int audioSession) throws IllegalArgumentException,
            UnsupportedOperationException, RuntimeException {
        super(EFFECT_TYPE_UUID, EFFECT_SST_UUID, priority, audioSession);
        if (audioSession == 0) {
            Log.i(TAG, "Creating SST audio effect on global output mix");
        }
    }

    boolean isInitialized() {
        try {
            checkState("hasControl()");
            return true;
        } catch (IllegalStateException e) {
            Log.w(TAG, "SST audio effect is out of control");
            return false;
        }
    }

    @Override
    public boolean hasControl() {
        try {
            checkState("hasControl()");
            return super.hasControl();
        } catch (IllegalStateException e) {
            Log.w(TAG, "SST audio effect is out of control");
            return false;
        }
    }
}
