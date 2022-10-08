package com.felixnie.echoprober;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modified by Hongtuo
 * Name:    EchoProber Client
 * Date:    2022/8/30
 * Note:    Stoppable recording     - Done.
 *          Main thread block-free  - Done.
 *          Accept remote control   - Done.
 *          Auto save configuration - Done.
 *          Play/Stop threading     - Done.
 *          File transmission       - Pending.
 *          Stabler transmission    - Pending.
 *          Buffer update           - Done.
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
    int f0 = 15000;
    int f1 = 20000;
    int sample_rate = 44100;
    int chirp_length = 100;
    int buffer_length = 4000; // changed from 4000 to 4800 to comply with iOS version; 1600 fpr faster sample rate
    // double[] mSound = new double[buffer_length];
    short[] mBufferMono = new short[buffer_length];
    short[] mBufferStereo = new short[buffer_length * 2];

    // for auto-stop timer
    private Timer timer;
    boolean isTiming = false;

    // create component handler
    private Button btnConnect, btnDisconnect, btnPlay, btnSend, btnClear;
    private EditText edtTxtHost, edtTxtPort, edtTxtMessage, edtTxtName;
    private TextView txtInfo;
    private RadioGroup radioGroup, radioGroup2, radioGroup3;
    private RadioButton radioButton;
    private int radioID;

    // flags
    private static final int ACTION_STOPPED = 0;
    private static final int ACTION_PLAYING = 1;
    private int mCurrentActionState = ACTION_STOPPED;
    private static final int BUFFER_READY = 0;
    private static final int BUFFER_WRITING = 1;
    private int mCurrentBufferState = BUFFER_READY;

    // upon starting
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.setTitle("Echo Prober 9.02");

        // initialize sound buffer
        // default settings:
        //     mono buffer: linear chirp without windowing, will play with all speakers
        //     stereo buffer: linear chirp without windowing, only channel R is enabled
        // calculateChirp(f0, f1, sample_rate, chirp_length, buffer_length);
        generateChirp(100, 4000, 15000, 20000, 1.0, "none", "right");
        generateChirp(100, 4000, 15000, 20000, 0.0, "none", "left");
        generateChirp(100, 4000, 15000, 20000, 1.0, "none", "mono");
        // generateChirp(100, 1600, 15000, 22000, 1.0, "hann", "right");
        // generateTone(100, 1600, 14000, 1.0, "left");

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

        radioGroup = findViewById(R.id.radioGroup);
        radioGroup2 = findViewById(R.id.radioGroup2);
        radioGroup3 = findViewById(R.id.radioGroup3);

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

        // welcome message
        PostInfo("Hello, echo boys.");
        PostInfo("PCM will be saved to " + getFilePath());

        // Connect button
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mThreadPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        // check if it's already connected
                        if (socket != null && !socket.isClosed()) {
                            PostInfo("Already connected.");
                            return;
                        }

                        // read host and port
                        String host = edtTxtHost.getText().toString();
                        int port = Integer.parseInt(edtTxtPort.getText().toString());

                        // try to set up socket
                        try {
                            socket = new Socket(host, port);
                            // check connection
                            if (socket.isConnected()) {
                                PostInfo("Connected successfully.");

                                // set up socket input stream and buffered reader
                                inputStream = socket.getInputStream();
                                inputStreamReader = new InputStreamReader(inputStream);
                                bufferedReader = new BufferedReader(inputStreamReader);

                                // create and start data receive thread
                                DataReceiveThread = new Thread(new DataReceiveThread());
                                DataReceiveThread.start();
                            }
                        } catch (IOException e) {
                            // print error title and message
                            System.out.println(e);
                            // usually it's due to existing tcp connection
                            PostInfo("Connection failed: connection refused.");
                        }
                    }
                });
            }
        });

        // Disconnect button
        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mCurrentActionState == ACTION_PLAYING) {
                    onPlayBtnClick();
                }
                onDisconnectBtnClick();
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
                        // check if it's connected
                        if (socket == null) {
                            PostInfo("Please connect first.");
                            return;
                        }

                        // check if it's disconnected
                        if (socket.isClosed()) {
                            PostInfo("Please check connection.");
                            return;
                        }

                        // try to send info in edtTxtMessage
                        try {
                            SendMessage(edtTxtMessage.getText().toString());
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
                onPlayBtnClick();
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

    // data receive thread
    class DataReceiveThread implements Runnable {
        @RequiresApi(api = Build.VERSION_CODES.N)
        @Override
        public void run() {
            try {
                while (socket.isConnected() && !socket.isClosed()) {
                    message = bufferedReader.readLine();
                    if (message != null) { // when short message received
                        if (message.length() < 100) {
                            PostInfo("Received message: " + message);
                            if (message.equals("Play.")) {
                                onPlayBtnClick();
                            }
                            if (message.equals("Disconnect.")) {
                                onDisconnectBtnClick();
                            }
                        }
                        else { // when long message received
                            updateChirp();
                        }
                    }
                }
            } catch (IOException e) {
                // when interrupted by Thread.interrupt(), BufferedReader will throw a exception:
                // java.net.SocketException: Socket closed
                e.printStackTrace();
            }
        }
    }

    // audio player thread
    public class AudioPlayThread extends Thread {
        public void run() {
            Log.d("AudioPlayThread", "entered AudioPlayThread");

            // check current volume
            int current_volume = CheckSystemVolume();
            PostInfo("Current player volume: ", current_volume);

            // get selected radio channel
            radioID = radioGroup3.getCheckedRadioButtonId();
            radioButton = findViewById(radioID);
            String player_channel = radioButton.getText().toString();
            PostInfo("Current player channel: " + player_channel + '.');

            int channelConfig;
            switch (player_channel){
                case "Mono player":
                    channelConfig = AudioFormat.CHANNEL_OUT_MONO;
                    break;
                case "Stereo player":
                    channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                    break;
                default:
                    channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                    break;
            }
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

            // set buffer size
            int mBufferSize = AudioTrack.getMinBufferSize(sample_rate, channelConfig, audioFormat); // used to be 8BIT. typo maybe.
            AudioTrack mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                    sample_rate,
                    channelConfig,
                    audioFormat,
                    mBufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioTrack.DUAL_MONO_MODE_LR); // to show that L R channels are different. this will not affect mono mode?

            // feeding buffer
            while (mCurrentActionState == ACTION_PLAYING) {
                while (mCurrentBufferState == BUFFER_WRITING) {
                    // wait
                    Log.d("AudioPlayThread", "waiting for BUFFER_WRITING");
                }
                // mAudioTrack.setStereoVolume(AudioTrack.getMaxVolume(), AudioTrack.getMaxVolume()); // do we need to use this?
                mAudioTrack.play();
                if (channelConfig == AudioFormat.CHANNEL_OUT_MONO) {
                    mAudioTrack.write(mBufferMono, 0, mBufferMono.length);
                } else {
                    mAudioTrack.write(mBufferStereo, 0, mBufferStereo.length);
                }

            }
            mAudioTrack.stop();
            mAudioTrack.release();
        }
    }

    public String getFilePath() {
        String path;
        String publicPath;
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            path = new File(getExternalCacheDir().getAbsolutePath()).toString();
            publicPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toString();

        } else {
            path = new File(getCacheDir().getAbsolutePath()).toString();
            publicPath = path;
            // if no external storage mounted
        }

        return publicPath;
    }

    public void onDisconnectBtnClick() {
        mThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                // check if it's already connected
                if (socket == null) {
                    PostInfo("Please connect first.");
                    return;
                }

                // check if it's already disconnected
                if (socket.isClosed()) {
                    PostInfo("Already disconnected.");
                    return;
                }

                // try to close socket
                try {
                    // send disconnect message "Goodbye."
                    SendMessage("Goodbye.");

                    // close socket
                    socket.close();
                    if (socket.isClosed()) {
                        PostInfo("Disconnected successfully.");

                        // stop data receive thread
                        DataReceiveThread.interrupt();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void onPlayBtnClick() {
        mThreadPool.execute(new Runnable() {
            @Override
            public void run() {

                String file_label = Objects.requireNonNull(edtTxtName.getText().toString());

                // offline mode detection
                boolean isOffline = socket == null || socket.isClosed(); // if it's uninitiated or disconnected
                boolean isTrainTest = file_label.startsWith("train") || file_label.startsWith("test"); // if the file name has prefix
                boolean isPlaying = mCurrentActionState == ACTION_PLAYING; // if it's playing (timing)
                // training data recording time: 90s; test data recording time: 9s
                int data_recording_time = file_label.startsWith("train") ? 90000 : 9000;

                // offline mode actions
                if (isOffline && isTrainTest && !isPlaying) { // offline mode, with prefix, when stopped -> will stop as scheduled
                    PostInfo("Offline mode, will auto-stop as scheduled.");
                    // if file name start with train/test, it'll auto-stop in 120/12s, e.g., "train01", "train_1"
                    isTiming = true;
                    timer = new Timer();
                    timer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            PostInfo("Stopped as scheduled.");
                            onPlayBtnClick(); // call myself
                        }
                    }, data_recording_time);
                } else if (isOffline && isTrainTest && isPlaying) { // offline mode, with prefix, when playing (timing) -> cancel timer
                    PostInfo("Stopped by force.");
                    timer.cancel();
                    timer.purge();
                } else if (isOffline && !isTrainTest && !isPlaying) { // offline mode, when stopped -> pass to play action
                    PostInfo("Offline mode, can stop manually.");
                } else if (isOffline && !isTrainTest && isPlaying) { // offline mode, when playing -> pass to stop action
                    PostInfo("Stopped manually.");
                }

                // if the file name is empty, set a name for it
                if (file_label.isEmpty()) {
                    PostInfo("Empty file name. Named as 'temp' instead.");
                    edtTxtName.post(new Runnable() {
                        @Override
                        public void run() {
                            String text = "temp";
                            edtTxtName.setText(text);
                        }
                    });
                    file_label = Objects.requireNonNull(edtTxtName.getText().toString());
                }

                // set up file path
                String file_name = file_label + ".pcm";
                String file_path = getFilePath() + File.separator + file_name;


                if (isPlaying) { // when Stop is pressed
                    // switch flag to normal state
                    mCurrentActionState = ACTION_STOPPED;
                    // change button to Play
                    btnPlay.post(new Runnable() {
                        @Override
                        public void run() {
                            String play_text = "Play";
                            btnPlay.setText(play_text);
                            btnPlay.setBackgroundColor(17170453);
                        }
                    });

                    // stop the recorder
                    // stopRecord() will return the number of recorded frames
                    int delivered_frames = mAudioRecordHelper.stopRecord();
                    PostInfo("Bytes sent: ", delivered_frames);
                } else { // when Play is pressed
                    // switch flag to playing (recording) state
                    mCurrentActionState = ACTION_PLAYING;
                    // change button text
                    btnPlay.post(new Runnable() {
                        @Override
                        public void run() {
                            String stop_text = "Stop";
                            btnPlay.setText(stop_text);
                            btnPlay.setBackgroundColor(Color.RED);
                        }
                    });

                    // start the recorder
                    try {
                        // get selected radio button
                        radioID = radioGroup.getCheckedRadioButtonId();
                        radioButton = findViewById(radioID);
                        String recorder_source = radioButton.getText().toString();
                        PostInfo("Current recorder source: " + recorder_source + '.');

                        // get selected radio channel
                        radioID = radioGroup2.getCheckedRadioButtonId();
                        radioButton = findViewById(radioID);
                        String recorder_channel = radioButton.getText().toString();
                        PostInfo("Current recorder channel: " + recorder_channel + '.');

                        mAudioRecordHelper = new AudioRecordHelper(file_path, socket, recorder_source, recorder_channel);

                        // send "Start playing." only when it's online mode
                        if (!isOffline) {
                            SendMessage("Start playing.");
                        }
                    } catch (IOException ex) {
                        Log.d("onPlayBtnClick", "mAudioRecordHelper failed");
                        ex.printStackTrace();
                    }

                    // start the player
                    try {
                        mAudioPlayThread = new AudioPlayThread();
                        mAudioPlayThread.start();
                        mAudioPlayThread.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    // upon finishing recording
                    PostInfo("Recorded successfully.");
                    isTiming = false;
                }
            }
        });
    }

    public void generateChirp(int chirp_len, int duration, double f0, double f1, double volume, String window, String target) {
        double fs = sample_rate;
        double t1 = (double) chirp_len / fs;
        double beta = (f1-f0) / t1;
        double t;
        double value;
        double win;
        double hann;
        short value_short;
        for (int i = 0; i < duration; i++) {
            if (i < chirp_len) {
                t = i / fs;
                value = Math.cos(2.0 * Math.PI * (beta / 2.0 * t * t + f0 * t));
                switch (window) {
                    case "hann":
                        hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / chirp_len);
                        win = hann;
                        break;
                    default:
                        win = 1.0;
                        break;
                }

                value_short = (short) (value * win * volume * Short.MAX_VALUE); // windowed chirp
            } else { // fill with 0
                value_short = 0;
            }

            switch (target) {
                case "mono":
                    mBufferMono[i] = value_short; // both channels will play the same audio
                    break;
                case "left":
                    mBufferStereo[2*i] = value_short; // channel L, usually the upper speaker
                    break;
                case "right":
                    mBufferStereo[2*i+1] = value_short; // channel R, usually the lower speaker
                    break;
                default:
                    // warnings
                    break;
            }
        }
    }

    public void generateTone(int tone_len, int duration, double f, double volume, String target) {
        double fs = sample_rate;
        double t1 = tone_len / fs;
        double beta = (f1-f0) / t1;
        double t;
        double value;
        short value_short;
        for (int i = 0; i < duration; i++) {
            if (i < tone_len) {
                t = i / fs;
                value = Math.cos(2.0 * Math.PI * f * t);
                value_short = (short) (value * volume * Short.MAX_VALUE); // un-windowed tone
            } else { // fill with 0
                value_short = 0;
            }

            switch (target) {
                case "mono":
                    mBufferMono[i] = value_short; // both channels will play the same audio
                    break;
                case "left":
                    mBufferStereo[2*i] = value_short; // channel L, usually the upper speaker
                    break;
                case "right":
                    mBufferStereo[2*i+1] = value_short; // channel R, usually the lower speaker
                    break;
                default:
                    // warnings
                    break;
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public void updateChirp() {
        // parse long message as double array
        String[] mSoundStr = message.split(",", 0);
        double[] mSoundVal = Arrays.stream(mSoundStr).mapToDouble(Double::parseDouble).toArray(); // should be -1 ~ 1
        PostInfo("Received values: ", mSoundVal.length);

//        // check if the buffer is being written
//        if (mCurrentBufferState == BUFFER_READY) {
//
//            mCurrentBufferState = BUFFER_WRITING;
//            Log.d("DataReceiveThread", "start writing");
//
//            // update mono buffer if data length <= mono buffer length
//            // we encourage you to fill your update data with 0
//            // also, please update when it's not playing
//            if (mSoundVal.length <= buffer_length) {
//                PostInfo("Update mono chirp buffer.");
//                for (int i = 0; i < buffer_length; i++) {
//                    if (i < mSoundVal.length) {
//                        mBufferMono[i] = (short) (mSoundVal[i] * Short.MAX_VALUE);
//                    } else {
//                        mBufferMono[i] = 0;
//                    }
//                }
//            } else if (mSoundVal.length <= buffer_length * 2) {
//                PostInfo("Update stereo chirp buffer.");
//                for (int i = 0; i < buffer_length * 2; i++) {
//                    if (i < mSoundVal.length) {
//                        mBufferStereo[i] = (short) (mSoundVal[i] * Short.MAX_VALUE);
//                    } else {
//                        mBufferStereo[i] = 0;
//                    }
//                }
//            } else {
//                PostInfo("Update data is too long.");
//            }
//
//            mCurrentBufferState = BUFFER_READY;
//            Log.d("DataReceiveThread", "finish writing");
//        }
    }

    public void PostInfo(String msg, int n) {
        @SuppressLint("DefaultLocale")
        String text = msg + String.format("%d.", n);
        PostInfo(text);
    }

    public void PostInfo(String msg) {
        txtInfo.post(new Runnable() {
            @Override
            public void run() {
                // timestamp
                Date c = Calendar.getInstance().getTime();
                SimpleDateFormat df = new SimpleDateFormat("[HH:mm:ss] ");
                String timestamp = df.format(c);
                String text = timestamp + msg + '\n' + txtInfo.getText();
                txtInfo.setText(text);
            }
        });
    }

    public void SendMessage(String msg) throws IOException {
        outputStream = socket.getOutputStream();
        String text = msg + "\r\n";
        outputStream.write(text.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();

        PostInfo("Bytes sent: ", text.length());
    }

    // fetch the stored data in onResume(), which will be called when the app opens again
    @Override
    protected void onResume() {
        super.onResume();

        // fetch the stored data from SharedPreference
        SharedPreferences sh = getSharedPreferences("MySharedPref", MODE_PRIVATE);

        String host = sh.getString("host", "155.69.142.8"); // default server ip
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

    // store data in onPause(), which will be called when the user closes the application
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