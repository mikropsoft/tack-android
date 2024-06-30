/*
 * This file is part of Tack Android.
 *
 * Tack Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Tack Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Tack Android. If not, see http://www.gnu.org/licenses/.
 *
 * Copyright (c) 2020-2024 by Patrick Zedler
 */

package xyz.zedler.patrick.tack.util;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.AudioTrack;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.RawRes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import xyz.zedler.patrick.tack.Constants.SOUND;
import xyz.zedler.patrick.tack.Constants.TICK_TYPE;
import xyz.zedler.patrick.tack.R;
import xyz.zedler.patrick.tack.util.MetronomeUtil.Tick;

public class AudioUtilNew implements OnAudioFocusChangeListener {

  private static final String TAG = AudioUtilNew.class.getSimpleName();
  private static final boolean DEBUG = false;

  public static final int SAMPLE_RATE_IN_HZ = 48000;
  private static final int SILENCE_CHUNK_SIZE = 8000;
  private static final int DATA_CHUNK_SIZE = 8;
  private static final byte[] DATA_MARKER = "data".getBytes(StandardCharsets.US_ASCII);

  private final Context context;
  private final AudioManager audioManager;
  private final AudioListener listener;
  private final BlockingQueue<Runnable> queueTrack = new LinkedBlockingQueue<>();
  private final BlockingQueue<Runnable> queueTrackLong = new LinkedBlockingQueue<>();
  private Thread threadTrack, threadTrackLong;
  private AudioTrack track, trackLong;
  private LoudnessEnhancer loudnessEnhancer, loudnessEnhancerLong;
  private float[] tickStrong, tickNormal, tickSub;
  private int gain;
  private boolean playing, muted, ignoreFocus, isStrongLong;
  private final float[] silence = new float[SILENCE_CHUNK_SIZE];

  public AudioUtilNew(@NonNull Context context, @NonNull AudioListener listener) {
    this.context = context;
    this.listener = listener;
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    resetTracks();
  }

  public void play() {
    playing = true;

    track = getTrack();
    loudnessEnhancer = new LoudnessEnhancer(track.getAudioSessionId());
    loudnessEnhancer.setTargetGain(gain * 100);
    loudnessEnhancer.setEnabled(gain > 0);
    track.play();

    trackLong = getTrack();
    loudnessEnhancerLong = new LoudnessEnhancer(trackLong.getAudioSessionId());
    loudnessEnhancerLong.setTargetGain(gain * 100);
    loudnessEnhancerLong.setEnabled(gain > 0);
    trackLong.play();

    if (ignoreFocus) {
      return;
    }
    if (VERSION.SDK_INT >= VERSION_CODES.O) {
      AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(getAttributes())
          .setWillPauseWhenDucked(true)
          .setOnAudioFocusChangeListener(this)
          .build();
      audioManager.requestAudioFocus(request);
    } else {
      audioManager.requestAudioFocus(
          this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
      );
    }
  }

  public void stop() {
    playing = false;
    if (track != null) {
      if (track.getState() == AudioTrack.STATE_INITIALIZED) {
        track.stop();
      }
      track.flush();
      track.release();
    }
    if (trackLong != null) {
      if (trackLong.getState() == AudioTrack.STATE_INITIALIZED) {
        trackLong.stop();
      }
      trackLong.flush();
      trackLong.release();
    }
    if (!ignoreFocus) {
      audioManager.abandonAudioFocus(this);
    }
  }

  public void resetTracks() {
    if (threadTrack == null) {
      threadTrack = new Thread(() -> {
        try {
          while (true) {
            Runnable task = queueTrack.take();
            queueTrack.clear();
            task.run();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          Log.w(TAG, "resetTracks: ", e);
        }
      });
      threadTrack.start();
    }

    if (threadTrackLong != null && !threadTrackLong.isInterrupted()) {
      threadTrackLong.interrupt();
    }
    threadTrackLong = new Thread(() -> {
      try {
        while (true) {
          Runnable task = queueTrackLong.take();
          task.run();
        }
      } catch (Exception e) {
        Thread.currentThread().interrupt();
        Log.w(TAG, "resetTracks: ", e);
      }
    });
    threadTrackLong.start();
  }

  @Override
  public void onAudioFocusChange(int focusChange) {
    if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
      if (track != null) {
        track.setVolume(1);
      }
      if (trackLong != null) {
        trackLong.setVolume(1);
      }
    } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
      stop();
      listener.onAudioStop();
    } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
      if (track != null) {
        track.setVolume(0.25f);
      }
      if (trackLong != null) {
        trackLong.setVolume(0.25f);
      }
    }
  }

  public void tick(Tick tick, int tempo, int subdivisionCount, String[] beats) {
    writeTickPeriod(tick, tempo, subdivisionCount, beats);
  }

  public void setSound(String sound) {
    int resIdNormal, resIdStrong, resIdSub;
    Pitch pitchNormal = Pitch.NORMAL;
    Pitch pitchStrong = Pitch.HIGH;
    Pitch pitchSub = Pitch.LOW;
    isStrongLong = false;
    switch (sound) {
      case SOUND.WOOD:
        resIdNormal = R.raw.wood;
        resIdStrong = R.raw.wood;
        resIdSub = R.raw.wood;
        break;
      case SOUND.MECHANICAL:
        resIdNormal = R.raw.mechanical_tick;
        resIdStrong = R.raw.mechanical_ding;
        resIdSub = R.raw.mechanical_knock;
        pitchStrong = Pitch.NORMAL;
        pitchSub = Pitch.NORMAL;
        isStrongLong = true;
        break;
      case SOUND.BEATBOXING_1:
        resIdNormal = R.raw.beatbox_snare1;
        resIdStrong = R.raw.beatbox_kick1;
        resIdSub = R.raw.beatbox_hihat1;
        pitchStrong = Pitch.NORMAL;
        pitchSub = Pitch.NORMAL;
        break;
      case SOUND.BEATBOXING_2:
        resIdNormal = R.raw.beatbox_snare2;
        resIdStrong = R.raw.beatbox_kick2;
        resIdSub = R.raw.beatbox_hihat2;
        pitchStrong = Pitch.NORMAL;
        pitchSub = Pitch.NORMAL;
        break;
      case SOUND.HANDS:
        resIdNormal = R.raw.hands_hit;
        resIdStrong = R.raw.hands_clap;
        resIdSub = R.raw.hands_snap;
        pitchStrong = Pitch.NORMAL;
        pitchSub = Pitch.NORMAL;
        break;
      case SOUND.FOLDING:
        resIdNormal = R.raw.folding_knock;
        resIdStrong = R.raw.folding_fold;
        resIdSub = R.raw.folding_tap;
        pitchStrong = Pitch.NORMAL;
        pitchSub = Pitch.NORMAL;
        break;
      default:
        resIdNormal = R.raw.sine;
        resIdStrong = R.raw.sine;
        resIdSub = R.raw.sine;
        break;
    }
    tickNormal = loadAudio(resIdNormal, pitchNormal);
    tickStrong = loadAudio(resIdStrong, pitchStrong);
    tickSub = loadAudio(resIdSub, pitchSub);
  }

  public void setGain(int gain) {
    this.gain = gain;
    if (loudnessEnhancer != null) {
      loudnessEnhancer.setTargetGain(gain * 100);
      loudnessEnhancer.setEnabled(gain > 0);
    }
    if (loudnessEnhancerLong != null) {
      loudnessEnhancerLong.setTargetGain(gain * 100);
      loudnessEnhancerLong.setEnabled(gain > 0);
    }
  }

  public int getGain() {
    return gain;
  }

  public void setMuted(boolean muted) {
    this.muted = muted;
  }

  public void setIgnoreFocus(boolean ignore) {
    ignoreFocus = ignore;
  }

  public boolean getIgnoreFocus() {
    return ignoreFocus;
  }

  private void writeTickPeriod(Tick tick, int tempo, int subdivisionCount, String[] beats) {
    float[] tickSound = muted ? silence : getTickSound(tick.type);
    int periodSize = 60 * SAMPLE_RATE_IN_HZ / tempo / subdivisionCount;
    int beatsToNextStrong = getBeatsToNextStrong(beats, tick.beat - 1);
    int periodSizeLong = 60 * SAMPLE_RATE_IN_HZ / tempo * beatsToNextStrong;
    boolean startsWithStrong = beats[0].equals(TICK_TYPE.STRONG);
    if (isStrongLong && beatsToNextStrong > 0) {
      if (tick.type.equals(TICK_TYPE.STRONG)) {
        Runnable trackLongTask = () -> {
          int sizeWritten = writeNextAudioData(tickSound, periodSizeLong, 0, trackLong);
          writeSilenceUntilPeriodFinished(sizeWritten, periodSizeLong, trackLong);
        };
        Runnable trackTask = () -> writeSilenceUntilPeriodFinished(
            0, periodSize, track
        );
        try {
          queueTrack.put(trackTask);
          queueTrackLong.put(trackLongTask);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          Log.e(TAG, "writeTickPeriod: ", e);
        }
      } else {
        Runnable trackLongTask = () -> writeSilenceUntilPeriodFinished(
            0, periodSizeLong, trackLong
        );
        Runnable trackTask = () -> {
          int sizeWritten = writeNextAudioData(tickSound, periodSize, 0, track);
          writeSilenceUntilPeriodFinished(sizeWritten, periodSize, track);
        };
        try {
          if (!startsWithStrong) {
            // Fill track with silence until first strong beat
            queueTrackLong.put(trackLongTask);
          }
          queueTrack.put(trackTask);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          Log.e(TAG, "writeTickPeriod: ", e);
        }
      }
    } else {
      Runnable trackTask = () -> {
        int sizeWritten = writeNextAudioData(tickSound, periodSize, 0, track);
        writeSilenceUntilPeriodFinished(sizeWritten, periodSize, track);
      };
      Runnable trackLongTask = () -> {
        writeSilenceUntilPeriodFinished(0, periodSize, trackLong);
      };
      try {
        queueTrack.put(trackTask);
        queueTrackLong.put(trackLongTask);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        Log.e(TAG, "writeTickPeriod: ", e);
      }
    }
    if (DEBUG) {
      Log.v(TAG, "writeTickPeriod: wrote tick sound for " + tick);
    }
  }

  private void writeSilenceUntilPeriodFinished(
      int previousSizeWritten, int periodSize, AudioTrack track
  ) {
    int sizeWritten = previousSizeWritten;
    while (sizeWritten < periodSize) {
      sizeWritten += writeNextAudioData(silence, periodSize, sizeWritten, track);
      if (DEBUG) {
        Log.v(TAG, "writeSilenceUntilPeriodFinished: wrote silence");
      }
    }
  }

  private int writeNextAudioData(float[] data, int periodSize, int sizeWritten, AudioTrack track) {
    int size = Math.min(data.length, periodSize - sizeWritten);
    if (playing) {
      writeAudio(track, data, size);
    }
    return size;
  }

  private float[] getTickSound(String tickType) {
    switch (tickType) {
      case TICK_TYPE.STRONG:
        return tickStrong;
      case TICK_TYPE.SUB:
        return tickSub;
      case TICK_TYPE.MUTED:
        return silence;
      default:
        return tickNormal;
    }
  }

  private static AudioTrack getTrack() {
    AudioFormat audioFormat = new AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(SAMPLE_RATE_IN_HZ)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build();
    return new AudioTrack(
        getAttributes(),
        audioFormat,
        AudioTrack.getMinBufferSize(
            audioFormat.getSampleRate(), audioFormat.getChannelMask(), audioFormat.getEncoding()
        ),
        AudioTrack.MODE_STREAM,
        AudioManager.AUDIO_SESSION_ID_GENERATE
    );
  }

  private static AudioAttributes getAttributes() {
    return new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build();
  }

  private float[] loadAudio(@RawRes int resId, Pitch pitch) {
    try (InputStream stream = context.getResources().openRawResource(resId)) {
      return adjustPitch(readDataFromWavFloat(stream), pitch);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private float[] adjustPitch(float[] originalData, Pitch pitch) {
    if (pitch == Pitch.HIGH) {
      float[] newData = new float[originalData.length / 2];
      for (int i = 0; i < newData.length; i++) {
        newData[i] = originalData[i * 2];
      }
      return newData;
    } else if (pitch == Pitch.LOW) {
      float[] newData = new float[originalData.length * 2];
      for (int i = 0, j = 0; i < originalData.length; i++, j += 2) {
        newData[j] = originalData[i];
        newData[j + 1] = originalData[i];
      }
      return newData;
    } else {
      return originalData;
    }
  }

  private static void writeAudio(AudioTrack track, float[] data, int size) {
    int offset = 0;
    try {
      while (offset < size && !Thread.currentThread().isInterrupted()) {
        int result = track.write(data, offset, 1, AudioTrack.WRITE_BLOCKING);
        if (result < 0) {
          throw new IllegalStateException("Failed to play audio data. Error code: " + result);
        }
        offset += 1;
      }
    } catch (Exception e) {
      Log.w(TAG, "writeAudio: ", e);
    }
  }

  private static float[] readDataFromWavFloat(InputStream input) throws IOException {
    byte[] content;
    if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
      content = input.readAllBytes();
    } else {
      content = readInputStreamToBytes(input);
    }
    int indexOfDataMarker = getIndexOfDataMarker(content);
    if (indexOfDataMarker < 0) {
      throw new RuntimeException("Could not find data marker in the content");
    }
    int startOfSound = indexOfDataMarker + DATA_CHUNK_SIZE;
    if (startOfSound > content.length) {
      throw new RuntimeException("Too short data chunk");
    }
    ByteBuffer byteBuffer = ByteBuffer.wrap(
        content, startOfSound, content.length - startOfSound
    );
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
    FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
    float[] data = new float[floatBuffer.remaining()];
    floatBuffer.get(data);
    return data;
  }

  private static byte[] readInputStreamToBytes(InputStream input) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int read;
    byte[] data = new byte[4096];
    while ((read = input.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, read);
    }
    return buffer.toByteArray();
  }

  private static int getIndexOfDataMarker(byte[] array) {
    if (DATA_MARKER.length == 0) {
      return 0;
    }
    outer:
    for (int i = 0; i < array.length - DATA_MARKER.length + 1; i++) {
      for (int j = 0; j < DATA_MARKER.length; j++) {
        if (array[i + j] != DATA_MARKER[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static int getBeatsToNextStrong(String[] beats, int index) {
    boolean hasStrong = false;
    for (String beat : beats) {
      if (Objects.equals(beat, TICK_TYPE.STRONG)) {
        hasStrong = true;
        break;
      }
    }
    if (hasStrong) {
      int steps = 0;
      for (int i = 0; i < beats.length; i++) {
        int currentIndex = (index + i + 1) % beats.length;
        steps++;
        if (Objects.equals(beats[currentIndex], TICK_TYPE.STRONG)) {
          return steps;
        }
      }
    }
    return 0;
  }

  private enum Pitch {
    NORMAL, HIGH, LOW
  }

  public interface AudioListener {
    void onAudioStop();
  }
}