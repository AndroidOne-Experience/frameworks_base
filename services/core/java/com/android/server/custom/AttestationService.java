/*
 * Copyright (C) 2024 The LeafOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.android.server.custom;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.server.SystemService;
import com.android.internal.util.custom.Utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AttestationService extends SystemService {

    private static final String TAG = AttestationService.class.getSimpleName();
    private static final String API = "https://raw.githubusercontent.com/AndroidOne-Experience/vendor_certification/refs/heads/main/gms_certified_props.json";

    private static final long INTERVAL_HOURS = 6;
    private static final long NETWORK_THROTTLE_MS = 10 * 60 * 1000; // 10 minutes throttle

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final Boolean sDisableGmsProps = SystemProperties.getBoolean(
            "persist.sys.pihooks.disable.gms_props", false);

    private final Context mContext;
    private final ScheduledExecutorService mScheduler;
    private long mLastFetchTime = 0;

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    dlog("Default network available: " + network);
                    long now = SystemClock.elapsedRealtime();
                    String savedProps = Settings.Secure.getString(
                            mContext.getContentResolver(), Settings.Secure.FETCHED_PIF);
                    if (savedProps == null || (now - mLastFetchTime >= NETWORK_THROTTLE_MS)) {
                        mScheduler.execute(new FetchGmsCertifiedProps());
                    }
                }
            };

    public AttestationService(Context context) {
        super(context);
        mContext = context;
        mScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (!sDisableGmsProps
                && Utils.isPackageInstalled(mContext, "com.google.android.gms")
                && phase == PHASE_BOOT_COMPLETED) {
            Log.i(TAG, "Registering network callback and scheduling periodic checks");
            registerNetworkCallback();
            mScheduler.scheduleAtFixedRate(
                    new FetchGmsCertifiedProps(), INTERVAL_HOURS, INTERVAL_HOURS, TimeUnit.HOURS);
        }
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                cm.registerDefaultNetworkCallback(mNetworkCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }

    private String fetchProps() {
        try {
            URL url = new URI(API).toURL();
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            try {
                urlConnection.setConnectTimeout(10000);
                urlConnection.setReadTimeout(10000);

                try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(urlConnection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    return response.toString();
                }
            } finally {
                urlConnection.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error making an API request", e);
            return null;
        }
    }

    private boolean isInternetConnected() {
        ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network nw = cm.getActiveNetwork();
        if (nw == null) return false;
        NetworkCapabilities actNw = cm.getNetworkCapabilities(nw);
        return actNw != null
                && actNw.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void killPackages() {
        try {
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am != null) {
                am.forceStopPackageAsUser("com.google.android.gms", UserHandle.USER_ALL);
                am.forceStopPackageAsUser("com.android.vending", UserHandle.USER_ALL);
                Log.i(TAG, "GMS and Vending killed after props update");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill packages", e);
        }
    }

    private void dlog(String message) {
        if (DEBUG) Log.d(TAG, message);
    }

    private class FetchGmsCertifiedProps implements Runnable {
        @Override
        public void run() {
            try {
                dlog("FetchGmsCertifiedProps started");

                if (!isInternetConnected()) {
                    Log.e(TAG, "Internet unavailable");
                    return;
                }

                String savedProps = Settings.Secure.getString(mContext.getContentResolver(), Settings.Secure.FETCHED_PIF);
                String props = fetchProps();

                if (props != null) {
                    mLastFetchTime = SystemClock.elapsedRealtime();
                    if (!props.equals(savedProps)) {
                        Log.i(TAG, "Found new props, updating FETCHED_PIF");
                        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.FETCHED_PIF, props);
                        killPackages();
                        dlog("FetchGmsCertifiedProps completed");
                    } else {
                        dlog("No change in props");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in FetchGmsCertifiedProps", e);
            }
        }
    }
}
