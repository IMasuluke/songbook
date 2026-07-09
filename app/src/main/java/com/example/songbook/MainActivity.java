package com.example.songbook;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final String PREFS = "songbook";
    private static final String SONGS = "songs";
    private static final String DRAFT_SONG = "draft_song";
    private static final int MIC_PERMISSION_REQUEST = 42;
    private static final int BACKUP_CREATE_REQUEST = 84;
    private static final int BACKUP_OPEN_REQUEST = 85;

    private final List<Song> songs = new ArrayList<>();
    private LinearLayout root;
    private LinearLayout songList;
    private EditText searchField;
    private String filter = "";
    private Screen screen = Screen.LIBRARY;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private Song recordingSong;
    private Song pendingRecordingSong;
    private String recordingPath = "";
    private String playingPath = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSongs();
        showLibrary();
    }

    private void showLibrary() {
        screen = Screen.LIBRARY;
        root = baseRoot();

        LinearLayout header = topBar("Songbook", songs.size() + " songs in your book", null, v -> showEditor(null), "+");
        header.addView(button("Settings", false, v -> showSettings()), 1, margins(wrap(), wrap(), 0, 0, 10, 0));
        root.addView(header, margins(match(), wrap(), 0, 0, 0, 18));

        searchField = input("Search songs, artists, lyrics, or chords");
        searchField.setSingleLine(true);
        searchField.setText(filter);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.addTextChangedListener(simpleTextWatcher(text -> {
            filter = text;
            renderSongList();
        }));
        root.addView(searchField, margins(match(), wrap(), 0, 0, 0, 12));

        LinearLayout actions = row();
        actions.addView(button("Find Tabs", true, v -> showWebLookup()), new LinearLayout.LayoutParams(0, wrap(), 1));

        if (hasDraft()) {
            actions.addView(button("Resume Draft", false, v -> showEditor(null)), margins(wrap(), wrap(), 10, 0, 0, 0));
        }
        root.addView(actions, margins(match(), wrap(), 0, 0, 0, 16));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        songList = list;
        list.setId(View.generateViewId());
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(match(), 0, 1));
        setContentView(root);
        renderSongList();
    }

    private void renderSongList() {
        if (root == null) {
            return;
        }
        if (songList == null) {
            return;
        }
        songList.removeAllViews();

        List<Song> filtered = filteredSongs();
        if (filtered.isEmpty()) {
            TextView empty = body("No songs yet. Add a song or use Find Tabs Online to look one up.");
            empty.setGravity(Gravity.CENTER);
            songList.addView(empty, margins(match(), wrap(), 18, 40, 18, 0));
            return;
        }

        for (Song song : filtered) {
            LinearLayout card = card();
            card.setClickable(true);
            card.setOnClickListener(v -> showSong(song));

            TextView songTitle = subtitle(song.title);
            TextView artist = muted(song.artist.isEmpty() ? "Unknown artist" : song.artist);
            TextView preview = muted(preview(song.body));

            card.addView(songTitle);
            card.addView(artist, margins(match(), wrap(), 0, 2, 0, 10));

            LinearLayout meta = row();
            meta.addView(badge(song.key.isEmpty() ? "No key set" : "Key " + song.key), margins(wrap(), wrap(), 0, 0, 8, 0));
            if (!song.sourceUrl.isEmpty()) {
                meta.addView(badge("Source"), margins(wrap(), wrap(), 0, 0, 8, 0));
            }
            if (!song.recordings.isEmpty()) {
                meta.addView(badge(song.recordings.size() + " rec"), margins(wrap(), wrap(), 0, 0, 8, 0));
            }
            card.addView(meta, margins(match(), wrap(), 0, 0, 0, 10));
            card.addView(preview, margins(match(), wrap(), 0, 0, 0, 0));
            songList.addView(card, margins(match(), wrap(), 0, 0, 0, 12));
        }
    }

    private void showSettings() {
        screen = Screen.SETTINGS;
        root = baseRoot();

        LinearLayout header = topBar("Settings", "Backup and app data", v -> showLibrary(), null, "");
        root.addView(header, margins(match(), wrap(), 0, 0, 0, 18));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();

        LinearLayout backup = panel();
        backup.addView(subtitle("Google Drive Backup"));
        backup.addView(muted("Backups include songs, source links, drafts, and voice recordings."), margins(match(), wrap(), 0, 4, 0, 14));
        backup.addView(button("Back Up to Google Drive", true, v -> chooseBackupDestination()), margins(match(), wrap(), 0, 0, 0, 10));
        backup.addView(button("Restore Backup", false, v -> chooseBackupToRestore()));
        content.addView(backup, margins(match(), wrap(), 0, 0, 0, 12));

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(match(), 0, 1));
        setContentView(root);
    }

    private List<Song> filteredSongs() {
        String needle = filter.trim().toLowerCase(Locale.US);
        List<Song> result = new ArrayList<>();
        for (Song song : songs) {
            String haystack = (song.title + " " + song.artist + " " + song.key + " " + song.body + " " + song.sourceUrl).toLowerCase(Locale.US);
            if (needle.isEmpty() || haystack.contains(needle)) {
                result.add(song);
            }
        }
        Collections.sort(result, Comparator.comparing(song -> song.title.toLowerCase(Locale.US)));
        return result;
    }

    private void showSong(Song song) {
        screen = Screen.DETAIL;
        root = baseRoot();

        LinearLayout header = topBar(song.title, song.artist.isEmpty() ? "Unknown artist" : song.artist, v -> leaveSong(song, () -> showLibrary()), v -> leaveSong(song, () -> showEditor(song)), "Edit");
        root.addView(header, margins(match(), wrap(), 0, 0, 0, 12));

        LinearLayout meta = row();
        meta.addView(badge(song.key.isEmpty() ? "No key set" : "Key " + song.key), margins(wrap(), wrap(), 0, 0, 8, 0));
        if (!song.notes.isEmpty()) {
            meta.addView(badge(song.notes), margins(wrap(), wrap(), 0, 0, 8, 0));
        }
        if (!song.sourceUrl.isEmpty()) {
            meta.addView(badge("Source"), margins(wrap(), wrap(), 0, 0, 8, 0));
        }
        if (!song.recordings.isEmpty()) {
            meta.addView(badge(song.recordings.size() + " recordings"), margins(wrap(), wrap(), 0, 0, 8, 0));
        }
        root.addView(meta, margins(match(), wrap(), 0, 12, 0, 12));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        content.addView(recordingSection(song), margins(match(), wrap(), 0, 0, 0, 18));

        if (!song.sourceUrl.isEmpty()) {
            LinearLayout sourcePanel = card();
            sourcePanel.addView(sectionTitle("Source"));
            LinearLayout sourceActions = row();
            sourceActions.addView(button("Open Source", true, v -> leaveSong(song, () -> showWebLookup(song.sourceUrl))), new LinearLayout.LayoutParams(0, wrap(), 1));
            sourceActions.addView(button("Edit Link", false, v -> leaveSong(song, () -> showEditor(song))), margins(wrap(), wrap(), 10, 0, 0, 0));
            sourcePanel.addView(sourceActions, margins(match(), wrap(), 0, 10, 0, 10));

            TextView source = muted(song.sourceUrl);
            source.setSingleLine(false);
            sourcePanel.addView(source);
            content.addView(sourcePanel, margins(match(), wrap(), 0, 0, 0, 18));
        }
        LinearLayout lyricPanel = card();
        lyricPanel.addView(sectionTitle("Lyrics & Chords"), margins(match(), wrap(), 0, 0, 0, 10));
        TextView lyrics = body(song.body.isEmpty() ? "No lyrics or chords yet." : song.body);
        lyrics.setTypeface(Typeface.MONOSPACE);
        lyrics.setTextSize(17);
        lyrics.setLineSpacing(dp(2), 1.0f);
        lyricPanel.addView(lyrics);
        content.addView(lyricPanel);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(match(), 0, 1));
        setContentView(root);
    }

    private void showEditor(Song existing) {
        showEditor(existing, null);
    }

    private void showEditor(Song existing, String initialSourceUrl) {
        screen = Screen.EDITOR;
        Song editableSong = existing == null ? loadDraftSong() : existing;
        if (editableSong == null) {
            editableSong = new Song();
        }
        if (existing == null && initialSourceUrl != null && !initialSourceUrl.trim().isEmpty()) {
            editableSong.sourceUrl = initialSourceUrl;
        }
        final Song song = editableSong;
        root = baseRoot();

        LinearLayout header = topBar(existing == null ? "New Song" : "Edit Song", existing == null ? "Drafts save automatically" : "Update song details", v -> {
            hideKeyboard();
            if (existing == null) {
                showLibrary();
            } else {
                showSong(existing);
            }
        }, null, "");
        root.addView(header, margins(match(), wrap(), 0, 0, 0, 18));

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        LinearLayout info = card();
        info.addView(sectionTitle("Song Info"), margins(match(), wrap(), 0, 0, 0, 10));

        EditText titleInput = input("Title");
        titleInput.setText(song.title);
        info.addView(label("Title"));
        info.addView(titleInput, margins(match(), wrap(), 0, 4, 0, 12));

        EditText artistInput = input("Artist");
        artistInput.setText(song.artist);
        info.addView(label("Artist"));
        info.addView(artistInput, margins(match(), wrap(), 0, 4, 0, 12));

        EditText keyInput = input("Key, capo, or tuning");
        keyInput.setText(song.key);
        info.addView(label("Key / Capo / Tuning"));
        info.addView(keyInput, margins(match(), wrap(), 0, 4, 0, 0));
        form.addView(info, margins(match(), wrap(), 0, 0, 0, 12));

        LinearLayout lyricEditor = card();
        lyricEditor.addView(sectionTitle("Lyrics & Chords"), margins(match(), wrap(), 0, 0, 0, 10));
        EditText bodyInput = input("Paste or write lyrics with chords here");
        bodyInput.setText(song.body);
        bodyInput.setGravity(Gravity.TOP | Gravity.START);
        bodyInput.setMinLines(14);
        bodyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        bodyInput.setTypeface(Typeface.MONOSPACE);
        lyricEditor.addView(bodyInput);
        form.addView(lyricEditor, margins(match(), wrap(), 0, 0, 0, 12));

        LinearLayout extras = card();
        extras.addView(sectionTitle("Source & Notes"), margins(match(), wrap(), 0, 0, 0, 10));
        EditText notesInput = input("Notes, tempo, arrangement");
        notesInput.setText(song.notes);
        extras.addView(label("Notes"));
        extras.addView(notesInput, margins(match(), wrap(), 0, 4, 0, 12));

        EditText sourceInput = input("https://...");
        sourceInput.setText(song.sourceUrl);
        sourceInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        extras.addView(label("Source Link"));
        extras.addView(sourceInput, margins(match(), wrap(), 0, 4, 0, 0));
        form.addView(extras, margins(match(), wrap(), 0, 0, 0, 18));

        LinearLayout actions = row();
        Button save = button("Save Song", true, v -> {
            String newTitle = titleInput.getText().toString().trim();
            if (newTitle.isEmpty()) {
                Toast.makeText(this, "Add a title before saving.", Toast.LENGTH_SHORT).show();
                return;
            }
            song.title = newTitle;
            song.artist = artistInput.getText().toString().trim();
            song.key = keyInput.getText().toString().trim();
            song.body = bodyInput.getText().toString();
            song.notes = notesInput.getText().toString().trim();
            song.sourceUrl = normalizeUrl(sourceInput.getText().toString().trim());
            if (existing == null) {
                songs.add(song);
                clearDraft();
            }
            saveSongs();
            hideKeyboard();
            showSong(song);
        });
        actions.addView(save, new LinearLayout.LayoutParams(0, wrap(), 1));

        if (existing != null) {
            Button delete = dangerButton("Delete", v -> confirmDelete(existing));
            actions.addView(delete, margins(wrap(), wrap(), 10, 0, 0, 0));
        }
        form.addView(actions);

        if (existing == null) {
            TextChange draftSaver = ignored -> saveDraftFromInputs(song, titleInput, artistInput, keyInput, bodyInput, notesInput, sourceInput);
            titleInput.addTextChangedListener(simpleTextWatcher(draftSaver));
            artistInput.addTextChangedListener(simpleTextWatcher(draftSaver));
            keyInput.addTextChangedListener(simpleTextWatcher(draftSaver));
            bodyInput.addTextChangedListener(simpleTextWatcher(draftSaver));
            notesInput.addTextChangedListener(simpleTextWatcher(draftSaver));
            sourceInput.addTextChangedListener(simpleTextWatcher(draftSaver));
            saveDraftFromInputs(song, titleInput, artistInput, keyInput, bodyInput, notesInput, sourceInput);
        }

        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(match(), 0, 1));
        setContentView(root);
        titleInput.requestFocus();
    }

    private void confirmDelete(Song song) {
        new AlertDialog.Builder(this)
                .setTitle("Delete song?")
                .setMessage("This removes \"" + song.title + "\" from your songbook.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    songs.remove(song);
                    deleteRecordingFiles(song);
                    saveSongs();
                    showLibrary();
                })
                .show();
    }

    private void leaveSong(Song song, Runnable action) {
        if (recorder != null && recordingSong == song) {
            Toast.makeText(this, "Stop the recording before leaving this song.", Toast.LENGTH_SHORT).show();
            return;
        }
        action.run();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showWebLookup() {
        showWebLookup(null);
    }

    private void showWebLookup(String initialUrl) {
        screen = Screen.WEB;
        root = baseRoot();

        LinearLayout header = topBar("Find Tabs", "Search online, then save your own copy", v -> showLibrary(), null, "");
        root.addView(header, margins(match(), wrap(), 0, 0, 0, 14));

        EditText query = input("Song, artist, or tab URL");
        query.setSingleLine(true);
        root.addView(query, margins(match(), wrap(), 0, 0, 0, 8));

        LinearLayout actions = row();
        actions.addView(button("Search Tabs", true, v -> loadTabSearch(query)), new LinearLayout.LayoutParams(0, wrap(), 1));
        actions.addView(button("Open URL", false, v -> loadUrl(query)), margins(wrap(), wrap(), 10, 0, 0, 0));
        actions.addView(button("+ Current", false, v -> showEditor(null, currentWebUrl())), margins(wrap(), wrap(), 10, 0, 0, 0));
        root.addView(actions, margins(match(), wrap(), 0, 0, 0, 10));

        WebView webView = new WebView(this);
        webView.setId(View.generateViewId());
        webView.setWebViewClient(new WebViewClient());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        root.addView(webView, new LinearLayout.LayoutParams(match(), 0, 1));
        setContentView(root);

        if (initialUrl != null && !initialUrl.trim().isEmpty()) {
            String url = normalizeUrl(initialUrl);
            query.setText(url);
            webView.loadUrl(url);
        } else {
            query.setText("site:ultimate-guitar.com tabs acoustic");
            webView.loadUrl("https://www.google.com/search?q=" + Uri.encode("site:ultimate-guitar.com tabs acoustic"));
        }
    }

    private void loadTabSearch(EditText query) {
        WebView webView = findWebView();
        if (webView == null) {
            return;
        }
        String text = query.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "Enter a song or artist.", Toast.LENGTH_SHORT).show();
            return;
        }
        hideKeyboard();
        webView.loadUrl("https://www.google.com/search?q=" + Uri.encode(text + " chords tabs"));
    }

    private void loadUrl(EditText query) {
        WebView webView = findWebView();
        if (webView == null) {
            return;
        }
        String text = query.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "Enter a URL.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            text = "https://" + text;
        }
        hideKeyboard();
        webView.loadUrl(text);
    }

    private String currentWebUrl() {
        WebView webView = findWebView();
        if (webView == null || webView.getUrl() == null || webView.getUrl().trim().isEmpty()) {
            return "";
        }
        return webView.getUrl();
    }

    private WebView findWebView() {
        for (int i = 0; i < root.getChildCount(); i++) {
            View view = root.getChildAt(i);
            if (view instanceof WebView) {
                return (WebView) view;
            }
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        WebView webView = findWebView();
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (screen == Screen.LIBRARY) {
            finish();
            return;
        }
        showLibrary();
    }

    @Override
    protected void onDestroy() {
        releaseRecorder();
        releasePlayer();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingRecordingSong != null) {
                startRecording(pendingRecordingSong);
            } else {
                Toast.makeText(this, "Microphone permission is needed for voice recordings.", Toast.LENGTH_SHORT).show();
            }
            pendingRecordingSong = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == BACKUP_CREATE_REQUEST) {
            exportBackup(data.getData());
        } else if (requestCode == BACKUP_OPEN_REQUEST) {
            confirmRestoreBackup(data.getData());
        }
    }

    private void loadSongs() {
        songs.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = prefs.getString(SONGS, "");
        if (!raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    songs.add(Song.fromJson(array.getJSONObject(i)));
                }
            } catch (JSONException ignored) {
                songs.clear();
            }
        }
        if (songs.isEmpty()) {
            songs.add(Song.sample("Amazing Grace", "Traditional", "G",
                    "G          C         G\nAmazing grace, how sweet the sound\nG                         D\nThat saved a soul like me\nG          C       G\nI once was lost but now am found\nG          D       G\nWas blind, but now I see", "Slow 3/4"));
            songs.add(Song.sample("House Practice Idea", "Original", "Em",
                    "Em        C\nWrite your verse here\nG         D\nKeep chord names above each line\n\n[Chorus]\nC         G\nUse sections, repeats, and notes\nD         Em\nThen save it to the book", "Draft"));
            saveSongs();
        }
    }

    private void saveSongs() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(SONGS, songsJson())
                .apply();
    }

    private String songsJson() {
        JSONArray array = new JSONArray();
        for (Song song : songs) {
            array.put(song.toJson());
        }
        return array.toString();
    }

    private void chooseBackupDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "songbook-backup.zip");
        startActivityForResult(intent, BACKUP_CREATE_REQUEST);
    }

    private void chooseBackupToRestore() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(intent, BACKUP_OPEN_REQUEST);
    }

    private void exportBackup(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("songs.json"));
            zip.write(songsJson().getBytes("UTF-8"));
            zip.closeEntry();

            String draft = getSharedPreferences(PREFS, MODE_PRIVATE).getString(DRAFT_SONG, "");
            if (!draft.isEmpty()) {
                zip.putNextEntry(new ZipEntry("draft.json"));
                zip.write(draft.getBytes("UTF-8"));
                zip.closeEntry();
            }

            for (Song song : songs) {
                for (Recording recording : song.recordings) {
                    File file = new File(recording.path);
                    if (file.exists()) {
                        zip.putNextEntry(new ZipEntry("recordings/" + file.getName()));
                        copyFileToZip(file, zip);
                        zip.closeEntry();
                    }
                }
            }
            Toast.makeText(this, "Backup saved.", Toast.LENGTH_SHORT).show();
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, "Could not save backup.", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmRestoreBackup(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("Restore backup?")
                .setMessage("This replaces the songs currently on this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (dialog, which) -> restoreBackup(uri))
                .show();
    }

    private void restoreBackup(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(input)) {
            String songsRaw = "";
            String draftRaw = "";
            Map<String, byte[]> recordingBytes = new HashMap<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] bytes = readZipEntry(zip);
                if ("songs.json".equals(entry.getName())) {
                    songsRaw = new String(bytes, "UTF-8");
                } else if ("draft.json".equals(entry.getName())) {
                    draftRaw = new String(bytes, "UTF-8");
                } else if (entry.getName().startsWith("recordings/")) {
                    recordingBytes.put(new File(entry.getName()).getName(), bytes);
                }
            }

            if (songsRaw.isEmpty()) {
                Toast.makeText(this, "Backup is missing song data.", Toast.LENGTH_SHORT).show();
                return;
            }

            List<Song> restored = parseSongs(songsRaw);
            restoreRecordingFiles(restored, recordingBytes);
            songs.clear();
            songs.addAll(restored);
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(SONGS, songsJson())
                    .putString(DRAFT_SONG, draftRaw)
                    .apply();
            Toast.makeText(this, "Backup restored.", Toast.LENGTH_SHORT).show();
            showLibrary();
        } catch (IOException | JSONException | RuntimeException e) {
            Toast.makeText(this, "Could not restore backup.", Toast.LENGTH_SHORT).show();
        }
    }

    private List<Song> parseSongs(String raw) throws JSONException {
        List<Song> parsed = new ArrayList<>();
        JSONArray array = new JSONArray(raw);
        for (int i = 0; i < array.length(); i++) {
            parsed.add(Song.fromJson(array.getJSONObject(i)));
        }
        return parsed;
    }

    private void restoreRecordingFiles(List<Song> restoredSongs, Map<String, byte[]> recordingBytes) throws IOException {
        File dir = new File(getFilesDir(), "recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create recordings folder");
        }
        for (Song song : restoredSongs) {
            List<Recording> missing = new ArrayList<>();
            for (Recording recording : song.recordings) {
                String originalName = new File(recording.path).getName();
                byte[] bytes = recordingBytes.get(originalName);
                if (bytes == null) {
                    missing.add(recording);
                    continue;
                }
                File output = new File(dir, UUID.randomUUID().toString() + "-" + originalName);
                try (FileOutputStream fileOutput = new FileOutputStream(output)) {
                    fileOutput.write(bytes);
                }
                recording.path = output.getAbsolutePath();
            }
            song.recordings.removeAll(missing);
        }
    }

    private void copyFileToZip(File file, ZipOutputStream zip) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                zip.write(buffer, 0, read);
            }
        }
    }

    private byte[] readZipEntry(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private LinearLayout recordingSection(Song song) {
        LinearLayout section = card();
        section.addView(sectionTitle("Voice Recordings"), margins(match(), wrap(), 0, 0, 0, 10));

        LinearLayout controls = row();
        boolean recordingThisSong = recorder != null && recordingSong == song;
        controls.addView(button(recordingThisSong ? "Stop Recording" : "Record Voice", true, v -> {
            if (recorder != null && recordingSong == song) {
                stopRecording();
            } else {
                requestOrStartRecording(song);
            }
        }), new LinearLayout.LayoutParams(0, wrap(), 1));
        if (!song.recordings.isEmpty()) {
            controls.addView(button("Play Latest", false, v -> playRecording(song.recordings.get(song.recordings.size() - 1))), margins(wrap(), wrap(), 10, 0, 0, 0));
        }
        section.addView(controls, margins(match(), wrap(), 0, 0, 0, 10));

        if (song.recordings.isEmpty()) {
            section.addView(muted("No recordings yet."));
            return section;
        }

        for (int i = song.recordings.size() - 1; i >= 0; i--) {
            Recording recording = song.recordings.get(i);
            LinearLayout row = panel();
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout titleBlock = column();
            titleBlock.addView(body(recordingDisplayName(recording, i)));
            titleBlock.addView(muted(formatTime(recording.createdAt)));
            row.addView(titleBlock, new LinearLayout.LayoutParams(0, wrap(), 1));
            row.addView(button(isPlaying(recording) ? "Stop" : "Play", false, v -> {
                if (isPlaying(recording)) {
                    releasePlayer();
                    showSong(song);
                } else {
                    playRecording(recording);
                }
            }), margins(wrap(), wrap(), 8, 0, 0, 0));
            row.addView(button("Rename", false, v -> promptRenameRecording(song, recording)), margins(wrap(), wrap(), 8, 0, 0, 0));
            row.addView(dangerButton("Delete", v -> confirmDeleteRecording(song, recording)), margins(wrap(), wrap(), 8, 0, 0, 0));
            section.addView(row, margins(match(), wrap(), 0, 0, 0, 8));
        }
        return section;
    }

    private void requestOrStartRecording(Song song) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording(song);
            return;
        }
        pendingRecordingSong = song;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST);
    }

    private void startRecording(Song song) {
        if (recorder != null) {
            Toast.makeText(this, "Stop the current recording first.", Toast.LENGTH_SHORT).show();
            return;
        }
        releasePlayer();
        File dir = new File(getFilesDir(), "recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, "Could not create recordings folder.", Toast.LENGTH_SHORT).show();
            return;
        }
        File out = new File(dir, song.id + "-" + System.currentTimeMillis() + ".m4a");
        MediaRecorder nextRecorder = new MediaRecorder();
        try {
            nextRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            nextRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            nextRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            nextRecorder.setAudioEncodingBitRate(128000);
            nextRecorder.setAudioSamplingRate(44100);
            nextRecorder.setOutputFile(out.getAbsolutePath());
            nextRecorder.prepare();
            nextRecorder.start();
            recorder = nextRecorder;
            recordingSong = song;
            recordingPath = out.getAbsolutePath();
            Toast.makeText(this, "Recording started.", Toast.LENGTH_SHORT).show();
            showSong(song);
        } catch (IOException | RuntimeException e) {
            nextRecorder.release();
            recordingPath = "";
            recordingSong = null;
            Toast.makeText(this, "Could not start recording.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        if (recorder == null || recordingSong == null) {
            return;
        }
        Song song = recordingSong;
        String path = recordingPath;
        try {
            recorder.stop();
            Recording recording = new Recording(path, System.currentTimeMillis(), "");
            song.recordings.add(recording);
            promptNameNewRecording(song, recording);
        } catch (RuntimeException e) {
            new File(path).delete();
            Toast.makeText(this, "Recording was too short to save.", Toast.LENGTH_SHORT).show();
            showSong(song);
        } finally {
            releaseRecorder();
        }
    }

    private void promptNameNewRecording(Song song, Recording recording) {
        EditText input = input("Recording name");
        input.setSingleLine(true);
        input.setText(defaultRecordingName(song, recording));
        input.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Name recording")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    recording.name = cleanRecordingName(input.getText().toString(), song, recording);
                    saveSongs();
                    Toast.makeText(this, "Recording saved.", Toast.LENGTH_SHORT).show();
                    showSong(song);
                })
                .setOnCancelListener(dialog -> {
                    recording.name = defaultRecordingName(song, recording);
                    saveSongs();
                    Toast.makeText(this, "Recording saved.", Toast.LENGTH_SHORT).show();
                    showSong(song);
                })
                .show();
    }

    private void promptRenameRecording(Song song, Recording recording) {
        EditText input = input("Recording name");
        input.setSingleLine(true);
        input.setText(recordingDisplayName(recording, song.recordings.indexOf(recording)));
        input.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Rename recording")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    recording.name = cleanRecordingName(input.getText().toString(), song, recording);
                    saveSongs();
                    showSong(song);
                })
                .show();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            recorder.release();
        }
        recorder = null;
        recordingSong = null;
        recordingPath = "";
    }

    private void playRecording(Recording recording) {
        File file = new File(recording.path);
        if (!file.exists()) {
            Toast.makeText(this, "Recording file is missing.", Toast.LENGTH_SHORT).show();
            return;
        }
        releasePlayer();
        MediaPlayer nextPlayer = new MediaPlayer();
        try {
            nextPlayer.setDataSource(recording.path);
            nextPlayer.setOnCompletionListener(mp -> {
                releasePlayer();
            });
            nextPlayer.prepare();
            nextPlayer.start();
            player = nextPlayer;
            playingPath = recording.path;
            Toast.makeText(this, "Playing recording.", Toast.LENGTH_SHORT).show();
        } catch (IOException | RuntimeException e) {
            nextPlayer.release();
            playingPath = "";
            Toast.makeText(this, "Could not play recording.", Toast.LENGTH_SHORT).show();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
        }
        player = null;
        playingPath = "";
    }

    private boolean isPlaying(Recording recording) {
        return player != null && player.isPlaying() && playingPath.equals(recording.path);
    }

    private void confirmDeleteRecording(Song song, Recording recording) {
        new AlertDialog.Builder(this)
                .setTitle("Delete recording?")
                .setMessage("This removes the voice recording from this song.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    releasePlayer();
                    song.recordings.remove(recording);
                    new File(recording.path).delete();
                    saveSongs();
                    showSong(song);
                })
                .show();
    }

    private void deleteRecordingFiles(Song song) {
        for (Recording recording : song.recordings) {
            new File(recording.path).delete();
        }
    }

    private String formatTime(long value) {
        return new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(new Date(value));
    }

    private String recordingDisplayName(Recording recording, int index) {
        if (!recording.name.trim().isEmpty()) {
            return recording.name;
        }
        if (index >= 0) {
            return "Recording " + (index + 1);
        }
        return "Recording";
    }

    private String defaultRecordingName(Song song, Recording recording) {
        int index = song.recordings.indexOf(recording);
        return recordingDisplayName(recording, index);
    }

    private String cleanRecordingName(String value, Song song, Recording recording) {
        String clean = value.trim();
        if (clean.isEmpty()) {
            return defaultRecordingName(song, recording);
        }
        return clean;
    }

    private boolean hasDraft() {
        return loadDraftSong() != null;
    }

    private Song loadDraftSong() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(DRAFT_SONG, "");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            Song song = Song.fromJson(new JSONObject(raw));
            return hasDraftContent(song) ? song : null;
        } catch (JSONException e) {
            clearDraft();
            return null;
        }
    }

    private void saveDraftFromInputs(Song song, EditText titleInput, EditText artistInput, EditText keyInput, EditText bodyInput, EditText notesInput, EditText sourceInput) {
        song.title = titleInput.getText().toString().trim();
        song.artist = artistInput.getText().toString().trim();
        song.key = keyInput.getText().toString().trim();
        song.body = bodyInput.getText().toString();
        song.notes = notesInput.getText().toString().trim();
        song.sourceUrl = normalizeUrl(sourceInput.getText().toString().trim());
        if (hasDraftContent(song)) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(DRAFT_SONG, song.toJson().toString())
                    .apply();
        } else {
            clearDraft();
        }
    }

    private boolean hasDraftContent(Song song) {
        return !song.title.isEmpty()
                || !song.artist.isEmpty()
                || !song.key.isEmpty()
                || !song.body.trim().isEmpty()
                || !song.notes.isEmpty()
                || !song.sourceUrl.isEmpty();
    }

    private void clearDraft() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .remove(DRAFT_SONG)
                .apply();
    }

    private LinearLayout baseRoot() {
        LinearLayout view = column();
        applySafePadding(view, 0);
        view.setBackgroundColor(getColor(R.color.paper));
        view.setFitsSystemWindows(false);
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottomInset = Math.max(
                        insets.getInsets(WindowInsets.Type.systemBars()).bottom,
                        insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            applySafePadding((LinearLayout) target, bottomInset);
            return insets;
        });
        view.requestApplyInsets();
        return view;
    }

    private void applySafePadding(LinearLayout view, int bottomInset) {
        view.setPadding(dp(18), dp(16), dp(18), dp(28) + bottomInset);
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.ink));
        view.setTextSize(30);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView subtitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.ink));
        view.setTextSize(20);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.ink));
        view.setTextSize(16);
        return view;
    }

    private TextView muted(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.muted));
        view.setTextSize(14);
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.accent_dark));
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView badge(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.accent_dark));
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setBackgroundResource(R.drawable.bg_badge);
        return view;
    }

    private EditText input(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setTextColor(getColor(R.color.ink));
        view.setHintTextColor(getColor(R.color.muted));
        view.setTextSize(16);
        view.setBackgroundResource(R.drawable.bg_input);
        view.setMinHeight(dp(48));
        return view;
    }

    private Button button(String text, boolean primary, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(primary ? getColor(android.R.color.white) : getColor(R.color.ink));
        view.setBackgroundResource(primary ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        view.setMinHeight(dp(44));
        view.setOnClickListener(listener);
        return view;
    }

    private Button dangerButton(String text, View.OnClickListener listener) {
        Button view = button(text, false, listener);
        view.setTextColor(getColor(R.color.danger));
        view.setBackgroundResource(R.drawable.bg_button_danger);
        return view;
    }

    private LinearLayout topBar(String screenTitle, String subtitle, View.OnClickListener back, View.OnClickListener primaryAction, String primaryLabel) {
        LinearLayout header = row();
        if (back != null) {
            header.addView(button("<", false, back), margins(wrap(), wrap(), 0, 0, 10, 0));
        }
        LinearLayout titleBlock = column();
        titleBlock.addView(title(screenTitle));
        if (subtitle != null && !subtitle.isEmpty()) {
            titleBlock.addView(muted(subtitle));
        }
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, wrap(), 1));
        if (primaryAction != null) {
            header.addView(button(primaryLabel, true, primaryAction));
        }
        return header;
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setBackgroundResource(R.drawable.bg_card);
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        return view;
    }

    private LinearLayout panel() {
        LinearLayout view = column();
        view.setBackgroundResource(R.drawable.bg_panel);
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView view = label(text);
        view.setTextSize(13);
        return view;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private String preview(String text) {
        String clean = text.replace("\n", " ").trim();
        if (clean.length() > 96) {
            return clean.substring(0, 96) + "...";
        }
        return clean.isEmpty() ? "No lyrics yet." : clean;
    }

    private String normalizeUrl(String text) {
        if (text.isEmpty()) {
            return "";
        }
        if (text.startsWith("http://") || text.startsWith("https://")) {
            return text;
        }
        return "https://" + text;
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    private SimpleTextWatcher simpleTextWatcher(TextChange change) {
        return new SimpleTextWatcher(change);
    }

    private interface TextChange {
        void onTextChanged(String text);
    }

    private enum Screen {
        LIBRARY,
        DETAIL,
        EDITOR,
        WEB,
        SETTINGS
    }

    private static class SimpleTextWatcher implements android.text.TextWatcher {
        private final TextChange change;

        SimpleTextWatcher(TextChange change) {
            this.change = change;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            change.onTextChanged(s.toString());
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
        }
    }

    private static class Song {
        String id = UUID.randomUUID().toString();
        String title = "";
        String artist = "";
        String key = "";
        String body = "";
        String notes = "";
        String sourceUrl = "";
        final List<Recording> recordings = new ArrayList<>();

        static Song sample(String title, String artist, String key, String body, String notes) {
            Song song = new Song();
            song.title = title;
            song.artist = artist;
            song.key = key;
            song.body = body;
            song.notes = notes;
            return song;
        }

        static Song fromJson(JSONObject object) {
            Song song = new Song();
            song.id = object.optString("id", UUID.randomUUID().toString());
            song.title = object.optString("title");
            song.artist = object.optString("artist");
            song.key = object.optString("key");
            song.body = object.optString("body");
            song.notes = object.optString("notes");
            song.sourceUrl = object.optString("sourceUrl");
            JSONArray recordings = object.optJSONArray("recordings");
            if (recordings != null) {
                for (int i = 0; i < recordings.length(); i++) {
                    JSONObject recording = recordings.optJSONObject(i);
                    if (recording != null) {
                        song.recordings.add(Recording.fromJson(recording));
                    }
                }
            }
            return song;
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id);
                object.put("title", title);
                object.put("artist", artist);
                object.put("key", key);
                object.put("body", body);
                object.put("notes", notes);
                object.put("sourceUrl", sourceUrl);
                JSONArray recordingArray = new JSONArray();
                for (Recording recording : recordings) {
                    recordingArray.put(recording.toJson());
                }
                object.put("recordings", recordingArray);
            } catch (JSONException ignored) {
            }
            return object;
        }
    }

    private static class Recording {
        String path;
        final long createdAt;
        String name;

        Recording(String path, long createdAt, String name) {
            this.path = path;
            this.createdAt = createdAt;
            this.name = name == null ? "" : name;
        }

        static Recording fromJson(JSONObject object) {
            return new Recording(object.optString("path"), object.optLong("createdAt"), object.optString("name"));
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("path", path);
                object.put("createdAt", createdAt);
                object.put("name", name);
            } catch (JSONException ignored) {
            }
            return object;
        }
    }
}
