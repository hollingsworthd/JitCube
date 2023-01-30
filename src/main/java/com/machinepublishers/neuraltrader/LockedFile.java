package com.machinepublishers.neuraltrader;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;

public class LockedFile {

  public static String read(File f) {
    synchronized (f.getName().intern()) {
      try (RandomAccessFile file = new RandomAccessFile(f,
          "rws"); FileChannel channel = file.getChannel(); FileLock ignored = channel.lock()) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(512);
        StringBuilder builder = new StringBuilder();
        while (channel.read(byteBuffer) != -1) {
          byteBuffer.rewind();
          builder.append(StandardCharsets.UTF_8.decode(byteBuffer));
          byteBuffer.flip();
        }
        if (builder.isEmpty()) {
          throw new RuntimeException("Empty " + f.getAbsolutePath());
        }
        return builder.toString();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static void write(File f, String string) {
    synchronized (f.getName().intern()) {
      try (RandomAccessFile file = new RandomAccessFile(f,
          "rws"); FileChannel channel = file.getChannel(); FileLock ignored = channel.lock()) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(string.getBytes(StandardCharsets.UTF_8));
        while (byteBuffer.hasRemaining()) {
          channel.write(byteBuffer);
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static boolean exists(File f) {
    synchronized (f.getName().intern()) {
      try (RandomAccessFile file = new RandomAccessFile(f,
          "rws"); FileChannel channel = file.getChannel(); FileLock ignored = channel.lock()) {
        return channel.size() > 0;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
