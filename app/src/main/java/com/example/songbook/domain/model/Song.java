package com.example.songbook.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Song {
    public String id = UUID.randomUUID().toString();
    public String title = "";
    public String artist = "";
    public String key = "";
    public String body = "";
    public String chordLinesJson = "";
    public String notes = "";
    public String sourceUrl = "";
    public boolean isOnlineSource = false;
    public boolean isFavorite = false;
    public String googleDocId = "";
    public String googleDocUrl = "";
    public String lastKnownDocRevisionId = "";
    public String lastSyncedBodyHash = "";
    public long lastSyncedAt = 0L;
    public String activeVersionId = "";
    public final List<SongVersion> versions = new ArrayList<>();
    public final List<Recording> recordings = new ArrayList<>();

    public static Song sample(String title, String artist, String key, String body, String notes) {
        Song song = new Song();
        song.title = title;
        song.artist = artist;
        song.key = key;
        song.body = body;
        song.notes = notes;
        return song;
    }
}
