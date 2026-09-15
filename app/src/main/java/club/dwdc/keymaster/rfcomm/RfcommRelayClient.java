package club.dwdc.keymaster.rfcomm;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import club.dwdc.keymaster.RelayClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * RFCOMM-based relay client using JSONL framing over BluetoothSocket.
 * Each Nostr message is one JSON line terminated by newline.
 */
public class RfcommRelayClient implements RelayClient {
    private static final String TAG = "RfcommRelayClient";

    private final String btAddress;
    private final int channel;
    private Listener listener;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private Thread readerThread;

    public RfcommRelayClient(String btAddress, int channel) {
        this.btAddress = btAddress;
        this.channel = channel;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void connect() throws Exception {
        if (listener == null) {
            throw new IllegalStateException("listener must be set before connect()");
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new IOException("No Bluetooth adapter");
        }

        BluetoothDevice device = adapter.getRemoteDevice(btAddress);
        socket = (BluetoothSocket) device.getClass()
                .getMethod("createRfcommSocket", int.class)
                .invoke(device, channel);
        socket.connect();

        outputStream = socket.getOutputStream();
        Log.i(TAG, "Connected to " + btAddress + " channel " + channel);
        listener.onConnected();

        readerThread = new Thread(this::readLoop, "rfcomm-reader");
        readerThread.start();
    }

    @Override
    public void send(String message) {
        OutputStream os = outputStream;
        if (os == null) {
            Log.w(TAG, "send() called but not connected");
            return;
        }
        try {
            byte[] data = (message + "\n").getBytes();
            os.write(data);
            os.flush();
        } catch (IOException e) {
            Log.e(TAG, "Send failed: " + e.getMessage());
            listener.onError(e.getMessage());
        }
    }

    @Override
    public void close() {
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.w(TAG, "Close error: " + e.getMessage());
            }
            socket = null;
            outputStream = null;
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {
            String line;
            while (!Thread.currentThread().isInterrupted()
                    && (line = reader.readLine()) != null) {
                Log.d(TAG, "RX: " + line);
                listener.onMessage(line);
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                Log.e(TAG, "Read error: " + e.getMessage());
                listener.onError(e.getMessage());
            }
        }
        Log.i(TAG, "Reader stopped, connection closed");
        listener.onClosed();
    }
}
