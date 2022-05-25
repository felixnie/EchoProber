package com.felixnie.echoprober;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modified by Hongtuo
 * Name:    EchoProber Client
 * Date:    2022/3/27
 * Note:    Stoppable recording     - Done.
 *          Main thread block-free  - Done.
 *          Accept remote control   - Done.
 *          Auto save configuration - Done.
 *          Play/Stop threading     - Working.
 *          File transmission       - Pending.
 *          Stable transmission     - Working.
 *          Real-time buffer update - Just can't. Damn.
 */

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    // what's this?
    private Handler mMainHandler;
    // create socket
    private Socket socket;
    // threads for each button click
    private ExecutorService mThreadPool;

    // for data receiving
    InputStream inputStream;
    InputStreamReader inputStreamReader;
    BufferedReader bufferedReader;
    String message;
    Thread DataReceiveThread;

    // for data sending
    OutputStream outputStream;

    //for recording
    private AudioRecordHelper mAudioRecordHelper;
    private AudioPlayThread mAudioPlayThread;

    // for sound design
    int sample_rate = 44100;
    int duration = 4000;
    int sig_len = 500;
    double[] mSound = new double[duration];
    short[] mBuffer = new short[duration];

    // create component handler
    private Button btnConnect, btnDisconnect, btnPlay, btnSend, btnClear;
    private EditText edtTxtHost, edtTxtPort, edtTxtMessage, edtTxtName;
    private TextView txtInfo;

    // flags
    private static final int ACTION_NORMAL = 0;
    private static final int ACTION_RECORDING = 1;
    private int mCurrentActionState = ACTION_NORMAL;
    private static final int BUFFER_NORMAL = 0;
    private static final int BUFFER_WRITING = 1;
    private int mCurrentBufferState = BUFFER_NORMAL;

    // upon starting
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // initialize sound buffer
        float f0 = 15000;
        float f1 = 20000;
        double w0 = f0 / 44100 * 2 * Math.PI;
        double w1 = f1 / 44100 * 2 * Math.PI;
        for (int i = 0; i < sig_len; i++) {
            double K = sig_len * w0 / Math.log(w1 / w0);
            double L = sig_len / Math.log(w1 / w0);
            double phase = K * (Math.exp(i / L) - 1.0);
            mSound[i] = Math.sin(phase);
            mBuffer[i] = (short) (mSound[i] * Short.MAX_VALUE);
        }

        // components
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnPlay = findViewById(R.id.btnPlay);
        btnSend = findViewById(R.id.btnSend);
        btnClear = findViewById(R.id.btnClear);
        edtTxtHost = findViewById(R.id.edtTxtHost);
        edtTxtPort = findViewById(R.id.edtTxtPort);
        edtTxtMessage = findViewById(R.id.edtTxtMessage);
        edtTxtName = findViewById(R.id.edtTxtName);
        txtInfo = findViewById(R.id.txtInfo);
        txtInfo.setMovementMethod(new ScrollingMovementMethod());

        // check permission upon start
        int PERMISSION_ALL = 1;
        String[] PERMISSIONS = {
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.RECORD_AUDIO
        };
        if (!CheckPermission(PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_ALL);
        }

        // initialize thread pool
        mThreadPool = Executors.newCachedThreadPool();

        // Connect button
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mThreadPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        String host = edtTxtHost.getText().toString();
                        int port = Integer.parseInt(edtTxtPort.getText().toString());
                        try {
                            // check if it's already connected
                            if (socket != null && !socket.isClosed()) {
                                txtInfo.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        txtInfo.setText("Already connected." + "\n" + txtInfo.getText());
                                    }
                                });
                                return;
                            }
                            // set up socket
                            socket = new Socket(host, port);
                            // check connection
                            if (socket.isConnected()) {
                                txtInfo.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        txtInfo.setText("Connected successfully." + "\n" + txtInfo.getText());
                                    }
                                });
                            } else {
                                // seems it's better to deal with connection failure in catch
                                txtInfo.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        txtInfo.setText("Connection failed." + "\n" + txtInfo.getText());
                                    }
                                });
                            }
                            // set up receiver
                            inputStream = socket.getInputStream();
                            inputStreamReader = new InputStreamReader(inputStream);
                            bufferedReader = new BufferedReader(inputStreamReader);
                            // create receiver thread
                            DataReceiveThread = new Thread(new DataReceiveThread());
                            DataReceiveThread.start();
                        } catch (IOException e) {
                            // 3 popular methods for error printing:
                            // e.printStackTrace(); // print error trace
                            // System.out.println(e.getMessage()); // print error message
                            System.out.println(e.toString()); // print error title and message
                            // usually it's due to existed tcp connection
                            // warn the user no matter what the true reason is
                            txtInfo.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtInfo.setText("Connection failed: connection existed." + "\n" + txtInfo.getText());
                                }
                            });
                        }
                    }
                });
            }
        });

        // Disconnect button
        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mThreadPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // check if it's already connected
                            if (socket == null) {
                                txtInfo.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        txtInfo.setText("Please connect first." + "\n" + txtInfo.getText());
                                    }
                                });
                                return;
                            }
                            // check if it's already disconnected
                            if (socket.isClosed()) {
                                txtInfo.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        txtInfo.setText("Already disconnected." + "\n" + txtInfo.getText());
                                    }
                                });
                                return;
                            }
                            // send "Goodbye."
                            outputStream = socket.getOutputStream();
                            String msg = "Goodbye." + "\r\n"; // disconnect message
                            outputStream.write(msg.getBytes(StandardCharsets.UTF_8));
                            outputStream.flush();
                            txtInfo.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtInfo.setText("Bytes sent: " + String.format("%d.", msg.length()) + "\n" + txtInfo.getText());
                                }
                            });

                            // close socket
                            socket.close();
                            if (socket.isClosed()) {
                                txtInfo.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        txtInfo.setText("Disconnected successfully." + "\n" + txtInfo.getText());
                                    }
                                });
                            } else {
                                // this never shows
                                txtInfo.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        txtInfo.setText("Disconnection failed." + "\n" + txtInfo.getText());
                                    }
                                });
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
        });

        // Clear button
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                edtTxtMessage.setText("");
            }
        });

        // Send button
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mThreadPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // check if it's connected
                            if (socket == null) {
                                txtInfo.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        txtInfo.setText("Please connect first." + "\n" + txtInfo.getText());
                                    }
                                });
                                return;
                            }
                            // check if it's disconnected
                            if (socket.isClosed()) {
                                txtInfo.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        txtInfo.setText("Please check connection." + "\n" + txtInfo.getText());
                                    }
                                });
                                return;
                            }
                            // send info in edtTxtMessage. shall we use a new thread for sending?
                            outputStream = socket.getOutputStream();
                            String msg = edtTxtMessage.getText().toString() + "\r\n";
                            outputStream.write(msg.getBytes(StandardCharsets.UTF_8));
                            outputStream.flush();
                            txtInfo.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtInfo.setText("Bytes sent: " + String.format("%d.", msg.length()) + "\n" + txtInfo.getText());
                                }
                            });
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
        });

        // Play button
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mThreadPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        // check if it's not connected yet
                        if (socket == null) {
                            txtInfo.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtInfo.setText("Please connect first." + "\n" + txtInfo.getText());
                                }
                            });

                            return;
                        }
                        // check if it's disconnected
                        if (socket.isClosed()) {
                            txtInfo.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtInfo.setText("Please check connection." + "\n" + txtInfo.getText());
                                }
                            });

                            return;
                        }
                        // check if the label is empty
                        String file_label = Objects.requireNonNull(edtTxtName.getText().toString());
                        if (file_label.isEmpty()) {
                            txtInfo.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtInfo.setText("Label is empty." + "\n" + txtInfo.getText());
                                }
                            });

                            return;
                        }
                        // set up file path
                        String file_name = file_label + ".pcm";
                        String file_path = getRecorderFilePath() + File.separator + file_name;
                        Log.d("btnPlay", "file_path = " + file_path);

                        // check if it's recording
                        if (mCurrentActionState == ACTION_RECORDING) {
                            // switch flag to normal state
                            mCurrentActionState = ACTION_NORMAL;
                            // stop the recorder
                            Log.d("btnPlay", "when pressed stop: try to stop recording");
                            int delivered_frames = mAudioRecordHelper.stopRecord(); // this will return the bytes from audio stream
                            txtInfo.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtInfo.setText("Bytes sent: " + String.format("%d.", delivered_frames) + "\n" + txtInfo.getText());
                                }
                            });

                            // change button to Play
                            btnPlay.post(new Runnable() {
                                @Override
                                public void run() {
                                    btnPlay.setText("Play");
                                }
                            });
                            Log.d("btnPlay", "when pressed top: ready to return");
                            return;
                        }
                        else {
                            // switch flag to recording (playing) state
                            mCurrentActionState = ACTION_RECORDING;
                            // change button text
                            btnPlay.post(new Runnable() {
                                @Override
                                public void run() {
                                    btnPlay.setText("Stop");
                                }
                            });
                            Log.d("btnPlay", "when pressed play: try to start recording");
                            // check current volume
                            int current_volume = CheckSystemVolume();
                            txtInfo.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtInfo.setText("Current volume: " + String.valueOf(current_volume) + "." + "\n" + txtInfo.getText());
                                }
                            });

                            // start the recorder
                            try {
                                // send "Start playing."
                                outputStream = socket.getOutputStream();
                                String msg = "Start playing." + "\r\n"; // disconnect message
                                outputStream.write(msg.getBytes(StandardCharsets.UTF_8));
                                outputStream.flush();
                                txtInfo.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        txtInfo.setText("Bytes sent: " + String.format("%d.", msg.length()) + "\n" + txtInfo.getText());
                                    }
                                });

                                // start recorder
                                mAudioRecordHelper = new AudioRecordHelper(file_path, socket);
                            } catch (IOException ex) {
                                ex.printStackTrace();
                            }
                            // start the player
                            mAudioPlayThread = new AudioPlayThread();
                            mAudioPlayThread.start();
                            try {
                                mAudioPlayThread.join();
                                Log.d("btnPlay", "mAudioPlayThread has joined");
                            } catch (InterruptedException e) {
                                Log.d("btnPlay", "mAudioPlayThread failed to join");
                                e.printStackTrace();
                            }
                            // upon finishing recording
                            txtInfo.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtInfo.setText("Recorded successfully." + "\n" + txtInfo.getText());
                                }
                            });

                        }
                    }
                });
            }
        });
    }

    // function to check and request permission
    public boolean CheckPermission(String... permissions)
    {
        if (permissions != null) {
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(MainActivity.this, permission) == PackageManager.PERMISSION_DENIED) {
                    return false;
                }
            }
        }
        return true;
    }

    // function to check system volume
    private int CheckSystemVolume() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int volumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVolumeLevel = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return (int) (((float) volumeLevel / maxVolumeLevel) * 100);
    }

    // data receiver thread
    class DataReceiveThread implements Runnable {
        @RequiresApi(api = Build.VERSION_CODES.N)
        @Override
        public void run() {
            while (true) {
                try {
                    message = bufferedReader.readLine();
                    if (message != null) {
                        // display the length of byte stream
                        txtInfo.post(new Runnable() {
                            @Override
                            public void run() {
                                txtInfo.setText("Received bytes: " + String.format("%d.", message.length()) + "\n" + txtInfo.getText());
                            }
                        });

                        if (message.length() < 100) {
                            // short message
                            txtInfo.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtInfo.setText("Received message: " + message + "\n" + txtInfo.getText());
                                }
                            });
                            Log.d("DataReceiveThread", message);

                            // newly added /////////////////////////////////////////////////////////
                            // TODO: 15/3/22 wrap into a function
                            if (message.equals("Play.")) {
                                // the lines when Play button is pressed
                                Log.d("DataReceiveThread", "equals Play.");

                                mThreadPool.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        // check if the label is empty
                                        String file_label = Objects.requireNonNull(edtTxtName.getText().toString());
                                        if (file_label.isEmpty()) {
                                            // txtInfo.setText("Label is empty." + "\n" + txtInfo.getText());
                                            // return;
                                            edtTxtName.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    edtTxtName.setText("calibration");
                                                }
                                            });

                                            file_label = Objects.requireNonNull(edtTxtName.getText().toString());
                                        }
                                        // set up file path
                                        String file_name = file_label + ".pcm";
                                        String file_path = getRecorderFilePath() + File.separator + file_name;
                                        Log.d("btnPlay", "file_path = " + file_path);

                                        // check if it's recording
                                        if (mCurrentActionState == ACTION_RECORDING) {
                                            // switch flag to normal state
                                            mCurrentActionState = ACTION_NORMAL;
                                            // stop the recorder
                                            Log.d("btnPlay", "when pressed stop: try to stop recording");
                                            int delivered_frames = mAudioRecordHelper.stopRecord(); // this will return the bytes from audio stream
                                            txtInfo.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    txtInfo.setText("Bytes sent: " + String.format("%d.", delivered_frames) + "\n" + txtInfo.getText());
                                                }
                                            });

                                            // change button to Play
                                            btnPlay.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    btnPlay.setText("Play");
                                                }
                                            });
                                            Log.d("btnPlay", "when pressed top: ready to return");
                                            return;
                                        }
                                        else {
                                            // switch flag to recording (playing) state
                                            mCurrentActionState = ACTION_RECORDING;
                                            // change button text
                                            btnPlay.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    btnPlay.setText("Stop");
                                                }
                                            });
                                            Log.d("btnPlay", "when pressed play: try to start recording");
                                            // check current volume
                                            int current_volume = CheckSystemVolume();
                                            txtInfo.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    txtInfo.setText("Current volume: " + String.valueOf(current_volume) + "." + "\n" + txtInfo.getText());
                                                }
                                            });

                                            // start the recorder
                                            try {
                                                // send "Start playing."
                                                outputStream = socket.getOutputStream();
                                                String msg = "Start playing." + "\r\n"; // disconnect message
                                                outputStream.write(msg.getBytes(StandardCharsets.UTF_8));
                                                outputStream.flush();
                                                txtInfo.post(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        txtInfo.setText("Bytes sent: " + String.format("%d.", msg.length()) + "\n" + txtInfo.getText());
                                                    }
                                                });

                                                // start recorder
                                                mAudioRecordHelper = new AudioRecordHelper(file_path, socket);
                                            } catch (IOException ex) {
                                                ex.printStackTrace();
                                            }
                                            // start the player
                                            mAudioPlayThread = new AudioPlayThread();
                                            mAudioPlayThread.start();
                                            try {
                                                mAudioPlayThread.join();
                                                Log.d("btnPlay", "mAudioPlayThread has joined");
                                            } catch (InterruptedException e) {
                                                Log.d("btnPlay", "mAudioPlayThread failed to join");
                                                e.printStackTrace();
                                            }
                                            // upon finishing recording
                                            txtInfo.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    txtInfo.setText("Recorded successfully." + "\n" + txtInfo.getText());
                                                }
                                            });

                                        }
                                    }
                                });
                            }
                        } else {
                            // long data
                            String[] mSoundStr = message.split(",", 0);
                            double[] mSoundVal = Arrays.stream(mSoundStr).mapToDouble(Double::parseDouble).toArray();
                            if (mCurrentBufferState == BUFFER_NORMAL) {
                                mCurrentBufferState = BUFFER_WRITING; // set the flag to writing
                                Log.d("DataReceiveThread", "Start writing");
                                for (int i = 0; i < duration; i++) {
                                    if (i < mSoundVal.length) {
                                        mSound[i] = mSoundVal[i]; // should be -1 ~ 1
                                    } else {
                                        mSound[i] = 0.0;
                                    }
                                    mBuffer[i] = (short) (mSound[i] * Short.MAX_VALUE);
                                }
                                mCurrentBufferState = BUFFER_NORMAL;
                                Log.d("DataReceiveThread", "Finish writing");
                            }
                            // finished writing
                            txtInfo.post(new Runnable() {
                                @Override
                                public void run() {
                                    txtInfo.setText("Received data: " + String.valueOf(mSoundVal.length) + "\n" + txtInfo.getText());
                                }
                            });
                            Log.d("DataReceiveThread", String.valueOf(mSoundStr[mSoundStr.length-1]));
                        }
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // audio player thread
    public class AudioPlayThread extends Thread {
        public void run() {
            Log.d("AudioPlayThread", "Entering AudioPlayThread");

            int mBufferSize = AudioTrack.getMinBufferSize(sample_rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_8BIT);
            AudioTrack mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sample_rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    mBufferSize, AudioTrack.MODE_STREAM);

            // feeding buffer
            while (mCurrentActionState == ACTION_RECORDING) {
                while (mCurrentBufferState == BUFFER_WRITING) {
                    // wait
                    Log.d("AudioPlayThread", "Waiting for BUFFER_WRITING");
                }
                // mAudioTrack.setStereoVolume(AudioTrack.getMaxVolume(), AudioTrack.getMaxVolume());
                mAudioTrack.play();
                mAudioTrack.write(mBuffer, 0, mBuffer.length);
            }
            mAudioTrack.stop();
            mAudioTrack.release();
        }
    }

    public String getRecorderFilePath() {
        String path;
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            path = getExternalCacheDir().getAbsolutePath();
        } else {
            path = getCacheDir().getAbsolutePath();
        }
        return path + File.separator + "Recorder";
    }

    // fetch the stored data in onResume(), which will be called when the app opens again
    @Override
    protected void onResume() {
        super.onResume();

        // fetch the stored data from the SharedPreference
        SharedPreferences sh = getSharedPreferences("MySharedPref", MODE_PRIVATE);

        String host = sh.getString("host", "155.69.142.178"); // default server ip
        int port = sh.getInt("port", 8170); // default server port

        // check model, change default port
        String device_model = Build.MODEL;
        Log.d("onResume", device_model);
        String[] device_list = new String[] {"MI 6", "Moto Z", "Pixel 4"};
        int[] port_list = new int[] {8170, 8171, 8172}; // calibrator, source, target
        for (int i = 0; i < device_list.length; i++) {
            if (device_model.equals(device_list[i])) {
                // edtTxtPort.setText(Integer.toString(port_list[i]));
                port = sh.getInt("port", port_list[i]);
                break;
            }
        }

        // set the fetched data in the EditTexts
        edtTxtHost.setText(host);
        edtTxtPort.setText(String.valueOf(port));
    }

    // store the data in onPause(), which will be called when the user closes the application
    @Override
    protected void onPause() {
        super.onPause();

        // create a shared pref object with a file name "MySharedPref" in private mode
        SharedPreferences sharedPreferences = getSharedPreferences("MySharedPref", MODE_PRIVATE);
        SharedPreferences.Editor myEdit = sharedPreferences.edit();

        // write all the data entered by the user in SharedPreference and apply
        myEdit.putString("host", edtTxtHost.getText().toString());
        myEdit.putInt("port", Integer.parseInt(edtTxtPort.getText().toString()));
        myEdit.apply();
    }

    @Override
    public void onClick(View view) {

    }
}