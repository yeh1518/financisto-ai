package tw.tib.financisto.ai;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AudioRecord → WAV（16kHz / 單聲道 / 16-bit PCM）。
 *
 * 為什麼自己寫 WAV 而不用 MediaRecorder 出 m4a：WAV 是三家 STT（Groq/OpenAI whisper 系、
 * Gemini inline audio）都白紙黑字支援的格式，無編碼器/容器相容疑慮；記帳語句都在一分鐘內，
 * 未壓縮的體積（32KB/s）完全可接受。16kHz 也正是語音模型的原生取樣率。
 *
 * 生命週期：start() 起背景執行緒邊錄邊寫檔（header 先佔位、stop 時回填長度），
 * stop() 停錄並回傳完成的檔案；cancel() 停錄並刪檔。
 * getAmplitude() 給 UI 畫音量條（最近一塊 buffer 的峰值 0~32767）。
 */
public class WavAudioRecorder {

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final File outFile;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private Thread writerThread;
    private volatile int lastAmplitude = 0;
    private volatile IOException writeError = null;

    public WavAudioRecorder(File outFile) {
        this.outFile = outFile;
    }

    /** 需已取得 RECORD_AUDIO 權限（呼叫端把關）。 */
    @SuppressLint("MissingPermission")
    public void start() throws IOException {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (minBuf <= 0) throw new IOException("裝置不支援 16kHz 錄音");
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, ENCODING, Math.max(minBuf * 2, 8192));
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            throw new IOException("麥克風初始化失敗");
        }

        final RandomAccessFile raf = new RandomAccessFile(outFile, "rw");
        raf.setLength(0);
        raf.write(new byte[44]);        // WAV header 佔位，stop 時回填

        recording.set(true);
        audioRecord.startRecording();
        writerThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                while (recording.get()) {
                    int n = audioRecord.read(buf, 0, buf.length);
                    if (n > 0) {
                        raf.write(buf, 0, n);
                        updateAmplitude(buf, n);
                    }
                }
            } catch (IOException e) {
                writeError = e;
                recording.set(false);
            } finally {
                try {
                    finishWavHeader(raf);
                } catch (IOException e) {
                    if (writeError == null) writeError = e;
                }
                try { raf.close(); } catch (IOException ignored) {}
            }
        }, "wav-recorder");
        writerThread.start();
    }

    private void updateAmplitude(byte[] buf, int n) {
        int peak = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
            int a = Math.abs(s);
            if (a > peak) peak = a;
        }
        lastAmplitude = peak;
    }

    /** 最近一塊音訊的峰值（0~32767），UI 畫音量用。 */
    public int getAmplitude() {
        return lastAmplitude;
    }

    /** 停止並完成 WAV 檔。回傳完成檔；錄寫過程有錯就丟出。 */
    public File stop() throws IOException {
        stopInternal();
        if (writeError != null) throw writeError;
        if (outFile.length() <= 44) throw new IOException("沒錄到聲音");
        return outFile;
    }

    /** 取消：停止並刪除半成品。 */
    public void cancel() {
        try {
            stopInternal();
        } catch (IOException ignored) {}
        outFile.delete();
    }

    private void stopInternal() throws IOException {
        recording.set(false);
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (IllegalStateException ignored) {}
        }
        if (writerThread != null) {
            try {
                writerThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writerThread = null;
        }
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
    }

    /** 回填 RIFF/WAVE header（PCM 16-bit mono 16kHz）。 */
    private void finishWavHeader(RandomAccessFile raf) throws IOException {
        long dataLen = raf.length() - 44;
        int byteRate = SAMPLE_RATE * 2;   // mono 16-bit
        raf.seek(0);
        raf.write(new byte[]{'R', 'I', 'F', 'F'});
        writeIntLE(raf, (int) (36 + dataLen));
        raf.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        writeIntLE(raf, 16);              // fmt chunk size
        writeShortLE(raf, (short) 1);     // PCM
        writeShortLE(raf, (short) 1);     // mono
        writeIntLE(raf, SAMPLE_RATE);
        writeIntLE(raf, byteRate);
        writeShortLE(raf, (short) 2);     // block align
        writeShortLE(raf, (short) 16);    // bits per sample
        raf.write(new byte[]{'d', 'a', 't', 'a'});
        writeIntLE(raf, (int) dataLen);
    }

    private static void writeIntLE(RandomAccessFile raf, int v) throws IOException {
        raf.write(new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)});
    }

    private static void writeShortLE(RandomAccessFile raf, short v) throws IOException {
        raf.write(new byte[]{(byte) v, (byte) (v >> 8)});
    }
}
