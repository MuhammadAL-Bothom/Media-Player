package com.example.media2

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var playButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalDurationTextView: TextView
    private lateinit var soundButton1: ImageButton
    private lateinit var soundButton2: ImageButton
    private lateinit var soundButton3: ImageButton
    private lateinit var soundButton4: ImageButton
    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var currentSoundResource = R.raw.sound

    private val soundResources = arrayOf(
        R.raw.sound,
        R.raw.sound2,
        R.raw.sound3,
        R.raw.sound4
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        playButton = findViewById(R.id.playButton)
        pauseButton = findViewById(R.id.pauseButton)
        stopButton = findViewById(R.id.stopButton)
        seekBar = findViewById(R.id.seekBar)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        totalDurationTextView = findViewById(R.id.totalDurationTextView)
        soundButton1 = findViewById(R.id.soundButton1)
        soundButton2 = findViewById(R.id.soundButton2)
        soundButton3 = findViewById(R.id.soundButton3)
        soundButton4 = findViewById(R.id.soundButton4)

        // Initialize MediaPlayer
        loadSound(currentSoundResource)

        setupButtonClickListeners()
        setupSoundButtonClickListeners()
        setupSeekBar()
        updateDurationTextView()
        updateSeekBarProgress()
    }

    private fun setupSoundButtonClickListeners() {
        soundButton1.setOnClickListener {
            loadSound(soundResources[0])
            currentSoundResource = soundResources[0]
        }
        soundButton2.setOnClickListener {
            loadSound(soundResources[1])
            currentSoundResource = soundResources[1]
        }
        soundButton3.setOnClickListener {
            loadSound(soundResources[2])
            currentSoundResource = soundResources[2]
        }
        soundButton4.setOnClickListener {
            loadSound(soundResources[3])
            currentSoundResource = soundResources[3]
        }
    }

    private fun loadSound(resourceId: Int) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, resourceId)

        mediaPlayer?.setOnPreparedListener {
            seekBar.max = mediaPlayer?.duration ?: 0
            updateDurationTextView()
        }
    }

    private fun setupButtonClickListeners() {
        playButton.setOnClickListener {
            mediaPlayer?.start()
        }

        pauseButton.setOnClickListener {
            mediaPlayer?.pause()
        }

        stopButton.setOnClickListener {
            mediaPlayer?.stop()
            mediaPlayer?.prepare()
            mediaPlayer?.seekTo(0)
            updateSeekBarProgress()
            currentTimeTextView.text = formatTime(0)
        }
    }

    private fun setupSeekBar() {
        mediaPlayer?.setOnPreparedListener {
            seekBar.max = mediaPlayer?.duration ?: 0
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                    currentTimeTextView.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
            }
        })
    }

    private fun updateDurationTextView() {
        val totalDuration = mediaPlayer?.duration ?: 0
        totalDurationTextView.text = formatTime(totalDuration.toLong())
    }

    private fun updateSeekBarProgress() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (mediaPlayer?.isPlaying == true && !isUserSeeking) {
                    val currentPosition = mediaPlayer?.currentPosition ?: 0
                    seekBar.progress = currentPosition
                    currentTimeTextView.text = formatTime(currentPosition.toLong())
                }
                handler.postDelayed(this, 1000)
            }
        }, 0)
    }

    private fun formatTime(milliseconds: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % TimeUnit.HOURS.toMinutes(1)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % TimeUnit.MINUTES.toSeconds(1)
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        handler.removeCallbacksAndMessages(null)
    }
}
