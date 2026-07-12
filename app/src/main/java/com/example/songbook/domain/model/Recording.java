package com.example.songbook.domain.model;

public class Recording {
    public String path;
    public final long createdAt;
    public String name;

    public Recording(String path, long createdAt, String name) {
        this.path = path;
        this.createdAt = createdAt;
        this.name = name == null ? "" : name;
    }
}
