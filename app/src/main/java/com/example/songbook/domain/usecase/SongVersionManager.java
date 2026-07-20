package com.example.songbook.domain.usecase;

import com.example.songbook.domain.model.Song;
import com.example.songbook.domain.model.SongVersion;

public class SongVersionManager {
    public void ensureDefaultVersion(Song song) {
        if (song.versions.isEmpty()) {
            SongVersion main = SongVersion.fromSongSnapshot(song, "Main", "");
            song.versions.add(main);
            song.activeVersionId = main.id;
            return;
        }
        if (findVersion(song, song.activeVersionId) == null) {
            song.activeVersionId = song.versions.get(0).id;
            loadVersionIntoSong(song, song.versions.get(0));
        }
    }

    public SongVersion activeVersion(Song song) {
        ensureDefaultVersion(song);
        SongVersion version = findVersion(song, song.activeVersionId);
        return version == null ? song.versions.get(0) : version;
    }

    public SongVersion findVersion(Song song, String versionId) {
        for (SongVersion version : song.versions) {
            if (version.id.equals(versionId)) {
                return version;
            }
        }
        return null;
    }

    public SongVersion createVersionFromCurrent(Song song, String name) {
        ensureDefaultVersion(song);
        saveActiveVersion(song);
        SongVersion parent = activeVersion(song);
        SongVersion version = SongVersion.fromSongSnapshot(song, name, parent.id);
        song.versions.add(version);
        song.activeVersionId = version.id;
        return version;
    }

    public void switchVersion(Song song, SongVersion version) {
        ensureDefaultVersion(song);
        saveActiveVersion(song);
        song.activeVersionId = version.id;
        loadVersionIntoSong(song, version);
    }

    public void saveActiveVersion(Song song) {
        SongVersion version = activeVersion(song);
        version.key = song.key;
        version.timeSignature = song.timeSignature;
        version.capo = song.capo;
        version.tuning = song.tuning;
        version.body = song.body;
        version.chordLinesJson = song.chordLinesJson;
        version.notes = song.notes;
        version.updatedAt = System.currentTimeMillis();
    }

    public void loadVersionIntoSong(Song song, SongVersion version) {
        song.key = version.key;
        song.timeSignature = version.timeSignature;
        song.capo = version.capo;
        song.tuning = version.tuning;
        song.body = version.body;
        song.chordLinesJson = version.chordLinesJson;
        song.notes = version.notes;
    }

    public String parentVersionLabel(Song song, SongVersion version) {
        if (version.parentId.isEmpty()) {
            return "";
        }
        SongVersion parent = findVersion(song, version.parentId);
        return parent == null ? " - branched" : " - branched from " + parent.name;
    }

    public String nextVersionName(Song song) {
        return "Version " + (song.versions.size() + 1);
    }
}
