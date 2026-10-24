package ru.giga.dictaphone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Диктофон для занятий: одна кнопка ведёт запись → стоп → прослушивание.
 * Запись живёт во временном файле и пропадает при новой записи; сохранить можно отдельной кнопкой.
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 41;
    private static final long TICK_MS = 60L;

    private enum Mode { IDLE, RECORDING, READY, PLAYING }

    private ImageButton action;
    private Button save;
    private Button newRecord;
    private View secondary;
    private TextView timer;
    private TextView status;
    private TextView hint;
    private SeekBar seek;
    private WaveformView wave;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaRecorder recorder;
    private MediaPlayer player;
    private File tempFile;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean noisyRegistered;

    private Mode mode = Mode.IDLE;
    private long recordStart;
    private int durationMs;
    private boolean dragging;
    private boolean paused;
    private boolean resumeAfterDrag;
    private boolean saving;
    private boolean playerPrepared;

    private final Runnable recordTick = new Runnable() {
        @Override
        public void run() {
            if (mode != Mode.RECORDING) {
                return;
            }
            timer.setText(format(SystemClock.elapsedRealtime() - recordStart));
            int amplitude = 0;
            try {
                if (recorder != null) {
                    amplitude = recorder.getMaxAmplitude();
                }
            } catch (RuntimeException ignored) {
                // запись уже остановлена
            }
            wave.addLevel(amplitude / 32767f);
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable playTick = new Runnable() {
        @Override
        public void run() {
            if (mode != Mode.PLAYING || player == null || dragging) {
                return;
            }
            int position = player.getCurrentPosition();
            timer.setText(format(position));
            seek.setProgress(position);
            wave.setProgress(durationMs > 0 ? position / (float) durationMs : 0f);
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()) && mode == Mode.PLAYING) {
                pausePlayback();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applySystemBarInsets();

        action = findViewById(R.id.action);
        save = findViewById(R.id.save);
        newRecord = findViewById(R.id.new_record);
        secondary = findViewById(R.id.secondary);
        timer = findViewById(R.id.timer);
        status = findViewById(R.id.status);
        hint = findViewById(R.id.hint);
        seek = findViewById(R.id.seek);
        wave = findViewById(R.id.wave);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        action.setOnClickListener(v -> onAction());
        save.setOnClickListener(v -> saveRecording());
        newRecord.setOnClickListener(v -> startRecording());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                timer.setText(format(progress));
                wave.setProgress(durationMs > 0 ? progress / (float) durationMs : 0f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
                resumeAfterDrag = mode == Mode.PLAYING;
                if (resumeAfterDrag && player != null) {
                    player.pause();
                    handler.removeCallbacks(playTick);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (player != null && durationMs > 0) {
                    player.seekTo(Math.min(bar.getProgress(), durationMs));
                }
                if (!resumeAfterDrag) {
                    return;
                }
                if (player != null && playerPrepared && mode == Mode.PLAYING) {
                    try {
                        player.start();
                        handler.post(playTick);
                    } catch (IllegalStateException e) {
                    }
                } else if (mode != Mode.PLAYING) {
                    resumePlayback();
                }
            }
        });

        applyState(Mode.IDLE);
    }

    /**
     * Контент идёт под системными панелями (edge-to-edge), поэтому добавляем их отступы.
     */
    private void applySystemBarInsets() {
        View root = findViewById(R.id.root);
        View content = findViewById(R.id.content);
        final int start = content.getPaddingLeft();
        final int top = content.getPaddingTop();
        final int end = content.getPaddingRight();
        final int bottom = content.getPaddingBottom();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int topInset;
            int right;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                topInset = bars.top;
                right = bars.right;
                bottomInset = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                topInset = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(start + left, top + topInset, end + right, bottom + bottomInset);
            return insets;
        });
    }

    private void onAction() {
        switch (mode) {
            case IDLE:
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    askForMic();
                } else {
                    startRecording();
                }
                break;
            case RECORDING:
                stopRecording();
                break;
            case READY:
                startPlayback();
                break;
            case PLAYING:
                pausePlayback();
                break;
        }
    }

    // ---------------------------------------------------------------- запись

    private void startRecording() {
        discardPlayer();
        if (tempFile != null && tempFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
        wave.clear();
        tempFile = new File(getCacheDir(), "session.m4a");

        try {
            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(this)
                    : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(tempFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (IOException | RuntimeException e) {
            releaseRecorder();
            tempFile = null;
            Toast.makeText(this, R.string.record_error, Toast.LENGTH_SHORT).show();
            applyState(Mode.IDLE);
            return;
        }

        recordStart = SystemClock.elapsedRealtime();
        durationMs = 0;
        paused = false;
        seek.setProgress(0);
        applyState(Mode.RECORDING);
        hint.setVisibility(View.GONE);
        handler.post(recordTick);
    }

    private void stopRecording() {
        handler.removeCallbacks(recordTick);
        long elapsed = SystemClock.elapsedRealtime() - recordStart;
        boolean ok = true;
        try {
            if (recorder != null) {
                recorder.stop();
            }
        } catch (RuntimeException e) {
            ok = false;
        }
        releaseRecorder();

        boolean tooShort = tempFile == null || !tempFile.exists() || tempFile.length() < 1024 || elapsed < 400;
        if (!ok || tooShort) {
            if (tempFile != null) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
            tempFile = null;
            timer.setText(R.string.timer_zero);
            applyState(Mode.IDLE);
            return;
        }

        paused = false;
        applyState(Mode.READY);
        timer.setText(format(elapsed));
        preparePlayer(false);
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.reset();
            } catch (RuntimeException ignored) {
                // уже сброшен
            }
            recorder.release();
            recorder = null;
        }
    }

    // ---------------------------------------------------------- воспроизведение

    private void preparePlayer(boolean autoPlay) {
        if (tempFile == null || !tempFile.exists()) {
            return;
        }
        discardPlayer();

        playerPrepared = false;
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        try {
            player.setDataSource(tempFile.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                playerPrepared = true;
                durationMs = mp.getDuration();
                seek.setMax(Math.max(1, durationMs));
                seek.setProgress(0);
                seek.setEnabled(true);
                if (autoPlay) {
                    startPlayback();
                }
            });
            player.setOnCompletionListener(mp -> onPlaybackEnded());
            player.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show();
                applyState(Mode.READY);
                return true;
            });
            player.prepareAsync();
        } catch (IOException e) {
            discardPlayer();
            Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void startPlayback() {
        if (player == null || !playerPrepared) {
            preparePlayer(true);
            return;
        }
        if (!requestFocus()) {
            Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            player.start();
        } catch (IllegalStateException e) {
            preparePlayer(true);
            return;
        }
        applyState(Mode.PLAYING);
        handler.post(playTick);
    }

    private void resumePlayback() {
        if (player == null || !playerPrepared || mode == Mode.PLAYING) {
            return;
        }
        if (player.getCurrentPosition() >= durationMs) {
            seek.setProgress(0);
        }
        if (requestFocus()) {
            try {
                player.start();
            } catch (IllegalStateException e) {
                preparePlayer(true);
                return;
            }
            applyState(Mode.PLAYING);
            handler.post(playTick);
        }
    }

    private void pausePlayback() {
        handler.removeCallbacks(playTick);
        paused = true;
        if (player != null && player.isPlaying()) {
            player.pause();
        }
        applyState(Mode.READY);
        if (durationMs > 0) {
            timer.setText(format(player != null ? player.getCurrentPosition() : 0));
        }
        abandonFocus();
    }

    private void onPlaybackEnded() {
        handler.removeCallbacks(playTick);
        abandonFocus();
        paused = false;
        seek.setProgress(0);
        wave.setProgress(0f);
        timer.setText(R.string.timer_zero);
        applyState(Mode.READY);
    }

    private void discardPlayer() {
        handler.removeCallbacks(playTick);
        playerPrepared = false;
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                }
            } catch (IllegalStateException ignored) {
                // уже остановлен
            }
            player.release();
            player = null;
        }
        abandonFocus();
    }

    // ------------------------------------------------------------------ фокус

    private boolean requestFocus() {
        if (audioManager == null) {
            return true;
        }
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS
                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            if (mode == Mode.PLAYING) {
                                runOnUiThread(this::pausePlayback);
                            }
                        }
                    })
                    .build();
        }
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    // ---------------------------------------------------------------- сохранение

    private void saveRecording() {
        if (saving || tempFile == null || !tempFile.exists()) {
            return;
        }
        saving = true;
        save.setEnabled(false);
        newRecord.setEnabled(false);
        save.setText(R.string.status_saving);
        final File source = tempFile;
        new Thread(() -> {
            boolean ok = copyToMusic(source);
            runOnUiThread(() -> {
                saving = false;
                save.setEnabled(true);
                newRecord.setEnabled(true);
                save.setText(R.string.btn_save);
                Toast.makeText(this, ok ? R.string.saved_to : R.string.save_failed, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private boolean copyToMusic(File source) {
        ContentResolver resolver = getContentResolver();
        String name = "Запись " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(new Date()) + ".m4a";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Диктофон");
        values.put(MediaStore.Audio.Media.IS_MUSIC, 1);
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return false;
            }
            OutputStream stream = resolver.openOutputStream(uri);
            if (stream == null) {
                resolver.delete(uri, null, null);
                return false;
            }
            try (InputStream in = new FileInputStream(source); OutputStream out = stream) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return true;
        } catch (IOException | RuntimeException e) {
            if (uri != null) {
                resolver.delete(uri, null, null);
            }
            return false;
        }
    }

    // -------------------------------------------------------------- разрешение

    private void askForMic() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_needed)
                    .setMessage(R.string.permission_rationale)
                    .setPositiveButton(android.R.string.ok, (d, w) ->
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
            return;
        }
        Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show();
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_needed)
                    .setMessage(R.string.permission_rationale)
                    .setPositiveButton(R.string.permission_settings, (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton(R.string.permission_cancel, null)
                    .show();
        }
    }

    // ------------------------------------------------------------------ UI

    private void applyState(Mode next) {
        mode = next;
        switch (next) {
            case IDLE:
                setActionIcon(R.drawable.ic_mic, R.color.record, R.string.btn_record);
                status.setText(R.string.status_idle);
                status.setTextColor(getColor(R.color.text_secondary));
                seek.setVisibility(View.INVISIBLE);
                secondary.setVisibility(View.INVISIBLE);
                timer.setTextColor(getColor(R.color.text_primary));
                timer.setText(R.string.timer_zero);
                wave.setLive(false);
                wave.clear();
                seek.setProgress(0);
                break;
            case RECORDING:
                setActionIcon(R.drawable.ic_stop, R.color.stop, R.string.btn_stop);
                status.setText(R.string.status_recording);
                status.setTextColor(getColor(R.color.record));
                seek.setVisibility(View.INVISIBLE);
                secondary.setVisibility(View.INVISIBLE);
                timer.setTextColor(getColor(R.color.text_primary));
                timer.setText(R.string.timer_zero);
                wave.setLive(true);
                break;
            case READY:
                setActionIcon(R.drawable.ic_play, R.color.play, R.string.btn_play);
                status.setText(paused ? R.string.status_paused : R.string.status_ready);
                status.setTextColor(getColor(R.color.text_secondary));
                seek.setVisibility(View.VISIBLE);
                seek.setEnabled(true);
                secondary.setVisibility(View.VISIBLE);
                timer.setTextColor(getColor(R.color.text_primary));
                wave.setLive(false);
                break;
            case PLAYING:
                setActionIcon(R.drawable.ic_pause, R.color.accent, R.string.btn_pause);
                status.setText(R.string.status_playing);
                status.setTextColor(getColor(R.color.play));
                seek.setVisibility(View.VISIBLE);
                secondary.setVisibility(View.VISIBLE);
                timer.setTextColor(getColor(R.color.text_primary));
                wave.setLive(false);
                break;
        }
    }

    private void setActionIcon(int icon, int colorRes, int descriptionRes) {
        action.setImageResource(icon);
        action.setBackgroundTintList(ColorStateList.valueOf(getColor(colorRes)));
        action.setContentDescription(getString(descriptionRes));
    }

    private static String format(long millis) {
        long tenths = Math.max(0, millis) / 100;
        long minutes = tenths / 600;
        long seconds = (tenths / 10) % 60;
        long fraction = tenths % 10;
        return String.format(Locale.US, "%02d:%02d,%d", minutes, seconds, fraction);
    }

    // ------------------------------------------------------------- жизненный цикл

    @Override
    protected void onStart() {
        super.onStart();
        if (!noisyRegistered) {
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mode == Mode.PLAYING) {
            pausePlayback();
        }
        if (noisyRegistered) {
            unregisterReceiver(noisyReceiver);
            noisyRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (mode == Mode.RECORDING && recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
                // слишком короткая запись
            }
            releaseRecorder();
        }
        discardPlayer();
        if (tempFile != null) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
        super.onDestroy();
    }
}
