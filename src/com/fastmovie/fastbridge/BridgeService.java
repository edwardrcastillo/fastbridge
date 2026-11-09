package com.fastmovie.fastbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BridgeService extends Service {
    private static final String TAG = "FastBridgeService";
    private static final String CHANNEL_ID = "fastbridge_channel";
    private static final int NOTIF_ID = 1001;
    private static final String PREFS_NAME = "fastbridge_usb_prefs";
    private static final String KEY_NON_EXFAT = "non_exfat_sigs";
    private static final String KEY_KNOWN_EXFAT = "known_exfat_sigs";

    private static volatile FastBridgeHttpServer httpServer;
    private static volatile ScsiBlockDevice activeDevice;
    private static volatile ExFatVolume activeVolume;
    private static volatile Runnable uiCallback;
    private static final java.util.concurrent.atomic.AtomicBoolean isMounting = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final Set<String> nonExfatSigs = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> knownExfatSigs = Collections.synchronizedSet(new HashSet<String>());
    private static volatile boolean prefsLoaded = false;

    public static String getDeviceSignature(UsbDevice dev) {
        if (dev == null) return "";
        return dev.getVendorId() + ":" + dev.getProductId();
    }

    public static synchronized void ensurePrefsLoaded(Context context) {
        if (prefsLoaded || context == null) return;
        // Pre-seed known NTFS Generic Mass Storage (Alcor Micro Corp. 058f:6387 = 1423:25479)
        // so Android TV's native NTFS mount is never detached by USB interface claiming.
        nonExfatSigs.add("1423:25479");
        // Pre-seed known Patriot / JMicron exFAT bridge (152d:0583 = 5421:1411)
        knownExfatSigs.add("5421:1411");

        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> savedNon = prefs.getStringSet(KEY_NON_EXFAT, null);
        if (savedNon != null) nonExfatSigs.addAll(savedNon);
        Set<String> savedEx = prefs.getStringSet(KEY_KNOWN_EXFAT, null);
        if (savedEx != null) knownExfatSigs.addAll(savedEx);
        prefsLoaded = true;
    }

    public static boolean isKnownNonExfat(Context context, UsbDevice dev) {
        ensurePrefsLoaded(context);
        return nonExfatSigs.contains(getDeviceSignature(dev));
    }

    public static boolean isKnownExfat(Context context, UsbDevice dev) {
        ensurePrefsLoaded(context);
        return knownExfatSigs.contains(getDeviceSignature(dev));
    }

    public static synchronized void markAsNonExfat(Context context, UsbDevice dev) {
        ensurePrefsLoaded(context);
        String sig = getDeviceSignature(dev);
        nonExfatSigs.add(sig);
        knownExfatSigs.remove(sig);
        if (context != null) {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putStringSet(KEY_NON_EXFAT, new HashSet<String>(nonExfatSigs))
                    .putStringSet(KEY_KNOWN_EXFAT, new HashSet<String>(knownExfatSigs))
                    .apply();
        }
    }

    public static synchronized void markAsExfat(Context context, UsbDevice dev) {
        ensurePrefsLoaded(context);
        String sig = getDeviceSignature(dev);
        knownExfatSigs.add(sig);
        nonExfatSigs.remove(sig);
        if (context != null) {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putStringSet(KEY_NON_EXFAT, new HashSet<String>(nonExfatSigs))
                    .putStringSet(KEY_KNOWN_EXFAT, new HashSet<String>(knownExfatSigs))
                    .apply();
        }
    }

    public static synchronized void clearNonExfatCache(Context context) {
        nonExfatSigs.clear();
        if (context != null) {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_NON_EXFAT).apply();
        }
    }

    private PowerManager.WakeLock wakeLock;
    private android.content.BroadcastReceiver serviceUsbReceiver;

    public static void setUiCallback(Runnable cb) {
        uiCallback = cb;
    }

    public static FastBridgeHttpServer getServer() {
        return httpServer;
    }

    public static ExFatVolume getActiveVolume() {
        return activeVolume;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification("FastBridge exFAT Server activo en puerto 8888");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastBridge::UsbServerLock");
            wakeLock.acquire();
        }

        if (httpServer == null) {
            httpServer = new FastBridgeHttpServer(FastBridgeHttpServer.DEFAULT_PORT);
            if (activeVolume != null && activeDevice != null) {
                httpServer.setVolume(activeVolume, "Montado: " + activeVolume.getVolumeLabel() + " (" + activeDevice.getVendorProduct() + ")");
            }
            try {
                httpServer.start();
            } catch (IOException e) {
                Log.e(TAG, "Failed to start HTTP server: " + e.getMessage(), e);
            }
        }

        serviceUsbReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    handleUsbDetached(dev);
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    tryMountAnyPermittedUsb(BridgeService.this);
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        registerReceiver(serviceUsbReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        tryMountAnyPermittedUsb(this);
        return START_STICKY;
    }

    public static synchronized void handleUsbDetached(UsbDevice detachedDevice) {
        if (activeDevice != null) {
            if (detachedDevice == null || activeDevice.getUsbDevice().getDeviceName().equals(detachedDevice.getDeviceName())) {
                Log.w(TAG, "USB device detached, releasing resources...");
                try {
                    activeDevice.close();
                } catch (Exception ignored) {
                }
                activeDevice = null;
                activeVolume = null;
                if (httpServer != null) {
                    httpServer.setVolume(null, "Disco USB desconectado. Esperando conexión...");
                }
                if (uiCallback != null) {
                    uiCallback.run();
                }
            }
        }
    }

    public static void tryMountAnyPermittedUsb(final Context context) {
        if (activeVolume != null || isMounting.get()) {
            if (uiCallback != null) uiCallback.run();
            return;
        }

        final Context appCtx = context.getApplicationContext();
        ensurePrefsLoaded(appCtx);

        final UsbManager usbManager = (UsbManager) appCtx.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        final List<UsbDevice> permittedCandidates = new ArrayList<UsbDevice>();
        final List<UsbDevice> unpermittedCandidates = new ArrayList<UsbDevice>();

        for (UsbDevice dev : deviceList.values()) {
            if (ScsiBlockDevice.isMassStorageDevice(dev)) {
                if (isKnownNonExfat(appCtx, dev)) {
                    Log.i(TAG, "Skipping known non-exFAT USB drive: " + getDeviceSignature(dev) + " (" + dev.getDeviceName() + ")");
                    continue;
                }
                if (usbManager.hasPermission(dev)) {
                    permittedCandidates.add(dev);
                } else {
                    unpermittedCandidates.add(dev);
                }
            }
        }

        // Prioritize devices that previously mounted as exFAT
        Collections.sort(permittedCandidates, new Comparator<UsbDevice>() {
            @Override
            public int compare(UsbDevice a, UsbDevice b) {
                boolean aEx = isKnownExfat(appCtx, a);
                boolean bEx = isKnownExfat(appCtx, b);
                if (aEx == bEx) return 0;
                return aEx ? -1 : 1;
            }
        });

        if (!permittedCandidates.isEmpty()) {
            if (isMounting.compareAndSet(false, true)) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            for (UsbDevice dev : permittedCandidates) {
                                if (activeVolume != null) break;
                                if (mountDeviceInternal(appCtx, usbManager, dev)) {
                                    break;
                                }
                            }
                            if (activeVolume == null && !unpermittedCandidates.isEmpty()) {
                                if (httpServer != null) {
                                    httpServer.setVolume(null, "Esperando permiso USB para " + unpermittedCandidates.get(0).getDeviceName());
                                }
                                appCtx.sendBroadcast(new Intent("com.fastmovie.fastbridge.USB_PERMISSION"));
                                if (uiCallback != null) uiCallback.run();
                            }
                        } finally {
                            isMounting.set(false);
                        }
                    }
                }).start();
            }
            return;
        }

        if (!unpermittedCandidates.isEmpty() && httpServer != null) {
            httpServer.setVolume(null, "Esperando permiso USB para " + unpermittedCandidates.get(0).getDeviceName());
        }
        if (uiCallback != null) uiCallback.run();
    }

    private static boolean mountDeviceInternal(Context appCtx, UsbManager usbManager, UsbDevice dev) {
        if (activeVolume != null) return true;
        ScsiBlockDevice scsi = null;
        try {
            if (httpServer != null) {
                httpServer.setVolume(null, "Montando disco exFAT por SCSI BOT...");
            }
            if (uiCallback != null) uiCallback.run();

            scsi = new ScsiBlockDevice(usbManager, dev);
            ExFatVolume vol = new ExFatVolume(scsi);

            activeDevice = scsi;
            activeVolume = vol;
            markAsExfat(appCtx, dev);

            String msg = "Montado: " + vol.getVolumeLabel() + " (" + scsi.getVendorProduct() + ")";
            Log.i(TAG, msg);
            if (httpServer != null) {
                httpServer.setVolume(vol, msg);
            }
            if (uiCallback != null) uiCallback.run();
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "";
            Log.e(TAG, "Error mounting USB exFAT on " + dev.getDeviceName() + ": " + errMsg, e);
            if (scsi != null) {
                scsi.close();
            }
            if (errMsg.contains("No exFAT partition found")) {
                Log.w(TAG, "Marking USB device " + getDeviceSignature(dev) + " as non-exFAT (NTFS/FAT32) so native Android mount is preserved.");
                markAsNonExfat(appCtx, dev);
            }
            if (httpServer != null) {
                httpServer.setVolume(null, "Error USB/exFAT: " + errMsg);
            }
            if (uiCallback != null) uiCallback.run();
            return false;
        }
    }

    private void startForegroundNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    CHANNEL_ID, "FastBridge USB Server", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(chan);
            }
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notif = builder
                .setContentTitle("FastBridge exFAT Server")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(NOTIF_ID, notif);
    }

    @Override
    public void onDestroy() {
        if (serviceUsbReceiver != null) {
            try {
                unregisterReceiver(serviceUsbReceiver);
            } catch (Exception ignored) {
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
