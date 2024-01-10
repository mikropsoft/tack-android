package xyz.zedler.patrick.tack.util;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
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
import xyz.zedler.patrick.tack.Constants.SOUND;
import xyz.zedler.patrick.tack.Constants.TICK_TYPE;
import xyz.zedler.patrick.tack.R;
import xyz.zedler.patrick.tack.util.MetronomeUtil.Tick;

public class AudioUtil {

  private static final String TAG = AudioUtil.class.getSimpleName();
  private static final boolean DEBUG = false;

  public static final int SAMPLE_RATE_IN_HZ = 48000;
  private static final int SILENCE_CHUNK_SIZE = 8000;
  private static final int DATA_CHUNK_SIZE = 8;
  private static final byte[] DATA_MARKER = "data".getBytes(StandardCharsets.US_ASCII);

  private AudioTrack track;
  private LoudnessEnhancer loudnessEnhancer;
  private final Context context;
  private float[] tickStrong, tickNormal, tickSub;
  private int gain;
  private boolean playing, muted;
  private final float[] silence = new float[SILENCE_CHUNK_SIZE];

  public AudioUtil(@NonNull Context context) {
    this.context = context;
  }

  public void play() {
    playing = true;
    track = AudioUtil.getTrack();
    loudnessEnhancer = new LoudnessEnhancer(track.getAudioSessionId());
    loudnessEnhancer.setTargetGain(gain * 100);
    loudnessEnhancer.setEnabled(gain > 0);
    track.play();
  }

  public void stop() {
    playing = false;
    if (track != null) {
      track.flush();
      track.release();
    }
  }

  public void tick(Tick tick, int tempo, int subdivisionCount) {
    writeTickPeriod(tick, tempo, subdivisionCount);
  }

  public void setSound(String sound) {
    int resIdNormal, resIdStrong, resIdSub;
    switch (sound) {
      case SOUND.SINE:
        resIdNormal = R.raw.sine_normal;
        resIdStrong = R.raw.sine_strong;
        resIdSub = R.raw.sine_sub;
        break;
      case SOUND.CLICK:
      case SOUND.DING:
      case SOUND.BEEP:
      default:
        resIdNormal = R.raw.wood_normal;
        resIdStrong = R.raw.wood_strong;
        resIdSub = R.raw.wood_normal;
        break;
    }
    tickNormal = loadAudio(resIdNormal);
    tickStrong = loadAudio(resIdStrong);
    tickSub = loadAudio(resIdSub);
  }

  public void setGain(int gain) {
    this.gain = gain;
    if (loudnessEnhancer != null) {
      loudnessEnhancer.setTargetGain(gain * 100);
      loudnessEnhancer.setEnabled(gain > 0);
    }
  }

  public void setMuted(boolean muted) {
    this.muted = muted;
  }

  private void writeTickPeriod(Tick tick, int tempo, int subdivisionCount) {
    float[] tickSound = muted ? silence : getTickSound(tick.type);
    int periodSize = 60 * SAMPLE_RATE_IN_HZ / tempo / subdivisionCount;
    int sizeWritten = writeNextAudioData(tickSound, periodSize, 0);
    if (DEBUG) {
      Log.v(TAG, "writeTickPeriod: wrote tick sound for " + tick);
    }
    writeSilenceUntilPeriodFinished(sizeWritten, periodSize);
  }

  private void writeSilenceUntilPeriodFinished(int previousSizeWritten, int periodSize) {
    int sizeWritten = previousSizeWritten;
    while (sizeWritten < periodSize) {
      sizeWritten += writeNextAudioData(silence, periodSize, sizeWritten);
      if (DEBUG) {
        Log.v(TAG, "writeSilenceUntilPeriodFinished: wrote silence");
      }
    }
  }

  private int writeNextAudioData(float[] data, int periodSize, int sizeWritten) {
    int size = Math.min(data.length, periodSize - sizeWritten);
    if (playing) {
      AudioUtil.writeAudio(track, data, size);
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
    AudioAttributes audioAttributes = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build();
    AudioFormat audioFormat = new AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(SAMPLE_RATE_IN_HZ)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build();
    return new AudioTrack(
        audioAttributes,
        audioFormat,
        AudioTrack.getMinBufferSize(
            audioFormat.getSampleRate(), audioFormat.getChannelMask(), audioFormat.getEncoding()
        ),
        AudioTrack.MODE_STREAM,
        AudioManager.AUDIO_SESSION_ID_GENERATE
    );
  }

  private float[] loadAudio(@RawRes int resId) {
    try (InputStream stream = context.getResources().openRawResource(resId)) {
      return readDataFromWavFloat(stream);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void writeAudio(AudioTrack track, float[] data, int size) {
    int result = track.write(data, 0, size, AudioTrack.WRITE_BLOCKING);
    if (result < 0) {
      throw new IllegalStateException("Failed to play audio data. Error code: " + result);
    }
  }

  private static float[] readDataFromWavFloat(InputStream input) throws IOException {
    byte[] content;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
}