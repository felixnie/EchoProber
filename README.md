# EchoProber

Android client for real-time audio playback and recording.

Check out https://github.com/felixnie/EchoProber-server for the server app written in MATLAB.

Credit to the developers of LocProber: Qun, Chaojie, Dongfang, Wenjie, et al.


## Refinements

1. Stoppable playback: play and stop at anytime you want.
2. Main thread is block-free: recorder will not freeze UI.
3. Accept remote control from server: 
    - short message (length <100) from the server like *'Play.'* and *'Disconnect.'* will be parsed as commands, 
    - long message (length >=100) from the server like *'0.8195,0.6037, ... ,0.1062'* will be parsed as chirp data.
4. Auto save configuration: host and port settings will be restored when starting the app.
5. No volume restriction.


## Get Started

1. Open the MATLAB server on your PC/laptop. Setup the analysis scripts you want to run.
2. Set **host** to the IP address of the PC/laptop and run the server script.
3. The server supports 3 clients by default, with **port** 8170, 8171 and 8172. Add more when needed.
4. Set the corresponding **Host** and **Port** on the Android client, then **Connect**. Info of connect/disconnect actions will be printed on both the client and the server side.
5. Set a file name for the .pcm file, then press **Play** to start the simultaneous playback and recording.
7. Press **Stop** to stop the playback and recording. The file will then be saved under *Android/data/com.felixnie.echoprober/cache/Recorder*.
8. If no file name is assigned, the file will be named *temp.pcm* by default.
9. You can send messages to the server using **Send**.
10. Press **Disconnect** to stop the playback and recording and close the socket connection.


## For Developers

### More details on Android client

1. When **Play/Stop** is pressed, a short message "Start playing." will be sent to the server automatically to let the server reset the buffers.
2. The Android client will fetch a audio clip of 4000 samples at a time and send in 1 socket. The MATLAB server will invoke the callback function **readData** upon receiving each socket, i.e, an audio clip of 4000 samples.
3. The default chirp is linear chirp from 15k to 20kHz in 500 samples, and then mute for 3500 samples to record the echoes. See **Customize the chirp remotely** below if you want to use your own chirp.


### More datails on MATLAB server
3. Since the playback and recording is not perfectly synchronized, the server will do peak-finding to find the start of each chirp-echo recording.
4. The figure windows in MATLAB can update themselves when you minimize the windows. They don't keep popping-up to the front. If you accidentally close one, it will pop-up again.
5. You can run other MATLAB scripts with the server running in the background. Some examples are privided in the MATLAB live scripts.
6. Sending a short message *'Play.'* from the server to a connected client is equal to pressing the **Play/Stop** button remotely.
    - Example MATLAB script:
        ```
        writeline(server{device}, "Play.") % start
        pause(5) % time for playback
        writeline(server{device}, "Play.") % stop
        ```
    - Check https://github.com/felixnie/EchoProber-server/blob/main/echo_server_live_1_codebase.mlx for more examples.
7. When you re-run the server (e.g, press F5 for the second time), a short message *'Disconnect.'* will be sent to all connected clients to release the sockets. It is equal to pressing the **Disconnect** button remotely.
8. ⚠️ If failed to restart the server, please retry as it might have some dalay for the OS to release the sockets.


### Customize the chirp remotely
9.  If you want to update chirp data without re-compiling the Android client, you can send a string like *'0.8195,0.6037, ... ,0.1062'* to the client.
    - Example MATLAB script:
        ```
        sig_len = 1000;
        single_frequency = 18000;

        i = 1:sig_len
        phase = 2 * pi * single_frequency * i / fs;
        chirp = sin(phase);

        sig_str = sprintf('%f,', chirp);
        sig_str = sig_str(1:end-1); % drop the last comma

        writeline(server{device}, sig_str)
        ```
    - If the length of the string data N is <4000, then the rest 4000-N samples will be set to 0. In the example above, we create a signal with sinusoid of 18kHz which lasts for 1000/44100 second, and mute for 3000/44100 second.


### Playback only or recording only
8. If you only need a smartphone to play and don't want to analysis the data from it:
    - Set the flag before each analysis script in https://github.com/felixnie/EchoProber-server/blob/main/echo_server.m:
        ```
        if ismember(idx, [1 2 3])   % enable this analysis script for device 1, 2, 3 (on port 8170, 8171, 8172)
        if ismember(idx, [2])       % enable this analysis script for device 2 only (the one on port 8171)
        if ismember(idx, [])        % disable this analysis script for all devices
        ```
    - Note that this will still create some network activities. Usually each device will have a data flow of about 100 kb/s.
9. If you only need a smartphone to record without playing, just mute that device as there is no volume restriction.
10. ⚠️ There will be some distortion if you set volume to 100% in the chirp-based EchoLoc experiments. Lower volume might work better.


## To-do

1. (Working) Stable transmission: socket size changes when CPU load is high.
2. (Working) Regularize the methods used for multithreading: multiple versions of Java multithreading used in MainActivity.java and AudioRecordHelper.java.


## Screenshot

<img src="https://raw.githubusercontent.com/felixnie/img/master/screenshot-echoprober.png" width="450">

