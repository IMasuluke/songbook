package com.example.songbook

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.songbook.data.local.LocalSongRepository
import com.example.songbook.data.local.SongJsonMapper
import com.example.songbook.domain.model.Recording
import com.example.songbook.domain.model.Song
import com.example.songbook.domain.model.SongVersion
import com.example.songbook.domain.usecase.SongVersionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class ComposeMainActivity : ComponentActivity() {
    private lateinit var repository: LocalSongRepository
    private val versionManager = SongVersionManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LocalSongRepository(this)
        setContent {
            SongbookTheme {
                SongbookApp(
                    repository = repository,
                    versionManager = versionManager,
                    openExternalUrl = { url ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    showToast = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
private fun SongbookApp(
    repository: LocalSongRepository,
    versionManager: SongVersionManager,
    openExternalUrl: (String) -> Unit,
    showToast: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    var songs by remember { mutableStateOf(seedSongsIfNeeded(repository)) }
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    var readerTextSize by rememberSaveable { mutableFloatStateOf(18f) }
    var showVersionDialogForId by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingSongId by remember { mutableStateOf<String?>(null) }
    var recordingPath by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var pendingRecordingSongId by remember { mutableStateOf<String?>(null) }
    var renameRecordingTarget by remember { mutableStateOf<RecordingTarget?>(null) }
    var deleteRecordingTarget by remember { mutableStateOf<RecordingTarget?>(null) }

    fun releaseRecorder() {
        recorder?.release()
        recorder = null
        recordingSongId = null
        recordingPath = null
    }

    fun releasePlayer() {
        player?.release()
        player = null
        playingPath = null
    }

    fun updateSong(updated: Song) {
        songs = songs.map { if (it.id == updated.id) updated else it }
        repository.saveSongs(songs)
        refreshNonce++
    }

    fun startRecordingNow(songId: String) {
        if (recorder != null) {
            showToast("Stop the current recording first.")
            return
        }
        releasePlayer()
        val dir = File(activity.filesDir, "recordings")
        if (!dir.exists() && !dir.mkdirs()) {
            showToast("Could not create recordings folder.")
            return
        }
        val output = File(dir, "$songId-${System.currentTimeMillis()}.m4a")
        val nextRecorder = MediaRecorder()
        try {
            nextRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            nextRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            nextRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            nextRecorder.setAudioEncodingBitRate(128000)
            nextRecorder.setAudioSamplingRate(44100)
            nextRecorder.setOutputFile(output.absolutePath)
            nextRecorder.prepare()
            nextRecorder.start()
            recorder = nextRecorder
            recordingSongId = songId
            recordingPath = output.absolutePath
            showToast("Recording started.")
        } catch (e: IOException) {
            nextRecorder.release()
            showToast("Could not start recording.")
        } catch (e: RuntimeException) {
            nextRecorder.release()
            showToast("Could not start recording.")
        }
    }

    fun stopRecordingForSong(songId: String) {
        if (recorder == null || recordingSongId != songId || recordingPath == null) {
            return
        }
        val path = recordingPath ?: return
        try {
            recorder?.stop()
            val original = songs.firstOrNull { it.id == songId } ?: return
            val updated = deepCopySong(original)
            val recording = Recording(path, System.currentTimeMillis(), "")
            updated.recordings.add(recording)
            recording.name = defaultRecordingName(updated, recording)
            updateSong(updated)
            showToast("Recording saved.")
        } catch (e: RuntimeException) {
            File(path).delete()
            showToast("Recording was too short to save.")
        } finally {
            releaseRecorder()
        }
    }

    fun togglePlayback(song: Song, recording: Recording) {
        if (playingPath == recording.path && player?.isPlaying == true) {
            releasePlayer()
            return
        }
        val file = File(recording.path)
        if (!file.exists()) {
            showToast("Recording file is missing.")
            return
        }
        releasePlayer()
        val nextPlayer = MediaPlayer()
        try {
            nextPlayer.setDataSource(recording.path)
            nextPlayer.setOnCompletionListener {
                releasePlayer()
            }
            nextPlayer.prepare()
            nextPlayer.start()
            player = nextPlayer
            playingPath = recording.path
            showToast("Playing recording.")
        } catch (e: IOException) {
            nextPlayer.release()
            showToast("Could not play recording.")
        } catch (e: RuntimeException) {
            nextPlayer.release()
            showToast("Could not play recording.")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val songId = pendingRecordingSongId
        pendingRecordingSongId = null
        if (granted && songId != null) {
            startRecordingNow(songId)
        } else if (!granted) {
            showToast("Microphone permission is needed for voice recordings.")
        }
    }

    fun requestOrStartRecording(songId: String) {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecordingNow(songId)
        } else {
            pendingRecordingSongId = songId
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            releaseRecorder()
            releasePlayer()
        }
    }

    fun persistSongs(next: List<Song>) {
        songs = next
        repository.saveSongs(next)
        refreshNonce++
    }

    fun upsertSong(updated: Song, existingId: String?) {
        val copy = deepCopySong(updated)
        versionManager.ensureDefaultVersion(copy)
        versionManager.saveActiveVersion(copy)
        val next = songs.toMutableList()
        if (existingId == null) {
            next.add(copy)
            repository.clearDraft()
        } else {
            val index = next.indexOfFirst { it.id == existingId }
            if (index >= 0) {
                next[index] = copy
            }
        }
        persistSongs(next)
        screen = Screen.Detail(copy.id)
    }

    fun deleteSong(songId: String) {
        songs.firstOrNull { it.id == songId }?.recordings?.forEach { recording ->
            File(recording.path).delete()
        }
        persistSongs(songs.filterNot { it.id == songId })
        screen = Screen.Library
    }

    val currentSong = remember(screen, songs, refreshNonce) {
        when (val current = screen) {
            is Screen.Detail -> songs.firstOrNull { it.id == current.songId }
            is Screen.Editor -> current.songId?.let { id -> songs.firstOrNull { it.id == id } }
            else -> null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppColors.Paper
    ) {
        when (val current = screen) {
            Screen.Library -> LibraryScreen(
                songs = songs,
                onOpenSong = { screen = Screen.Detail(it) },
                onNewSong = { screen = Screen.Editor(null, null) },
                onSettings = { screen = Screen.Settings }
            )

            is Screen.Detail -> {
                val song = currentSong
                if (song == null) {
                    screen = Screen.Library
                } else {
                    DetailScreen(
                        song = song,
                        versionManager = versionManager,
                        readerTextSize = readerTextSize,
                        onReaderTextSizeChange = { readerTextSize = it },
                        onBack = { screen = Screen.Library },
                        onEdit = { screen = Screen.Editor(song.id, null) },
                        onOpenSource = {
                            if (song.sourceUrl.isNotBlank()) {
                                openExternalUrl(normalizeUrl(song.sourceUrl))
                            }
                        },
                        onCreateVersion = { showVersionDialogForId = song.id },
                        onSwitchVersion = { version ->
                            val switched = deepCopySong(song)
                            versionManager.switchVersion(switched, version)
                            persistSongs(songs.map { if (it.id == switched.id) switched else it })
                        },
                        isRecording = recordingSongId == song.id,
                        playingPath = playingPath,
                        onToggleRecording = {
                            if (recordingSongId == song.id) {
                                stopRecordingForSong(song.id)
                            } else {
                                requestOrStartRecording(song.id)
                            }
                        },
                        onPlayLatest = {
                            song.recordings.lastOrNull()?.let { togglePlayback(song, it) }
                        },
                        onTogglePlayback = { recording ->
                            togglePlayback(song, recording)
                        },
                        onRenameRecording = { recording ->
                            renameRecordingTarget = RecordingTarget(song.id, recording.path)
                        },
                        onDeleteRecording = { recording ->
                            deleteRecordingTarget = RecordingTarget(song.id, recording.path)
                        }
                    )
                }
            }

            is Screen.Editor -> EditorScreen(
                key = "${current.songId ?: "new"}:${current.initialSource.orEmpty()}",
                baseSong = currentSong,
                initialSource = current.initialSource,
                repository = repository,
                showToast = showToast,
                onCancel = {
                    screen = current.songId?.let(Screen::Detail) ?: Screen.Library
                },
                onSave = { edited -> upsertSong(edited, current.songId) },
                onDelete = { songId -> deleteSong(songId) }
            )

            Screen.Settings -> SettingsScreen(
                songCount = songs.size,
                draftAvailable = repository.hasDraft(),
                onBack = { screen = Screen.Library },
                onNewSong = { screen = Screen.Editor(null, null) },
                onClearDraft = {
                    repository.clearDraft()
                    showToast("Draft cleared.")
                }
            )
        }
    }

    if (showVersionDialogForId != null) {
        val song = songs.firstOrNull { it.id == showVersionDialogForId }
        if (song == null) {
            showVersionDialogForId = null
        } else {
            CreateVersionDialog(
                suggestedName = "Version ${song.versions.size + 1}",
                onDismiss = { showVersionDialogForId = null },
                onConfirm = { name ->
                    val branched = deepCopySong(song)
                    versionManager.createVersionFromCurrent(branched, name)
                    persistSongs(songs.map { if (it.id == branched.id) branched else it })
                    showVersionDialogForId = null
                }
            )
        }
    }

    if (renameRecordingTarget != null) {
        val target = renameRecordingTarget
        val song = songs.firstOrNull { it.id == target?.songId }
        val recording = song?.recordings?.firstOrNull { it.path == target?.recordingPath }
        if (song == null || recording == null || target == null) {
            renameRecordingTarget = null
        } else {
            RenameRecordingDialog(
                currentName = recordingDisplayName(recording, song.recordings.indexOf(recording)),
                onDismiss = { renameRecordingTarget = null },
                onConfirm = { newName ->
                    val updated = deepCopySong(song)
                    val targetRecording = updated.recordings.firstOrNull { it.path == recording.path }
                    if (targetRecording != null) {
                        targetRecording.name = cleanRecordingName(newName, updated, targetRecording)
                        updateSong(updated)
                    }
                    renameRecordingTarget = null
                }
            )
        }
    }

    if (deleteRecordingTarget != null) {
        val target = deleteRecordingTarget
        val song = songs.firstOrNull { it.id == target?.songId }
        val recording = song?.recordings?.firstOrNull { it.path == target?.recordingPath }
        if (song == null || recording == null || target == null) {
            deleteRecordingTarget = null
        } else {
            AlertDialog(
                onDismissRequest = { deleteRecordingTarget = null },
                title = { Text("Delete recording?", color = AppColors.Ink) },
                text = { Text("This removes the voice recording from this song.", color = AppColors.Muted) },
                confirmButton = {
                    TextButton(onClick = {
                        releasePlayer()
                        val updated = deepCopySong(song)
                        updated.recordings.removeAll { it.path == recording.path }
                        File(recording.path).delete()
                        updateSong(updated)
                        deleteRecordingTarget = null
                    }) {
                        Text("Delete", color = AppColors.Danger)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteRecordingTarget = null }) {
                        Text("Cancel", color = AppColors.Muted)
                    }
                }
            )
        }
    }
}

@Composable
private fun LibraryScreen(
    songs: List<Song>,
    onOpenSong: (String) -> Unit,
    onNewSong: () -> Unit,
    onSettings: () -> Unit
) {
    var search by rememberSaveable { mutableStateOf("") }
    val filteredSongs = remember(songs, search) {
        val needle = search.trim().lowercase(Locale.US)
        songs.filter { song ->
            needle.isBlank() || buildString {
                append(song.title)
                append(' ')
                append(song.artist)
                append(' ')
                append(song.key)
                append(' ')
                append(song.body)
                append(' ')
                append(song.sourceUrl)
            }.lowercase(Locale.US).contains(needle)
        }.sortedBy { it.title.lowercase(Locale.US) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Prestissimo",
                    style = TextStyle(
                        color = AppColors.Ink,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp
                    ),
                    modifier = Modifier.weight(1f)
                )
                CircleIconButton(Icons.Outlined.BookmarkBorder, onClick = onSettings)
                Spacer(modifier = Modifier.width(10.dp))
                CircleIconButton(Icons.Outlined.Settings, onClick = onSettings)
            }

            Spacer(modifier = Modifier.height(18.dp))
            SearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = "Search songs, artists, lyrics, or chords"
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TabPill("All", active = true, onClick = {})
                TabPill("Favorites", active = false, onClick = {})
                TabPill("Setlists", active = false, onClick = {})
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "RECENTLY PLAYED",
                color = AppColors.SoftGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (filteredSongs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No songs yet. Add one to start your book.",
                        color = AppColors.Muted,
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    item {
                        FeaturedSongCard(song = filteredSongs.first(), onClick = { onOpenSong(filteredSongs.first().id) })
                    }
                    items(filteredSongs.drop(1), key = { it.id }) { song ->
                        CompactSongRow(song = song, onClick = { onOpenSong(song.id) })
                    }
                }
            }
        }

        Button(
            onClick = onNewSong,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.Accent,
                contentColor = AppColors.Ink
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(64.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "Add song")
        }
    }
}

@Composable
private fun DetailScreen(
    song: Song,
    versionManager: SongVersionManager,
    readerTextSize: Float,
    onReaderTextSizeChange: (Float) -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenSource: () -> Unit,
    onCreateVersion: () -> Unit,
    onSwitchVersion: (SongVersion) -> Unit,
    isRecording: Boolean,
    playingPath: String?,
    onToggleRecording: () -> Unit,
    onPlayLatest: () -> Unit,
    onTogglePlayback: (Recording) -> Unit,
    onRenameRecording: (Recording) -> Unit,
    onDeleteRecording: (Recording) -> Unit
) {
    val scrollState = rememberScrollState()
    var autoScroll by rememberSaveable(song.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activeVersion = remember(song.id, song.activeVersionId, song.versions.size) { versionManager.activeVersion(song) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var isBottomSheetExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(autoScroll, scrollState.maxValue) {
        while (autoScroll) {
            delay(120L)
            val next = (scrollState.value + 16).coerceAtMost(scrollState.maxValue)
            scrollState.scrollTo(next)
            if (next >= scrollState.maxValue) {
                autoScroll = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIconButton(Icons.Outlined.ArrowBack, onClick = onBack, size = 44.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = AppColors.Ink,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                )
                Text(
                    text = if (song.artist.isBlank()) "Unknown artist" else song.artist,
                    color = AppColors.Accent,
                    fontSize = 14.sp
                )
            }
            CircleIconButton(Icons.Outlined.BookmarkBorder, onClick = {}, size = 44.dp)
            Spacer(modifier = Modifier.width(4.dp))
            CircleIconButton(Icons.Outlined.MoreVert, onClick = onEdit, size = 44.dp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Key/Tempo Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedControlButton("Key ${song.key.ifBlank { "-" }}")
            OutlinedControlButton(song.notes.ifBlank { "3/4" })
            OutlinedControlButton("Capo -")
            OutlinedControlButton("Main")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Playback Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReaderControlSmall("-", onClick = { onReaderTextSizeChange((readerTextSize - 1f).coerceAtLeast(14f)) }, modifier = Modifier.weight(1f))
            ReaderControlSmall("+", onClick = { onReaderTextSizeChange((readerTextSize + 1f).coerceAtMost(28f)) }, modifier = Modifier.weight(1f))
            ReaderControlSmall("Aa", onClick = {}, modifier = Modifier.weight(1f))
            ReaderControlToggle(isActive = autoScroll, onClick = { autoScroll = !autoScroll }, modifier = Modifier.weight(1f))
            ReaderControlSmall("⬆", onClick = { scope.launch { scrollState.animateScrollTo(0) } }, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(12.dp))
        Divider(color = AppColors.Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
        Spacer(modifier = Modifier.height(12.dp))

        // Lyrics Display
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = styledLyrics(song.body.ifBlank { "No lyrics or chords yet." }),
                color = AppColors.Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = readerTextSize.sp,
                lineHeight = (readerTextSize + 10).sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Collapsible Bottom Sheet
        if (isBottomSheetExpanded) {
            // Expanded state
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                // Tabs header with close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    TabItem("Versions", isSelected = selectedTab == 0, onClick = { selectedTab = 0 }, modifier = Modifier.weight(1f))
                    Divider(color = AppColors.Line, modifier = Modifier
                        .width(1.dp)
                        .height(40.dp))
                    TabItem("Voice", isSelected = selectedTab == 1, onClick = { selectedTab = 1 }, modifier = Modifier.weight(1f))
                    Divider(color = AppColors.Line, modifier = Modifier
                        .width(1.dp)
                        .height(40.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clickable {
                                isBottomSheetExpanded = false
                                onEdit()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Edit",
                            color = AppColors.Accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Bottom Content based on selected tab
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    when (selectedTab) {
                        0 -> {
                            // Versions
                            DarkPanel {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Versions", color = AppColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text("Latest: ${song.versions.size}", color = AppColors.Muted, fontSize = 13.sp)
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, AppColors.Line, RoundedCornerShape(12.dp))
                                        .clickable { selectedTab = 0 }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Versions", color = AppColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Latest: ${song.versions.size}", color = AppColors.Muted, fontSize = 12.sp)
                                    }
                                    Text(">", color = AppColors.Muted, fontSize = 18.sp)
                                }
                            }
                        }
                        1 -> {
                            // Voice Notes
                            DarkPanel {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Voice notes", color = AppColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text("Latest: ${song.recordings.size}", color = AppColors.Muted, fontSize = 13.sp)
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, AppColors.Line, RoundedCornerShape(12.dp))
                                        .clickable { selectedTab = 1 }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Voice notes", color = AppColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Latest: ${song.recordings.size}", color = AppColors.Muted, fontSize = 12.sp)
                                    }
                                    Text(">", color = AppColors.Muted, fontSize = 18.sp)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Collapsed state - just show tabs as buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                TabItem("Versions", isSelected = false, onClick = { selectedTab = 0; isBottomSheetExpanded = true }, modifier = Modifier.weight(1f))
                Divider(color = AppColors.Line, modifier = Modifier
                    .width(1.dp)
                    .height(40.dp))
                TabItem("Voice", isSelected = false, onClick = { selectedTab = 1; isBottomSheetExpanded = true }, modifier = Modifier.weight(1f))
                Divider(color = AppColors.Line, modifier = Modifier
                    .width(1.dp)
                    .height(40.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clickable {
                            isBottomSheetExpanded = false
                            onEdit()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Edit",
                        color = AppColors.Accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceNotesSection(
    song: Song,
    isRecording: Boolean,
    playingPath: String?,
    onToggleRecording: () -> Unit,
    onPlayLatest: () -> Unit,
    onTogglePlayback: (Recording) -> Unit,
    onRenameRecording: (Recording) -> Unit,
    onDeleteRecording: (Recording) -> Unit
) {
    DarkPanel {
        Text("VOICE NOTES", color = AppColors.SoftGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            AccentButton(
                text = if (isRecording) "Stop recording" else "Record voice",
                onClick = onToggleRecording,
                modifier = Modifier.weight(1f)
            )
            if (song.recordings.isNotEmpty()) {
                SecondaryButton(
                    text = "Play latest",
                    onClick = onPlayLatest,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (song.recordings.isEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("No recordings yet.", color = AppColors.Muted, fontSize = 14.sp)
            return@DarkPanel
        }

        song.recordings.asReversed().forEachIndexed { reversedIndex, recording ->
            val index = song.recordings.lastIndex - reversedIndex
            Spacer(modifier = Modifier.height(12.dp))
            DarkPanel(padding = 12.dp) {
                Text(recordingDisplayName(recording, index), color = AppColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(formatTime(recording.createdAt), color = AppColors.Muted, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton(
                        text = if (playingPath == recording.path) "Stop" else "Play",
                        onClick = { onTogglePlayback(recording) },
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryButton(
                        text = "Rename",
                        onClick = { onRenameRecording(recording) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { onDeleteRecording(recording) },
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Danger),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Danger)
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorScreen(
    key: String,
    baseSong: Song?,
    initialSource: String?,
    repository: LocalSongRepository,
    showToast: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: (Song) -> Unit,
    onDelete: (String) -> Unit
) {
    val initialSong = remember(key, baseSong, initialSource) {
        when {
            baseSong != null -> deepCopySong(baseSong)
            repository.hasDraft() -> repository.loadDraftSong() ?: Song()
            else -> Song()
        }.apply {
            if (baseSong == null && !initialSource.isNullOrBlank()) {
                sourceUrl = initialSource
            }
        }
    }

    var title by rememberSaveable(key) { mutableStateOf(initialSong.title) }
    var artist by rememberSaveable(key) { mutableStateOf(initialSong.artist) }
    var songKey by rememberSaveable(key) { mutableStateOf(initialSong.key) }
    var notes by rememberSaveable(key) { mutableStateOf(initialSong.notes) }
    var sourceUrl by rememberSaveable(key) { mutableStateOf(initialSong.sourceUrl) }
    var body by rememberSaveable(key, stateSaver = textFieldSaver()) { mutableStateOf(TextFieldValue(initialSong.body)) }
    var chordLinesJson by rememberSaveable(key) { mutableStateOf(initialSong.chordLinesJson) }
    var capo by rememberSaveable(key) { mutableStateOf("-") }
    var tuning by rememberSaveable(key) { mutableStateOf("Standard") }
    var monospace by rememberSaveable(key) { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var chordModeActive by remember { mutableStateOf(false) }
    var chordLines by remember(key) { mutableStateOf(loadChordLines(initialSong.body, initialSong.chordLinesJson)) }
    var selectedLineIndex by remember { mutableIntStateOf(firstEditableLine(chordLines)) }
    var selectedWordStart by remember { mutableStateOf<Int?>(null) }
    var showChordPicker by remember { mutableStateOf(false) }

    fun draftSong(): Song {
        val draft = deepCopySong(initialSong)
        draft.title = title.trim()
        draft.artist = artist.trim()
        draft.key = songKey.trim()
        draft.notes = notes.trim()
        draft.sourceUrl = normalizeUrl(sourceUrl.trim())
        draft.body = body.text
        draft.chordLinesJson = chordLinesJson
        return draft
    }

    fun syncBodyFromChordLines(nextLines: List<EditorChordLine>) {
        chordLines = nextLines
        chordLinesJson = chordLinesToJson(nextLines)
        body = TextFieldValue(
            text = renderChordLines(nextLines),
            selection = androidx.compose.ui.text.TextRange(renderChordLines(nextLines).length)
        )
    }

    LaunchedEffect(title, artist, songKey, notes, sourceUrl, body.text, key) {
        if (baseSong == null) {
            val draft = draftSong()
            if (hasDraftContent(draft)) {
                repository.saveDraft(draft)
            } else {
                repository.clearDraft()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = AppColors.Accent, fontSize = 16.sp)
            }
            Text(
                text = "Edit song",
                color = AppColors.Ink,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            TextButton(
                onClick = {
                    if (title.isBlank()) {
                        return@TextButton
                    }
                    val song = draftSong()
                    onSave(song)
                }
            ) {
                Text("Save", color = AppColors.Accent, fontSize = 16.sp)
            }
        }

        Text(
            text = "Draft saved",
            color = AppColors.SoftGold,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            UnderlineField("Title", title, { title = it })
            UnderlineField("Artist", artist, { artist = it })

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SelectField("Key", songKey, { songKey = it }, Modifier.weight(1f))
                SelectField("Capo", capo, { capo = it }, Modifier.weight(1f))
                SelectField("Tuning", tuning, { tuning = it }, Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Lyrics & Chords", color = AppColors.SoftGold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Monospace", color = AppColors.SoftGold, fontSize = 12.sp)
                Spacer(modifier = Modifier.width(10.dp))
                TabPill(if (monospace) "On" else "Off", active = monospace, onClick = { monospace = !monospace })
            }

            if (!chordModeActive) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("G", "C", "D", "Em", "Am").forEach { chord ->
                        SecondaryButton(chord, onClick = { body = insertChord(body, chord) })
                    }
                    AccentButton("+ Chord", onClick = { body = insertChord(body, "Cmaj7") })
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            SecondaryButton(
                text = if (chordModeActive) "Done Editing Chords" else "Edit Chords",
                onClick = {
                    if (chordModeActive) {
                        syncBodyFromChordLines(chordLines)
                        chordModeActive = false
                    } else {
                        val cleared = clearChordAnchorsIfBodyChanged(body.text, chordLinesJson)
                        chordLinesJson = cleared
                        chordLines = loadChordLines(body.text, cleared)
                        val firstLine = firstEditableLine(chordLines)
                        if (firstLine < 0) {
                            showToast("Add lyrics before adding chords.")
                        } else {
                            selectedLineIndex = firstLine
                            selectedWordStart = null
                            chordModeActive = true
                        }
                    }
                }
            )

            if (chordModeActive) {
                Spacer(modifier = Modifier.height(12.dp))
                ChordModePanel(
                    onDone = {
                        syncBodyFromChordLines(chordLines)
                        chordModeActive = false
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (chordModeActive) {
                LyricsChordModeEditor(
                    lines = chordLines,
                    onChooseWord = { lineIndex, wordStart ->
                        selectedLineIndex = lineIndex
                        selectedWordStart = wordStart
                        showChordPicker = true
                    }
                )
            } else {
                LyricsEditor(
                    value = body,
                    onValueChange = {
                        body = it
                        chordLinesJson = clearChordAnchorsIfBodyChanged(body.text, chordLinesJson)
                    },
                    monospace = monospace
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            UnderlineField("Notes", notes, { notes = it }, singleLine = false)
            UnderlineField("Source Link", sourceUrl, { sourceUrl = it }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))

            if (baseSong != null) {
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Danger),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Danger)
                ) {
                    Text("Delete song")
                }
            }
        }
    }

    if (showChordPicker && selectedWordStart != null && selectedLineIndex in chordLines.indices) {
        ChordPickerDialog(
            currentChord = chordLines[selectedLineIndex].chords[selectedWordStart],
            onDismiss = { showChordPicker = false },
            onPick = { choice ->
                val start = selectedWordStart ?: return@ChordPickerDialog
                chordLines = chordLines.mapIndexed { index, line ->
                    if (index != selectedLineIndex) {
                        line
                    } else {
                        val nextChords = line.chords.toMutableMap()
                        if (choice == null) {
                            nextChords.remove(start)
                        } else {
                            nextChords[start] = choice
                        }
                        line.copy(chords = nextChords.toMap())
                    }
                }
                syncBodyFromChordLines(chordLines)
                showChordPicker = false
            }
        )
    }

    if (showDeleteDialog && baseSong != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete song?", color = AppColors.Ink) },
            text = { Text("This removes \"${baseSong.title}\" from your songbook.", color = AppColors.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(baseSong.id)
                    showDeleteDialog = false
                }) {
                    Text("Delete", color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = AppColors.Muted)
                }
            }
        )
    }
}

@Composable
private fun ChordModePanel(
    onDone: () -> Unit
) {
    DarkPanel {
        Text("CHORD MODE", color = AppColors.SoftGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "The lyric box is read-only in this mode. Tap any word below to add a chord, or change the one already above it.",
            color = AppColors.Muted,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        SecondaryButton("Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LyricsChordModeEditor(
    lines: List<EditorChordLine>,
    onChooseWord: (Int, Int) -> Unit
) {
    val lineNumbers = remember(lines) {
        (1..lines.size.coerceAtLeast(1)).joinToString("\n")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 280.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.Panel)
            .border(1.dp, AppColors.Line, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = lineNumbers,
            color = AppColors.Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            lineHeight = 26.sp,
            modifier = Modifier.padding(top = 4.dp, end = 10.dp)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            lines.forEachIndexed { lineIndex, line ->
                val words = wordTokens(line.text)
                if (words.isEmpty()) {
                    Text(
                        text = line.text.ifBlank { " " },
                        color = AppColors.Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                } else {
                    Text(
                        text = renderedChordOnlyLine(line).ifBlank { " " },
                        color = AppColors.Accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                    val lyricAnnotated = remember(line) { chordModeAnnotatedLine(line) }
                    ClickableText(
                        text = lyricAnnotated,
                        style = TextStyle(
                            color = AppColors.Ink,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 24.sp
                        ),
                        onClick = { offset ->
                            lyricAnnotated
                                .getStringAnnotations(tag = "word_start", start = offset, end = offset)
                                .firstOrNull()
                                ?.item
                                ?.toIntOrNull()
                                ?.let { onChooseWord(lineIndex, it) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChordPickerDialog(
    currentChord: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit
) {
    val presets = listOf(
        "C", "D", "E", "F", "G", "A", "B",
        "Am", "Bm", "Cm", "Dm", "Em", "Fm", "Gm",
        "A7", "B7", "C7", "D7", "E7", "F7", "G7"
    )
    var custom by rememberSaveable { mutableStateOf(currentChord ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose chord", color = AppColors.Ink) },
        text = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    presets.forEach { chord ->
                        SecondaryButton(chord, onClick = { onPick(chord) })
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                UnderlineField("Custom chord", custom, { custom = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = custom.trim()
                if (clean.isNotEmpty()) {
                    onPick(clean)
                }
            }) {
                Text("Use custom", color = AppColors.Accent)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) {
                    Text("Remove", color = AppColors.Danger)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = AppColors.Muted)
                }
            }
        }
    )
}

@Composable
private fun SettingsScreen(
    songCount: Int,
    draftAvailable: Boolean,
    onBack: () -> Unit,
    onNewSong: () -> Unit,
    onClearDraft: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.Outlined.ArrowBack, onClick = onBack)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Settings",
                color = AppColors.Ink,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            )
        }
        Spacer(modifier = Modifier.height(18.dp))

        DarkPanel {
            Text("LIBRARY", color = AppColors.SoftGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("$songCount songs saved on this device.", color = AppColors.Ink, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(12.dp))
            AccentButton("Add new song", onClick = onNewSong)
        }

        Spacer(modifier = Modifier.height(14.dp))
        DarkPanel {
            Text("DRAFTS", color = AppColors.SoftGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (draftAvailable) "A local draft is available and will reopen in the editor." else "No local draft saved.",
                color = AppColors.Muted,
                fontSize = 14.sp
            )
            if (draftAvailable) {
                Spacer(modifier = Modifier.height(12.dp))
                SecondaryButton("Clear draft", onClick = onClearDraft)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        DarkPanel {
            Text("MIGRATION", color = AppColors.SoftGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "This build now renders the app with Jetpack Compose while keeping the existing local song storage and version data.",
                color = AppColors.Muted,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun FeaturedSongCard(song: Song, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 112.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AppColors.Panel),
                contentAlignment = Alignment.Center
            ) {
                Text("♪", color = AppColors.Muted, fontSize = 42.sp, fontFamily = FontFamily.Serif)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(song.title, color = AppColors.Ink, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(if (song.artist.isBlank()) "Unknown artist" else song.artist, color = AppColors.Muted, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(12.dp))
                MetaChip("Key ${song.key.ifBlank { "-" }}", accent = false)
            }
        }
    }
}

@Composable
private fun CompactSongRow(song: Song, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.Panel)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = AppColors.Muted)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, color = AppColors.Ink, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(if (song.artist.isBlank()) "Unknown artist" else song.artist, color = AppColors.Muted, fontSize = 13.sp)
        }
        MetaChip("Key ${song.key.ifBlank { "-" }}", accent = false)
        Spacer(modifier = Modifier.width(8.dp))
        Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = AppColors.Muted)
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, AppColors.Line, RoundedCornerShape(8.dp))
            .background(AppColors.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = AppColors.Muted)
        Spacer(modifier = Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = AppColors.Ink, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            decorationBox = { inner ->
                if (value.isBlank()) {
                    Text(placeholder, color = AppColors.Muted, fontSize = 15.sp)
                }
                inner()
            }
        )
    }
}

@Composable
private fun MetaChip(text: String, accent: Boolean) {
    Text(
        text = text,
        color = if (accent) AppColors.Accent else AppColors.InkSoft,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (accent) AppColors.Accent else AppColors.Line, RoundedCornerShape(8.dp))
            .background(AppColors.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun TabPill(text: String, active: Boolean, onClick: () -> Unit) {
    val background = if (active) AppColors.Accent else AppColors.Surface
    val textColor = if (active) AppColors.Ink else AppColors.InkSoft
    Text(
        text = text,
        color = textColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .border(1.dp, if (active) AppColors.Accent else AppColors.Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

@Composable
private fun CircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.InkSoft)
    }
}

@Composable
private fun DarkPanel(
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.Panel)
            .border(1.dp, AppColors.Line, RoundedCornerShape(8.dp))
            .padding(padding),
        content = content
    )
}

@Composable
private fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.Ink)
    ) {
        Text(text)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.InkSoft),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Line)
    ) {
        Text(text)
    }
}

@Composable
private fun ReaderControl(symbol: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterVertically, modifier = modifier) {
        SecondaryButton(text = symbol, onClick = onClick)
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, color = AppColors.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun OutlinedControlButton(text: String) {
    OutlinedButton(
        onClick = {},
        modifier = Modifier.heightIn(min = 40.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, AppColors.Accent),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Accent)
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ReaderControlSmall(symbol: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, AppColors.Accent),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Accent)
    ) {
        Text(symbol, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ReaderControlToggle(isActive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (isActive) AppColors.Accent else AppColors.Line),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (isActive) AppColors.Accent else AppColors.Muted,
            containerColor = if (isActive) AppColors.Accent.copy(alpha = 0.15f) else Color.Transparent
        )
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isActive) AppColors.Accent else Color.Transparent)
                .border(1.5.dp, if (isActive) AppColors.Accent else AppColors.Line, CircleShape)
        )
    }
}

@Composable
private fun TabItem(text: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                color = if (isSelected) AppColors.Accent else AppColors.Muted,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (isSelected) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(2.dp)
                        .background(AppColors.Accent)
                )
            }
        }
    }
}

@Composable
private fun UnderlineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default.copy(
        capitalization = KeyboardCapitalization.Sentences,
        imeAction = ImeAction.Next
    )
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = AppColors.SoftGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = AppColors.Ink, fontSize = 16.sp),
            keyboardOptions = keyboardOptions,
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Column {
                    inner()
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = AppColors.Line)
                }
            }
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun SelectField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = AppColors.SoftGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Line, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = AppColors.Ink, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LyricsEditor(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, monospace: Boolean) {
    val lineNumbers = remember(value.text) {
        (1..value.text.lineSequence().count().coerceAtLeast(1)).joinToString("\n")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 280.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.Panel)
            .border(1.dp, AppColors.Line, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = lineNumbers,
            color = AppColors.Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            lineHeight = 25.sp,
            modifier = Modifier.padding(top = 2.dp, end = 10.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                color = AppColors.Ink,
                fontSize = 16.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                lineHeight = 25.sp
            )
        )
    }
}

@Composable
private fun CreateVersionDialog(
    suggestedName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Branch version", color = AppColors.Ink) },
        text = {
            Column {
                Text("Create a new version from the current song state.", color = AppColors.Muted, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(12.dp))
                UnderlineField(label = "Version name", value = name, onValueChange = { name = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) {
                Text("Create", color = AppColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppColors.Muted)
            }
        }
    )
}

@Composable
private fun RenameRecordingDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename recording", color = AppColors.Ink) },
        text = {
            Column {
                Text("Update the label used for this voice note.", color = AppColors.Muted, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(12.dp))
                UnderlineField("Recording name", name, { name = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) {
                Text("Save", color = AppColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppColors.Muted)
            }
        }
    )
}

private fun seedSongsIfNeeded(repository: LocalSongRepository): List<Song> {
    val loaded = repository.loadSongs().toMutableList()
    if (loaded.isNotEmpty()) {
        return loaded
    }
    loaded += Song.sample(
        "Amazing Grace",
        "Traditional",
        "G",
        "G          C         G\nAmazing grace, how sweet the sound\nG                         D\nThat saved a soul like me\nG          C       G\nI once was lost but now am found\nG          D       G\nWas blind, but now I see",
        "Slow 3/4"
    )
    loaded += Song.sample(
        "House Practice Idea",
        "Original",
        "Em",
        "Em        C\nWrite your verse here\nG         D\nKeep chord names above each line\n\n[Chorus]\nC         G\nUse sections, repeats, and notes\nD         Em\nThen save it to the book",
        "Draft"
    )
    repository.saveSongs(loaded)
    return loaded
}

private fun deepCopySong(song: Song): Song = SongJsonMapper.fromJson(SongJsonMapper.toJson(song))

private fun normalizeUrl(text: String): String {
    if (text.isBlank()) {
        return ""
    }
    return if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
}

private fun hasDraftContent(song: Song): Boolean {
    return song.title.isNotBlank()
            || song.artist.isNotBlank()
            || song.key.isNotBlank()
            || song.body.isNotBlank()
            || song.notes.isNotBlank()
            || song.sourceUrl.isNotBlank()
}

private data class EditorChordLine(
    val text: String,
    val chords: Map<Int, String> = emptyMap()
)

private fun insertChord(value: TextFieldValue, chord: String): TextFieldValue {
    val insert = "$chord "
    val start = minOf(value.selection.start, value.selection.end).coerceAtLeast(0)
    val end = maxOf(value.selection.start, value.selection.end).coerceAtLeast(0)
    val nextText = value.text.replaceRange(start, end, insert)
    val cursor = start + insert.length
    return TextFieldValue(nextText, selection = androidx.compose.ui.text.TextRange(cursor))
}

private fun loadChordLines(body: String, chordLinesJson: String): List<EditorChordLine> {
    val lines = chordLinesFromJson(chordLinesJson)
    return if (lines.isNotEmpty() && renderChordLines(lines).trim() == body.trim()) lines else chordLinesFromBody(body)
}

private fun clearChordAnchorsIfBodyChanged(body: String, chordLinesJson: String): String {
    val lines = chordLinesFromJson(chordLinesJson)
    return if (lines.isNotEmpty() && renderChordLines(lines).trim() != body.trim()) "" else chordLinesJson
}

private fun chordLinesFromBody(body: String): List<EditorChordLine> {
    val lines = mutableListOf<EditorChordLine>()
    val rawLines = body.split("\n")
    var index = 0
    while (index < rawLines.size) {
        val rawLine = rawLines[index]
        if (index + 1 < rawLines.size && isChordLine(rawLine) && rawLines[index + 1].trim().isNotEmpty()) {
            val lyricLine = rawLines[index + 1]
            lines += EditorChordLine(
                text = lyricLine,
                chords = parseChordAnchors(rawLine, lyricLine)
            )
            index += 2
        } else {
            lines += EditorChordLine(rawLine)
            index++
        }
    }
    return lines
}

private fun isChordLine(line: String): Boolean {
    val tokens = wordTokens(line)
    if (tokens.isEmpty()) {
        return false
    }
    return tokens.all { isChordToken(it.text) }
}

private fun parseChordAnchors(chordLine: String, lyricLine: String): Map<Int, String> {
    val chords = mutableMapOf<Int, String>()
    val lyricWords = wordTokens(lyricLine)
    if (lyricWords.isEmpty()) {
        return chords
    }
    for (chordToken in wordTokens(chordLine)) {
        val chord = cleanChordToken(chordToken.text)
        if (chord.isEmpty() || isChordSeparator(chord) || !isChordToken(chord)) {
            continue
        }
        chords[nearestWordStart(lyricWords, chordToken.start)] = chord
    }
    return chords
}

private fun nearestWordStart(words: List<com.example.songbook.domain.model.WordToken>, targetStart: Int): Int {
    var nearest = words.first().start
    var bestDistance = Int.MAX_VALUE
    for (word in words) {
        val distance = kotlin.math.abs(word.start - targetStart)
        if (distance < bestDistance) {
            nearest = word.start
            bestDistance = distance
        }
    }
    return nearest
}

private fun cleanChordToken(token: String): String {
    return token.trim().trim('[', ']', '(', ')', ',', ';')
}

private fun isChordSeparator(token: String): Boolean {
    return token == "|" || token == "/" || token == "%"
}

private fun chordLinesFromJson(raw: String): List<EditorChordLine> {
    if (raw.isBlank()) {
        return emptyList()
    }
    return try {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val chords = mutableMapOf<Int, String>()
                val chordArray = item.optJSONArray("chords")
                if (chordArray != null) {
                    for (j in 0 until chordArray.length()) {
                        val chord = chordArray.optJSONObject(j) ?: continue
                        val start = chord.optInt("start", -1)
                        val value = chord.optString("chord")
                        if (start >= 0 && value.isNotBlank()) {
                            chords[start] = value
                        }
                    }
                }
                add(EditorChordLine(item.optString("text"), chords.toMap()))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun chordLinesToJson(lines: List<EditorChordLine>): String {
    val array = JSONArray()
    lines.forEach { line ->
        val item = JSONObject()
        item.put("text", line.text)
        val chords = JSONArray()
        line.chords.toSortedMap().forEach { (start, chord) ->
            chords.put(JSONObject().put("start", start).put("chord", chord))
        }
        item.put("chords", chords)
        array.put(item)
    }
    return array.toString()
}

private fun renderChordLines(lines: List<EditorChordLine>): String {
    return lines.joinToString("\n") { renderChordLine(it) }
}

private fun renderChordLine(line: EditorChordLine): String {
    return if (line.chords.isEmpty()) line.text else renderedChordOnlyLine(line) + "\n" + line.text
}

private fun renderedChordOnlyLine(line: EditorChordLine): String {
    val width = maxOf(line.text.length, maxChordEnd(line))
    val chordChars = CharArray(maxOf(width, 1)) { ' ' }
    line.chords.toSortedMap().forEach { (startRaw, chord) ->
        val start = startRaw.coerceIn(0, chordChars.lastIndex)
        chord.forEachIndexed { index, char ->
            val outputIndex = start + index
            if (outputIndex <= chordChars.lastIndex) {
                chordChars[outputIndex] = char
            }
        }
    }
    return trimRight(String(chordChars))
}

private fun maxChordEnd(line: EditorChordLine): Int {
    var max = 0
    line.chords.forEach { (start, chord) ->
        max = maxOf(max, start + chord.length)
    }
    return max
}

private fun trimRight(text: String): String = text.replace(Regex("\\s+$"), "")

private fun wordTokens(line: String): List<com.example.songbook.domain.model.WordToken> {
    val words = mutableListOf<com.example.songbook.domain.model.WordToken>()
    var index = 0
    while (index < line.length) {
        while (index < line.length && line[index].isWhitespace()) {
            index++
        }
        val start = index
        while (index < line.length && !line[index].isWhitespace()) {
            index++
        }
        if (start < index) {
            words += com.example.songbook.domain.model.WordToken(line.substring(start, index), start)
        }
    }
    return words
}

private fun firstEditableLine(lines: List<EditorChordLine>): Int = lines.indexOfFirst { wordTokens(it.text).isNotEmpty() }


private fun styledLyrics(text: String): AnnotatedString {
    val source = text.ifBlank { "No lyrics or chords yet." }
    return buildAnnotatedString {
        val lines = source.split("\n")
        lines.forEachIndexed { index, line ->
            val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val chordLine = tokens.isNotEmpty() && tokens.all { isChordToken(it) }
            if (chordLine) {
                withStyle(SpanStyle(color = AppColors.Accent)) { append(line) }
            } else {
                append(line)
            }
            if (index != lines.lastIndex) {
                append("\n")
            }
        }
    }
}

private fun chordModeAnnotatedLine(line: EditorChordLine): AnnotatedString {
    val words = wordTokens(line.text)
    return buildAnnotatedString {
        append(line.text)
        words.forEach { word ->
            addStringAnnotation(
                tag = "word_start",
                annotation = word.start.toString(),
                start = word.start,
                end = word.start + word.text.length
            )
            addStyle(
                SpanStyle(
                    color = AppColors.Accent,
                    fontWeight = if (line.chords[word.start] == null) FontWeight.Normal else FontWeight.SemiBold
                ),
                start = word.start,
                end = word.start + word.text.length
            )
        }
    }
}

private fun isChordToken(token: String): Boolean {
    val clean = token.trim().trim('[', ']', '(', ')', ',', ';')
    if (clean.isEmpty()) {
        return false
    }
    if (clean == "|" || clean == "/" || clean == "%") {
        return true
    }
    val root = clean.first()
    if (root !in 'A'..'G') {
        return false
    }
    return clean.drop(1).all { it.isLetterOrDigit() || it in charArrayOf('#', 'b', '/', '+', '-', '.', '(', ')') }
}

private fun timestamp(value: Long): String {
    if (value <= 0L) {
        return "Just now"
    }
    return SimpleDateFormat("MMM d, HH:mm", Locale.US).format(value)
}

private fun formatTime(value: Long): String {
    return SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(Date(value))
}

private fun recordingDisplayName(recording: Recording, index: Int): String {
    if (recording.name.trim().isNotEmpty()) {
        return recording.name
    }
    return if (index >= 0) "Recording ${index + 1}" else "Recording"
}

private fun defaultRecordingName(song: Song, recording: Recording): String {
    return recordingDisplayName(recording, song.recordings.indexOf(recording))
}

private fun cleanRecordingName(value: String, song: Song, recording: Recording): String {
    val clean = value.trim()
    return if (clean.isEmpty()) defaultRecordingName(song, recording) else clean
}

private fun textFieldSaver(): Saver<TextFieldValue, Any> = listSaver(
    save = { listOf(it.text, it.selection.start, it.selection.end) },
    restore = {
        TextFieldValue(
            text = it[0] as String,
            selection = androidx.compose.ui.text.TextRange(it[1] as Int, it[2] as Int)
        )
    }
)

private sealed interface Screen {
    data object Library : Screen
    data object Settings : Screen
    data class Detail(val songId: String) : Screen
    data class Editor(val songId: String?, val initialSource: String?) : Screen
}

private data class RecordingTarget(
    val songId: String,
    val recordingPath: String
)

private object AppColors {
    val Paper = Color(0xFF121211)
    val Surface = Color(0xFF171614)
    val Panel = Color(0xFF1C1A17)
    val Line = Color(0xFF302D29)
    val Ink = Color(0xFFF4EFE4)
    val InkSoft = Color(0xFFDDD5C3)
    val Muted = Color(0xFF8D877B)
    val SoftGold = Color(0xFF98916D)
    val Accent = Color(0xFFE66A50)
    val Danger = Color(0xFFE06A5F)
}

@Composable
private fun SongbookTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
