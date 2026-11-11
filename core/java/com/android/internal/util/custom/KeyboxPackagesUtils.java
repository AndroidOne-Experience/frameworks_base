/*
 * SPDX-FileCopyrightText: 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.custom;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for managing packages configured to use Keybox spoofing.
 * @hide
 */
public final class KeyboxPackagesUtils {

    private KeyboxPackagesUtils() {}

    public static Set<String> getPackages(Context context) {
        if (context == null) {
            return Collections.emptySet();
        }
        return getPackages(context.getContentResolver());
    }

    public static Set<String> getPackages(ContentResolver cr) {
        if (cr == null) {
            return Collections.emptySet();
        }
        String packages = Settings.Secure.getString(cr, Settings.Secure.KEYBOX_PACKAGES);
        if (!TextUtils.isEmpty(packages)) {
            return new HashSet<>(Arrays.asList(packages.split(",")));
        }
        return Collections.emptySet();
    }

    public static boolean isPackageEnabled(Context context, String packageName) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return false;
        }
        return getPackages(context).contains(packageName);
    }

    public static void addPackage(Context context, String packageName, int userId) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return;
        }
        Set<String> packages = new HashSet<>(getPackages(context));
        if (packages.add(packageName)) {
            savePackages(context, packages, userId);
        }
    }

    public static void removePackage(Context context, String packageName, int userId) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return;
        }
        Set<String> packages = new HashSet<>(getPackages(context));
        if (packages.remove(packageName)) {
            savePackages(context, packages, userId);
        }
    }

    public static void setPackages(Context context, Set<String> packages, int userId) {
        if (context == null) {
            return;
        }
        savePackages(context, packages != null ? packages : Collections.emptySet(), userId);
    }

    private static void savePackages(Context context, Set<String> packages, int userId) {
        String flattened = (packages == null || packages.isEmpty()) ? "" : String.join(",", packages);
        if (userId >= 0) {
            Settings.Secure.putStringForUser(
                    context.getContentResolver(),
                    Settings.Secure.KEYBOX_PACKAGES,
                    flattened,
                    userId);
        } else {
            Settings.Secure.putString(
                    context.getContentResolver(),
                    Settings.Secure.KEYBOX_PACKAGES,
                    flattened);
        }
    }
}
