/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.Application;
import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.util.Xml;

import com.android.internal.R;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manager class for handling keybox providers.
 * @hide
 */
public final class KeyProviderManager {
    private static final String TAG = "KeyProviderManager";

    private KeyProviderManager() {}

    public static IKeyboxProvider getProvider() {
        return new DefaultKeyboxProvider();
    }

    public static boolean hasKeybox() {
        return getProvider().hasKeybox();
    }

    public static boolean isKeyboxAvailable() {
        return hasKeybox() && isKeyboxEnabledForCallingApp();
    }

    public static boolean isKeyboxEnabledForCallingApp() {
        Context context = getApplicationContext();
        if (context == null) {
            return false;
        }
        return isKeyboxEnabledForApp(context);
    }

    public static boolean isKeyboxEnabledForApp(Context context) {
        if (context == null) {
            return false;
        }
        try {
            Set<String> packageSet = KeyboxPackagesUtils.getPackages(context);
            if (packageSet == null || packageSet.isEmpty()) {
                return false;
            }

            String currentPackage = ActivityThread.currentPackageName();
            if (currentPackage != null && packageSet.contains(currentPackage)) {
                return true;
            }

            String processName = ActivityThread.currentProcessName();
            if (processName != null) {
                if (packageSet.contains(processName)) {
                    return true;
                }
                int colonIdx = processName.indexOf(':');
                if (colonIdx > 0 && packageSet.contains(processName.substring(0, colonIdx))) {
                    return true;
                }
            }

            try {
                Application app = ActivityThread.currentApplication();
                if (app != null && packageSet.contains(app.getPackageName())) {
                    return true;
                }
            } catch (Exception ignored) {}

            int uid = Binder.getCallingUid();
            if (uid == 0) {
                uid = Process.myUid();
            }
            if (uid > 0 && uid != Process.SYSTEM_UID) {
                try {
                    String[] packages = null;
                    if (context.getPackageManager() != null) {
                        packages = context.getPackageManager().getPackagesForUid(uid);
                    }
                    if (packages == null) {
                        packages = AppGlobals.getPackageManager().getPackagesForUid(uid);
                    }
                    if (packages != null) {
                        for (String pkg : packages) {
                            if (packageSet.contains(pkg)) {
                                return true;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking if keybox is enabled for app", e);
        }
        return false;
    }

    private static Context getApplicationContext() {
        try {
            Application app = ActivityThread.currentApplication();
            if (app != null) {
                Context ctx = app.getApplicationContext();
                if (ctx != null) return ctx;
            }
        } catch (Exception ignored) {}
        try {
            ActivityThread at = ActivityThread.currentActivityThread();
            if (at != null) {
                Context sysCtx = at.getSystemContext();
                if (sysCtx != null) return sysCtx;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static class DefaultKeyboxProvider implements IKeyboxProvider {
        private final Map<String, String> keyboxData = new HashMap<>();

        private DefaultKeyboxProvider() {
            Context context = getApplicationContext();
            if (context == null) {
                Log.e(TAG, "Failed to get application context");
                return;
            }

            if (!loadFromXmlSetting(context)) {
                loadFromConfigArray(context);
            }
        }

        private boolean loadFromXmlSetting(Context ctx) {
            try {
                String xml = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.KEYBOX_DATA);
                if (xml == null || xml.trim().isEmpty()) return false;

                XmlPullParser p = Xml.newPullParser();
                p.setInput(new StringReader(xml));

                String currentAlg = null;
                int certCount = 0;
                boolean numberOfKeyboxesChecked = false;

                for (int ev = p.next(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        String tag = p.getName();
                        switch (tag) {
                            case "NumberOfKeyboxes":
                                p.next();
                                numberOfKeyboxesChecked = true;
                                try {
                                    int count = Integer.parseInt(p.getText().trim());
                                    if (count != 1) {
                                        Log.w(TAG, "Invalid NumberOfKeyboxes: " + count);
                                        return false;
                                    }
                                } catch (NumberFormatException e) {
                                    Log.w(TAG, "Failed to parse NumberOfKeyboxes", e);
                                    return false;
                                }
                                break;

                            case "Key":
                                currentAlg = p.getAttributeValue(null, "algorithm");
                                if ("ecdsa".equalsIgnoreCase(currentAlg)) currentAlg = "EC";
                                else if ("rsa".equalsIgnoreCase(currentAlg)) currentAlg = "RSA";
                                else currentAlg = null;
                                certCount = 0;
                                break;

                            case "PrivateKey": {
                                String format = p.getAttributeValue(null, "format");
                                if (!"pem".equalsIgnoreCase(format)) {
                                    Log.w(TAG, "Unsupported PrivateKey format: " + format);
                                    return false;
                                }
                                p.next();
                                if (currentAlg != null) {
                                    keyboxData.put(currentAlg + ".PRIV", p.getText().trim());
                                }
                                break;
                            }

                            case "Certificate": {
                                String format = p.getAttributeValue(null, "format");
                                if (!"pem".equalsIgnoreCase(format)) {
                                    Log.w(TAG, "Unsupported Certificate format: " + format);
                                    return false;
                                }
                                if (currentAlg != null) {
                                    p.next();
                                    certCount++;
                                    keyboxData.put(currentAlg + ".CERT_" + certCount, p.getText().trim());
                                }
                                break;
                            }
                        }
                    }
                }

                if (!numberOfKeyboxesChecked) {
                    Log.w(TAG, "Missing <NumberOfKeyboxes> in keybox XML");
                    return false;
                }

                if (!hasKeybox()) {
                    Log.w(TAG, "Failed to load keybox from XML setting");
                    return false;
                }

                Log.i(TAG, "Loaded keybox from XML setting");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "XML keybox load failed", e);
                return false;
            }
        }

        private void loadFromConfigArray(Context ctx) {
            for (String entry : ctx.getResources().getStringArray(R.array.config_certifiedKeybox)) {
                String[] parts = entry.split(":", 2);
                if (parts.length == 2) {
                    keyboxData.put(parts[0], parts[1]);
                }
            }

            if (!hasKeybox()) {
                Log.w(TAG, "Incomplete keybox provided by overlays");
            }
        }

        @Override
        public boolean hasKeybox() {
            if (!keyboxData.containsKey("EC.PRIV") || !keyboxData.containsKey("RSA.PRIV")) {
                return false;
            }
            if (!keyboxData.containsKey("EC.CERT_1") || !keyboxData.containsKey("RSA.CERT_1")) {
                return false;
            }
            return true;
        }

        @Override
        public String getEcPrivateKey() {
            return keyboxData.get("EC.PRIV");
        }

        @Override
        public String getRsaPrivateKey() {
            return keyboxData.get("RSA.PRIV");
        }

        @Override
        public String[] getEcCertificateChain() {
            return getCertificateChain("EC");
        }

        @Override
        public String[] getRsaCertificateChain() {
            return getCertificateChain("RSA");
        }

        private String[] getCertificateChain(String prefix) {
            List<String> dataList = new ArrayList<>();
            for (String key : keyboxData.keySet()) {
                if (key.startsWith(prefix + ".CERT_")) {
                    dataList.add(keyboxData.get(key));
                }
            }
            String[] chain = dataList.toArray(String[]::new);
            Arrays.sort(chain);
            return chain;
        }
    }
}
