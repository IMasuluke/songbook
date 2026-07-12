package com.example.songbook.domain.model;

public class WordToken {
    public final String text;
    public final int start;

    public WordToken(String text, int start) {
        this.text = text;
        this.start = start;
    }
}
