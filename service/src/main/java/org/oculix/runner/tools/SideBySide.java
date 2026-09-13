package org.oculix.runner.tools;

import org.oculix.runner.Db;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One compositor, two uses: "log on the left, screen on the right, same
 * clock" drawn at an instant t. {@link #render} replays a finished run into an
 * mp4 through ffmpeg; the live MJPEG route draws the same picture every tick
 * while the run executes.
 */
public final class SideBySide {

  public record Line(long atMs, String text) { }

  public static final int LEFT_W = 760;
  private static final int LINE_H = 20;
  private static final Color BG = new Color(0x10, 0x14, 0x18);
  private static final Color HEADER = new Color(0x1c, 0x24, 0x2c);
  private static final Color OLD = new Color(0xb8, 0xc0, 0xc8);
  private static final Color NEW = Color.WHITE;
  private static final Color TS = new Color(0x7a, 0x8a, 0x9a);
  private static final Color STEP = new Color(0xff, 0xd2, 0x66);
  private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 15);
  private static final Font BOLD = new Font(Font.MONOSPACED, Font.BOLD, 16);

  private SideBySide() { }

  public static List<Line> lines(Db db, long runId) throws SQLException {
    List<Line> lines = new ArrayList<>();
    for (Map<String, Object> r : db.query("SELECT at, line FROM run_lines WHERE run_id=? ORDER BY seq", runId)) {
      lines.add(new Line(Instant.parse(String.valueOf(r.get("at"))).toEpochMilli(), String.valueOf(r.get("line"))));
    }
    return lines;
  }

  /** Canvas sized for a given screen: left panel + screen, even dimensions for yuv420p. */
  public static BufferedImage canvasFor(int screenW, int screenH) {
    int w = LEFT_W + screenW, h = Math.max(screenH, 480);
    if (w % 2 == 1) w++;
    if (h % 2 == 1) h++;
    return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
  }

  /** Draws the picture at instant t. {@code ended} null while the run is still running. */
  public static void compose(BufferedImage canvas, List<Line> lines, BufferedImage screen, long t,
                             long started, Long ended, String status, String title) {
    Graphics2D g = canvas.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(BG);
    g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
    if (screen != null) g.drawImage(screen, LEFT_W, 0, null);

    int h = canvas.getHeight();
    int headerH = 52;
    g.setColor(HEADER);
    g.fillRect(0, 0, LEFT_W, headerH);
    g.setFont(BOLD);
    g.setColor(NEW);
    g.drawString(title, 14, 22);
    g.setFont(MONO);
    g.setColor(TS);
    long end = ended == null ? t : Math.min(t, ended);
    double elapsed = Math.max(0, (end - started) / 1000.0);
    String state = (ended != null && t >= ended) ? status : "running";
    g.drawString(String.format("t+%6.2fs   %s", elapsed, state), 14, 44);

    FontMetrics fm = g.getFontMetrics(MONO);
    int visible = (h - headerH - 12) / LINE_H;
    int count = 0;
    for (Line l : lines) if (l.atMs() <= t) count++;
    int from = Math.max(0, count - visible);
    int y = headerH + 8 + fm.getAscent();
    int charW = fm.charWidth('M');
    int maxChars = Math.max(10, (LEFT_W - 28 - 13 * charW) / charW);
    for (int i = from; i < count; i++) {
      Line l = lines.get(i);
      g.setColor(TS);
      g.drawString(Instant.ofEpochMilli(l.atMs()).toString().substring(11, 23), 14, y);
      String text = l.text();
      if (text.length() > maxChars) text = text.substring(0, maxChars - 1) + "…";
      g.setColor(i == count - 1 ? NEW : l.text().startsWith("@@STEP|") ? STEP : OLD);
      g.drawString(text, 14 + 13 * charW, y);
      y += LINE_H;
    }
    g.dispose();
  }

  /** Replays a finished run into {@code out}. Returns the number of video frames written. */
  public static int render(Db db, long runId, String title, List<Recorder.Frame> frames, Path out, int fps) throws Exception {
    if (frames.isEmpty()) throw new IllegalStateException("no frames recorded");
    List<Line> lines = lines(db, runId);
    Map<String, Object> run = db.one("SELECT started_at, ended_at, status FROM runs WHERE id=?", runId);
    long started = run.get("started_at") == null ? frames.get(0).atMs() : Instant.parse(String.valueOf(run.get("started_at"))).toEpochMilli();
    long ended = run.get("ended_at") == null ? frames.get(frames.size() - 1).atMs() : Instant.parse(String.valueOf(run.get("ended_at"))).toEpochMilli();
    String status = String.valueOf(run.get("status"));

    BufferedImage first = ImageIO.read(frames.get(0).file().toFile());
    BufferedImage canvas = canvasFor(first.getWidth(), first.getHeight());
    int w = canvas.getWidth(), h = canvas.getHeight();

    long t0 = Math.min(frames.get(0).atMs(), lines.isEmpty() ? Long.MAX_VALUE : lines.get(0).atMs()) - 500;
    long t1 = Math.max(frames.get(frames.size() - 1).atMs(), ended) + 1500;
    long stepMs = 1000L / fps;

    ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", w + "x" + h, "-r", String.valueOf(fps), "-i", "-",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", out.toString());
    pb.redirectErrorStream(true);
    pb.redirectOutput(out.resolveSibling("ffmpeg.log").toFile());
    Process ffmpeg = pb.start();

    byte[] row = new byte[w * h * 3];
    int written = 0, frameIdx = -1;
    BufferedImage current = first;
    try (OutputStream pipe = ffmpeg.getOutputStream()) {
      for (long t = t0; t <= t1; t += stepMs) {
        int idx = latestFrame(frames, t);
        if (idx != frameIdx && idx >= 0) {
          current = ImageIO.read(frames.get(idx).file().toFile());
          frameIdx = idx;
        }
        compose(canvas, lines, idx >= 0 ? current : null, t, started, ended, status, title);
        int[] px = canvas.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0, j = 0; i < px.length; i++) {
          row[j++] = (byte) (px[i] >> 16);
          row[j++] = (byte) (px[i] >> 8);
          row[j++] = (byte) px[i];
        }
        pipe.write(row);
        written++;
      }
    }
    int rc = ffmpeg.waitFor();
    if (rc != 0) throw new IOException("ffmpeg exited with " + rc + ", see ffmpeg.log");
    return written;
  }

  private static int latestFrame(List<Recorder.Frame> frames, long t) {
    int lo = 0, hi = frames.size() - 1, best = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (frames.get(mid).atMs() <= t) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return best;
  }
}
