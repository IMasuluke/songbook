package com.example.songbook.domain.model;

import java.util.UUID;

public class SongVersion {
    public String id = UUID.randomUUID().toString();
    public String name = "Main";
    public String parentId = "";
    public String key = "";
    public String timeSignature = "";
    public String capo = "";
    public String tuning = "";
    public String body = "";
    public String chordLinesJson = "";
    public String notes = "";
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = createdAt;

    public static SongVersion fromSongSnapshot(Song song, String name, String parentId) {
        SongVersion version = new SongVersion();
        version.name = name == null || name.trim().isEmpty() ? "Version" : name.trim();
        version.parentId = parentId == null ? "" : parentId;
        version.key = song.key;
        version.timeSignature = song.timeSignature;
        version.capo = song.capo;
        version.tuning = song.tuning;
        version.body = song.body;
        version.chordLinesJson = song.chordLinesJson;
        version.notes = song.notes;
        return version;
    }
}
