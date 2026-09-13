package org.oculix.runner.tools;

import org.sikuli.vnc.VNCScreen;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the screen of a VNC target while a run executes: its own VNC session
 * (the registry is per thread since OculiX 1be2b84e), one PNG every
 * {@code intervalMs}, and a frames.csv of "epoch_ms,filename" next to them.
 *
 * Experimental, one-shot tooling: nothing here is reachable through the API.
 */
public final class Recorder extends Thread {

  public record Frame(long atMs, Path file) { }

  private final String host;
  private final int port;
  private final Path dir;
  private final long intervalMs;
  private volatile boolean stopRequested = false;
  private final List<Frame> frames = new ArrayList<>();
  private volatile BufferedImage latest = null;
  private String failure = null;

  public Recorder(String host, int port, Path dir, long intervalMs) {
    super("oculix-recorder");
    this.host = host;
    this.port = port;
    this.dir = dir;
    this.intervalMs = intervalMs;
    setDaemon(true);
  }

  @Override
  public void run() {
    VNCScreen vnc = null;
    try {
      Files.createDirectories(dir);
      vnc = VNCScreen.start(host, port, 10, 0);
      if (vnc == null || !vnc.isRunning()) {
        failure = "recorder: no VNC connection to " + host + ":" + port;
        return;
      }
      int n = 0;
      while (!stopRequested) {
        long t = System.currentTimeMillis();
        BufferedImage img = vnc.capture().getImage();
        Path f = dir.resolve(String.format("f%05d.png", n++));
        ImageIO.write(img, "png", f.toFile());
        latest = img;
        synchronized (frames) {
          frames.add(new Frame(t, f));
        }
        long spent = System.currentTimeMillis() - t;
        if (spent < intervalMs) Thread.sleep(intervalMs - spent);
      }
    } catch (InterruptedException ignored) {
      // stop requested through interrupt
    } catch (Throwable e) {
      failure = "recorder: " + e;
    } finally {
      if (vnc != null) {
        try {
          vnc.stop();
        } catch (Throwable ignored) { }
      }
      writeIndex();
    }
  }

  private void writeIndex() {
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("frames.csv")))) {
      synchronized (frames) {
        for (Frame f : frames) w.println(f.atMs() + "," + f.file().getFileName());
      }
    } catch (IOException e) {
      failure = "recorder: could not write frames.csv: " + e;
    }
  }

  /** Stops recording and waits for the thread to close its VNC session. */
  public List<Frame> stopAndJoin() {
    stopRequested = true;
    interrupt();
    try {
      join(15_000);
    } catch (InterruptedException ignored) { }
    synchronized (frames) {
      return new ArrayList<>(frames);
    }
  }

  public String failure() { return failure; }
  public Path dir() { return dir; }
  /** Most recent capture, for the live view; null until the first one. */
  public BufferedImage latest() { return latest; }
  public boolean stopped() { return stopRequested || !isAlive(); }
}
