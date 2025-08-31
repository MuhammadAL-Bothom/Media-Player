package com.example.media2

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var playPauseBtn: ImageButton
    private lateinit var stopBtn: ImageButton
    private lateinit var rewindBtn: ImageButton
    private lateinit var forwardBtn: ImageButton
    private lateinit var repeatBtn: ImageButton
    private lateinit var shuffleBtn: ImageButton
    private lateinit var prevBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeTv: TextView
    private lateinit var totalTimeTv: TextView
    private lateinit var titleTv: TextView
    private lateinit var coverImage: ImageView
    private lateinit var trackListRv: RecyclerView

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val PERM_REQ = 2001
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }

    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val uri: Uri,
        val durationMs: Long
    )

    private val tracks = mutableListOf<Track>()

    // Current / last state
    private var currentIndex = -1
    private var lastSelectedIndex = -1              // last chosen/played index
    private var lastPausedPosition: Int = 0         // position saved on PAUSE (not stop)
    private var lastStoppedIndex: Int = -1          // armed by STOP for restart
    private var stopReadyToRestart: Boolean = false // STOP toggle state

    private enum class RepeatMode { OFF, ALL, ONE }
    private var repeatMode = RepeatMode.OFF
    private var shuffleOn = false

    // headset unplug pause
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
            if (i?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (mediaPlayer?.isPlaying == true) {
                    lastPausedPosition = mediaPlayer?.currentPosition ?: 0
                    mediaPlayer?.pause()
                    updatePlayPause(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        // views
        playPauseBtn = findViewById(R.id.playPauseBtn)
        stopBtn = findViewById(R.id.stopBtn)
        rewindBtn = findViewById(R.id.rewindBtn)
        forwardBtn = findViewById(R.id.forwardBtn)
        repeatBtn = findViewById(R.id.repeatBtn)
        shuffleBtn = findViewById(R.id.shuffleBtn)
        prevBtn = findViewById(R.id.prevBtn)
        nextBtn = findViewById(R.id.nextBtn)
        seekBar = findViewById(R.id.seekBar)
        currentTimeTv = findViewById(R.id.currentTimeTextView)
        totalTimeTv = findViewById(R.id.totalDurationTextView)
        titleTv = findViewById(R.id.titleText)
        coverImage = findViewById(R.id.coverImage)
        trackListRv = findViewById(R.id.trackListRv)

        // recycler
        trackListRv.layoutManager = LinearLayoutManager(this)
        trackListRv.adapter = TrackAdapter(tracks) { pos -> playIndex(pos) }

        setupButtons()
        setupSeekBar()

        // restore user prefs
        shuffleOn = prefs.getBoolean("shuffle", false)
        repeatMode = RepeatMode.valueOf(prefs.getString("repeat", RepeatMode.OFF.name)!!)
        // ensure no tint on the image (some themes auto-tint)
        ImageViewCompat.setImageTintList(shuffleBtn, null)
        updateShuffleIcon()
        updateRepeatIcon()

        if (hasAudioPermission()) loadTracks() else requestAudioPermission()

        startSeekbarUpdates()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            noisyReceiver,
            android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(noisyReceiver)
        // persist flags and last state
        prefs.edit()
            .putBoolean("shuffle", shuffleOn)
            .putString("repeat", repeatMode.name)
            .putInt("lastIndex", currentIndex)
            .putInt("lastPos", mediaPlayer?.currentPosition ?: lastPausedPosition)
            .apply()

        if (mediaPlayer?.isPlaying == true) {
            lastPausedPosition = mediaPlayer?.currentPosition ?: 0
            mediaPlayer?.pause()
            updatePlayPause(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // ---------- Permissions ----------
    private fun hasAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_AUDIO), PERM_REQ)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PERM_REQ)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == PERM_REQ && r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) {
            loadTracks()
        } else {
            Toast.makeText(this, "Permission required to list audio files.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- MediaStore load ----------
    private fun loadTracks() {
        tracks.clear()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.MIME_TYPE}=?"
        val args = arrayOf("audio/mpeg") // mp3 only
        val sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"

        contentResolver.query(uri, projection, selection, args, sort)?.use { cursor ->
            val cId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val cTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val cArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val cDur = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(cId)
                val title = cursor.getString(cTitle) ?: "Unknown title"
                val artist = cursor.getString(cArtist) ?: "Unknown artist"
                val duration = cursor.getLong(cDur)
                val contentUri = ContentUris.withAppendedId(uri, id)
                tracks += Track(id, title, artist, contentUri, duration)
            }
        }

        (trackListRv.adapter as TrackAdapter).notifyDataSetChanged()

        // restore last track (do not auto-play)
        val idx = prefs.getInt("lastIndex", -1)
        val pos = prefs.getInt("lastPos", 0)
        if (idx in tracks.indices) {
            currentIndex = idx
            lastSelectedIndex = idx
            titleTv.text = tracks[idx].title
            totalTimeTv.text = formatTime(tracks[idx].durationMs)
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, tracks[idx].uri)
            mediaPlayer?.seekTo(pos)
            updatePlayPause(false)
            loadEmbeddedArt(tracks[idx].uri)
        } else if (tracks.isEmpty()) {
            titleTv.text = "No MP3 found"
        } else {
            titleTv.text = "Select a track"
        }
    }

    // ---------- Buttons & Behavior ----------
    private fun setupButtons() {
        // Play/Pause connects to last selected/stopped track
        playPauseBtn.setOnClickListener {
            val mp = mediaPlayer
            if (mp == null) {
                // Nothing loaded: start the same track user was on (paused/stopped), else first
                val targetIndex = when {
                    currentIndex in tracks.indices -> currentIndex
                    lastSelectedIndex in tracks.indices -> lastSelectedIndex
                    lastStoppedIndex in tracks.indices -> lastStoppedIndex
                    tracks.isNotEmpty() -> 0
                    else -> return@setOnClickListener
                }
                startTrackFromLastState(targetIndex)
                stopReadyToRestart = false
                stopBtn.contentDescription = "Stop"
            } else {
                if (mp.isPlaying) {
                    lastPausedPosition = mp.currentPosition
                    mp.pause()
                    updatePlayPause(false)
                } else {
                    mp.start()
                    updatePlayPause(true)
                    // we resumed; no need for paused pos
                    lastPausedPosition = 0
                }
            }
        }

        // STOP toggles: stop -> (next press) restart same track
        stopBtn.setOnClickListener { stopOrRestart() }

        rewindBtn.setOnClickListener { seekBy(-10_000) }
        forwardBtn.setOnClickListener { seekBy(+10_000) }
        prevBtn.setOnClickListener { playPrev() }
        nextBtn.setOnClickListener { playNext() }

        repeatBtn.setOnClickListener {
            repeatMode = when (repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            updateRepeatIcon()
        }

        shuffleBtn.setOnClickListener {
            shuffleOn = !shuffleOn
            updateShuffleIcon()
        }
    }

    /** Start a track; if it was paused previously, resume from that position; if it was stopped, start from 0. */
    private fun startTrackFromLastState(index: Int) {
        if (index !in tracks.indices) return
        currentIndex = index
        lastSelectedIndex = index
        val track = tracks[index]

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, track.uri).apply {
            setOnPreparedListener {
                seekBar.max = duration
                totalTimeTv.text = formatTime(duration.toLong())
                // resume from paused position (if any)
                if (lastPausedPosition > 0 && lastSelectedIndex == index) {
                    seekTo(lastPausedPosition)
                }
                start()
                updatePlayPause(true)
                // clear paused pos once we start
                lastPausedPosition = 0
            }
            setOnCompletionListener { onCompletionAdvance() }
        }

        titleTv.text = track.title
        loadEmbeddedArt(track.uri)
        (trackListRv.adapter as TrackAdapter).setSelected(index)
    }

    private fun stopOrRestart() {
        // If we're armed to restart and nothing is loaded, restart the same track
        if (stopReadyToRestart && mediaPlayer == null && lastStoppedIndex in tracks.indices) {
            startTrackFromLastState(lastStoppedIndex) // from start (lastPausedPosition is 0 after stop)
            stopReadyToRestart = false
            stopBtn.contentDescription = "Stop"
            return
        }
        // Otherwise, stop current playback and arm for restart
        stopPlaybackAndArmRestart()
    }

    private fun stopPlaybackAndArmRestart() {
        if (currentIndex in tracks.indices) {
            lastStoppedIndex = currentIndex
            lastSelectedIndex = currentIndex
        }
        // stopping means next start is from 0, not from paused pos
        lastPausedPosition = 0

        mediaPlayer?.let { if (it.isPlaying) it.stop() }
        mediaPlayer?.release()
        mediaPlayer = null

        seekBar.progress = 0
        currentTimeTv.text = formatTime(0)
        totalTimeTv.text = formatTime(0)
        updatePlayPause(false)

        stopReadyToRestart = true
        stopBtn.contentDescription = "Restart last track"
    }

    private fun updateRepeatIcon() {
        when (repeatMode) {
            RepeatMode.OFF -> { repeatBtn.setImageResource(R.drawable.ic_repeat); repeatBtn.imageAlpha = 120 }
            RepeatMode.ALL -> { repeatBtn.setImageResource(R.drawable.ic_repeat); repeatBtn.imageAlpha = 255 }
            RepeatMode.ONE -> { repeatBtn.setImageResource(R.drawable.ic_repeat_one); repeatBtn.imageAlpha = 255 }
        }
    }

    private fun updateShuffleIcon() {
        // Using Option A: selector handles the art; we only toggle selected state.
        ImageViewCompat.setImageTintList(shuffleBtn, null) // make sure no theme tint
        shuffleBtn.isSelected = shuffleOn
    }

    // ---------- SeekBar ----------
    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                mediaPlayer?.seekTo(sb?.progress ?: 0)
            }
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) currentTimeTv.text = formatTime(p.toLong())
            }
        })
    }

    private fun startSeekbarUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    if (it.isPlaying && !userSeeking) {
                        val pos = it.currentPosition
                        seekBar.progress = pos
                        currentTimeTv.text = formatTime(pos.toLong())
                    }
                }
                handler.postDelayed(this, 250)
            }
        })
    }

    // ---------- Core playback helpers ----------
    private fun playIndex(index: Int) {
        if (index !in tracks.indices) return
        // switching track: clear any paused position
        lastPausedPosition = 0
        stopReadyToRestart = false
        stopBtn.contentDescription = "Stop"

        currentIndex = index
        lastSelectedIndex = index
        val t = tracks[index]
        titleTv.text = t.title

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, t.uri).apply {
            setOnPreparedListener {
                seekBar.max = duration
                totalTimeTv.text = formatTime(duration.toLong())
                start()
                updatePlayPause(true)
            }
            setOnCompletionListener { onCompletionAdvance() }
        }
        loadEmbeddedArt(t.uri)
        (trackListRv.adapter as TrackAdapter).setSelected(index)
    }

    private fun onCompletionAdvance() {
        when (repeatMode) {
            RepeatMode.ONE -> playIndex(currentIndex)
            RepeatMode.ALL -> playIndex((currentIndex + 1) % tracks.size)
            RepeatMode.OFF -> {
                val next = currentIndex + 1
                if (next < tracks.size) playIndex(next) else stopPlaybackSimple()
            }
        }
    }

    private fun playNext() {
        if (tracks.isEmpty()) return
        val next = if (shuffleOn) randomOtherIndex() else {
            val n = currentIndex + 1
            when {
                n < tracks.size -> n
                repeatMode == RepeatMode.ALL -> 0
                else -> { stopPlaybackSimple(); return }
            }
        }
        playIndex(next)
    }

    private fun playPrev() {
        if (tracks.isEmpty()) return
        val prev = if (shuffleOn) randomOtherIndex() else {
            val p = currentIndex - 1
            when {
                p >= 0 -> p
                repeatMode == RepeatMode.ALL -> tracks.lastIndex
                else -> { stopPlaybackSimple(); return }
            }
        }
        playIndex(prev)
    }

    private fun randomOtherIndex(): Int {
        if (tracks.size <= 1) return currentIndex
        var r: Int
        do { r = Random.nextInt(tracks.size) } while (r == currentIndex)
        return r
    }

    private fun seekBy(deltaMs: Int) {
        mediaPlayer?.let {
            val newPos = (it.currentPosition + deltaMs).coerceIn(0, it.duration)
            it.seekTo(newPos)
            seekBar.progress = newPos
            currentTimeTv.text = formatTime(newPos.toLong())
        }
    }

    /** Simple stop without arming a restart (used by completion/prev/next edge cases). */
    private fun stopPlaybackSimple() {
        mediaPlayer?.let { if (it.isPlaying) it.stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        seekBar.progress = 0
        currentTimeTv.text = formatTime(0)
        totalTimeTv.text = formatTime(0)
        updatePlayPause(false)
        // Do not arm restart, leave flags alone
    }

    private fun updatePlayPause(isPlaying: Boolean) {
        playPauseBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun loadEmbeddedArt(uri: Uri) {
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(this, uri)
            val art = mmr.embeddedPicture
            if (art != null) {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                coverImage.setImageBitmap(bmp)
            } else {
                coverImage.setImageResource(R.drawable.ic_waveform)
            }
            mmr.release()
        } catch (_: Exception) {
            coverImage.setImageResource(R.drawable.ic_waveform)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    // ---------- RecyclerView Adapter ----------
    private class TrackAdapter(
        private val items: List<Track>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<TrackAdapter.VH>() {

        private var selected = -1

        fun setSelected(pos: Int) {
            val old = selected
            selected = pos
            if (old != -1) notifyItemChanged(old)
            if (pos != -1) notifyItemChanged(pos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_track, parent, false)
            return VH(v, onClick)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], position == selected)
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View, private val onClick: (Int) -> Unit) :
            RecyclerView.ViewHolder(itemView) {

            private val art: ImageView = itemView.findViewById(R.id.artThumb)
            private val title: TextView = itemView.findViewById(R.id.trackTitle)
            private val artist: TextView = itemView.findViewById(R.id.trackArtist)
            private val duration: TextView = itemView.findViewById(R.id.trackDuration)
            private val card = itemView as com.google.android.material.card.MaterialCardView

            fun bind(t: Track, isSelected: Boolean) {
                title.text = t.title
                artist.text = t.artist
                duration.text = formatTime(t.durationMs)

                // simple placeholder in list to stay fast
                art.setImageResource(R.drawable.ic_waveform)

                // visual selection
                card.strokeWidth = if (isSelected) 3 else 1

                itemView.setOnClickListener { onClick(bindingAdapterPosition) }
            }

            private fun formatTime(ms: Long): String {
                val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
                val min = totalSec / 60
                val sec = totalSec % 60
                return String.format("%02d:%02d", min, sec)
            }
        }
    }
}
