package com.example.songbook;

import android.util.Log;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;
import org.json.JSONObject;

public class RemoteConfigManager {
    private static final String TAG = "RemoteConfig";
    private static final long CACHE_EXPIRY_SECONDS = 3600;
    private static RemoteConfigManager instance;
    private final FirebaseRemoteConfig remoteConfig;

    private RemoteConfigManager() {
        remoteConfig = FirebaseRemoteConfig.getInstance();
        FirebaseRemoteConfigSettings settings = new FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(CACHE_EXPIRY_SECONDS)
                .build();
        remoteConfig.setConfigSettingsAsync(settings);
        setDefaults();
    }

    public static RemoteConfigManager getInstance() {
        if (instance == null) {
            instance = new RemoteConfigManager();
        }
        return instance;
    }

    private void setDefaults() {
        JSONObject defaults = new JSONObject();
        try {
            defaults.put("enable_google_docs_collaboration", true);
            defaults.put("enable_voice_recordings", true);
            defaults.put("enable_web_lookup", true);
            defaults.put("enable_song_versions", true);
            defaults.put("max_recording_duration_seconds", 300);
            remoteConfig.setDefaultsAsync(defaults);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set remote config defaults", e);
        }
    }

    public void fetchAndActivate(Runnable onComplete) {
        remoteConfig.fetchAndActivate()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Remote config fetched and activated");
                    } else {
                        Log.w(TAG, "Failed to fetch remote config", task.getException());
                    }
                    if (onComplete != null) {
                        onComplete.run();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Remote config fetch failed", e);
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
    }

    public boolean isFeatureEnabled(String featureName) {
        return remoteConfig.getBoolean(featureName);
    }

    public long getLongValue(String key, long defaultValue) {
        try {
            long value = remoteConfig.getLong(key);
            return value > 0 ? value : defaultValue;
        } catch (Exception e) {
            Log.w(TAG, "Error getting long value for " + key, e);
            return defaultValue;
        }
    }

    public String getStringValue(String key, String defaultValue) {
        try {
            String value = remoteConfig.getString(key);
            return value != null && !value.isEmpty() ? value : defaultValue;
        } catch (Exception e) {
            Log.w(TAG, "Error getting string value for " + key, e);
            return defaultValue;
        }
    }
}
