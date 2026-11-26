/*
 * SPDX-FileCopyrightText: 2025 AxionOS
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.lineageparts.style;

import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.graphics.Typeface;
import android.graphics.fonts.FontFamilyUpdateRequest;
import android.graphics.fonts.FontFileUpdateRequest;
import android.graphics.fonts.FontFileUtil;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.FontStyle;
import android.net.Uri;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.statusbar.IStatusBarService;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class ExternalFontInstaller {

    private static final String TAG = "ExternalFontInstaller";
    private static final String CUSTOM_FONT_FILE = "cust_font.ttf";
    private static final String TEMP_PREVIEW_FONT = "preview_font.ttf";
    private static final String OVERLAY_CATEGORY_FONT = "android.theme.customization.font";
    private static final String DEFAULT_FONT_FAMILY = "google-sans-flex";
    private static final String DEFAULT_FONT_OVERLAY = "com.android.theme.font.googlesansflex";

    private final Context mContext;
    private final FontManager mFontManager;

    public ExternalFontInstaller(Context context) {
        mContext = context;
        mFontManager = context.getSystemService(FontManager.class);
    }

    public static void rebootDevice(Context context) {
        try {
            android.os.IBinder binder = ServiceManager.getService("statusbar");
            IStatusBarService statusBarService = IStatusBarService.Stub.asInterface(binder);
            statusBarService.reboot(false, "system_font_change");
        } catch (Exception e) {
            Log.e(TAG, "Failed to reboot device via statusbar service", e);
        }
    }

    public Typeface loadTypefaceFromUri(Context context, Uri uri) {
        File tempFile = copyUriToCache(context, uri, TEMP_PREVIEW_FONT);
        if (tempFile == null) return null;

        String postScriptName = extractPostScriptName(tempFile);
        if (postScriptName == null) {
            tempFile.delete();
            return null;
        }

        return Typeface.createFromFile(tempFile);
    }

    public String installFontFromUri(Context context, Uri uri) {
        File fontFile = copyUriToCache(context, uri, CUSTOM_FONT_FILE);
        if (fontFile == null) {
            return null;
        }

        String postScriptName = extractPostScriptName(fontFile);
        if (postScriptName == null) {
            fontFile.delete();
            return null;
        }

        if (!applyFontToSystem(fontFile, postScriptName)) {
            fontFile.delete();
            return null;
        }

        updateThemeOverlays(context);
        cleanupPreviewFont(context);
        return postScriptName;
    }

    private File copyUriToCache(Context context, Uri uri, String fileName) {
        try {
            File cacheFile = new File(context.getCacheDir(), fileName);
            ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return null;

            FileInputStream input = new FileInputStream(pfd.getFileDescriptor());
            FileOutputStream output = new FileOutputStream(cacheFile);

            FileUtils.copy(input, output);

            input.close();
            output.close();
            pfd.close();

            return cacheFile;
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy URI to cache", e);
            return null;
        }
    }

    private String extractPostScriptName(File fontFile) {
        try {
            FileInputStream fis = new FileInputStream(fontFile);
            FileChannel channel = fis.getChannel();
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            String name = FontFileUtil.getPostScriptName(buffer, 0);
            fis.close();
            return name;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract PostScript name", e);
            return null;
        }
    }

    private boolean applyFontToSystem(File fontFile, String postScriptName) {
        try {
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(fontFile, ParcelFileDescriptor.MODE_READ_ONLY);
            FontFileUpdateRequest fontFileUpdateRequest = new FontFileUpdateRequest(pfd, new byte[0]);

            FontFamilyUpdateRequest.Font fontRegular = new FontFamilyUpdateRequest.Font.Builder(
                    postScriptName,
                    new FontStyle()
            ).build();

            List<FontFamilyUpdateRequest.Font> fonts = new ArrayList<>();
            fonts.add(fontRegular);

            FontFamilyUpdateRequest.FontFamily familyRegular = new FontFamilyUpdateRequest.FontFamily.Builder(
                    DEFAULT_FONT_FAMILY,
                    fonts
            ).build();

            FontFamilyUpdateRequest updateRequest = new FontFamilyUpdateRequest.Builder()
                    .addFontFileUpdateRequest(fontFileUpdateRequest)
                    .addFontFamily(familyRegular)
                    .build();

            int result = mFontManager.updateFontFamily(
                    updateRequest,
                    mFontManager.getFontConfig().getConfigVersion()
            );

            return result == FontManager.RESULT_SUCCESS;
        } catch (Exception e) {
            Log.e(TAG, "Failed to update system font", e);
            return false;
        }
    }

    private void updateThemeOverlays(Context context) {
        try {
            int userId = UserHandle.myUserId();
            String current = Settings.Secure.getStringForUser(
                    context.getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    userId
            );

            JSONObject json;
            if (current == null || current.isEmpty()) {
                json = new JSONObject();
            } else {
                json = new JSONObject(current);
            }

            if (json.has(OVERLAY_CATEGORY_FONT)) {
                json.remove(OVERLAY_CATEGORY_FONT);
            }

            Settings.Secure.putStringForUser(
                    context.getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    json.toString(),
                    userId
            );

            disableEnabledFontOverlays();
        } catch (Exception e) {
            Log.e(TAG, "Failed to persist custom font overlay", e);
        }
    }

    private void disableEnabledFontOverlays() {
        try {
            IOverlayManager om = IOverlayManager.Stub.asInterface(
                    ServiceManager.getService(Context.OVERLAY_SERVICE));
            if (om == null) return;
            List<OverlayInfo> infos = om.getOverlayInfosForTarget("android", UserHandle.USER_SYSTEM);
            for (OverlayInfo info : infos) {
                if (OVERLAY_CATEGORY_FONT.equals(info.category) && info.isEnabled()) {
                    om.setEnabled(info.packageName, false, UserHandle.USER_SYSTEM);
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to disable font overlays", e);
        }
    }

    public void resetFontUpdates(Context context) {
        try {
            mFontManager.clearUpdates();
        } catch (Exception e) {
            Log.w(TAG, "Failed to clear font updates, reboot may be required", e);
        }
        cleanupPreviewFont(context);
    }

    private void cleanupPreviewFont(Context context) {
        try {
            File previewFile = new File(context.getCacheDir(), TEMP_PREVIEW_FONT);
            if (previewFile.exists()) {
                previewFile.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to cleanup preview font", e);
        }
    }
}
