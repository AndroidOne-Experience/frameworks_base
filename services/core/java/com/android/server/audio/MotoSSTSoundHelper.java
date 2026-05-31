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

import android.content.ContentResolver;
import android.content.Context;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

final class MotoSSTSoundHelper {
    private static final String TAG = "MotoSSTSoundHelper";

    static final String SETTING_SST_EFFECT_STATE = "sst_effect_state";
    static final Uri MOTOROLA_GLOBAL_SETTINGS_URI =
            Uri.parse("content://com.motorola.android.providers.settings/global");
    static final Uri SST_EFFECT_STATE_URI =
            Uri.withAppendedPath(MOTOROLA_GLOBAL_SETTINGS_URI, SETTING_SST_EFFECT_STATE);

    private static final String CALL_METHOD_GET_GLOBAL = "GET_global";
    private static final String CALL_METHOD_PUT_GLOBAL = "PUT_global";
    private static final String CALL_VALUE_KEY = "value";
    private static final String CALL_USER_KEY = "_user";

    private static final int MSG_ENABLE_SST = 10;
    private static final int MSG_DISABLE_SST = 11;
    private static final int SST_ENABLED = 1;
    private static final int SST_DISABLED = 0;

    private final Context mContext;
    private final Handler mHandler;

    private SstAudioEffect mSstAudio;
    private int mSstState = SST_DISABLED;
    private boolean mRestoreInProgress;

    MotoSSTSoundHelper(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Invalid null context");
        }
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message message) {
                if (message.what == MSG_ENABLE_SST) {
                    setEnabled(true);
                } else if (message.what == MSG_DISABLE_SST) {
                    setEnabled(false);
                }
            }
        };
    }

    synchronized void checkAndInitSstInstance() {
        if (mRestoreInProgress) {
            mRestoreInProgress = false;
            return;
        }

        updateSstState();
        boolean shouldEnable = mSstState == SST_ENABLED;
        boolean currentlyEnabled = getEnabled();
        if (!shouldEnable && currentlyEnabled == shouldEnable) {
            Log.d(TAG, "enabled state unchanged");
            return;
        }

        if (!ensureSstAudioEffect()) {
            mSstState = SST_DISABLED;
            restoreSstState(mSstState);
            return;
        }

        if (shouldEnable) {
            mHandler.removeMessages(MSG_ENABLE_SST);
            mHandler.sendEmptyMessage(MSG_ENABLE_SST);
        } else {
            if (mSstAudio != null && mSstAudio.isInitialized()) {
                mSstAudio.setParameter(100, 1);
            }
            mHandler.removeMessages(MSG_DISABLE_SST);
            mHandler.sendEmptyMessageDelayed(MSG_DISABLE_SST, 50);
        }
    }

    private void updateSstState() {
        mSstState = getMotorolaGlobalInt(SETTING_SST_EFFECT_STATE, SST_DISABLED);
    }

    private void restoreSstState(int state) {
        putMotorolaGlobalInt(SETTING_SST_EFFECT_STATE, state);
        mRestoreInProgress = true;
    }

    private int setEnabled(boolean enabled) {
        int result = setEnabledInternal(enabled);
        if (shouldRetryWithFreshEffect(result)) {
            Log.w(TAG, "SST audio effect handle is stale, retrying setEnabled");
            releaseSstAudioEffect();
            if (ensureSstAudioEffect()) {
                result = setEnabledInternal(enabled);
            }
        }
        if (result == AudioEffect.SUCCESS && !enabled) {
            releaseSstAudioEffect();
        }
        Log.d(TAG, "setEnabled result: enabled=" + enabled + " state=" + result);
        return result;
    }

    private boolean getEnabled() {
        try {
            return mSstAudio != null && mSstAudio.isInitialized() && mSstAudio.getEnabled();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Unable to read SST enabled state", e);
            releaseSstAudioEffect();
            return false;
        }
    }

    private boolean ensureSstAudioEffect() {
        if (mSstAudio != null && mSstAudio.isInitialized()) {
            return true;
        }
        Log.d(TAG, "SST audio effect needs to be initialized");
        try {
            mSstAudio = new SstAudioEffect(1, 0);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "SST effect init failed", e);
            mSstAudio = null;
            return false;
        }
    }

    private int setEnabledInternal(boolean enabled) {
        if (mSstAudio == null || !mSstAudio.isInitialized()) {
            return AudioEffect.ERROR_NO_INIT;
        }
        try {
            return mSstAudio.setEnabled(enabled);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Unable to update SST enabled state", e);
            return AudioEffect.ERROR_NO_INIT;
        }
    }

    private boolean shouldRetryWithFreshEffect(int result) {
        return result == AudioEffect.ERROR_DEAD_OBJECT
                || result == AudioEffect.ERROR_INVALID_OPERATION
                || result == AudioEffect.ERROR_NO_INIT;
    }

    private void releaseSstAudioEffect() {
        if (mSstAudio != null) {
            mSstAudio.release();
            mSstAudio = null;
            Log.d(TAG, "released SST audio effect");
        }
    }

    private int getMotorolaGlobalInt(String name, int defaultValue) {
        try {
            ContentResolver resolver = mContext.getContentResolver();
            Bundle args = new Bundle();
            args.putInt(CALL_USER_KEY, resolver.getUserId());
            Bundle result = resolver.call(MOTOROLA_GLOBAL_SETTINGS_URI, CALL_METHOD_GET_GLOBAL,
                    name, args);
            if (result == null) {
                return defaultValue;
            }
            String value = result.getString(CALL_VALUE_KEY);
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (Exception e) {
            Log.w(TAG, "Unable to read Motorola global setting " + name, e);
            return defaultValue;
        }
    }

    private boolean putMotorolaGlobalInt(String name, int value) {
        try {
            ContentResolver resolver = mContext.getContentResolver();
            Bundle args = new Bundle();
            args.putString(CALL_VALUE_KEY, Integer.toString(value));
            args.putInt(CALL_USER_KEY, resolver.getUserId());
            resolver.call(MOTOROLA_GLOBAL_SETTINGS_URI, CALL_METHOD_PUT_GLOBAL, name, args);
            resolver.notifyChange(SST_EFFECT_STATE_URI, null);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Unable to write Motorola global setting " + name, e);
            return false;
        }
    }
}
