package com.example.songbook;

import com.example.songbook.data.local.SongJsonMapper;
import com.example.songbook.data.local.LocalSongRepository;
import com.example.songbook.domain.model.ChordLine;
import com.example.songbook.domain.model.Recording;
import com.example.songbook.domain.model.Song;
import com.example.songbook.domain.model.SongVersion;
import com.example.songbook.domain.model.WordToken;
import com.example.songbook.domain.usecase.SongVersionManager;

import android.Manifest;
import android.accounts.Account;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.KeyListener;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.view.Window;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

public class MainActivity extends Activity {
    private static final int MIC_PERMISSION_REQUEST = 42;
    private static final int BACKUP_CREATE_REQUEST = 84;
    private static final int BACKUP_OPEN_REQUEST = 85;
    private static final int GOOGLE_AUTH_REQUEST = 86;
    private static final long DOC_AUTO_SYNC_MS = 60000;
    private static final String GOOGLE_API_SCOPES = "oauth2:https://www.googleapis.com/auth/documents https://www.googleapis.com/auth/drive.file";

    private final List<Song> songs = new ArrayList<>();
    private final SongVersionManager versionManager = new SongVersionManager();
    private LocalSongRepository songRepository;
    private RemoteConfigManager remoteConfigManager;
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
    private FirebaseAuth auth;
    private CredentialManager credentialManager;
    private ExecutorService credentialExecutor;
    private Song pendingCollaborationSong;
    private String pendingShareEmail = "";
    private CollaborationAction pendingCollaborationAction;
    private Runnable pendingAfterSignIn;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private Song autoSyncSong;
    private final Runnable autoSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (screen == Screen.DETAIL && autoSyncSong != null && !autoSyncSong.googleDocId.isEmpty()) {
                syncGoogleDoc(autoSyncSong, false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enableEdgeToEdge();
        songRepository = new LocalSongRepository(this);
        auth = FirebaseAuth.getInstance();
        credentialManager = CredentialManager.create(this);
        credentialExecutor = Executors.newSingleThreadExecutor();
        remoteConfigManager = RemoteConfigManager.getInstance();
        remoteConfigManager.fetchAndActivate(() -> {
            loadSongs();
            showLibrary();
        });
    }

    private void showLibrary() {
        stopAutoSync();
        screen = Screen.LIBRARY;
        root = baseRoot();

        LinearLayout header = topBar("Songbook", librarySubtitle(), null, v -> showEditor(null), "+");
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
        stopAutoSync();
        screen = Screen.SETTINGS;
        root = baseRoot();

        LinearLayout header = topBar("Settings", "Backup and app data", v -> showLibrary(), null, "");
        root.addView(header, margins(match(), wrap(), 0, 0, 0, 18));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();

        LinearLayout account = panel();
        account.addView(subtitle("Google Account"));
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            account.addView(muted("Optional. You only need this for Google Docs collaboration."), margins(match(), wrap(), 0, 4, 0, 14));
            account.addView(button("Sign in with Google", true, v -> startGoogleSignIn(null)));
        } else {
            account.addView(body(displayName(user)), margins(match(), wrap(), 0, 4, 0, 2));
            account.addView(muted(user.getEmail() == null ? "Signed in with Google" : user.getEmail()), margins(match(), wrap(), 0, 0, 0, 14));
            account.addView(button("Sign Out", false, v -> signOut()));
        }
        content.addView(account, margins(match(), wrap(), 0, 0, 0, 12));

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

    private void startGoogleSignIn(Runnable afterSignIn) {
        pendingAfterSignIn = afterSignIn;
        beginGoogleSignIn(true);
    }

    private void beginGoogleSignIn(boolean filterByAuthorizedAccounts) {
        GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
                .setServerClientId(getString(R.string.default_web_client_id))
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build();

        credentialManager.getCredentialAsync(
                this,
                request,
                new CancellationSignal(),
                credentialExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        handleSignInCredential(result.getCredential());
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (filterByAuthorizedAccounts) {
                            beginGoogleSignIn(false);
                            return;
                        }
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Google sign-in was cancelled.", Toast.LENGTH_SHORT).show());
                    }
                });
    }

    private void handleSignInCredential(Credential credential) {
        if (credential instanceof CustomCredential
                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            try {
                GoogleIdTokenCredential googleCredential = GoogleIdTokenCredential.createFrom(((CustomCredential) credential).getData());
                firebaseAuthWithGoogle(googleCredential.getIdToken());
            } catch (RuntimeException e) {
                runOnUiThread(() -> Toast.makeText(this, "Could not read Google sign-in token.", Toast.LENGTH_SHORT).show());
            }
            return;
        }
        runOnUiThread(() -> Toast.makeText(this, "Choose a Google account to sign in.", Toast.LENGTH_SHORT).show());
    }

    private void firebaseAuthWithGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        auth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = auth.getCurrentUser();
                        Toast.makeText(this, "Signed in as " + displayName(user), Toast.LENGTH_SHORT).show();
                        Runnable next = pendingAfterSignIn;
                        pendingAfterSignIn = null;
                        if (next != null) {
                            next.run();
                        } else {
                            showSettings();
                        }
                    } else {
                        Toast.makeText(this, "Firebase sign-in failed.", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void signOut() {
        auth.signOut();
        ClearCredentialStateRequest request = new ClearCredentialStateRequest();
        credentialManager.clearCredentialStateAsync(
                request,
                new CancellationSignal(),
                credentialExecutor,
                new CredentialManagerCallback<Void, ClearCredentialException>() {
                    @Override
                    public void onResult(Void result) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Signed out.", Toast.LENGTH_SHORT).show();
                            showSettings();
                        });
                    }

                    @Override
                    public void onError(ClearCredentialException e) {
                        runOnUiThread(() -> showSettings());
                    }
                });
    }

    private boolean ensureSignedInForCollaboration(String action, Runnable afterSignIn) {
        FirebaseUser user = auth.getCurrentUser();
        if (user != null && user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
            return true;
        }
        promptSignInForCollaboration(action, afterSignIn);
        return false;
    }

    private void promptSignInForCollaboration(String action, Runnable afterSignIn) {
        new AlertDialog.Builder(this)
                .setTitle("Sign in to collaborate")
                .setMessage("Google sign-in is needed to " + action + ". You can keep using Songbook locally without signing in.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Sign in with Google", (dialog, which) -> startGoogleSignIn(afterSignIn))
                .show();
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
        startAutoSync(song);
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
        if (isGoogleDocManaged(song)) {
            meta.addView(badge(syncStatus(song)), margins(wrap(), wrap(), 0, 0, 8, 0));
        }
        root.addView(meta, margins(match(), wrap(), 0, 12, 0, 12));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        content.addView(versionSection(song), margins(match(), wrap(), 0, 0, 0, 18));
        content.addView(collaborationSection(song), margins(match(), wrap(), 0, 0, 0, 18));

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
        content.addView(lyricPanel, margins(match(), wrap(), 0, 0, 0, 18));
        content.addView(recordingSection(song));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(match(), 0, 1));
        setContentView(root);
    }

    private void showEditor(Song existing) {
        showEditor(existing, null);
    }

    private void showEditor(Song existing, String initialSourceUrl) {
        stopAutoSync();
        screen = Screen.EDITOR;
        Song editableSong = existing == null ? loadDraftSong() : existing;
        if (editableSong == null) {
            editableSong = new Song();
        }
        if (existing == null && initialSourceUrl != null && !initialSourceUrl.trim().isEmpty()) {
            editableSong.sourceUrl = initialSourceUrl;
        }
        if (existing != null) {
            ensureDefaultVersion(editableSong);
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
        boolean googleDocManaged = existing != null && isGoogleDocManaged(song);
        EditText bodyInput = input("Add or edit lyrics here, then use Add Chords");
        bodyInput.setText(song.body);
        bodyInput.setGravity(Gravity.TOP | Gravity.START);
        bodyInput.setMinLines(14);
        bodyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        bodyInput.setTypeface(Typeface.MONOSPACE);
        if (googleDocManaged) {
            lyricEditor.addView(muted("This song has a Google Doc. Local lyric edits are kept in Songbook and will not overwrite the doc automatically."), margins(match(), wrap(), 0, 0, 0, 10));
            lyricEditor.addView(button("Open Live Doc", true, v -> openGoogleDoc(song)), margins(match(), wrap(), 0, 0, 0, 10));
        }
        lyricEditor.addView(bodyInput);
        LinearLayout chordMode = column();
        chordMode.setVisibility(View.GONE);
        final Button[] chordModeButton = new Button[1];
        chordModeButton[0] = button("Add Chords", false, v -> {
            song.title = titleInput.getText().toString().trim();
            song.artist = artistInput.getText().toString().trim();
            song.key = keyInput.getText().toString().trim();
            song.body = bodyInput.getText().toString();
            clearChordAnchorsIfBodyChanged(song);
            showChordBuilder(song, bodyInput, chordMode, chordModeButton[0]);
        });
        lyricEditor.addView(chordModeButton[0], margins(match(), wrap(), 0, 10, 0, 0));
        lyricEditor.addView(chordMode, margins(match(), wrap(), 0, 10, 0, 0));
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
            applyEditorInputs(song, titleInput, artistInput, keyInput, bodyInput, notesInput, sourceInput, true);
            if (existing == null) {
                songs.add(song);
                clearDraft();
            }
            saveActiveVersion(song);
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

    private void applyEditorInputs(Song song, EditText titleInput, EditText artistInput, EditText keyInput, EditText bodyInput, EditText notesInput, EditText sourceInput, boolean includeBody) {
        song.title = titleInput.getText().toString().trim();
        song.artist = artistInput.getText().toString().trim();
        song.key = keyInput.getText().toString().trim();
        if (includeBody) {
            song.body = bodyInput.getText().toString();
            clearChordAnchorsIfBodyChanged(song);
        }
        song.notes = notesInput.getText().toString().trim();
        song.sourceUrl = normalizeUrl(sourceInput.getText().toString().trim());
    }

    private void showChordBuilder(Song song, EditText bodyInput, LinearLayout chordMode, Button chordModeButton) {
        ArrayList<ChordLine> lines = loadChordLines(song);
        if (lines.isEmpty()) {
            Toast.makeText(this, "Add lyrics before adding chords.", Toast.LENGTH_SHORT).show();
            return;
        }

        final KeyListener originalKeyListener = bodyInput.getKeyListener();
        final int originalInputType = bodyInput.getInputType();
        final boolean originalFocusable = bodyInput.isFocusable();
        final boolean originalFocusableInTouchMode = bodyInput.isFocusableInTouchMode();
        final boolean originalCursorVisible = bodyInput.isCursorVisible();
        final MovementMethod originalMovementMethod = bodyInput.getMovementMethod();

        hideKeyboard();
        bodyInput.setKeyListener(null);
        bodyInput.setCursorVisible(false);
        bodyInput.setFocusable(false);
        bodyInput.setFocusableInTouchMode(false);
        bodyInput.setTextIsSelectable(false);
        bodyInput.setLinksClickable(true);
        bodyInput.setMovementMethod(LinkMovementMethod.getInstance());
        bodyInput.setHighlightColor(0x00000000);
        chordModeButton.setText("Chord Mode Active");
        chordModeButton.setEnabled(false);
        chordMode.setVisibility(View.VISIBLE);
        chordMode.removeAllViews();

        chordMode.addView(muted("Tap a word in the lyrics box to add, change, or remove its chord."), margins(match(), wrap(), 0, 0, 0, 10));
        chordMode.addView(button("Done", true, v -> {
            applyChordLines(song, bodyInput, lines);
            bodyInput.setKeyListener(originalKeyListener);
            bodyInput.setInputType(originalInputType);
            bodyInput.setFocusable(originalFocusable);
            bodyInput.setFocusableInTouchMode(originalFocusableInTouchMode);
            bodyInput.setCursorVisible(originalCursorVisible);
            bodyInput.setMovementMethod(originalMovementMethod);
            chordModeButton.setText("Add Chords");
            chordModeButton.setEnabled(true);
            chordMode.setVisibility(View.GONE);
            chordMode.removeAllViews();
        }), margins(match(), wrap(), 0, 0, 0, 0));

        renderClickableChordEditor(song, bodyInput, lines);
    }

    private void renderClickableChordEditor(Song song, EditText bodyInput, ArrayList<ChordLine> lines) {
        SpannableString text = new SpannableString(renderChordLines(lines));
        int offset = 0;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            ChordLine line = lines.get(lineIndex);
            if (lineIndex > 0) {
                offset++;
            }
            int lyricOffset = offset;
            if (!line.chords.isEmpty()) {
                lyricOffset += renderedChordOnlyLine(line).length() + 1;
            }
            for (WordToken word : wordTokens(line.text)) {
                int start = lyricOffset + word.start;
                int end = Math.min(start + word.text.length(), text.length());
                if (start >= 0 && start < end) {
                    final int selectedLineIndex = lineIndex;
                    final int selectedWordStart = word.start;
                    text.setSpan(new ClickableSpan() {
                        @Override
                        public void onClick(View widget) {
                            showChordPicker(song, bodyInput, lines, selectedLineIndex, selectedWordStart,
                                    () -> renderClickableChordEditor(song, bodyInput, lines));
                        }

                        @Override
                        public void updateDrawState(TextPaint ds) {
                            super.updateDrawState(ds);
                            ds.setUnderlineText(false);
                            ds.setFakeBoldText(true);
                        }
                    }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            offset += renderChordLine(line).length();
        }
        bodyInput.setText(text, TextView.BufferType.SPANNABLE);
    }

    private void showChordPicker(Song song, EditText bodyInput, ArrayList<ChordLine> lines, int lineIndex, int wordStart, Runnable afterApply) {
        String[] chords = {"C", "D", "E", "F", "G", "A", "B", "Am", "Bm", "Cm", "Dm", "Em", "Fm", "Gm", "A7", "B7", "C7", "D7", "E7", "F7", "G7", "Custom", "Remove"};
        LinearLayout grid = column();
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout row = row();
        int count = 0;
        for (String chord : chords) {
            Button chordButton = button(chord, !"Remove".equals(chord), v -> {
                String choice = ((Button) v).getText().toString();
                if ("Custom".equals(choice)) {
                    holder[0].dismiss();
                    promptCustomChord(song, bodyInput, lines, lineIndex, wordStart, afterApply);
                    return;
                }
                ChordLine line = lines.get(lineIndex);
                if ("Remove".equals(choice)) {
                    line.chords.remove(wordStart);
                } else {
                    line.chords.put(wordStart, choice);
                }
                applyChordLines(song, bodyInput, lines);
                afterApply.run();
                holder[0].dismiss();
            });
            row.addView(chordButton, new LinearLayout.LayoutParams(0, wrap(), 1));
            count++;
            if (count == 3) {
                grid.addView(row, margins(match(), wrap(), 0, 0, 0, 8));
                row = row();
                count = 0;
            } else {
                TextView spacer = new TextView(this);
                row.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));
            }
        }
        if (count > 0) {
            grid.addView(row, margins(match(), wrap(), 0, 0, 0, 8));
        }
        holder[0] = new AlertDialog.Builder(this)
                .setTitle("Choose chord")
                .setView(grid)
                .create();
        holder[0].show();
    }

    private void promptCustomChord(Song song, EditText bodyInput, ArrayList<ChordLine> lines, int lineIndex, int wordStart, Runnable afterApply) {
        EditText input = input("Chord, e.g. Cmaj7");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Custom chord")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    String chord = input.getText().toString().trim();
                    if (!chord.isEmpty()) {
                        lines.get(lineIndex).chords.put(wordStart, chord);
                        applyChordLines(song, bodyInput, lines);
                        afterApply.run();
                    }
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
        stopAutoSync();
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
        stopAutoSync();
        releaseRecorder();
        releasePlayer();
        if (credentialExecutor != null) {
            credentialExecutor.shutdownNow();
        }
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
            if (requestCode == GOOGLE_AUTH_REQUEST && resultCode == RESULT_OK) {
                retryPendingCollaborationAction();
            }
            return;
        }
        if (requestCode == BACKUP_CREATE_REQUEST) {
            exportBackup(data.getData());
        } else if (requestCode == BACKUP_OPEN_REQUEST) {
            confirmRestoreBackup(data.getData());
        } else if (requestCode == GOOGLE_AUTH_REQUEST) {
            retryPendingCollaborationAction();
        }
    }

    private LinearLayout versionSection(Song song) {
        ensureDefaultVersion(song);
        LinearLayout section = card();
        section.addView(sectionTitle("Versions"), margins(match(), wrap(), 0, 0, 0, 10));
        SongVersion active = activeVersion(song);
        section.addView(muted("Active: " + active.name + parentVersionLabel(song, active)), margins(match(), wrap(), 0, 0, 0, 10));

        section.addView(button("Branch Current", true, v -> promptCreateVersion(song)), margins(match(), wrap(), 0, 0, 0, 10));

        for (SongVersion version : song.versions) {
            LinearLayout versionRow = row();
            String label = version.id.equals(song.activeVersionId) ? version.name + " (active)" : version.name;
            versionRow.addView(body(label), new LinearLayout.LayoutParams(0, wrap(), 1));
            if (!version.id.equals(song.activeVersionId)) {
                versionRow.addView(button("Switch", false, v -> switchVersion(song, version)), margins(wrap(), wrap(), 8, 0, 0, 0));
            }
            section.addView(versionRow, margins(match(), wrap(), 0, 0, 0, 8));
        }
        section.addView(muted("Branches share this song's Google Doc. Switching versions changes which local version you view and edit."), margins(match(), wrap(), 0, 4, 0, 0));
        return section;
    }

    private void promptCreateVersion(Song song) {
        ensureDefaultVersion(song);
        EditText input = input("Version name");
        input.setSingleLine(true);
        input.setText(nextVersionName(song));
        input.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Branch from " + activeVersion(song).name)
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Add a version name.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createVersionFromCurrent(song, name);
                })
                .show();
    }

    private void createVersionFromCurrent(Song song, String name) {
        versionManager.createVersionFromCurrent(song, name);
        saveSongs();
        showSong(song);
    }

    private void switchVersion(Song song, SongVersion version) {
        versionManager.switchVersion(song, version);
        saveSongs();
        showSong(song);
    }

    private void ensureDefaultVersion(Song song) {
        versionManager.ensureDefaultVersion(song);
    }

    private SongVersion activeVersion(Song song) {
        return versionManager.activeVersion(song);
    }

    private SongVersion findVersion(Song song, String versionId) {
        return versionManager.findVersion(song, versionId);
    }

    private void saveActiveVersion(Song song) {
        versionManager.saveActiveVersion(song);
    }

    private void loadVersionIntoSong(Song song, SongVersion version) {
        versionManager.loadVersionIntoSong(song, version);
    }

    private String parentVersionLabel(Song song, SongVersion version) {
        return versionManager.parentVersionLabel(song, version);
    }

    private String nextVersionName(Song song) {
        return versionManager.nextVersionName(song);
    }

    private LinearLayout collaborationSection(Song song) {
        LinearLayout section = card();
        section.addView(sectionTitle("Google Doc Collaboration"), margins(match(), wrap(), 0, 0, 0, 10));
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            section.addView(muted("Create and share a live Google Doc when you are ready. Sign-in happens only when you use collaboration."), margins(match(), wrap(), 0, 0, 0, 10));
        } else {
            section.addView(muted("Signed in as " + displayName(user) + "."), margins(match(), wrap(), 0, 0, 0, 10));
        }

        if (song.googleDocId.isEmpty()) {
            section.addView(muted("Create a Google Doc from this song, then share it with collaborators."), margins(match(), wrap(), 0, 0, 0, 10));
            section.addView(button("Create Google Doc", true, v -> createGoogleDocForSong(song)));
            return section;
        }

        section.addView(muted(song.googleDocUrl), margins(match(), wrap(), 0, 0, 0, 8));
        section.addView(muted(syncDetail(song)), margins(match(), wrap(), 0, 0, 0, 10));
        LinearLayout actions = row();
        actions.addView(button("Open Doc", true, v -> openGoogleDoc(song)), new LinearLayout.LayoutParams(0, wrap(), 1));
        actions.addView(button("Sync Latest", false, v -> syncGoogleDoc(song, true)), margins(wrap(), wrap(), 10, 0, 0, 0));
        actions.addView(button("Share", false, v -> promptShareGoogleDoc(song)), margins(wrap(), wrap(), 10, 0, 0, 0));
        section.addView(actions);
        section.addView(muted("Lyrics are pulled from Google Docs. Songbook will not overwrite the doc automatically."), margins(match(), wrap(), 0, 10, 0, 0));
        return section;
    }

    private void createGoogleDocForSong(Song song) {
        if (!ensureSignedInForCollaboration("create a Google Doc", () -> createGoogleDocForSong(song))) {
            return;
        }
        pendingCollaborationSong = song;
        pendingCollaborationAction = CollaborationAction.CREATE_DOC;
        pendingShareEmail = "";
        runGoogleApiAction(song, "", accessToken -> {
            JSONObject created = postJson(
                    "https://docs.googleapis.com/v1/documents",
                    accessToken,
                    new JSONObject().put("title", song.title.isEmpty() ? "Untitled Song" : song.title));
            song.googleDocId = created.optString("documentId");
            song.googleDocUrl = "https://docs.google.com/document/d/" + song.googleDocId + "/edit";
            if (!song.googleDocId.isEmpty()) {
                postJson(
                        "https://docs.googleapis.com/v1/documents/" + song.googleDocId + ":batchUpdate",
                        accessToken,
                        docContentRequest(song));
                song.lastSyncedBodyHash = bodyHash(song.body);
                song.lastSyncedAt = System.currentTimeMillis();
                saveSongs();
            }
            return "Google Doc created.";
        });
    }

    private void promptShareGoogleDoc(Song song) {
        if (!ensureSignedInForCollaboration("share this Google Doc", () -> promptShareGoogleDoc(song))) {
            return;
        }
        EditText input = input("email@example.com");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        new AlertDialog.Builder(this)
                .setTitle("Share Google Doc")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Share", (dialog, which) -> {
                    String email = input.getText().toString().trim();
                    if (!isEmailLike(email)) {
                        Toast.makeText(this, "Enter a valid email address.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    shareGoogleDoc(song, email);
                })
                .show();
    }

    private void shareGoogleDoc(Song song, String email) {
        pendingCollaborationSong = song;
        pendingCollaborationAction = CollaborationAction.SHARE_DOC;
        pendingShareEmail = email;
        runGoogleApiAction(song, email, accessToken -> {
            String encodedId = Uri.encode(song.googleDocId);
            postJson(
                    "https://www.googleapis.com/drive/v3/files/" + encodedId + "/permissions?sendNotificationEmail=true",
                    accessToken,
                    new JSONObject()
                            .put("type", "user")
                            .put("role", "writer")
                            .put("emailAddress", email));
            return "Shared with " + email + ".";
        });
    }

    private void runGoogleApiAction(Song song, String email, GoogleApiTask task) {
        runGoogleApiAction(song, email, task, true, true);
    }

    private void runGoogleApiAction(Song song, String email, GoogleApiTask task, boolean showProgress, boolean refreshSong) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            promptSignInForCollaboration("continue collaboration", () -> runGoogleApiAction(song, email, task, showProgress, refreshSong));
            return;
        }
        if (showProgress) {
            Toast.makeText(this, "Connecting to Google...", Toast.LENGTH_SHORT).show();
        }
        credentialExecutor.execute(() -> {
            try {
                Account account = new Account(user.getEmail(), GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE);
                String token = GoogleAuthUtil.getToken(this, account, GOOGLE_API_SCOPES);
                String message = task.run(token);
                runOnUiThread(() -> {
            pendingCollaborationAction = null;
            pendingAfterSignIn = null;
                    if (showProgress && !message.isEmpty()) {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    }
                    if (refreshSong && screen == Screen.DETAIL) {
                        showSong(song);
                    }
                });
            } catch (UserRecoverableAuthException e) {
                runOnUiThread(() -> startActivityForResult(e.getIntent(), GOOGLE_AUTH_REQUEST));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (showProgress) {
                        Toast.makeText(this, googleApiErrorMessage(e, email), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void syncGoogleDoc(Song song, boolean showProgress) {
        if (song.googleDocId.isEmpty()) {
            return;
        }
        if (!ensureSignedInForCollaboration("sync this Google Doc", () -> syncGoogleDoc(song, showProgress))) {
            return;
        }
        if (hasLocalChangesSinceSync(song)) {
            if (showProgress) {
                confirmPullOverLocalChanges(song);
            }
            return;
        }
        pendingCollaborationSong = song;
        pendingCollaborationAction = CollaborationAction.SYNC_DOC;
        pendingShareEmail = "";
        runGoogleApiAction(song, "", accessToken -> {
            String encodedId = Uri.encode(song.googleDocId);
            JSONObject document = getJson("https://docs.googleapis.com/v1/documents/" + encodedId, accessToken);
            song.lastKnownDocRevisionId = document.optString("revisionId", song.lastKnownDocRevisionId);
            String latestText = extractDocumentText(document);
            String body = activeBodyFromDocument(song, stripGeneratedHeader(song, latestText)).trim();
            if (!body.isEmpty() && !body.equals(song.body.trim())) {
                song.body = body;
                saveActiveVersion(song);
                song.lastSyncedBodyHash = bodyHash(song.body);
                song.lastSyncedAt = System.currentTimeMillis();
                saveSongs();
                return "Synced latest Google Doc.";
            }
            song.lastSyncedBodyHash = bodyHash(song.body);
            song.lastSyncedAt = System.currentTimeMillis();
            saveSongs();
            return showProgress ? "Google Doc is already up to date." : "";
        }, showProgress, true);
    }

    private void confirmPullOverLocalChanges(Song song) {
        new AlertDialog.Builder(this)
                .setTitle("Local lyrics changed")
                .setMessage("Syncing will replace the local lyrics/chords with the latest Google Doc text. The Google Doc will not be overwritten.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace Local", (dialog, which) -> {
                    song.lastSyncedBodyHash = bodyHash(song.body);
                    syncGoogleDoc(song, true);
                })
                .show();
    }

    private void retryPendingCollaborationAction() {
        if (pendingCollaborationSong == null || pendingCollaborationAction == null) {
            return;
        }
        if (pendingCollaborationAction == CollaborationAction.CREATE_DOC) {
            createGoogleDocForSong(pendingCollaborationSong);
        } else if (pendingCollaborationAction == CollaborationAction.SHARE_DOC) {
            shareGoogleDoc(pendingCollaborationSong, pendingShareEmail);
        } else if (pendingCollaborationAction == CollaborationAction.SYNC_DOC) {
            syncGoogleDoc(pendingCollaborationSong, true);
        }
    }

    private JSONObject docContentRequest(Song song) throws JSONException {
        StringBuilder text = new StringBuilder();
        saveActiveVersion(song);
        text.append(generatedDocHeader(song)).append(versionsDocumentText(song));

        JSONArray requests = new JSONArray();
        requests.put(new JSONObject().put("insertText", new JSONObject()
                .put("location", new JSONObject().put("index", 1))
                .put("text", text.toString())));
        return new JSONObject().put("requests", requests);
    }

    private JSONObject postJson(String endpoint, String accessToken, JSONObject body) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = body.toString().getBytes("UTF-8");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = stream == null ? "" : new String(readAll(stream), "UTF-8");
        if (code < 200 || code >= 300) {
            throw new IOException("Google API error " + code + ": " + response);
        }
        return response.isEmpty() ? new JSONObject() : new JSONObject(response);
    }

    private JSONObject getJson(String endpoint, String accessToken) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = stream == null ? "" : new String(readAll(stream), "UTF-8");
        if (code < 200 || code >= 300) {
            throw new IOException("Google API error " + code + ": " + response);
        }
        return response.isEmpty() ? new JSONObject() : new JSONObject(response);
    }

    private String extractDocumentText(JSONObject document) {
        StringBuilder text = new StringBuilder();
        JSONObject body = document.optJSONObject("body");
        JSONArray content = body == null ? null : body.optJSONArray("content");
        if (content == null) {
            return "";
        }
        for (int i = 0; i < content.length(); i++) {
            JSONObject element = content.optJSONObject(i);
            JSONObject paragraph = element == null ? null : element.optJSONObject("paragraph");
            JSONArray elements = paragraph == null ? null : paragraph.optJSONArray("elements");
            if (elements == null) {
                continue;
            }
            for (int j = 0; j < elements.length(); j++) {
                JSONObject item = elements.optJSONObject(j);
                JSONObject textRun = item == null ? null : item.optJSONObject("textRun");
                if (textRun != null) {
                    text.append(textRun.optString("content"));
                }
            }
        }
        return text.toString();
    }

    private String stripGeneratedHeader(Song song, String docText) {
        String text = docText == null ? "" : docText;
        String header = generatedDocHeader(song);
        if (text.startsWith(header)) {
            return text.substring(header.length());
        }
        return text;
    }

    private String generatedDocHeader(Song song) {
        StringBuilder text = new StringBuilder();
        text.append(song.title.isEmpty() ? "Untitled Song" : song.title).append("\n");
        if (!song.artist.isEmpty()) {
            text.append(song.artist).append("\n");
        }
        if (!song.key.isEmpty()) {
            text.append("Key: ").append(song.key).append("\n");
        }
        if (!song.notes.isEmpty()) {
            text.append("Notes: ").append(song.notes).append("\n");
        }
        if (!song.sourceUrl.isEmpty()) {
            text.append("Source: ").append(song.sourceUrl).append("\n");
        }
        text.append("\n");
        return text.toString();
    }

    private String versionsDocumentText(Song song) {
        ensureDefaultVersion(song);
        StringBuilder text = new StringBuilder();
        text.append("Songbook Versions\n\n");
        for (SongVersion version : song.versions) {
            text.append("Version: ").append(version.name);
            if (version.id.equals(song.activeVersionId)) {
                text.append(" (active)");
            }
            text.append("\n");
            if (!version.parentId.isEmpty()) {
                SongVersion parent = findVersion(song, version.parentId);
                text.append("Branched from: ").append(parent == null ? "Unknown" : parent.name).append("\n");
            }
            text.append("\n").append(version.body == null ? "" : version.body.trim()).append("\n\n");
        }
        return text.toString().trim();
    }

    private String activeBodyFromDocument(Song song, String documentText) {
        String text = documentText == null ? "" : documentText.trim();
        if (!text.startsWith("Songbook Versions")) {
            return text;
        }
        SongVersion active = activeVersion(song);
        String activeHeading = "Version: " + active.name + " (active)";
        String plainHeading = "Version: " + active.name;
        int start = text.indexOf(activeHeading);
        int headingLength = activeHeading.length();
        if (start < 0) {
            start = text.indexOf(plainHeading);
            headingLength = plainHeading.length();
        }
        if (start < 0) {
            return song.body;
        }
        int bodyStart = text.indexOf("\n\n", start + headingLength);
        if (bodyStart < 0) {
            return song.body;
        }
        bodyStart += 2;
        int next = text.indexOf("\n\nVersion: ", bodyStart);
        return next < 0 ? text.substring(bodyStart).trim() : text.substring(bodyStart, next).trim();
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void openGoogleDoc(Song song) {
        if (song.googleDocUrl.isEmpty()) {
            return;
        }
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(song.googleDocUrl)));
    }

    private void startAutoSync(Song song) {
        syncHandler.removeCallbacks(autoSyncRunnable);
        autoSyncSong = song.googleDocId.isEmpty() ? null : song;
        if (autoSyncSong != null) {
            syncHandler.postDelayed(autoSyncRunnable, DOC_AUTO_SYNC_MS);
        }
    }

    private void stopAutoSync() {
        syncHandler.removeCallbacks(autoSyncRunnable);
        autoSyncSong = null;
    }

    private void loadSongs() {
        songs.clear();
        songs.addAll(songRepository.loadSongs());
        if (songs.isEmpty()) {
            songs.add(Song.sample("Amazing Grace", "Traditional", "G",
                    "G          C         G\nAmazing grace, how sweet the sound\nG                         D\nThat saved a soul like me\nG          C       G\nI once was lost but now am found\nG          D       G\nWas blind, but now I see", "Slow 3/4"));
            songs.add(Song.sample("House Practice Idea", "Original", "Em",
                    "Em        C\nWrite your verse here\nG         D\nKeep chord names above each line\n\n[Chorus]\nC         G\nUse sections, repeats, and notes\nD         Em\nThen save it to the book", "Draft"));
            saveSongs();
        }
    }

    private void saveSongs() {
        songRepository.saveSongs(songs);
    }

    private String songsJson() {
        return songRepository.songsJson(songs);
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

            Song draftSong = songRepository.loadDraftSong();
            if (draftSong != null) {
                zip.putNextEntry(new ZipEntry("draft.json"));
                zip.write(SongJsonMapper.toJson(draftSong).toString().getBytes("UTF-8"));
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
            saveSongs();
            if (draftRaw.isEmpty()) {
                songRepository.clearDraft();
            } else {
                songRepository.saveDraft(SongJsonMapper.fromJson(new JSONObject(draftRaw)));
            }
            Toast.makeText(this, "Backup restored.", Toast.LENGTH_SHORT).show();
            showLibrary();
        } catch (IOException | JSONException | RuntimeException e) {
            Toast.makeText(this, "Could not restore backup.", Toast.LENGTH_SHORT).show();
        }
    }

    private List<Song> parseSongs(String raw) throws JSONException {
        return songRepository.parseSongs(raw);
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
            LinearLayout titleBlock = column();
            titleBlock.addView(body(recordingDisplayName(recording, i)));
            titleBlock.addView(muted(formatTime(recording.createdAt)));
            row.addView(titleBlock, margins(match(), wrap(), 0, 0, 0, 10));

            LinearLayout actions = row();
            actions.addView(button(isPlaying(recording) ? "Stop" : "Play", false, v -> {
                if (isPlaying(recording)) {
                    releasePlayer();
                    showSong(song);
                } else {
                    playRecording(recording);
                }
            }), new LinearLayout.LayoutParams(0, wrap(), 1));
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, wrap(), 1);
            actionParams.setMargins(dp(8), 0, 0, 0);
            actions.addView(button("Rename", false, v -> promptRenameRecording(song, recording)), actionParams);
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, wrap(), 1);
            deleteParams.setMargins(dp(8), 0, 0, 0);
            actions.addView(dangerButton("Delete", v -> confirmDeleteRecording(song, recording)), deleteParams);
            row.addView(actions);
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

    private ArrayList<ChordLine> loadChordLines(Song song) {
        ArrayList<ChordLine> lines = chordLinesFromJson(song.chordLinesJson);
        if (!lines.isEmpty() && renderChordLines(lines).trim().equals(song.body.trim())) {
            return lines;
        }
        return chordLinesFromBody(song.body);
    }

    private void clearChordAnchorsIfBodyChanged(Song song) {
        ArrayList<ChordLine> lines = chordLinesFromJson(song.chordLinesJson);
        if (!lines.isEmpty() && !renderChordLines(lines).trim().equals(song.body.trim())) {
            song.chordLinesJson = "";
        }
    }

    private ArrayList<ChordLine> chordLinesFromBody(String body) {
        ArrayList<ChordLine> lines = new ArrayList<>();
        String[] rawLines = body.split("\\r?\\n", -1);
        int index = 0;
        while (index < rawLines.length) {
            String rawLine = rawLines[index];
            if (index + 1 < rawLines.length && isChordLine(rawLine) && !rawLines[index + 1].trim().isEmpty()) {
                ChordLine lyricLine = new ChordLine(rawLines[index + 1]);
                lyricLine.chords.putAll(parseChordAnchors(rawLine, lyricLine.text));
                lines.add(lyricLine);
                index += 2;
            } else {
                lines.add(new ChordLine(rawLine));
                index++;
            }
        }
        return lines;
    }

    private boolean isChordLine(String line) {
        List<WordToken> tokens = wordTokens(line);
        if (tokens.isEmpty()) {
            return false;
        }
        for (WordToken token : tokens) {
            if (!isChordToken(token.text)) {
                return false;
            }
        }
        return true;
    }

    private Map<Integer, String> parseChordAnchors(String chordLine, String lyricLine) {
        Map<Integer, String> chords = new HashMap<>();
        List<WordToken> lyricWords = wordTokens(lyricLine);
        if (lyricWords.isEmpty()) {
            return chords;
        }
        for (WordToken chordToken : wordTokens(chordLine)) {
            String chord = cleanChordToken(chordToken.text);
            if (chord.isEmpty() || isChordSeparator(chord) || !isChordToken(chord)) {
                continue;
            }
            chords.put(nearestWordStart(lyricWords, chordToken.start), chord);
        }
        return chords;
    }

    private int nearestWordStart(List<WordToken> words, int targetStart) {
        int bestStart = words.get(0).start;
        int bestDistance = Math.abs(bestStart - targetStart);
        for (int i = 1; i < words.size(); i++) {
            int distance = Math.abs(words.get(i).start - targetStart);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestStart = words.get(i).start;
            }
        }
        return bestStart;
    }

    private boolean isChordToken(String rawToken) {
        String token = cleanChordToken(rawToken);
        if (token.isEmpty()) {
            return false;
        }
        if (isChordSeparator(token) || "NC".equalsIgnoreCase(token) || "N.C.".equalsIgnoreCase(token)) {
            return true;
        }
        char root = token.charAt(0);
        if (root < 'A' || root > 'G') {
            return false;
        }
        for (int i = 1; i < token.length(); i++) {
            char value = token.charAt(i);
            if (Character.isDigit(value)) {
                continue;
            }
            if (value == '#' || value == 'b' || value == '/' || value == '(' || value == ')' || value == '+' || value == '-' || value == '.') {
                continue;
            }
            if (value >= 'A' && value <= 'G') {
                continue;
            }
            if (value == 'm' || value == 'a' || value == 'j' || value == 'i' || value == 'n' || value == 'o' || value == 'r'
                    || value == 'd' || value == 'u' || value == 's' || value == 'g') {
                continue;
            }
            return false;
        }
        return true;
    }

    private String cleanChordToken(String token) {
        String clean = token == null ? "" : token.trim();
        while (clean.startsWith("[") || clean.startsWith("(")) {
            clean = clean.substring(1).trim();
        }
        while (clean.endsWith("]") || clean.endsWith(")") || clean.endsWith(",") || clean.endsWith(";")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        return clean;
    }

    private boolean isChordSeparator(String token) {
        return "|".equals(token) || "/".equals(token) || "%".equals(token);
    }

    private ArrayList<ChordLine> chordLinesFromJson(String raw) {
        ArrayList<ChordLine> lines = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return lines;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                ChordLine line = new ChordLine(item.optString("text"));
                JSONArray chords = item.optJSONArray("chords");
                if (chords != null) {
                    for (int j = 0; j < chords.length(); j++) {
                        JSONObject chord = chords.optJSONObject(j);
                        if (chord != null) {
                            int start = chord.optInt("start", -1);
                            String value = chord.optString("chord");
                            if (start >= 0 && !value.isEmpty()) {
                                line.chords.put(start, value);
                            }
                        }
                    }
                }
                lines.add(line);
            }
        } catch (JSONException ignored) {
            lines.clear();
        }
        return lines;
    }

    private void applyChordLines(Song song, EditText bodyInput, ArrayList<ChordLine> lines) {
        song.chordLinesJson = chordLinesToJson(lines);
        song.body = renderChordLines(lines);
        bodyInput.setText(song.body);
    }

    private String chordLinesToJson(ArrayList<ChordLine> lines) {
        JSONArray array = new JSONArray();
        for (ChordLine line : lines) {
            JSONObject item = new JSONObject();
            try {
                item.put("text", line.text);
                JSONArray chords = new JSONArray();
                for (Map.Entry<Integer, String> entry : line.chords.entrySet()) {
                    chords.put(new JSONObject()
                            .put("start", entry.getKey())
                            .put("chord", entry.getValue()));
                }
                item.put("chords", chords);
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        return array.toString();
    }

    private String renderChordLines(ArrayList<ChordLine> lines) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                output.append("\n");
            }
            output.append(renderChordLine(lines.get(i)));
        }
        return output.toString();
    }

    private String renderChordLine(ChordLine line) {
        if (line.chords.isEmpty()) {
            return line.text;
        }
        return renderedChordOnlyLine(line) + "\n" + line.text;
    }

    private String renderedChordOnlyLine(ChordLine line) {
        int width = Math.max(line.text.length(), maxChordEnd(line));
        char[] chordChars = new char[Math.max(width, 1)];
        for (int i = 0; i < chordChars.length; i++) {
            chordChars[i] = ' ';
        }
        for (Map.Entry<Integer, String> entry : line.chords.entrySet()) {
            int start = Math.max(0, Math.min(entry.getKey(), chordChars.length - 1));
            String chord = entry.getValue();
            for (int i = 0; i < chord.length() && start + i < chordChars.length; i++) {
                chordChars[start + i] = chord.charAt(i);
            }
        }
        return trimRight(new String(chordChars));
    }

    private int maxChordEnd(ChordLine line) {
        int max = line.text.length();
        for (Map.Entry<Integer, String> entry : line.chords.entrySet()) {
            max = Math.max(max, entry.getKey() + entry.getValue().length());
        }
        return max;
    }

    private String trimRight(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private List<WordToken> wordTokens(String line) {
        List<WordToken> words = new ArrayList<>();
        int index = 0;
        while (index < line.length()) {
            while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
                index++;
            }
            int start = index;
            while (index < line.length() && !Character.isWhitespace(line.charAt(index))) {
                index++;
            }
            if (start < index) {
                words.add(new WordToken(line.substring(start, index), start));
            }
        }
        return words;
    }

    private int firstEditableLine(ArrayList<ChordLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!wordTokens(lines.get(i).text).isEmpty()) {
                return i;
            }
        }
        return 0;
    }

    private int nextEditableLine(ArrayList<ChordLine> lines, int current) {
        for (int i = current + 1; i < lines.size(); i++) {
            if (!wordTokens(lines.get(i).text).isEmpty()) {
                return i;
            }
        }
        return firstEditableLine(lines);
    }

    private int previousEditableLine(ArrayList<ChordLine> lines, int current) {
        for (int i = current - 1; i >= 0; i--) {
            if (!wordTokens(lines.get(i).text).isEmpty()) {
                return i;
            }
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!wordTokens(lines.get(i).text).isEmpty()) {
                return i;
            }
        }
        return 0;
    }

    private boolean hasDraft() {
        return songRepository.hasDraft();
    }

    private Song loadDraftSong() {
        Song song = songRepository.loadDraftSong();
        return song != null && hasDraftContent(song) ? song : null;
    }

    private void saveDraftFromInputs(Song song, EditText titleInput, EditText artistInput, EditText keyInput, EditText bodyInput, EditText notesInput, EditText sourceInput) {
        song.title = titleInput.getText().toString().trim();
        song.artist = artistInput.getText().toString().trim();
        song.key = keyInput.getText().toString().trim();
        song.body = bodyInput.getText().toString();
        song.notes = notesInput.getText().toString().trim();
        song.sourceUrl = normalizeUrl(sourceInput.getText().toString().trim());
        if (hasDraftContent(song)) {
            songRepository.saveDraft(song);
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
        songRepository.clearDraft();
    }

    private LinearLayout baseRoot() {
        LinearLayout view = column();
        applySafePadding(view, 0, 0);
        view.setBackgroundColor(getColor(R.color.paper));
        view.setFitsSystemWindows(false);
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                topInset = insets.getInsets(WindowInsets.Type.systemBars()).top;
                bottomInset = Math.max(
                        insets.getInsets(WindowInsets.Type.systemBars()).bottom,
                        insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            applySafePadding((LinearLayout) target, topInset, bottomInset);
            return insets;
        });
        view.requestApplyInsets();
        return view;
    }

    private void applySafePadding(LinearLayout view, int topInset, int bottomInset) {
        view.setPadding(dp(18), dp(12) + topInset, dp(18), dp(28) + bottomInset);
    }

    private void enableEdgeToEdge() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.setStatusBarColor(getColor(android.R.color.transparent));
            window.setNavigationBarColor(getColor(android.R.color.transparent));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.getDecorView().setSystemUiVisibility(
                    window.getDecorView().getSystemUiVisibility()
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
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

    private LinearLayout.LayoutParams weightedMargins(float weight, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, wrap(), weight);
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

    private boolean isEmailLike(String text) {
        return text.contains("@") && text.indexOf("@") > 0 && text.lastIndexOf(".") > text.indexOf("@") + 1;
    }

    private String googleApiErrorMessage(Exception error, String email) {
        if (error instanceof GoogleAuthException) {
            return "Google authorization failed. Try signing out and back in.";
        }
        if (!email.isEmpty()) {
            return "Could not share with " + email + ".";
        }
        return "Could not update Google Doc.";
    }

    private boolean isGoogleDocManaged(Song song) {
        return !song.googleDocId.isEmpty();
    }

    private boolean hasLocalChangesSinceSync(Song song) {
        if (!isGoogleDocManaged(song)) {
            return false;
        }
        if (song.lastSyncedBodyHash.isEmpty()) {
            return !song.body.trim().isEmpty();
        }
        return !song.lastSyncedBodyHash.equals(bodyHash(song.body));
    }

    private String syncStatus(Song song) {
        if (hasLocalChangesSinceSync(song)) {
            return "Local changes";
        }
        if (song.lastSyncedAt > 0) {
            return "Synced";
        }
        return "Google Doc";
    }

    private String syncDetail(Song song) {
        if (hasLocalChangesSinceSync(song)) {
            return "Local lyrics differ from the last synced Google Doc. Sync Latest will ask before replacing them.";
        }
        if (song.lastSyncedAt > 0) {
            return "Last synced " + formatTime(song.lastSyncedAt) + ".";
        }
        return "Google Docs is the live editor for this song.";
    }

    private String bodyHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value.trim()).getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format(Locale.US, "%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            return String.valueOf((value == null ? "" : value.trim()).hashCode());
        }
    }

    private String librarySubtitle() {
        FirebaseUser user = auth == null ? null : auth.getCurrentUser();
        if (user == null) {
            return songs.size() + " songs in your book";
        }
        return songs.size() + " songs - " + displayName(user);
    }

    private String displayName(FirebaseUser user) {
        if (user == null) {
            return "Google";
        }
        if (user.getDisplayName() != null && !user.getDisplayName().trim().isEmpty()) {
            return user.getDisplayName();
        }
        if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
            return user.getEmail();
        }
        return "Google account";
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

    private interface GoogleApiTask {
        String run(String accessToken) throws IOException, JSONException, GoogleAuthException;
    }

    private enum Screen {
        LIBRARY,
        DETAIL,
        EDITOR,
        WEB,
        SETTINGS
    }

    private enum CollaborationAction {
        CREATE_DOC,
        SHARE_DOC,
        SYNC_DOC
    }

    private boolean isFeatureEnabled(String featureName) {
        return remoteConfigManager != null && remoteConfigManager.isFeatureEnabled(featureName);
    }

    private boolean isGoogleDocCollaborationEnabled() {
        return isFeatureEnabled("enable_google_docs_collaboration");
    }

    private boolean isVoiceRecordingsEnabled() {
        return isFeatureEnabled("enable_voice_recordings");
    }

    private boolean isWebLookupEnabled() {
        return isFeatureEnabled("enable_web_lookup");
    }

    private boolean isSongVersionsEnabled() {
        return isFeatureEnabled("enable_song_versions");
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

}
