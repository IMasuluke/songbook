package com.example.songbook.domain.model;

import java.util.HashMap;
import java.util.Map;

public class ChordLine {
    public final String text;
    public final Map<Integer, String> chords = new HashMap<>();

    public ChordLine(String text) {
        this.text = text == null ? "" : text;
    }
}
