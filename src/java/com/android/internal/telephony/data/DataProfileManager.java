/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.internal.telephony.data;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Telephony;
import android.telephony.Annotation;
import android.telephony.Annotation.NetCapability;
import android.telephony.Annotation.NetworkType;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.data.DataProfile;
import android.telephony.data.TrafficDescriptor;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.LocalLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.data.DataNetworkController.DataNetworkControllerCallback;
import com.android.telephony.Rlog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * DataProfileManager manages the all {@link DataProfile}s for the current
 * subscription.
 */
public class DataProfileManager extends Handler {
    /** Event for data config updated. */
    private static final int EVENT_DATA_CONFIG_UPDATED = 1;

    /** Event for APN database changed. */
    private static final int EVENT_APN_DATABASE_CHANGED = 2;

    /** Event for SIM refresh. */
    private static final int EVENT_SIM_REFRESH = 3;

    private final Phone mPhone;
    private final String mLogTag;
    private final LocalLog mLocalLog = new LocalLog(128);

    /** Data network controller. */
    private final @NonNull DataNetworkController mDataNetworkController;

    /** Data config manager. */
    private final @NonNull DataConfigManager mDataConfigManager;

    /** Cellular data service. */
    private final @NonNull DataServiceManager mWwanDataServiceManager;

    /** All data profiles for the current carrier. */
    private final @NonNull List<DataProfile> mAllDataProfiles = new ArrayList<>();

    /** The data profile used for initial attach. */
    private @Nullable DataProfile mInitialAttachDataProfile = null;

    /** The preferred data profile used for internet. */
    private @Nullable DataProfile mPreferredDataProfile = null;

    /** Preferred data profile set id. */
    private int mPreferredDataProfileSetId = Telephony.Carriers.NO_APN_SET_ID;

    /** Data profile manager callbacks. */
    private final @NonNull Set<DataProfileManagerCallback> mDataProfileManagerCallbacks =
            new ArraySet<>();

    /**
     * Data profile manager callback. This should be only used by {@link DataNetworkController}.
     */
    public abstract static class DataProfileManagerCallback extends DataCallback {
        /**
         * Constructor
         *
         * @param executor The executor of the callback.
         */
        public DataProfileManagerCallback(@NonNull @CallbackExecutor Executor executor) {
            super(executor);
        }

        /**
         * Called when data profiles changed.
         */
        public abstract void onDataProfilesChanged();
    }

    /**
     * Constructor
     *
     * @param phone The phone instance.
     * @param dataNetworkController Data network controller.
     * @param dataServiceManager WWAN data service manager.
     * @param looper The looper to be used by the handler. Currently the handler thread is the
     * phone process's main thread.
     * @param callback Data profile manager callback.
     */
    public DataProfileManager(@NonNull Phone phone,
            @NonNull DataNetworkController dataNetworkController,
            @NonNull DataServiceManager dataServiceManager, @NonNull Looper looper,
            @NonNull DataProfileManagerCallback callback) {
        super(looper);
        mPhone = phone;
        mLogTag = "DPM-" + mPhone.getPhoneId();
        mDataNetworkController = dataNetworkController;
        mWwanDataServiceManager = dataServiceManager;
        mDataConfigManager = dataNetworkController.getDataConfigManager();
        mDataProfileManagerCallbacks.add(callback);
        registerAllEvents();
    }

    /**
     * Register for all events that data network controller is interested.
     */
    private void registerAllEvents() {
        mDataNetworkController.registerDataNetworkControllerCallback(
                new DataNetworkControllerCallback(this::post) {
                    @Override
                    public void onInternetDataNetworkConnected(
                            @NonNull List<DataProfile> dataProfiles) {
                        DataProfileManager.this.onInternetDataNetworkConnected(dataProfiles);
                    }});
        mDataConfigManager.registerForConfigUpdate(this, EVENT_DATA_CONFIG_UPDATED);
        mPhone.getContext().getContentResolver().registerContentObserver(
                Telephony.Carriers.CONTENT_URI, true, new ContentObserver(this) {
                    @Override
                    public void onChange(boolean selfChange) {
                        super.onChange(selfChange);
                        sendEmptyMessage(EVENT_APN_DATABASE_CHANGED);
                    }
                });
        mPhone.mCi.registerForIccRefresh(this, EVENT_SIM_REFRESH, null);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_DATA_CONFIG_UPDATED:
                onDataConfigUpdated();
                break;
            case EVENT_SIM_REFRESH:
                log("Update data profiles due to SIM refresh.");
                updateDataProfiles();
                break;
            case EVENT_APN_DATABASE_CHANGED:
                log("Update data profiles due to APN db updated.");
                updateDataProfiles();
                break;
            default:
                loge("Unexpected event " + msg);
                break;
        }
    }

    /**
     * Called when data config was updated.
     */
    private void onDataConfigUpdated() {
        log("Update data profiles due to config updated.");
        updateDataProfiles();

        //TODO: more works needed to be done here.
    }

    /**
     * Update all data profiles, including preferred data profile, and initial attach data profile.
     * Also send those profiles down to the modem if needed.
     */
    private void updateDataProfiles() {
        List<DataProfile> profiles = new ArrayList<>();
        if (mDataConfigManager.isConfigCarrierSpecific()) {
            Cursor cursor = mPhone.getContext().getContentResolver().query(
                    Uri.withAppendedPath(Telephony.Carriers.SIM_APN_URI, "filtered/subId/"
                            + mPhone.getSubId()), null, null, null, Telephony.Carriers._ID);
            if (cursor == null) {
                loge("Cannot access APN database through telephony provider.");
                return;
            }

            while (cursor.moveToNext()) {
                ApnSetting apn = ApnSetting.makeApnSetting(cursor);
                if (apn != null) {
                    DataProfile dataProfile = new DataProfile.Builder()
                            .setApnSetting(apn)
                            // TODO: Support TD correctly once ENTERPRISE becomes an APN type.
                            .setTrafficDescriptor(new TrafficDescriptor(apn.getApnName(), null))
                            .setPreferred(false)
                            .build();
                    profiles.add(dataProfile);
                    log("Added " + dataProfile);
                }
            }
            cursor.close();
        }

        // Check if any of the profile already supports IMS, if not, add the default one.
        DataProfile dataProfile = profiles.stream()
                .filter(dp -> dp.canSatisfy(NetworkCapabilities.NET_CAPABILITY_IMS))
                .findFirst()
                .orElse(null);
        if (dataProfile == null) {
            profiles.add(new DataProfile.Builder()
                    .setApnSetting(buildDefaultApnSetting("DEFAULT IMS", "ims",
                            ApnSetting.TYPE_IMS))
                    .build());
            log("Added default IMS data profile.");
        }

        // Check if any of the profile already supports EIMS, if not, add the default one.
        dataProfile = profiles.stream()
                .filter(dp -> dp.canSatisfy(NetworkCapabilities.NET_CAPABILITY_EIMS))
                .findFirst()
                .orElse(null);
        if (dataProfile == null) {
            profiles.add(new DataProfile.Builder()
                    .setApnSetting(buildDefaultApnSetting("DEFAULT EIMS", "sos",
                            ApnSetting.TYPE_EMERGENCY))
                    .build());
            log("Added default EIMS data profile.");
        }

        log("Found " + profiles.size() + " data profiles. profiles = " + profiles);

        boolean profilesChanged = false;
        if (mAllDataProfiles.size() != profiles.size() || !mAllDataProfiles.containsAll(profiles)) {
            log("Data profiles changed.");
            mAllDataProfiles.clear();
            mAllDataProfiles.addAll(profiles);
            profilesChanged = true;
        }

        int setId = getPreferredDataProfileSetId();
        if (setId != mPreferredDataProfileSetId) {
            logl("Changed preferred data profile set id to " + setId);
            mPreferredDataProfileSetId = setId;
            profilesChanged = true;
        }
        // Reload the latest preferred data profile from either database or config.
        profilesChanged |= updatePreferredDataProfile();

        updateDataProfilesAtModem();
        updateInitialAttachDataProfileAtModem();

        if (profilesChanged) {
            mDataProfileManagerCallbacks.forEach(callback -> callback.invokeFromExecutor(
                    callback::onDataProfilesChanged));
        }
    }

    /**
     * @return The preferred data profile set id.
     */
    private int getPreferredDataProfileSetId() {
        // Query the preferred APN set id. The set id is automatically set when we set by
        // TelephonyProvider when setting preferred APN in setPreferredDataProfile().
        Cursor cursor = mPhone.getContext().getContentResolver()
                .query(Uri.withAppendedPath(Telephony.Carriers.PREFERRED_APN_SET_URI,
                        String.valueOf(mPhone.getSubId())),
                        new String[] {Telephony.Carriers.APN_SET_ID}, null, null, null);
        // Returns all APNs for the current carrier which have an apn_set_id
        // equal to the preferred APN (if no preferred APN, or if the preferred APN has no set id,
        // the query will return null)
        if (cursor == null) {
            log("getPreferredDataProfileSetId: cursor is null");
            return Telephony.Carriers.NO_APN_SET_ID;
        }

        int setId;
        if (cursor.getCount() < 1) {
            loge("getPreferredDataProfileSetId: no APNs found");
            setId = Telephony.Carriers.NO_APN_SET_ID;
        } else {
            cursor.moveToFirst();
            setId = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN_SET_ID));
        }

        cursor.close();
        return setId;
    }

    /**
     * Called when internet data is connected.
     *
     * @param dataProfiles The connected internet data networks' profiles.
     */
    private void onInternetDataNetworkConnected(@NonNull List<DataProfile> dataProfiles) {
        // If there is already a preferred data profile set, then we don't need to do anything.
        if (mPreferredDataProfile != null) return;

        // If there is no preferred data profile, then we should use one of the data profiles,
        // which is good for internet, as the preferred data profile.

        // Most of the cases there should be only one, but in case there are multiple, choose the
        // one which has longest life cycle.
        DataProfile dataProfile = dataProfiles.stream()
                .max(Comparator.comparingLong(DataProfile::getLastSetupTimestamp).reversed())
                .orElse(null);
        // Save the preferred data profile into database.
        setPreferredDataProfile(dataProfile);
    }

    /**
     * Get the preferred data profile for internet data.
     *
     * @return The preferred data profile.
     */
    private @Nullable DataProfile getPreferredDataProfileFromDb() {
        Cursor cursor = mPhone.getContext().getContentResolver().query(
                Uri.withAppendedPath(Telephony.Carriers.PREFERRED_APN_URI,
                        String.valueOf(mPhone.getSubId())), null, null, null,
                Telephony.Carriers.DEFAULT_SORT_ORDER);
        DataProfile dataProfile = null;
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                int apnId = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID));
                dataProfile = mAllDataProfiles.stream()
                        .filter(dp -> dp.getApnSetting() != null
                                && dp.getApnSetting().getId() == apnId)
                        .findFirst()
                        .orElse(null);
            }
            cursor.close();
        }
        log("getPreferredDataProfileFromDb: " + dataProfile);
        return dataProfile;
    }

    /**
     * @return The preferred data profile from carrier config.
     */
    private @Nullable DataProfile getPreferredDataProfileFromConfig() {
        // Check if there is configured default preferred data profile.
        String defaultPreferredApn = mDataConfigManager.getDefaultPreferredApn();
        if (!TextUtils.isEmpty(defaultPreferredApn)) {
            return mAllDataProfiles.stream()
                    .filter(dp -> dp.getApnSetting() != null && defaultPreferredApn.equals(
                                    dp.getApnSetting().getApnName()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    /**
     * Save the preferred data profile into the database.
     *
     * @param dataProfile The preferred data profile used for internet data. {@code null} to clear
     * the preferred data profile from database.
     */
    private void setPreferredDataProfile(@Nullable DataProfile dataProfile) {
        log("setPreferredDataProfile: " + dataProfile);

        String subId = Long.toString(mPhone.getSubId());
        Uri uri = Uri.withAppendedPath(Telephony.Carriers.PREFERRED_APN_URI, subId);
        ContentResolver resolver = mPhone.getContext().getContentResolver();
        resolver.delete(uri, null, null);

        if (dataProfile != null && dataProfile.getApnSetting() != null) {
            ContentValues values = new ContentValues();
            // Fill only the id here. TelephonyProvider will pull the rest of key fields and write
            // into the database.
            values.put(Telephony.Carriers.APN_ID, dataProfile.getApnSetting().getId());
            resolver.insert(uri, values);
        }
    }

    /**
     * Reload the latest preferred data profile from either database or the config. This is to
     * make sure the cached {@link #mPreferredDataProfile} is in-sync.
     *
     * @return {@code true} if preferred data profile changed.
     */
    private boolean updatePreferredDataProfile() {
        DataProfile preferredDataProfile;
        if (SubscriptionManager.isValidSubscriptionId(mPhone.getSubId())) {
            preferredDataProfile = getPreferredDataProfileFromDb();
            if (preferredDataProfile == null) {
                preferredDataProfile = getPreferredDataProfileFromConfig();
            }
        } else {
            preferredDataProfile = null;
        }

        if (!Objects.equals(mPreferredDataProfile, preferredDataProfile)) {
            // Replaced the data profile with preferred bit set.
            if (preferredDataProfile != null) preferredDataProfile.setPreferred(true);
            mPreferredDataProfile = preferredDataProfile;

            logl("Changed preferred data profile to " + mPreferredDataProfile);
            return true;
        }
        return false;
    }

    /**
     * Update the data profile used for initial attach.
     *
     * Note that starting from Android 13 only APNs that supports "IA" type will be used for
     * initial attach. Please update APN configuration file if needed.
     *
     * Some carriers might explicitly require that using "user-added" APN for initial
     * attach. In this case, exception can be configured through
     * {@link CarrierConfigManager#KEY_ALLOWED_INITIAL_ATTACH_APN_TYPES_STRING_ARRAY}.
     */
    private void updateInitialAttachDataProfileAtModem() {
        DataProfile initialAttachDataProfile = null;

        // Sort the data profiles so the preferred data profile is at the beginning.
        List<DataProfile> allDataProfiles = mAllDataProfiles.stream()
                .sorted(Comparator.comparing((DataProfile dp) -> !dp.equals(mPreferredDataProfile)))
                .collect(Collectors.toList());
        // Search in the order. "IA" type should be the first from getAllowedInitialAttachApnTypes.
        for (int apnType : mDataConfigManager.getAllowedInitialAttachApnTypes()) {
            initialAttachDataProfile = allDataProfiles.stream()
                    .filter(dp -> dp.canSatisfy(DataUtils.apnTypeToNetworkCapability(apnType)))
                    .findFirst()
                    .orElse(null);
            if (initialAttachDataProfile != null) break;
        }

        if (!Objects.equals(mInitialAttachDataProfile, initialAttachDataProfile)) {
            mInitialAttachDataProfile = initialAttachDataProfile;
            logl("Initial attach data profile updated as " + mInitialAttachDataProfile);
            // TODO: Push the null data profile to modem on new AIDL HAL. Modem should clear the IA
            //  APN.
            if (mInitialAttachDataProfile != null) {
                mWwanDataServiceManager.setInitialAttachApn(mInitialAttachDataProfile,
                        mPhone.getServiceState().getDataRoamingFromRegistration(), null);
            }
        }
    }

    /**
     * Update the data profiles at modem.
     */
    private void updateDataProfilesAtModem() {
        log("updateDataProfilesAtModem: set " + mAllDataProfiles.size() + " data profiles.");
        mWwanDataServiceManager.setDataProfile(mAllDataProfiles,
                mPhone.getServiceState().getDataRoamingFromRegistration(), null);
    }

    /**
     * Create default apn settings for the apn type like emergency, and ims
     *
     * @param entry Entry name
     * @param apn APN name
     * @param apnTypeBitmask APN type
     * @return The APN setting
     */
    private @NonNull ApnSetting buildDefaultApnSetting(@NonNull String entry,
            @NonNull String apn, @Annotation.ApnType int apnTypeBitmask) {
        return new ApnSetting.Builder()
                .setEntryName(entry)
                .setProtocol(ApnSetting.PROTOCOL_IPV4V6)
                .setRoamingProtocol(ApnSetting.PROTOCOL_IPV4V6)
                .setApnName(apn)
                .setApnTypeBitmask(apnTypeBitmask)
                .setCarrierEnabled(true)
                .setApnSetId(Telephony.Carriers.MATCH_ALL_APN_SET_ID)
                .build();
    }

    /**
     * Get the data profile that can satisfy the network request.
     *
     * @param networkRequest The network request.
     * @param networkType The current data network type.
     * @return The data profile. {@code null} if can't find any satisfiable data profile.
     */
    public @Nullable DataProfile getDataProfileForNetworkRequest(
            @NonNull TelephonyNetworkRequest networkRequest, @NetworkType int networkType) {
        // Filter out the data profile that can't satisfy the request.
        // Preferred data profile should be returned in the top of the list.
        List<DataProfile> dataProfiles = getDataProfilesForNetworkCapabilities(
                networkRequest.getCapabilities());
        if (dataProfiles.size() == 0) {
            log("Can't find any data profile that can satisfy " + networkRequest);
            return null;
        }

        // Step 3: Check if the remaining data profiles can used in current data network type.
        dataProfiles = dataProfiles.stream()
                .filter(dp -> dp.getApnSetting() != null
                        && dp.getApnSetting().canSupportNetworkType(networkType))
                .collect(Collectors.toList());
        if (dataProfiles.size() == 0) {
            log("Can't find any data profile for network type "
                    + TelephonyManager.getNetworkTypeName(networkType));
            return null;
        }

        // Step 4: Check if preferred data profile set id matches.
        dataProfiles = dataProfiles.stream()
                .filter(dp -> dp.getApnSetting() != null
                        && (dp.getApnSetting().getApnSetId()
                        == Telephony.Carriers.MATCH_ALL_APN_SET_ID
                        || dp.getApnSetting().getApnSetId() == mPreferredDataProfileSetId))
                .collect(Collectors.toList());
        if (dataProfiles.size() == 0) {
            log("Can't find any data profile has APN set id matched. mPreferredDataProfileSetId="
                    + mPreferredDataProfileSetId);
            return null;
        }

        return dataProfiles.get(0);
    }

    /**
     * Get data profiles that can satisfy given network capabilities.
     *
     * @param networkCapabilities The network capabilities.
     * @return data profiles that can satisfy given network capabilities.
     */
    @VisibleForTesting
    public @NonNull List<DataProfile> getDataProfilesForNetworkCapabilities(
            @NonNull @NetCapability int[] networkCapabilities) {
        return mAllDataProfiles.stream()
                .filter(dp -> dp.canSatisfy(networkCapabilities))
                // Put the preferred data profile at the top of the list, then the longest time
                // hasn't used data profile will be in the front so all the data profiles can be
                // tried.
                .sorted(Comparator.comparing((DataProfile dp) -> !dp.equals(mPreferredDataProfile))
                        .thenComparingLong(DataProfile::getLastSetupTimestamp))
                .collect(Collectors.toList());
    }

    /**
     * Check if the data profile is valid. Profiles can change dynamically when users add/remove/
     * switch APNs in APN editors, when SIM refreshes, or when SIM swapped. This is used to check
     * if the data profile which is used for current data network is still valid. If the profile
     * is not valid anymore, the data network should be torn down.
     *
     * @param dataProfile The data profile to check.
     * @return {@code true} if the data profile is still valid for current environment.
     */
    public boolean isDataProfileValid(@NonNull DataProfile dataProfile) {
        return mAllDataProfiles.contains(dataProfile)
                && (dataProfile.getApnSetting() == null
                || dataProfile.getApnSetting().getApnSetId() == mPreferredDataProfileSetId
                || mPreferredDataProfileSetId == Telephony.Carriers.MATCH_ALL_APN_SET_ID);
    }

    /**
     * Check if the data profile is the preferred data profile.
     *
     * @param dataProfile The data profile to check.
     * @return {@code true} if the data profile is the preferred data profile.
     */
    public boolean isDataProfilePreferred(@NonNull DataProfile dataProfile) {
        return dataProfile.equals(mPreferredDataProfile);
    }

    /**
     * Register the callback for receiving information from {@link DataProfileManager}.
     *
     * @param callback The callback.
     */
    public void registerCallback(@NonNull DataProfileManagerCallback callback) {
        mDataProfileManagerCallbacks.add(callback);
    }

    /**
     * Unregister the previously registered {@link DataProfileManagerCallback}.
     *
     * @param callback The callback to unregister.
     */
    public void unregisterCallback(@NonNull DataProfileManagerCallback callback) {
        mDataProfileManagerCallbacks.remove(callback);
    }

    /**
     * Log debug messages.
     * @param s debug messages
     */
    private void log(@NonNull String s) {
        Rlog.d(mLogTag, s);
    }

    /**
     * Log error messages.
     * @param s error messages
     */
    private void loge(@NonNull String s) {
        Rlog.e(mLogTag, s);
    }

    /**
     * Log debug messages and also log into the local log.
     * @param s debug messages
     */
    private void logl(@NonNull String s) {
        log(s);
        mLocalLog.log(s);
    }

    /**
     * Dump the state of DataProfileManager
     *
     * @param fd File descriptor
     * @param printWriter Print writer
     * @param args Arguments
     */
    public void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
        pw.println(DataProfileManager.class.getSimpleName() + "-" + mPhone.getPhoneId() + ":");
        pw.increaseIndent();

        pw.println("Data profiles for the current carrier:");
        pw.increaseIndent();
        for (DataProfile dp : mAllDataProfiles) {
            pw.print(dp);
            pw.println(", last setup time: " + DataUtils.elapsedTimeToString(
                    dp.getLastSetupTimestamp()));
        }
        pw.decreaseIndent();

        pw.println("Preferred data profile=" + mPreferredDataProfile);
        pw.println("Preferred data profile from db=" + getPreferredDataProfileFromDb());
        pw.println("Preferred data profile from config=" + getPreferredDataProfileFromConfig());
        pw.println("Preferred data profile set id=" + mPreferredDataProfileSetId);
        pw.println("Initial attach data profile=" + mInitialAttachDataProfile);

        pw.println("Local logs:");
        pw.increaseIndent();
        mLocalLog.dump(fd, pw, args);
        pw.decreaseIndent();
        pw.decreaseIndent();
    }
}