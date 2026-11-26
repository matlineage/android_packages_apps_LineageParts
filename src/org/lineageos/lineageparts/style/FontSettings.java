/*
 * SPDX-FileCopyrightText: 2025 AxionOS
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.lineageparts.style;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import org.json.JSONException;
import org.json.JSONObject;
import org.lineageos.lineageparts.R;
import org.lineageos.lineageparts.SettingsPreferenceFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FontSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

    private static final String TAG = "FontSettings";

    private static final String KEY_FONT_PREBUILT = "font_prebuilt";
    private static final String KEY_CUSTOM_FONT_PICKER = "custom_font_picker";
    private static final String KEY_CUSTOM_FONT_INFO = "custom_font_info";
    private static final String KEY_RESET_CUSTOM_FONT = "reset_custom_font";
    private static final String KEY_REBOOT_FOR_FONT = "reboot_for_font";
    private static final String OVERLAY_CATEGORY_FONT = "android.theme.customization.font";
    private static final String DEFAULT_FONT_PKG = "com.android.theme.font.googlesansflex";
    private static final int REQUEST_PICK_FONT = 1001;

    private Preference mPrebuiltFontPref;
    private Preference mCustomFontPickerPref;
    private Preference mCustomFontInfoPref;
    private Preference mResetCustomFontPref;
    private Preference mRebootForFontPref;
    private ExternalFontInstaller mFontInstaller;
    private IOverlayManager mOverlayManager;
    private final List<String> mFontPackages = new ArrayList<>();
    private final List<String> mFontLabels = new ArrayList<>();
    private String mSelectedFontPkg = "default";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.font_settings);

        mFontInstaller = new ExternalFontInstaller(getActivity());
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));

        mPrebuiltFontPref = findPreference(KEY_FONT_PREBUILT);
        mPrebuiltFontPref.setOnPreferenceClickListener(this);

        mCustomFontPickerPref = findPreference(KEY_CUSTOM_FONT_PICKER);
        mCustomFontPickerPref.setOnPreferenceClickListener(this);
        mCustomFontInfoPref = findPreference(KEY_CUSTOM_FONT_INFO);
        mResetCustomFontPref = findPreference(KEY_RESET_CUSTOM_FONT);
        mResetCustomFontPref.setOnPreferenceClickListener(this);
        mRebootForFontPref = findPreference(KEY_REBOOT_FOR_FONT);
        mRebootForFontPref.setOnPreferenceClickListener(this);

        refreshPrebuiltFonts();
        updateFontPreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPrebuiltFonts();
        updateFontPreferences();
    }

    private void refreshPrebuiltFonts() {
        if (mOverlayManager == null || mPrebuiltFontPref == null) return;
        try {
            List<OverlayInfo> infos = mOverlayManager.getOverlayInfosForTarget(
                    "android", UserHandle.USER_SYSTEM);
            List<OverlayInfo> fontOverlays = new ArrayList<>();
            for (OverlayInfo info : infos) {
                if (OVERLAY_CATEGORY_FONT.equals(info.category)) {
                    fontOverlays.add(info);
                }
            }
            Collections.sort(fontOverlays, Comparator.comparing(o -> o.packageName));

            mFontPackages.clear();
            mFontLabels.clear();
            mFontPackages.add("default");
            mFontLabels.add(getString(R.string.font_prebuilt_default));

            mSelectedFontPkg = "default";
            boolean gsfEnabled = false;
            boolean gsfFound = false;
            PackageManager pm = getContext().getPackageManager();
            for (OverlayInfo info : fontOverlays) {
                if (DEFAULT_FONT_PKG.equals(info.packageName)) {
                    gsfFound = true;
                    gsfEnabled = info.isEnabled();
                    if (info.isEnabled()) {
                        mSelectedFontPkg = "default";
                    }
                    continue;
                }
                String label;
                try {
                    label = pm.getApplicationInfo(info.packageName, 0)
                            .loadLabel(pm).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    label = info.packageName;
                }
                mFontPackages.add(info.packageName);
                mFontLabels.add(label);
                if (info.isEnabled()) {
                    mSelectedFontPkg = info.packageName;
                }
            }

            final String customFontName = Settings.Secure.getStringForUser(
                    getContext().getContentResolver(),
                    "custom_font_name",
                    UserHandle.USER_CURRENT);
            final boolean hasCustomFont = customFontName != null && !customFontName.isEmpty();
            if (!hasCustomFont && gsfFound && !gsfEnabled && "default".equals(mSelectedFontPkg)) {
                applyPrebuiltFont("default");
                mSelectedFontPkg = "default";
            }

            int idx = mFontPackages.indexOf(mSelectedFontPkg);
            if (idx < 0) idx = 0;
            mPrebuiltFontPref.setSummary(mFontLabels.get(idx));
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to load font overlays", e);
        }
    }

    private void updateFontPreferences() {
        final String customFontName = Settings.Secure.getStringForUser(
                getContext().getContentResolver(),
                "custom_font_name",
                UserHandle.USER_CURRENT
        );

        final boolean hasCustomFont = customFontName != null && !customFontName.isEmpty();
        mCustomFontInfoPref.setVisible(hasCustomFont);
        mResetCustomFontPref.setVisible(hasCustomFont);
        mRebootForFontPref.setVisible(hasCustomFont);

        if (hasCustomFont) {
            mCustomFontInfoPref.setSummary(
                    getString(R.string.custom_font_installed_summary, customFontName));
        }
    }

    private void pickCustomFont() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"font/ttf", "font/otf", "application/x-font-ttf",
                "application/x-font-otf", "application/octet-stream"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, REQUEST_PICK_FONT);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FONT && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri fontUri = data.getData();
                installCustomFont(fontUri);
            }
        }
    }

    private void installCustomFont(Uri fontUri) {
        final Context context = getContext();
        new Thread(() -> {
            String postScriptName = mFontInstaller.installFontFromUri(context, fontUri);

            Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (postScriptName != null) {
                    Settings.Secure.putStringForUser(
                            context.getContentResolver(),
                            "custom_font_name",
                            postScriptName,
                            UserHandle.USER_CURRENT
                    );
                    refreshPrebuiltFonts();
                    updateFontPreferences();
                    Toast.makeText(context, R.string.custom_font_installed_success,
                            Toast.LENGTH_SHORT).show();
                    showRebootDialog();
                } else {
                    Toast.makeText(context, R.string.custom_font_install_failed,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void clearCustomFontInternal() {
        final String customFontName = Settings.Secure.getStringForUser(
                getContext().getContentResolver(),
                "custom_font_name",
                UserHandle.USER_CURRENT
        );
        if (customFontName == null || customFontName.isEmpty()) {
            return;
        }
        final Context context = getContext();
        mFontInstaller.resetFontUpdates(context);
        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                "custom_font_name",
                "",
                UserHandle.USER_CURRENT
        );
        updateFontPreferences();
    }

    private void resetCustomFont() {
        clearCustomFontInternal();
        refreshPrebuiltFonts();
        Toast.makeText(getContext(), R.string.custom_font_reset_success, Toast.LENGTH_SHORT).show();
        showRebootDialog();
    }

    private void showRebootDialog() {
        final Context context = getContext();
        if (context == null) return;
        new AlertDialog.Builder(context)
                .setTitle(R.string.reboot_required_title)
                .setMessage(R.string.reboot_required_custom_font_desc)
                .setPositiveButton(R.string.reboot_device, (dialog, which) -> {
                    ExternalFontInstaller.rebootDevice(context);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean applyPrebuiltFont(String packageName) {
        if (mOverlayManager == null) return false;
        try {
            List<OverlayInfo> infos = mOverlayManager.getOverlayInfosForTarget(
                    "android", UserHandle.USER_SYSTEM);
            for (OverlayInfo info : infos) {
                if (OVERLAY_CATEGORY_FONT.equals(info.category) && info.isEnabled()) {
                    mOverlayManager.setEnabled(info.packageName, false, UserHandle.USER_SYSTEM);
                }
            }

            if ("default".equals(packageName)) {
                mOverlayManager.setEnabledExclusiveInCategory(
                        DEFAULT_FONT_PKG, UserHandle.USER_SYSTEM);
                writeFontOverlaySetting(DEFAULT_FONT_PKG);
            } else {
                mOverlayManager.setEnabledExclusiveInCategory(packageName, UserHandle.USER_SYSTEM);
                writeFontOverlaySetting(packageName);
            }
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to apply font overlay", e);
            return false;
        }
    }

    private void writeFontOverlaySetting(String packageName) {
        try {
            String current = Settings.Secure.getStringForUser(
                    getContext().getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    UserHandle.USER_CURRENT);
            JSONObject json = current == null || current.isEmpty()
                    ? new JSONObject() : new JSONObject(current);
            if (packageName == null) {
                json.remove(OVERLAY_CATEGORY_FONT);
            } else {
                json.put(OVERLAY_CATEGORY_FONT, packageName);
            }
            Settings.Secure.putStringForUser(getContext().getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    json.toString(), UserHandle.USER_CURRENT);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to write font overlay setting", e);
        }
    }

    private Typeface getTypefaceForPackage(String pkg) {
        if (pkg == null) {
            return Typeface.DEFAULT;
        }
        if ("default".equals(pkg)) {
            pkg = DEFAULT_FONT_PKG;
        }
        try {
            PackageManager pm = getContext().getPackageManager();
            Resources res = pm.getResourcesForApplication(pkg);
            int id = res.getIdentifier("config_bodyFontFamily", "string", pkg);
            if (id != 0) {
                return Typeface.create(res.getString(id), Typeface.NORMAL);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load typeface for " + pkg, e);
        }
        return Typeface.DEFAULT;
    }

    private void showPrebuiltFontDialog() {
        final Context context = getContext();
        if (context == null || mFontLabels.isEmpty()) return;

        int checked = Math.max(0, mFontPackages.indexOf(mSelectedFontPkg));
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context,
                android.R.layout.select_dialog_singlechoice, mFontLabels) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                if (tv != null && position >= 0 && position < mFontPackages.size()) {
                    tv.setTypeface(getTypefaceForPackage(mFontPackages.get(position)));
                }
                return view;
            }
        };

        new AlertDialog.Builder(context)
                .setTitle(R.string.font_prebuilt_title)
                .setSingleChoiceItems(adapter, checked, (dialog, which) -> {
                    String pkg = mFontPackages.get(which);
                    clearCustomFontInternal();
                    if (applyPrebuiltFont(pkg)) {
                        mSelectedFontPkg = pkg;
                        mPrebuiltFontPref.setSummary(mFontLabels.get(which));
                        Toast.makeText(context, R.string.font_prebuilt_applied, Toast.LENGTH_SHORT).show();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return false;
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        if (KEY_FONT_PREBUILT.equals(key)) {
            showPrebuiltFontDialog();
            return true;
        } else if (KEY_CUSTOM_FONT_PICKER.equals(key)) {
            pickCustomFont();
            return true;
        } else if (KEY_RESET_CUSTOM_FONT.equals(key)) {
            resetCustomFont();
            return true;
        } else if (KEY_REBOOT_FOR_FONT.equals(key)) {
            showRebootDialog();
            return true;
        }
        return false;
    }
}
