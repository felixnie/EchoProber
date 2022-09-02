package com.felixnie.echoprober;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by _SOLID
 * Date:    2017/5/18
 * Modified by Hongtuo
 * Date:    2022/7/8
 */

public class AudioRecordHelper {

    private final static int BUFFER_SIZE_16BIT_MONO = 4800 * 2;
    private final static int BUFFER_SIZE_16BIT_STEREO = 4800 * 4;
    private final static int sampleRate = 44100;
    private volatile boolean isRecording = false;
    private volatile boolean isMono = false; // the default radio button is Stereo recorder
    private byte[] mBufferMono = new byte[BUFFER_SIZE_16BIT_MONO];
    private byte[] mBufferStereo = new byte[BUFFER_SIZE_16BIT_STEREO];
    private File mAudioFile;
    private FileOutputStream mFileOutputStream;
    private AudioRecord mAudioRecord;
    private final ExecutorService mExecutorService;
    private final Handler mMainHandler;
    private int total_bytes;
    private int bufferSize;
    private boolean isOffline = true;

    OutputStream outputStream;

    @SuppressLint("MissingPermission")
    public AudioRecordHelper(String file_path, Socket socket, String recorder_source, String recorder_channel) throws IOException {

        // manage file and socket stream
        isOffline = socket == null || socket.isClosed();
        if (!isOffline) {
            outputStream = socket.getOutputStream();
        }

        mExecutorService = Executors.newSingleThreadExecutor();
        mMainHandler = new Handler(Looper.getMainLooper());
        mAudioFile = new File(file_path);
        if (!mAudioFile.exists()) {
            mAudioFile.getParentFile().mkdirs();
        }
        mAudioFile.createNewFile();
        mFileOutputStream = new FileOutputStream(mAudioFile);

        // configure source and channel
        int audioSource, channelConfig;

        switch (recorder_source) {
            case "Default":
                audioSource = MediaRecorder.AudioSource.DEFAULT;
                break;
            case "Mic":
                audioSource = MediaRecorder.AudioSource.MIC;
                break;
            case "Unprocessed":
                audioSource = MediaRecorder.AudioSource.UNPROCESSED;
                break;
            case "Camcorder":
                audioSource = MediaRecorder.AudioSource.CAMCORDER;
                break;
            default:
                audioSource = MediaRecorder.AudioSource.UNPROCESSED;
                break;
        }

        switch (recorder_channel) {
            case "Mono recorder":
                channelConfig = AudioFormat.CHANNEL_IN_MONO;
                break;
            case "Stereo recorder":
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
                break;
            default:
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
                break;
        }

        // set up mAudioRecord
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        isMono = (channelConfig == AudioFormat.CHANNEL_IN_MONO);
        bufferSize = isMono ? BUFFER_SIZE_16BIT_MONO : BUFFER_SIZE_16BIT_STEREO;
        bufferSize = Math.max(minBufferSize, bufferSize);
        mAudioRecord = new AudioRecord(audioSource,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize);

        mExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                start();
            }
        });
    }

    private boolean startRecord() {

        mAudioRecord.startRecording();

        // DEBUG
        // Long tsLong = System.currentTimeMillis();
        // String ts = tsLong.toString();
        // Log.d("startRecord", "time", ts);

        try {
            byte[] mBuffer = isMono ? mBufferMono : mBufferStereo; // is this just a pointer?
            total_bytes = 0;
            while (isRecording) {
                int read = mAudioRecord.read(mBuffer, 0, bufferSize);
                if (read > 0) {
                    Log.d("startRecord", "buffer size:" + String.valueOf(read));
                    total_bytes += read;
                    mFileOutputStream.write(mBuffer, 0, read);
                    if (!isOffline) { // enable socket stream only when at online mode
                        outputStream.write(mBuffer, 0, read);
                        outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    }
                } else {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (mAudioRecord != null) {
                mAudioRecord.release();
            }
        }
    }

    public int stopRecord() {
        try {
            Log.d("stopRecord", "stopping");
            isRecording = false;
            if (mAudioRecord != null) {
                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
            }
            if (mFileOutputStream != null) {
                mFileOutputStream.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return total_bytes;
    }

    public void start() {
        isRecording = true;
        startRecord();
    }
}
