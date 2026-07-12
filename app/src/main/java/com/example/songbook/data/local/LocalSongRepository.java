package com.example.songbook.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.songbook.domain.model.Song;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class LocalSongRepository {
    private static final String PREFS = "songbook";
    private static final String SONGS = "songs";
    private static final String DRAFT_SONG = "draft_song";

    private final SharedPreferences preferences;

    public LocalSongRepository(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Song> loadSongs() {
        return parseSongs(preferences.getString(SONGS, ""));
    }

    public void saveSongs(List<Song> songs) {
        preferences.edit().putString(SONGS, songsJson(songs)).apply();
    }

    public String songsJson(List<Song> songs) {
        JSONArray array = new JSONArray();
        for (Song song : songs) {
            array.put(SongJsonMapper.toJson(song));
        }
        return array.toString();
    }

    public List<Song> parseSongs(String raw) {
        List<Song> songs = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return songs;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                songs.add(SongJsonMapper.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
            songs.clear();
        }
        return songs;
    }

    public boolean hasDraft() {
        return loadDraftSong() != null;
    }

    public Song loadDraftSong() {
        String raw = preferences.getString(DRAFT_SONG, "");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return SongJsonMapper.fromJson(new JSONObject(raw));
        } catch (JSONException ignored) {
            clearDraft();
            return null;
        }
    }

    public void saveDraft(Song song) {
        preferences.edit()
                .putString(DRAFT_SONG, SongJsonMapper.toJson(song).toString())
                .apply();
    }

    public void clearDraft() {
        preferences.edit().remove(DRAFT_SONG).apply();
    }
}
