/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.settings.volume;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.Gefingerpoken;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.haptics.slider.HapticSlider;
import com.android.systemui.haptics.slider.HapticSliderPlugin;
import com.android.systemui.haptics.slider.HapticSliderViewBinder;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.res.R;
import com.android.systemui.settings.brightness.BrightnessSliderView;
import com.android.systemui.settings.brightness.ToggleSeekBar;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.time.SystemClock;

import com.google.android.msdl.domain.MSDLPlayer;

import javax.inject.Inject;

/** Controller for the media volume slider shown in expanded QS. */
public class VolumeSliderController extends ViewController<BrightnessSliderView> {

    private static final int STREAM_UNKNOWN = -1;
    private static final int STREAM_TYPE = AudioManager.STREAM_MUSIC;

    private final FalsingManager mFalsingManager;
    private final HapticSliderPlugin mHapticSliderPlugin;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Handler mMainHandler;
    private final AudioManager mAudioManager;

    private boolean mExternalChange;
    private boolean mListening;
    private boolean mTracking;

    private final Gefingerpoken mOnInterceptListener = new Gefingerpoken() {
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            mHapticSliderPlugin.onTouchEvent(ev);
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mFalsingManager.isFalseTouch(Classifier.BRIGHTNESS_SLIDER);
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            return false;
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !isMediaVolumeBroadcast(intent)) {
                return;
            }
            updateSliderState(false);
        }
    };

    private final SeekBar.OnSeekBarChangeListener mSeekListener =
            new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (!fromUser || mExternalChange) {
                return;
            }
            mAudioManager.setStreamVolume(STREAM_TYPE, progress, 0);
            mHapticSliderPlugin.onProgressChanged(progress, true);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            mTracking = true;
            mHapticSliderPlugin.onStartTrackingTouch();
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mTracking = false;
            mHapticSliderPlugin.onStopTrackingTouch();
            updateSliderState(true);
        }
    };

    protected VolumeSliderController(
            BrightnessSliderView volumeSliderView,
            FalsingManager falsingManager,
            HapticSliderPlugin hapticSliderPlugin,
            BroadcastDispatcher broadcastDispatcher,
            Handler mainHandler,
            AudioManager audioManager) {
        super(volumeSliderView);
        mFalsingManager = falsingManager;
        mHapticSliderPlugin = hapticSliderPlugin;
        mBroadcastDispatcher = broadcastDispatcher;
        mMainHandler = mainHandler;
        mAudioManager = audioManager;
    }

    public View getRootView() {
        return mView;
    }

    @Override
    protected void onViewAttached() {
        mView.setOnSeekBarChangeListener(mSeekListener);
        mView.setOnInterceptListener(mOnInterceptListener);
    }

    @Override
    protected void onViewDetached() {
        mView.setOnSeekBarChangeListener(null);
        mView.setOnInterceptListener(null);
        unregisterCallbacks();
    }

    public void registerCallbacks() {
        if (mListening) {
            return;
        }
        mListening = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        filter.addAction(AudioManager.STREAM_DEVICES_CHANGED_ACTION);
        filter.addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        mBroadcastDispatcher.registerReceiverWithHandler(mReceiver, filter, mMainHandler);
        updateSliderState(true);
    }

    public void unregisterCallbacks() {
        if (!mListening) {
            return;
        }
        mListening = false;
        mBroadcastDispatcher.unregisterReceiver(mReceiver);
    }

    private boolean isMediaVolumeBroadcast(Intent intent) {
        int stream = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, STREAM_UNKNOWN);
        int streamAlias =
                intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE_ALIAS, STREAM_UNKNOWN);
        return stream == STREAM_TYPE || streamAlias == STREAM_TYPE;
    }

    private void updateSliderState(boolean force) {
        if (mTracking && !force) {
            return;
        }
        mView.enableSlider(!mAudioManager.isVolumeFixed());
        mView.setMax(mAudioManager.getStreamMaxVolume(STREAM_TYPE));
        mExternalChange = true;
        try {
            mView.setValue(mAudioManager.getStreamVolume(STREAM_TYPE));
        } finally {
            mExternalChange = false;
        }
    }

    /** Factory for creating a {@link VolumeSliderController} and its view. */
    public static class Factory {
        private final FalsingManager mFalsingManager;
        private final VibratorHelper mVibratorHelper;
        private final MSDLPlayer mMSDLPlayer;
        private final SystemClock mSystemClock;
        private final BroadcastDispatcher mBroadcastDispatcher;
        private final Handler mMainHandler;
        private final AudioManager mAudioManager;

        @Inject
        public Factory(
                FalsingManager falsingManager,
                VibratorHelper vibratorHelper,
                MSDLPlayer msdlPlayer,
                SystemClock systemClock,
                BroadcastDispatcher broadcastDispatcher,
                @Main Handler mainHandler,
                AudioManager audioManager) {
            mFalsingManager = falsingManager;
            mVibratorHelper = vibratorHelper;
            mMSDLPlayer = msdlPlayer;
            mSystemClock = systemClock;
            mBroadcastDispatcher = broadcastDispatcher;
            mMainHandler = mainHandler;
            mAudioManager = audioManager;
        }

        @NonNull
        public VolumeSliderController create(Context context, @Nullable ViewGroup viewRoot) {
            BrightnessSliderView root = (BrightnessSliderView) LayoutInflater.from(context)
                    .inflate(R.layout.quick_settings_volume_dialog, viewRoot, false);
            HapticSliderPlugin plugin = new HapticSliderPlugin(
                    mVibratorHelper,
                    mMSDLPlayer,
                    mSystemClock,
                    new HapticSlider.SeekBar((ToggleSeekBar) root.requireViewById(R.id.slider)));
            HapticSliderViewBinder.bind(root, plugin);
            return new VolumeSliderController(root, mFalsingManager, plugin,
                    mBroadcastDispatcher, mMainHandler, mAudioManager);
        }
    }
}
