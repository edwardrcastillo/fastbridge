package com.fastmovie.fastbridge;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ExFatVolume {
    private static final String TAG = "FastBridgeExFAT";

    public static class ExFatEntry {
        public final String name;
        public final String path;
        public final boolean isDirectory;
        public final long size;
        public final long dataLength;
        public final long firstCluster;
        public final boolean noFatChain;
        public long[] cachedClusterChain;

        public ExFatEntry(String name, String path, boolean isDirectory, long size, long dataLength, long firstCluster, boolean noFatChain) {
            this.name = name;
            this.path = path;
            this.isDirectory = isDirectory;
            this.size = size;
            this.dataLength = dataLength;
            this.firstCluster = firstCluster;
            this.noFatChain = noFatChain;
        }
    }

    private final ScsiBlockDevice device;
    private final long partitionStartLba;
    private final long fatOffsetSectors;
    private final long fatLengthSectors;
    private final long clusterHeapOffsetSectors;
    private final long clusterCount;
    private final long rootDirCluster;
    private final int volumeSerial;
    private final int bytesPerSector;
    private final int sectorsPerCluster;
    private final int bytesPerCluster;
    private String volumeLabel = "exFAT_USB";

    private final Map<String, List<ExFatEntry>> dirCache = new ConcurrentHashMap<>();

    public ExFatVolume(ScsiBlockDevice device) throws IOException {
        this.device = device;
        this.partitionStartLba = findExFatPartition(device);
        if (this.partitionStartLba < 0) {
            throw new IOException("No exFAT partition found on USB disk");
        }

        byte[] vbr = new byte[512];
        device.readBytes(this.partitionStartLba * device.getBlockSize(), vbr, 0, 512);
        ByteBuffer bb = ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN);

        this.fatOffsetSectors = bb.getInt(80) & 0xFFFFFFFFL;
        this.fatLengthSectors = bb.getInt(84) & 0xFFFFFFFFL;
        this.clusterHeapOffsetSectors = bb.getInt(88) & 0xFFFFFFFFL;
        this.clusterCount = bb.getInt(92) & 0xFFFFFFFFL;
        this.rootDirCluster = bb.getInt(96) & 0xFFFFFFFFL;
        this.volumeSerial = bb.getInt(100);

        int bpsShift = vbr[108] & 0xFF;
        int spcShift = vbr[109] & 0xFF;
        this.bytesPerSector = 1 << bpsShift;
        this.sectorsPerCluster = 1 << spcShift;
        this.bytesPerCluster = this.bytesPerSector * this.sectorsPerCluster;

        Log.i(TAG, String.format("Mounted exFAT at LBA %d: clusterSize=%d KB, clusters=%d, rootCluster=%d",
                partitionStartLba, bytesPerCluster / 1024, clusterCount, rootDirCluster));

        // Pre-scan root directory to also load Volume Label (0x83)
        listDirectory("/");
    }

    private static boolean isExFatBootSector(byte[] sector) {
        if (sector == null || sector.length < 16) return false;
        String sig = new String(sector, 3, 8, StandardCharsets.US_ASCII);
        return "EXFAT   ".equals(sig);
    }

    private static long findExFatPartition(ScsiBlockDevice dev) throws IOException {
        int blockSize = dev.getBlockSize();
        byte[] sec0 = new byte[512];
        dev.readBytes(0, sec0, 0, 512);
        if (isExFatBootSector(sec0)) {
            return 0L;
        }

        // Check GPT Header at LBA 1
        byte[] sec1 = new byte[512];
        dev.readBytes(blockSize, sec1, 0, 512);
        String gptSig = new String(sec1, 0, 8, StandardCharsets.US_ASCII);
        if ("EFI PART".equals(gptSig)) {
            ByteBuffer gpt = ByteBuffer.wrap(sec1).order(ByteOrder.LITTLE_ENDIAN);
            long partEntryLba = gpt.getLong(72);
            int numEntries = Math.min(128, gpt.getInt(80));
            int entrySize = gpt.getInt(84);
            byte[] tableBytes = new byte[numEntries * entrySize];
            dev.readBytes(partEntryLba * blockSize, tableBytes, 0, tableBytes.length);

            ByteBuffer tableBb = ByteBuffer.wrap(tableBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < numEntries; i++) {
                int off = i * entrySize;
                long startLba = tableBb.getLong(off + 32);
                long endLba = tableBb.getLong(off + 40);
                if (startLba > 0 && endLba >= startLba && startLba < dev.getTotalBlocks()) {
                    byte[] probe = new byte[512];
                    dev.readBytes(startLba * blockSize, probe, 0, 512);
                    if (isExFatBootSector(probe)) {
                        Log.i(TAG, "Found GPT exFAT partition #" + (i + 1) + " at LBA " + startLba);
                        return startLba;
                    }
                }
            }
        }

        // Check MBR partitions in Sector 0 (offsets 446, 462, 478, 494)
        ByteBuffer mbrBb = ByteBuffer.wrap(sec0).order(ByteOrder.LITTLE_ENDIAN);
        for (int p = 0; p < 4; p++) {
            int off = 446 + p * 16;
            long startLba = mbrBb.getInt(off + 8) & 0xFFFFFFFFL;
            long count = mbrBb.getInt(off + 12) & 0xFFFFFFFFL;
            if (startLba > 0 && count > 0 && startLba < dev.getTotalBlocks()) {
                byte[] probe = new byte[512];
                dev.readBytes(startLba * blockSize, probe, 0, 512);
                if (isExFatBootSector(probe)) {
                    Log.i(TAG, "Found MBR exFAT partition #" + (p + 1) + " at LBA " + startLba);
                    return startLba;
                }
            }
        }

        return -1L;
    }

    public long clusterToByteOffset(long cluster) {
        long sectorOffset = clusterHeapOffsetSectors + ((cluster - 2L) * (long) sectorsPerCluster);
        return (partitionStartLba * device.getBlockSize()) + (sectorOffset * bytesPerSector);
    }

    public long getNextCluster(long cluster) throws IOException {
        long fatByteStart = (partitionStartLba * device.getBlockSize()) + (fatOffsetSectors * bytesPerSector);
        long entryByteOffset = fatByteStart + (cluster * 4L);
        byte[] buf = new byte[4];
        device.readBytes(entryByteOffset, buf, 0, 4);
        long next = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        if (next >= 0xFFFFFFF7L || next < 2L) {
            return -1L;
        }
        return next;
    }

    private byte[] readDirectoryBytes(long firstCluster, boolean noFatChain, long dataLength) throws IOException {
        if (firstCluster < 2L) {
            return new byte[0];
        }
        if (noFatChain && dataLength > 0) {
            int len = (int) Math.min(dataLength, 16 * 1024 * 1024L);
            byte[] data = new byte[len];
            device.readBytes(clusterToByteOffset(firstCluster), data, 0, len);
            return data;
        }

        List<byte[]> clusters = new ArrayList<>();
        long cur = firstCluster;
        int safety = 0;
        while (cur >= 2L && safety < 8192) {
            byte[] cBuf = new byte[bytesPerCluster];
            device.readBytes(clusterToByteOffset(cur), cBuf, 0, bytesPerCluster);
            clusters.add(cBuf);
            // If last 32-byte entry or any entry is 0x00, we can stop following FAT chain early
            boolean hasEndMarker = false;
            for (int off = 0; off < bytesPerCluster; off += 32) {
                if (cBuf[off] == 0x00) {
                    hasEndMarker = true;
                    break;
                }
            }
            if (hasEndMarker) {
                break;
            }
            cur = getNextCluster(cur);
            safety++;
        }

        byte[] result = new byte[clusters.size() * bytesPerCluster];
        for (int i = 0; i < clusters.size(); i++) {
            System.arraycopy(clusters.get(i), 0, result, i * bytesPerCluster, bytesPerCluster);
        }
        return result;
    }

    public static String normalizePath(String path) {
        if (path == null || path.trim().isEmpty()) return "/";
        String p = path.trim().replace('\\', '/');
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public synchronized List<ExFatEntry> listDirectory(String dirPath) throws IOException {
        String normPath = normalizePath(dirPath);
        List<ExFatEntry> cached = dirCache.get(normPath);
        if (cached != null) {
            return cached;
        }

        long targetCluster = rootDirCluster;
        boolean targetNoFat = false;
        long targetDataLen = 0;

        if (!"/".equals(normPath)) {
            ExFatEntry dirEntry = resolveEntry(normPath);
            if (dirEntry == null || !dirEntry.isDirectory) {
                throw new IOException("Directory not found: " + normPath);
            }
            targetCluster = dirEntry.firstCluster;
            targetNoFat = dirEntry.noFatChain;
            targetDataLen = dirEntry.dataLength;
        }

        byte[] raw = readDirectoryBytes(targetCluster, targetNoFat, targetDataLen);
        List<ExFatEntry> entries = new ArrayList<>();

        ByteBuffer rawBb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int totalEntries = raw.length / 32;
        for (int i = 0; i < totalEntries; i++) {
            int off = i * 32;
            int entryType = raw[off] & 0xFF;
            if (entryType == 0x00) {
                break; // End of directory
            }
            if (entryType == 0x83 && "/".equals(normPath)) {
                // Volume Label Entry
                int charCount = Math.min(11, raw[off + 1] & 0xFF);
                if (charCount > 0) {
                    this.volumeLabel = new String(raw, off + 2, charCount * 2, StandardCharsets.UTF_16LE).trim();
                }
                continue;
            }
            if (entryType == 0x85) {
                int secondaryCount = raw[off + 1] & 0xFF;
                if (i + secondaryCount >= totalEntries || secondaryCount < 2) {
                    continue;
                }
                int attr = rawBb.getShort(off + 4) & 0xFFFF;
                boolean isDir = (attr & 0x10) != 0;

                int streamOff = (i + 1) * 32;
                if ((raw[streamOff] & 0xFF) != 0xC0) {
                    continue;
                }
                int secFlags = raw[streamOff + 1] & 0xFF;
                boolean noFatChain = (secFlags & 0x02) != 0;
                int nameLen = raw[streamOff + 3] & 0xFF;
                long validDataLength = rawBb.getLong(streamOff + 8);
                long firstCluster = rawBb.getInt(streamOff + 20) & 0xFFFFFFFFL;
                long dataLength = rawBb.getLong(streamOff + 24);

                StringBuilder nameSb = new StringBuilder();
                for (int s = 2; s <= secondaryCount; s++) {
                    int nameOff = (i + s) * 32;
                    if ((raw[nameOff] & 0xFF) == 0xC1) {
                        String part = new String(raw, nameOff + 2, 30, StandardCharsets.UTF_16LE);
                        nameSb.append(part);
                    }
                }
                String fullName = nameSb.toString();
                if (nameLen > 0 && fullName.length() > nameLen) {
                    fullName = fullName.substring(0, nameLen);
                } else {
                    int nulIdx = fullName.indexOf('\0');
                    if (nulIdx >= 0) {
                        fullName = fullName.substring(0, nulIdx);
                    }
                }

                i += secondaryCount; // advance past secondary entries

                // Skip macOS spotlight/trash/resource fork noise
                if (fullName.isEmpty() || fullName.startsWith("._") ||
                    ".".equals(fullName) || "..".equals(fullName) ||
                    ".DS_Store".equalsIgnoreCase(fullName) ||
                    ".Spotlight-V100".equalsIgnoreCase(fullName) ||
                    ".Trashes".equalsIgnoreCase(fullName) ||
                    ".TemporaryItems".equalsIgnoreCase(fullName) ||
                    ".fseventsd".equalsIgnoreCase(fullName) ||
                    "$TXRAJNL.DAT".equalsIgnoreCase(fullName) ||
                    "$Recycle.Bin".equalsIgnoreCase(fullName) ||
                    "System Volume Information".equalsIgnoreCase(fullName)) {
                    continue;
                }

                String childPath = "/".equals(normPath) ? ("/" + fullName) : (normPath + "/" + fullName);
                entries.add(new ExFatEntry(fullName, childPath, isDir, validDataLength, dataLength, firstCluster, noFatChain));
            }
        }

        Collections.sort(entries, new Comparator<ExFatEntry>() {
            @Override
            public int compare(ExFatEntry a, ExFatEntry b) {
                if (a.isDirectory != b.isDirectory) {
                    return a.isDirectory ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        dirCache.put(normPath, entries);
        return entries;
    }

    public ExFatEntry resolveEntry(String path) throws IOException {
        String norm = normalizePath(path);
        if ("/".equals(norm)) {
            return new ExFatEntry("/", "/", true, 0, 0, rootDirCluster, false);
        }
        int lastSlash = norm.lastIndexOf('/');
        String parentPath = (lastSlash <= 0) ? "/" : norm.substring(0, lastSlash);
        String targetName = norm.substring(lastSlash + 1);

        List<ExFatEntry> siblings = listDirectory(parentPath);
        for (ExFatEntry e : siblings) {
            if (e.name.equals(targetName) || e.name.equalsIgnoreCase(targetName)) {
                return e;
            }
        }
        return null;
    }

    private synchronized long[] getOrBuildClusterChain(ExFatEntry entry) throws IOException {
        if (entry.cachedClusterChain != null) {
            return entry.cachedClusterChain;
        }
        int expectedClusters = (int) ((entry.size + bytesPerCluster - 1) / bytesPerCluster);
        long[] chain = new long[Math.max(1, expectedClusters)];
        long cur = entry.firstCluster;
        int idx = 0;
        while (cur >= 2L && idx < chain.length) {
            chain[idx++] = cur;
            cur = getNextCluster(cur);
        }
        entry.cachedClusterChain = chain;
        return chain;
    }

    public int readFileBytes(ExFatEntry file, long fileOffset, byte[] dest, int destOffset, int length) throws IOException {
        if (fileOffset >= file.size || length <= 0 || file.firstCluster < 2L) {
            return 0;
        }
        int toRead = (int) Math.min((long) length, file.size - fileOffset);
        if (file.noFatChain) {
            long diskByteOffset = clusterToByteOffset(file.firstCluster) + fileOffset;
            device.readBytes(diskByteOffset, dest, destOffset, toRead);
            return toRead;
        }

        // Fragmented file: walk cluster chain
        long[] chain = getOrBuildClusterChain(file);
        int copied = 0;
        while (copied < toRead) {
            long pos = fileOffset + copied;
            int clusterIdx = (int) (pos / bytesPerCluster);
            int offsetInCluster = (int) (pos % bytesPerCluster);
            if (clusterIdx >= chain.length || chain[clusterIdx] < 2L) {
                break;
            }
            int chunk = Math.min(toRead - copied, bytesPerCluster - offsetInCluster);
            long diskByteOffset = clusterToByteOffset(chain[clusterIdx]) + offsetInCluster;
            device.readBytes(diskByteOffset, dest, destOffset + copied, chunk);
            copied += chunk;
        }
        return copied;
    }

    public String getVolumeLabel() {
        return volumeLabel;
    }

    public String getVolumeUuidHex() {
        return String.format("%04X-%04X", (volumeSerial >>> 16) & 0xFFFF, volumeSerial & 0xFFFF);
    }

    public long getPartitionStartLba() {
        return partitionStartLba;
    }

    public int getBytesPerCluster() {
        return bytesPerCluster;
    }

    public long getTotalVolumeBytes() {
        return clusterCount * (long) bytesPerCluster;
    }

    public ScsiBlockDevice getDevice() {
        return device;
    }
}
