package com.example.songbook.data.local;

import com.example.songbook.domain.model.Recording;
import com.example.songbook.domain.model.Song;
import com.example.songbook.domain.model.SongVersion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class SongJsonMapper {
    private SongJsonMapper() {
    }

    public static Song fromJson(JSONObject object) {
        Song song = new Song();
        song.id = object.optString("id", UUID.randomUUID().toString());
        song.title = object.optString("title");
        song.artist = object.optString("artist");
        song.key = object.optString("key");
        song.timeSignature = object.optString("timeSignature");
        song.capo = object.optString("capo");
        song.tuning = object.optString("tuning");
        song.body = object.optString("body");
        song.chordLinesJson = object.optString("chordLinesJson");
        song.notes = object.optString("notes");
        song.sourceUrl = object.optString("sourceUrl");
        song.isOnlineSource = object.optBoolean("isOnlineSource", false);
        song.isFavorite = object.optBoolean("isFavorite", false);
        song.googleDocId = object.optString("googleDocId");
        song.googleDocUrl = object.optString("googleDocUrl");
        song.lastKnownDocRevisionId = object.optString("lastKnownDocRevisionId");
        song.lastSyncedBodyHash = object.optString("lastSyncedBodyHash");
        song.lastSyncedAt = object.optLong("lastSyncedAt");
        song.activeVersionId = object.optString("activeVersionId");

        JSONArray versions = object.optJSONArray("versions");
        if (versions != null) {
            for (int i = 0; i < versions.length(); i++) {
                JSONObject version = versions.optJSONObject(i);
                if (version != null) {
                    song.versions.add(versionFromJson(version));
                }
            }
        }
        hydrateActiveVersion(song);

        JSONArray recordings = object.optJSONArray("recordings");
        if (recordings != null) {
            for (int i = 0; i < recordings.length(); i++) {
                JSONObject recording = recordings.optJSONObject(i);
                if (recording != null) {
                    song.recordings.add(recordingFromJson(recording));
                }
            }
        }
        return song;
    }

    public static JSONObject toJson(Song song) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", song.id);
            object.put("title", song.title);
            object.put("artist", song.artist);
            object.put("key", song.key);
            object.put("timeSignature", song.timeSignature);
            object.put("capo", song.capo);
            object.put("tuning", song.tuning);
            object.put("body", song.body);
            object.put("chordLinesJson", song.chordLinesJson);
            object.put("notes", song.notes);
            object.put("sourceUrl", song.sourceUrl);
            object.put("isOnlineSource", song.isOnlineSource);
            object.put("isFavorite", song.isFavorite);
            object.put("googleDocId", song.googleDocId);
            object.put("googleDocUrl", song.googleDocUrl);
            object.put("lastKnownDocRevisionId", song.lastKnownDocRevisionId);
            object.put("lastSyncedBodyHash", song.lastSyncedBodyHash);
            object.put("lastSyncedAt", song.lastSyncedAt);
            object.put("activeVersionId", song.activeVersionId);
            JSONArray versionArray = new JSONArray();
            for (SongVersion version : song.versions) {
                versionArray.put(versionToJson(version));
            }
            object.put("versions", versionArray);
            JSONArray recordingArray = new JSONArray();
            for (Recording recording : song.recordings) {
                recordingArray.put(recordingToJson(recording));
            }
            object.put("recordings", recordingArray);
        } catch (JSONException ignored) {
        }
        return object;
    }

    private static void hydrateActiveVersion(Song song) {
        if (song.versions.isEmpty()) {
            return;
        }
        SongVersion active = null;
        for (SongVersion version : song.versions) {
            if (version.id.equals(song.activeVersionId)) {
                active = version;
                break;
            }
        }
        if (active == null) {
            active = song.versions.get(0);
            song.activeVersionId = active.id;
        }
        song.key = active.key;
        song.timeSignature = active.timeSignature;
        song.capo = active.capo;
        song.tuning = active.tuning;
        song.body = active.body;
        song.chordLinesJson = active.chordLinesJson;
        song.notes = active.notes;
    }

    private static SongVersion versionFromJson(JSONObject object) {
        SongVersion version = new SongVersion();
        version.id = object.optString("id", UUID.randomUUID().toString());
        version.name = object.optString("name", "Main");
        version.parentId = object.optString("parentId");
        version.key = object.optString("key");
        version.timeSignature = object.optString("timeSignature");
        version.capo = object.optString("capo");
        version.tuning = object.optString("tuning");
        version.body = object.optString("body");
        version.chordLinesJson = object.optString("chordLinesJson");
        version.notes = object.optString("notes");
        version.createdAt = object.optLong("createdAt", System.currentTimeMillis());
        version.updatedAt = object.optLong("updatedAt", version.createdAt);
        return version;
    }

    private static JSONObject versionToJson(SongVersion version) throws JSONException {
        return new JSONObject()
                .put("id", version.id)
                .put("name", version.name)
                .put("parentId", version.parentId)
                .put("key", version.key)
                .put("timeSignature", version.timeSignature)
                .put("capo", version.capo)
                .put("tuning", version.tuning)
                .put("body", version.body)
                .put("chordLinesJson", version.chordLinesJson)
                .put("notes", version.notes)
                .put("createdAt", version.createdAt)
                .put("updatedAt", version.updatedAt);
    }

    private static Recording recordingFromJson(JSONObject object) {
        return new Recording(object.optString("path"), object.optLong("createdAt"), object.optString("name"));
    }

    private static JSONObject recordingToJson(Recording recording) throws JSONException {
        return new JSONObject()
                .put("path", recording.path)
                .put("createdAt", recording.createdAt)
                .put("name", recording.name);
    }
}
