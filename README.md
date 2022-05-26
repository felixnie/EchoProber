# EchoProber

Android client for real-time audio playback and recording.

Check out https://github.com/felixnie/EchoProber-server for the server app written in MATLAB.

Credit to the developers of LocProber: Qun, Chaojie, Dongfang, Wenjie, et al.

## Refinements

1. Stoppable playback.
2. Main thread block-free: recorder will not freeze UI.
3. Accept remote control from server: short message as commands, long message as chirp data.
4. Auto save configuration: host and port settings will be restored when starting the app.

## To-do

1. (Pending) File transmission: send recorded .pcm files to server.
2. (Working) Stable transmission: socket size changes when CPU load is high.
3. (Working) Regularize the methods used for multithreading: multiple versions of Java multithreading used in MainActivity.java and AudioRecordHelper.java.

## Screenshot

<img src="https://user-images.githubusercontent.com/27218536/170224313-f6407753-0aac-4d71-aac8-48781af89ba8.png" width="450" height="800">

