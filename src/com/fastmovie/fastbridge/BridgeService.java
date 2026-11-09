package com.fastmovie.fastbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;

public class BridgeService extends Service {
    private static final String TAG = "FastBridgeService";
    private static final String CHANNEL_ID = "fastbridge_channel";
    private static final int NOTIF_ID = 1001;

    private static volatile FastBridgeHttpServer httpServer;
    private static volatile ScsiBlockDevice activeDevice;
    private static volatile ExFatVolume activeVolume;
    private static volatile Runnable uiCallback;
    private static final java.util.concurrent.atomic.AtomicBoolean isMounting = new java.util.concurrent.atomic.AtomicBoolean(false);

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

        final UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (final UsbDevice dev : deviceList.values()) {
            if (ScsiBlockDevice.isMassStorageDevice(dev)) {
                if (usbManager.hasPermission(dev)) {
                    if (isMounting.compareAndSet(false, true)) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    mountDeviceInternal(usbManager, dev);
                                } finally {
                                    isMounting.set(false);
                                }
                            }
                        }).start();
                    }
                    return;
                } else {
                    if (httpServer != null) {
                        httpServer.setVolume(null, "Esperando permiso USB para " + dev.getDeviceName());
                    }
                }
            }
        }
        if (uiCallback != null) uiCallback.run();
    }

    private static void mountDeviceInternal(UsbManager usbManager, UsbDevice dev) {
        if (activeVolume != null) return;
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

            String msg = "Montado: " + vol.getVolumeLabel() + " (" + scsi.getVendorProduct() + ")";
            Log.i(TAG, msg);
            if (httpServer != null) {
                httpServer.setVolume(vol, msg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error mounting USB exFAT: " + e.getMessage(), e);
            if (scsi != null) {
                scsi.close();
            }
            if (httpServer != null) {
                httpServer.setVolume(null, "Error USB/exFAT: " + e.getMessage());
            }
        }
        if (uiCallback != null) uiCallback.run();
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
