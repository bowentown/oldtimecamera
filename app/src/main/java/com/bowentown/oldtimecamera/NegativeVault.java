package com.bowentown.oldtimecamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rolling 36-exposure lossless master negative vault ({@code filesDir/negatives/}).
 *
 * <p>Stores pre-filter, upright-cropped master negatives (JPEG quality 94 with preserved
 * camera EXIF tags) so captured frames can be re-developed later in the album darkroom with
 * any film stock, white balance, paper frame, date mode, or note without double-filtering.
 * Oldest entries beyond {@link #MAX_NEGATIVES} are automatically evicted.</p>
 */
public final class NegativeVault {

    /** Rolling cap matching one standard 36-exposure 135 film roll. */
    public static final int MAX_NEGATIVES = 36;

    private static final String DIR_NAME = "negatives";
    private static final String INDEX_FILE = "index.tsv";
    private static final Object LOCK = new Object();
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    public static final class Record {
        public final String printDisplayName;
        public final String printUri;
        public final String negFileName;
        public final CameraPrefs.AspectRatio aspect;
        public final long createdMillis;

        public Record(String printDisplayName, String printUri, String negFileName,
                      CameraPrefs.AspectRatio aspect, long createdMillis) {
            this.printDisplayName = sanitizeField(printDisplayName);
            this.printUri = sanitizeField(printUri);
            this.negFileName = sanitizeField(negFileName);
            this.aspect = aspect != null ? aspect : CameraPrefs.AspectRatio.RATIO_3_4;
            this.createdMillis = createdMillis;
        }
    }

    public static final class Entry {
        public final Record record;
        public final File negFile;

        public Entry(Record record, File negFile) {
            this.record = record;
            this.negFile = negFile;
        }

        public byte[] readJpegBytes() {
            if (negFile == null || !negFile.isFile()) return null;
            try (FileInputStream fis = new FileInputStream(negFile);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream(
                         Math.max(4096, (int) Math.min(negFile.length(), 8 * 1024 * 1024L)))) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = fis.read(buf)) >= 0) {
                    if (n > 0) bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            } catch (IOException e) {
                return null;
            }
        }
    }

    private NegativeVault() {}

    private static String sanitizeField(String raw) {
        if (raw == null) return "";
        return raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** Pure-Java TSV encoder for unit testing without Android Context. */
    public static String encodeRecords(List<Record> records) {
        if (records == null || records.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Record r : records) {
            if (r == null || r.negFileName.isEmpty()) continue;
            sb.append(r.printDisplayName).append('\t')
                    .append(r.printUri).append('\t')
                    .append(r.negFileName).append('\t')
                    .append(r.aspect.name()).append('\t')
                    .append(r.createdMillis).append('\n');
        }
        return sb.toString();
    }

    /** Pure-Java TSV decoder for unit testing without Android Context. */
    public static List<Record> decodeRecords(String rawTsv) {
        List<Record> list = new ArrayList<>();
        if (rawTsv == null || rawTsv.isEmpty()) return list;
        String[] lines = rawTsv.split("\n");
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 5) continue;
            String negName = parts[2].trim();
            if (negName.isEmpty() || negName.contains("/") || negName.contains("..")) continue;
            long ts = 0L;
            try {
                ts = Long.parseLong(parts[4].trim());
            } catch (NumberFormatException ignored) {
            }
            list.add(new Record(
                    parts[0],
                    parts[1],
                    negName,
                    CameraPrefs.AspectRatio.fromName(parts[3].trim()),
                    ts));
        }
        return list;
    }

    /**
     * Pure-Java FIFO pruning: keeps the newest {@code maxCount} records (appended at tail)
     * and returns the evicted oldest records.
     */
    public static List<Record> pruneToMax(List<Record> records, int maxCount) {
        List<Record> evicted = new ArrayList<>();
        if (records == null) return evicted;
        int cap = Math.max(1, maxCount);
        while (records.size() > cap) {
            evicted.add(records.remove(0));
        }
        return evicted;
    }

    /** Returns true if any record in {@code records} still points to {@code negFileName}. */
    public static boolean isFileReferenced(List<Record> records, String negFileName) {
        if (records == null || negFileName == null || negFileName.isEmpty()) return false;
        for (Record r : records) {
            if (r != null && negFileName.equals(r.negFileName)) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchesPrint(Record r, String uriStr, String displayName) {
        if (r == null) return false;
        if (displayName != null && !displayName.isEmpty()
                && displayName.equals(r.printDisplayName)) {
            return true;
        }
        return uriStr != null && !uriStr.isEmpty() && uriStr.equals(r.printUri);
    }

    /** Saves a pre-filter upright master negative and associates it with the saved gallery print. */
    public static void saveNegative(Context context, Uri printUri, String printDisplayName,
                                    Bitmap cleanBitmap, byte[] sourceExifJpeg,
                                    CameraPrefs.AspectRatio aspect) {
        saveNegative(context, printUri, printDisplayName, cleanBitmap, sourceExifJpeg, null, aspect);
    }

    /** Saves a pre-filter upright master negative, copying EXIF from either raw JPEG bytes or a Uri. */
    public static void saveNegative(Context context, Uri printUri, String printDisplayName,
                                    Bitmap cleanBitmap, byte[] sourceExifJpeg, Uri sourceExifUri,
                                    CameraPrefs.AspectRatio aspect) {
        if (context == null || cleanBitmap == null || cleanBitmap.isRecycled()) return;
        String uriStr = printUri != null ? printUri.toString() : "";
        String disp = printDisplayName != null ? printDisplayName : "";
        if (uriStr.isEmpty() && disp.isEmpty()) return;

        synchronized (LOCK) {
            File dir = ensureDir(context);
            if (dir == null) return;

            long now = System.currentTimeMillis();
            String negFileName = String.format(Locale.US, "neg_%d_%02d.jpg",
                    now, SEQ.incrementAndGet() % 100);
            File negFile = new File(dir, negFileName);

            Bitmap capped = cleanBitmap;
            boolean ownsCapped = false;
            try {
                if (Math.max(cleanBitmap.getWidth(), cleanBitmap.getHeight()) > 2560) {
                    float scale = 2560f / Math.max(cleanBitmap.getWidth(), cleanBitmap.getHeight());
                    int nw = Math.max(1, Math.round(cleanBitmap.getWidth() * scale));
                    int nh = Math.max(1, Math.round(cleanBitmap.getHeight() * scale));
                    capped = Bitmap.createScaledBitmap(cleanBitmap, nw, nh, true);
                    ownsCapped = capped != cleanBitmap;
                }
                try (FileOutputStream fos = new FileOutputStream(negFile)) {
                    if (!capped.compress(Bitmap.CompressFormat.JPEG, 94, fos)) {
                        //noinspection ResultOfMethodCallIgnored
                        negFile.delete();
                        return;
                    }
                }
                PhotoSaver.copyCameraExifToNegative(context, negFile, sourceExifJpeg, sourceExifUri);
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                negFile.delete();
                return;
            } finally {
                if (ownsCapped && capped != null && !capped.isRecycled()) {
                    capped.recycle();
                }
            }

            List<Record> records = readIndexLocked(dir);
            records.removeIf(r -> matchesPrint(r, uriStr, disp));
            records.add(new Record(disp, uriStr, negFileName, aspect, now));
            pruneToMax(records, MAX_NEGATIVES);
            writeIndexAndSweepOrphansLocked(dir, records);
        }
    }

    /** Links a newly re-developed print to the same underlying negative file without copying bytes. */
    public static void linkDerivedPrint(Context context, Entry sourceEntry,
                                        Uri newPrintUri, String newPrintDisplayName) {
        if (context == null || sourceEntry == null || sourceEntry.record == null) return;
        String uriStr = newPrintUri != null ? newPrintUri.toString() : "";
        String disp = newPrintDisplayName != null ? newPrintDisplayName : "";
        if (uriStr.isEmpty() && disp.isEmpty()) return;

        synchronized (LOCK) {
            File dir = ensureDir(context);
            if (dir == null) return;
            File negFile = new File(dir, sourceEntry.record.negFileName);
            if (!negFile.isFile()) return;

            List<Record> records = readIndexLocked(dir);
            records.removeIf(r -> matchesPrint(r, uriStr, disp));
            records.add(new Record(disp, uriStr, sourceEntry.record.negFileName,
                    sourceEntry.record.aspect, System.currentTimeMillis()));
            pruneToMax(records, MAX_NEGATIVES);
            writeIndexAndSweepOrphansLocked(dir, records);
        }
    }

    /** Looks up a stored master negative for an album item, returning {@code null} if unavailable. */
    public static Entry findNegative(Context context, Uri printUri, String printDisplayName) {
        if (context == null) return null;
        String uriStr = printUri != null ? printUri.toString() : "";
        String disp = printDisplayName != null ? printDisplayName : "";
        if (uriStr.isEmpty() && disp.isEmpty()) return null;

        synchronized (LOCK) {
            File dir = new File(context.getFilesDir(), DIR_NAME);
            if (!dir.isDirectory()) return null;
            List<Record> records = readIndexLocked(dir);
            for (int i = records.size() - 1; i >= 0; i--) {
                Record r = records.get(i);
                if (matchesPrint(r, uriStr, disp)) {
                    File negFile = new File(dir, r.negFileName);
                    if (negFile.isFile()) {
                        return new Entry(r, negFile);
                    }
                }
            }
            return null;
        }
    }

    /** Removes the negative record for a deleted album print and cleans up unreferenced files. */
    public static void deleteForPrint(Context context, Uri printUri, String printDisplayName) {
        if (context == null) return;
        String uriStr = printUri != null ? printUri.toString() : "";
        String disp = printDisplayName != null ? printDisplayName : "";
        if (uriStr.isEmpty() && disp.isEmpty()) return;

        synchronized (LOCK) {
            File dir = new File(context.getFilesDir(), DIR_NAME);
            if (!dir.isDirectory()) return;
            List<Record> records = readIndexLocked(dir);
            boolean changed = false;
            Iterator<Record> it = records.iterator();
            while (it.hasNext()) {
                if (matchesPrint(it.next(), uriStr, disp)) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) {
                writeIndexAndSweepOrphansLocked(dir, records);
            }
        }
    }

    private static File ensureDir(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) return null;
        return dir;
    }

    private static List<Record> readIndexLocked(File dir) {
        File idx = new File(dir, INDEX_FILE);
        if (!idx.isFile()) return new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(idx), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
            return new ArrayList<>();
        }
        return decodeRecords(sb.toString());
    }

    private static void writeIndexAndSweepOrphansLocked(File dir, List<Record> records) {
        File idx = new File(dir, INDEX_FILE);
        String encoded = encodeRecords(records);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(idx, false), StandardCharsets.UTF_8)) {
            writer.write(encoded);
        } catch (IOException ignored) {
        }

        Set<String> referenced = new HashSet<>();
        for (Record r : records) {
            if (r != null && !r.negFileName.isEmpty()) {
                referenced.add(r.negFileName);
            }
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) {
                String name = f.getName();
                if (INDEX_FILE.equals(name)) continue;
                if (name.startsWith("neg_") && !referenced.contains(name)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }
}
