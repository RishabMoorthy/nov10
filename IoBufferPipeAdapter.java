package org.framework.utils;

import java.io.ByteArrayOutputStream;

public class IoBufferPipeAdapter {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private int position = 0;
    private int lastRead = 0;
    boolean closed = false;

    public synchronized void close() {
        if (!closed) {
            closed = true;
            this.notifyAll();
        }
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized void append(byte[] data, int offset, int length) {
        buffer.write(data, offset, length);
    }

    public synchronized int remaining() {
        return buffer.size() - position;
    }

    public int size() {
        return buffer.size();
    }

    public synchronized void get(byte[] target, int offset, int length) {
        byte[] source = buffer.toByteArray();
        System.arraycopy(source, position, target, offset, length);
        position += length;
        lastRead = length;
        Logger.getInstance().info("[get] read lines", position, lastRead, new String(source));
    }

    public synchronized int position() {
        return position;
    }

    public synchronized void position(int newPosition) {
        this.position = newPosition;
    }

    public synchronized byte[] peek() {
        return buffer.toByteArray();
    }

    public synchronized void clear() {
        buffer.reset();
        position = 0;
    }

    /**
     * Removes the first {@code count} bytes (one complete TCP message) and keeps the rest,
     * which is the start of the next message. Position and the revert marker go back to 0.
     */
    public synchronized void consume(int count) {
        byte[] all = buffer.toByteArray();
        int n = Math.max(0, Math.min(count, all.length));
        buffer.reset();
        buffer.write(all, n, all.length - n);
        position = 0;
        lastRead = 0;
    }

    public synchronized void resetRevert() {
        lastRead = 0;
    }

    public synchronized void revert() {
        if (position - lastRead >= 0) {
            position -= lastRead;
            lastRead = 0;
        } else {
            Logger.getInstance().info("inside else", position, lastRead);
            position = 0;
            lastRead = 0;
        }

        Logger.getInstance().info("after revert", position, lastRead);
    }

    public synchronized int getLastRead() {
        System.out.println("Current pods" + this.position);
        Logger.getInstance().info("[Get Last Read] Current pos", position, lastRead);
        return lastRead;
    }
}

