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
 * Time:    13:35
 * Desc:    demo
 */

public class AudioRecordHelper {

    private final static int BUFFER_SIZE = 8000;
    private volatile boolean mIsRecording = false;
    private byte[] mBuffer = new byte[BUFFER_SIZE];
    private File mAudioFile;
    private FileOutputStream mFileOutputStream;
    private AudioRecord mAudioRecord;
    private final ExecutorService mExecutorService;
    private final Handler mMainHandler;
    private int total_bytes;

    OutputStream outputStream;

    @SuppressLint("MissingPermission")
    public AudioRecordHelper(String file_path, Socket socket) throws IOException {

        outputStream = socket.getOutputStream();

        mExecutorService = Executors.newSingleThreadExecutor();
        mMainHandler = new Handler(Looper.getMainLooper());
        mAudioFile = new File(file_path);
        if (!mAudioFile.exists()) {
            mAudioFile.getParentFile().mkdirs();
        }
        mAudioFile.createNewFile();
        mFileOutputStream = new FileOutputStream(mAudioFile);
        int audioSource = MediaRecorder.AudioSource.MIC;
        int sampleRate = 44100;
        //单声道录制
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        mAudioRecord = new AudioRecord(audioSource
                , sampleRate
                , channelConfig
                , audioFormat
                , Math.max(minBufferSize, BUFFER_SIZE));
        mExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                start();
            }
        });
    }

    private boolean startRecord() {

        mAudioRecord.startRecording();

        Long tsLong = System.currentTimeMillis();
        String ts = tsLong.toString();
        Log.d("startRecord time", ts);

        try {
            total_bytes = 0;
            while (mIsRecording) {
                int read = mAudioRecord.read(mBuffer, 0, BUFFER_SIZE);
                if (read > 0) {
                    Log.d("startRecord", "buffer size:" + String.valueOf(read));
                    total_bytes += read;
                    mFileOutputStream.write(mBuffer, 0, read);
                    outputStream.write(mBuffer, 0, read);
                    outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
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
            mIsRecording = false;
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
        mIsRecording = true;
        startRecord();
    }
}
