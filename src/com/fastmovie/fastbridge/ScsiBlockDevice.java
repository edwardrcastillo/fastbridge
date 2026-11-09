package com.fastmovie.fastbridge;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class ScsiBlockDevice {
    private static final String TAG = "FastBridgeSCSI";
    private static final int CBW_SIGNATURE = 0x43425355; // "USBC" LE
    private static final int CSW_SIGNATURE = 0x53425355; // "USBS" LE
    private static final int TIMEOUT_MS = 8000;

    // 64 KB chunk cache (128 sectors of 512 bytes) -> 256 chunks = 16 MB RAM
    private static final int SECTORS_PER_CHUNK = 128;
    private static final int MAX_CACHED_CHUNKS = 256;

    private final UsbDevice usbDevice;
    private final UsbInterface usbInterface;
    private final UsbDeviceConnection connection;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;

    private int tagCounter = 1;
    private int blockSize = 512;
    private long totalBlocks = 0;
    private String vendorProduct = "USB Storage";

    private final LinkedHashMap<Long, byte[]> chunkCache = new LinkedHashMap<Long, byte[]>(MAX_CACHED_CHUNKS + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > MAX_CACHED_CHUNKS;
        }
    };

    public ScsiBlockDevice(UsbManager usbManager, UsbDevice device) throws IOException {
        this.usbDevice = device;
        UsbInterface foundIntf = null;
        UsbEndpoint foundIn = null;
        UsbEndpoint foundOut = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE &&
                (intf.getInterfaceSubclass() == 6 || intf.getInterfaceSubclass() == 1) &&
                intf.getInterfaceProtocol() == 0x50) {
                for (int j = 0; j < intf.getEndpointCount(); j++) {
                    UsbEndpoint ep = intf.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                            foundIn = ep;
                        } else {
                            foundOut = ep;
                        }
                    }
                }
                if (foundIn != null && foundOut != null) {
                    foundIntf = intf;
                    break;
                }
            }
        }

        if (foundIntf == null || foundIn == null || foundOut == null) {
            throw new IOException("No valid USB Mass Storage BOT interface found on device");
        }

        this.usbInterface = foundIntf;
        this.epIn = foundIn;
        this.epOut = foundOut;

        this.connection = usbManager.openDevice(device);
        if (this.connection == null) {
            throw new IOException("usbManager.openDevice() returned null (permission missing?)");
        }

        if (!this.connection.claimInterface(this.usbInterface, true)) {
            this.connection.close();
            throw new IOException("Failed to claim USB Mass Storage interface (force=true)");
        }

        initScsi();
    }

    public static boolean isMassStorageDevice(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) {
                return true;
            }
        }
        return false;
    }

    private synchronized void clearHalt(UsbEndpoint ep) {
        byte[] empty = new byte[0];
        connection.controlTransfer(0x02, 0x01, 0, ep.getAddress(), empty, 0, 1000);
    }

    private synchronized void clearSense() {
        try {
            byte[] senseCmd = new byte[] { 0x03, 0, 0, 0, 18, 0 };
            byte[] senseData = new byte[18];
            scsiTransferIn(senseCmd, senseData, 0, 18, false);
        } catch (IOException ignored) {
        }
    }

    private synchronized void initScsi() throws IOException {
        // 1. INQUIRY (0x12)
        byte[] inquiryCmd = new byte[] { 0x12, 0, 0, 0, 36, 0 };
        byte[] inquiryData = new byte[36];
        scsiTransferIn(inquiryCmd, inquiryData, 0, 36, true);
        String vendor = new String(inquiryData, 8, 8, StandardCharsets.US_ASCII).trim();
        String product = new String(inquiryData, 16, 16, StandardCharsets.US_ASCII).trim();
        this.vendorProduct = (vendor + " " + product).trim();
        if (this.vendorProduct.isEmpty() && usbDevice.getProductName() != null) {
            this.vendorProduct = usbDevice.getProductName().trim();
        }
        Log.i(TAG, "SCSI Inquiry: " + this.vendorProduct);

        // 2. Clear any Unit Attention via TEST UNIT READY + REQUEST SENSE
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                byte[] turCmd = new byte[] { 0x00, 0, 0, 0, 0, 0 };
                scsiTransferIn(turCmd, new byte[0], 0, 0, false);
                break;
            } catch (IOException e) {
                clearSense();
            }
        }

        // 3. READ CAPACITY(10) (0x25)
        byte[] cap10Cmd = new byte[10];
        cap10Cmd[0] = 0x25;
        byte[] cap10Data = new byte[8];
        scsiTransferIn(cap10Cmd, cap10Data, 0, 8, true);

        ByteBuffer bb = ByteBuffer.wrap(cap10Data).order(ByteOrder.BIG_ENDIAN);
        long lastLba = bb.getInt() & 0xFFFFFFFFL;
        int blkLen = bb.getInt();
        if (blkLen > 0) {
            this.blockSize = blkLen;
        }
        if (lastLba == 0xFFFFFFFFL) {
            // READ CAPACITY(16) (0x9E / 0x10)
            byte[] cap16Cmd = new byte[16];
            cap16Cmd[0] = (byte) 0x9E;
            cap16Cmd[1] = 0x10;
            cap16Cmd[13] = 32;
            byte[] cap16Data = new byte[32];
            scsiTransferIn(cap16Cmd, cap16Data, 0, 32, true);
            ByteBuffer bb16 = ByteBuffer.wrap(cap16Data).order(ByteOrder.BIG_ENDIAN);
            this.totalBlocks = bb16.getLong(0) + 1L;
            this.blockSize = bb16.getInt(8);
        } else {
            this.totalBlocks = lastLba + 1L;
        }
        Log.i(TAG, "SCSI Capacity: " + totalBlocks + " blocks of " + blockSize + " bytes (" + (getTotalBytes() / (1024 * 1024 * 1024)) + " GB)");
    }

    private synchronized void scsiTransferIn(byte[] cdb, byte[] dataBuffer, int offset, int expectedLength, boolean allowRetry) throws IOException {
        int tag = tagCounter++;
        ByteBuffer cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
        cbw.putInt(CBW_SIGNATURE);
        cbw.putInt(tag);
        cbw.putInt(expectedLength);
        cbw.put((byte) (expectedLength > 0 ? 0x80 : 0x00));
        cbw.put((byte) 0); // LUN 0
        cbw.put((byte) cdb.length);
        cbw.put(cdb);
        byte[] cbwBytes = cbw.array();

        int sent = connection.bulkTransfer(epOut, cbwBytes, cbwBytes.length, TIMEOUT_MS);
        if (sent != 31) {
            clearHalt(epOut);
            if (allowRetry) {
                clearSense();
                scsiTransferIn(cdb, dataBuffer, offset, expectedLength, false);
                return;
            }
            throw new IOException("Failed to send CBW, sent=" + sent);
        }

        int totalRead = 0;
        while (totalRead < expectedLength) {
            int toRead = Math.min(16384, expectedLength - totalRead);
            int r = connection.bulkTransfer(epIn, dataBuffer, offset + totalRead, toRead, TIMEOUT_MS);
            if (r < 0) {
                clearHalt(epIn);
                byte[] cswTrash = new byte[13];
                connection.bulkTransfer(epIn, cswTrash, 13, 1000);
                clearSense();
                if (allowRetry) {
                    scsiTransferIn(cdb, dataBuffer, offset, expectedLength, false);
                    return;
                }
                throw new IOException("Bulk IN transfer failed at " + totalRead + "/" + expectedLength);
            }
            totalRead += r;
            if (r < toRead) {
                break;
            }
        }

        byte[] cswBytes = new byte[13];
        int cswRead = connection.bulkTransfer(epIn, cswBytes, 13, TIMEOUT_MS);
        if (cswRead != 13) {
            clearHalt(epIn);
            cswRead = connection.bulkTransfer(epIn, cswBytes, 13, TIMEOUT_MS);
            if (cswRead != 13) {
                throw new IOException("Failed to read CSW, cswRead=" + cswRead);
            }
        }

        ByteBuffer csw = ByteBuffer.wrap(cswBytes).order(ByteOrder.LITTLE_ENDIAN);
        int sig = csw.getInt();
        int rTag = csw.getInt();
        int residue = csw.getInt();
        byte status = csw.get();
        if (sig != CSW_SIGNATURE || status != 0) {
            clearSense();
            if (allowRetry) {
                scsiTransferIn(cdb, dataBuffer, offset, expectedLength, false);
                return;
            }
            throw new IOException("SCSI CSW error: sig=0x" + Integer.toHexString(sig) + " tag=" + rTag + " status=" + status + " residue=" + residue);
        }
    }

    private synchronized void rawReadSectors(long lba, int sectorCount, byte[] dest, int destOffset) throws IOException {
        if (lba < 0 || lba >= totalBlocks) {
            throw new IOException("LBA out of range: " + lba + " (totalBlocks=" + totalBlocks + ")");
        }
        int remaining = sectorCount;
        long curLba = lba;
        int curOffset = destOffset;

        while (remaining > 0) {
            int batch = Math.min(128, remaining);
            int byteLen = batch * blockSize;
            byte[] cdb;
            if (curLba <= 0xFFFFFFFFL) {
                cdb = new byte[10];
                cdb[0] = 0x28;
                cdb[2] = (byte) ((curLba >> 24) & 0xFF);
                cdb[3] = (byte) ((curLba >> 16) & 0xFF);
                cdb[4] = (byte) ((curLba >> 8) & 0xFF);
                cdb[5] = (byte) (curLba & 0xFF);
                cdb[7] = (byte) ((batch >> 8) & 0xFF);
                cdb[8] = (byte) (batch & 0xFF);
            } else {
                cdb = new byte[16];
                cdb[0] = (byte) 0x88;
                cdb[2] = (byte) ((curLba >> 56) & 0xFF);
                cdb[3] = (byte) ((curLba >> 48) & 0xFF);
                cdb[4] = (byte) ((curLba >> 40) & 0xFF);
                cdb[5] = (byte) ((curLba >> 32) & 0xFF);
                cdb[6] = (byte) ((curLba >> 24) & 0xFF);
                cdb[7] = (byte) ((curLba >> 16) & 0xFF);
                cdb[8] = (byte) ((curLba >> 8) & 0xFF);
                cdb[9] = (byte) (curLba & 0xFF);
                cdb[10] = (byte) ((batch >> 24) & 0xFF);
                cdb[11] = (byte) ((batch >> 16) & 0xFF);
                cdb[12] = (byte) ((batch >> 8) & 0xFF);
                cdb[13] = (byte) (batch & 0xFF);
            }
            scsiTransferIn(cdb, dest, curOffset, byteLen, true);
            curLba += batch;
            curOffset += byteLen;
            remaining -= batch;
        }
    }

    private synchronized byte[] getCachedChunk(long chunkIndex) throws IOException {
        byte[] chunk = chunkCache.get(chunkIndex);
        if (chunk != null) {
            return chunk;
        }
        long startLba = chunkIndex * SECTORS_PER_CHUNK;
        if (startLba >= totalBlocks) {
            throw new IOException("Chunk LBA beyond disk capacity: " + startLba);
        }
        int count = SECTORS_PER_CHUNK;
        if (startLba + count > totalBlocks) {
            count = (int) (totalBlocks - startLba);
        }
        chunk = new byte[SECTORS_PER_CHUNK * blockSize];
        rawReadSectors(startLba, count, chunk, 0);
        chunkCache.put(chunkIndex, chunk);
        return chunk;
    }

    public synchronized void readBytes(long byteOffset, byte[] dest, int destOffset, int length) throws IOException {
        long chunkBytes = (long) SECTORS_PER_CHUNK * blockSize;
        int copied = 0;
        while (copied < length) {
            long curPos = byteOffset + copied;
            long chunkIndex = curPos / chunkBytes;
            int offsetInChunk = (int) (curPos % chunkBytes);
            int toCopy = (int) Math.min((long) (length - copied), chunkBytes - offsetInChunk);
            byte[] chunk = getCachedChunk(chunkIndex);
            System.arraycopy(chunk, offsetInChunk, dest, destOffset + copied, toCopy);
            copied += toCopy;
        }
    }

    public int getBlockSize() {
        return blockSize;
    }

    public long getTotalBlocks() {
        return totalBlocks;
    }

    public long getTotalBytes() {
        return totalBlocks * blockSize;
    }

    public String getVendorProduct() {
        return vendorProduct;
    }

    public UsbDevice getUsbDevice() {
        return usbDevice;
    }

    public synchronized void close() {
        try {
            connection.releaseInterface(usbInterface);
        } catch (Exception ignored) {
        }
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }
}
