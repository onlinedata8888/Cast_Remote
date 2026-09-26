package com.hari.androidtvremote.androidLib.wire;

import java.io.IOException;
import java.io.InputStream;

public abstract class PacketParser extends Thread {
    private final InputStream mInputStream;

    private volatile boolean isAbort = false;

    // Set only by an explicit, intentional abort() call (user disconnected,
    // reconnecting, app shutting the session down on purpose). This lets run()
    // tell the difference between "we closed it" and "the network/TV closed it
    // on us", so onStreamClosed() only fires for the latter.
    private volatile boolean explicitAbort = false;

    public PacketParser(InputStream inputStream) {
        mInputStream = inputStream;
    }

    /**
     * Read a varint-encoded length from the stream.
     * Returns the decoded length, or -1 if the stream ended.
     * Matches the varint encoding in MessageManager.addLengthAndCreate().
     */
    private int readVarint() throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = mInputStream.read();
            if (b < 0) return -1;  // Stream closed
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;  // MSB=0 → last byte
            shift += 7;
            if (shift >= 35) return -1;  // Malformed varint (>5 bytes)
        }
    }

    @Override
    public void run() {
        while (!isAbort) {
            try {
                int available = readVarint();

                if (available < 0) {
                    // Stream closed
                    isAbort = true;
                    break;
                }
                if (available == 0) continue;

                byte[] buf = new byte[available];
                int bytesRead = 0;
                while (bytesRead < available) {
                    int read = mInputStream.read(buf, bytesRead, available - bytesRead);
                    if (read < 0) {
                        isAbort = true;
                        break;
                    }
                    bytesRead += read;
                }

                if (!isAbort) {
                    messageBufferReceived(buf);
                }
            } catch (IOException e) {
                isAbort = true;
                e.printStackTrace();
            }
        }

        // BUG FIX: previously the read loop just quietly exited here whenever the
        // socket died (TV went to sleep, Wi-Fi hiccup, router NAT timeout, TV
        // closed the connection, etc). Nothing was ever told about it, so the app
        // kept believing it was still connected and never tried to reconnect.
        // Notify the subclass unless *we* were the ones who called abort().
        if (!explicitAbort) {
            onStreamClosed();
        }
    }

    public void abort() {
        explicitAbort = true;
        isAbort = true;
    }

    public abstract void messageBufferReceived(byte[] buf);

    /**
     * Called exactly once, from the reader thread, when the stream ends because
     * the peer/network closed it (not because someone called abort()). Default
     * no-op; override to react (e.g. tell listeners the connection was lost).
     */
    protected void onStreamClosed() {
    }
}
