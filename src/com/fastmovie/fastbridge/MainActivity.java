package com.fastmovie.fastbridge;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.fastmovie.fastbridge.USB_PERMISSION";

    // Palette tokens: Obsidian Cinema + High-Contrast Amber Gold Focus
    private static final int COL_BG_CANVAS      = Color.parseColor("#070B14");
    private static final int COL_BG_PANEL       = Color.parseColor("#111827");
    private static final int COL_BORDER_PANEL   = Color.parseColor("#1F2937");
    private static final int COL_ROW_NORMAL     = Color.parseColor("#131C2E");
    private static final int COL_ROW_BORDER     = Color.parseColor("#23324E");
    private static final int COL_ROW_FOCUSED    = Color.parseColor("#F59E0B"); // High-visibility Amber Gold
    private static final int COL_ROW_FOC_BORDER = Color.parseColor("#FEF08A"); // Bright Yellow-White rim
    private static final int COL_TEXT_PRIMARY   = Color.parseColor("#F8FAFC");
    private static final int COL_TEXT_SECONDARY = Color.parseColor("#94A3B8");
    private static final int COL_TEXT_ON_FOCUS  = Color.parseColor("#090D16"); // Deep ink on gold
    private static final int COL_ACCENT_CYAN    = Color.parseColor("#38BDF8");
    private static final int COL_ACCENT_GREEN   = Color.parseColor("#10B981");

    private TextView diskBadgeText;
    private TextView breadcrumbText;
    private TextView countBadgeText;
    private LinearLayout listContainer;
    private ScrollView listScrollView;

    // Right-hand Inspector views
    private TextView inspIcon;
    private TextView inspTitle;
    private TextView inspType;
    private TextView inspSize;
    private TextView inspUrl;
    private View refreshBtnView;

    private String currentPath = "/";
    private final Stack<String> lastSelectedNameStack = new Stack<>();

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                BridgeService.handleUsbDetached(dev);
                currentPath = "/";
                lastSelectedNameStack.clear();
                refreshUi(null);
            } else if (ACTION_USB_PERMISSION.equals(action) || UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                checkAndRequestUsbPermission(false);
            }
        }
    };

    private int dp(int val) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(val * density);
    }

    private GradientDrawable makeBox(int fillColor, int strokeColor, int strokePx, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        if (strokePx > 0) {
            gd.setStroke(strokePx, strokeColor);
        }
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COL_BG_CANVAS);
        root.setPadding(dp(28), dp(22), dp(28), dp(22));

        // ================= TOP HEADER BAR =================
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(makeBox(COL_BG_PANEL, COL_BORDER_PANEL, dp(1), dp(12)));
        header.setPadding(dp(18), dp(12), dp(20), dp(12));

        android.widget.ImageView logoImg = new android.widget.ImageView(this);
        logoImg.setImageResource(R.drawable.ic_logo);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        logoParams.rightMargin = dp(14);
        logoImg.setLayoutParams(logoParams);
        header.addView(logoImg);

        LinearLayout headerLeft = new LinearLayout(this);
        headerLeft.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hlParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headerLeft.setLayoutParams(hlParams);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView appTitle = new TextView(this);
        appTitle.setText("FASTBRIDGE");
        appTitle.setTextColor(COL_ROW_FOCUSED);
        appTitle.setTextSize(20);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(appTitle);

        diskBadgeText = new TextView(this);
        diskBadgeText.setText("  •  Conectando USB...");
        diskBadgeText.setTextColor(COL_TEXT_PRIMARY);
        diskBadgeText.setTextSize(18);
        diskBadgeText.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(diskBadgeText);

        headerLeft.addView(titleRow);

        breadcrumbText = new TextView(this);
        breadcrumbText.setText("📍 Raíz (/)");
        breadcrumbText.setTextColor(COL_ACCENT_CYAN);
        breadcrumbText.setTextSize(15);
        breadcrumbText.setPadding(0, dp(4), 0, 0);
        headerLeft.addView(breadcrumbText);

        header.addView(headerLeft);

        countBadgeText = new TextView(this);
        countBadgeText.setText("0 elementos");
        countBadgeText.setTextColor(COL_TEXT_SECONDARY);
        countBadgeText.setTextSize(14);
        countBadgeText.setPadding(dp(12), dp(6), dp(16), dp(6));
        header.addView(countBadgeText);

        refreshBtnView = createTopActionButton("🔄 Re-escanear USB");
        refreshBtnView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkAndRequestUsbPermission(true);
            }
        });
        header.addView(refreshBtnView);

        root.addView(header);

        // ================= MAIN 2-COLUMN BODY =================
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyParams.topMargin = dp(16);
        body.setLayoutParams(bodyParams);

        // LEFT COLUMN: Scrollable File/Folder List (68% width)
        listScrollView = new ScrollView(this);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.68f);
        leftParams.rightMargin = dp(16);
        listScrollView.setLayoutParams(leftParams);
        listScrollView.setVerticalScrollBarEnabled(true);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setClipChildren(false);
        listContainer.setClipToPadding(false);
        listContainer.setPadding(dp(12), dp(8), dp(14), dp(24));
        listScrollView.setClipChildren(false);
        listScrollView.setClipToPadding(false);
        listScrollView.addView(listContainer);
        body.addView(listScrollView);

        // RIGHT COLUMN: Live Selection Inspector & Remote Guide (32% width)
        LinearLayout inspector = new LinearLayout(this);
        inspector.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.32f);
        inspector.setLayoutParams(rightParams);
        inspector.setBackground(makeBox(COL_BG_PANEL, COL_BORDER_PANEL, dp(1), dp(12)));
        inspector.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView inspHeader = new TextView(this);
        inspHeader.setText("ELEMENTO SELECCIONADO");
        inspHeader.setTextColor(COL_ROW_FOCUSED);
        inspHeader.setTextSize(13);
        inspHeader.setTypeface(Typeface.DEFAULT_BOLD);
        inspector.addView(inspHeader);

        inspIcon = new TextView(this);
        inspIcon.setText("📁");
        inspIcon.setTextSize(42);
        inspIcon.setPadding(0, dp(12), 0, dp(8));
        inspector.addView(inspIcon);

        inspTitle = new TextView(this);
        inspTitle.setText("Selecciona un archivo o carpeta");
        inspTitle.setTextColor(COL_TEXT_PRIMARY);
        inspTitle.setTextSize(18);
        inspTitle.setTypeface(Typeface.DEFAULT_BOLD);
        inspTitle.setMaxLines(4);
        inspTitle.setEllipsize(TextUtils.TruncateAt.END);
        inspector.addView(inspTitle);

        inspType = new TextView(this);
        inspType.setText("Usa las flechas Arriba / Abajo del control");
        inspType.setTextColor(COL_ACCENT_CYAN);
        inspType.setTextSize(14);
        inspType.setPadding(0, dp(8), 0, dp(4));
        inspector.addView(inspType);

        inspSize = new TextView(this);
        inspSize.setText("");
        inspSize.setTextColor(COL_TEXT_SECONDARY);
        inspSize.setTextSize(14);
        inspector.addView(inspSize);

        View spacer = new View(this);
        inspector.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Remote Guide Box at bottom of Inspector
        LinearLayout guideBox = new LinearLayout(this);
        guideBox.setOrientation(LinearLayout.VERTICAL);
        guideBox.setBackground(makeBox(COL_ROW_NORMAL, COL_ROW_BORDER, dp(1), dp(8)));
        guideBox.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView guideTitle = new TextView(this);
        guideTitle.setText("🎮 CONTROL REMOTO & RED");
        guideTitle.setTextColor(COL_TEXT_PRIMARY);
        guideTitle.setTextSize(13);
        guideTitle.setTypeface(Typeface.DEFAULT_BOLD);
        guideBox.addView(guideTitle);

        inspUrl = new TextView(this);
        inspUrl.setTextColor(COL_TEXT_SECONDARY);
        inspUrl.setTextSize(12);
        inspUrl.setTypeface(Typeface.MONOSPACE);
        inspUrl.setPadding(0, dp(6), 0, 0);
        guideBox.addView(inspUrl);

        inspector.addView(guideBox);
        body.addView(inspector);

        root.addView(body);
        setContentView(root);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);

        BridgeService.setUiCallback(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshUi(null);
                    }
                });
            }
        });

        Intent svcIntent = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }

        checkAndRequestUsbPermission(false);
    }

    private View createTopActionButton(String label) {
        final TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(COL_TEXT_PRIMARY);
        btn.setPadding(dp(16), dp(8), dp(16), dp(8));
        btn.setBackground(makeBox(COL_ROW_NORMAL, COL_ROW_BORDER, dp(2), dp(8)));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    btn.setBackground(makeBox(COL_ROW_FOCUSED, COL_ROW_FOC_BORDER, dp(3), dp(8)));
                    btn.setTextColor(COL_TEXT_ON_FOCUS);
                } else {
                    btn.setBackground(makeBox(COL_ROW_NORMAL, COL_ROW_BORDER, dp(2), dp(8)));
                    btn.setTextColor(COL_TEXT_PRIMARY);
                }
            }
        });
        return btn;
    }

    private View createMediaRow(final String iconStr, final String titleStr, final String badgeStr,
                                final String subInfoStr, final boolean isDirectory, final Runnable onClickAction) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        row.setBackground(makeBox(COL_ROW_NORMAL, COL_ROW_BORDER, dp(2), dp(10)));
        row.setFocusable(true);
        row.setFocusableInTouchMode(false);
        row.setClickable(true);

        // Left selection arrow indicator (visible when focused)
        final TextView focusArrow = new TextView(this);
        focusArrow.setText("▶ ");
        focusArrow.setTextSize(18);
        focusArrow.setTypeface(Typeface.DEFAULT_BOLD);
        focusArrow.setTextColor(COL_TEXT_ON_FOCUS);
        focusArrow.setVisibility(View.GONE);
        row.addView(focusArrow);

        // File/Folder Icon
        final TextView iconView = new TextView(this);
        iconView.setText(iconStr);
        iconView.setTextSize(20);
        iconView.setPadding(0, 0, dp(14), 0);
        row.addView(iconView);

        // Title Text
        final TextView titleView = new TextView(this);
        titleView.setText(titleStr);
        titleView.setTextSize(17);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(isDirectory ? COL_ACCENT_CYAN : COL_TEXT_PRIMARY);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams tlParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleView.setLayoutParams(tlParams);
        row.addView(titleView);

        // Right Badge (Size or CARPETA)
        final TextView badgeView = new TextView(this);
        badgeView.setText(badgeStr);
        badgeView.setTextSize(14);
        badgeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        badgeView.setTextColor(COL_TEXT_SECONDARY);
        badgeView.setPadding(dp(12), dp(4), dp(4), dp(4));
        row.addView(badgeView);

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    row.setBackground(makeBox(COL_ROW_FOCUSED, COL_ROW_FOC_BORDER, dp(3), dp(10)));
                    focusArrow.setVisibility(View.VISIBLE);
                    titleView.setTextColor(COL_TEXT_ON_FOCUS);
                    badgeView.setTextColor(COL_TEXT_ON_FOCUS);
                    row.setScaleX(1.015f);
                    row.setScaleY(1.03f);

                    // Update Right Inspector Panel
                    inspIcon.setText(iconStr);
                    inspTitle.setText(titleStr);
                    inspType.setText(isDirectory ? "📂 Carpeta de Contenido" : "🎬 Archivo Multimedia (" + getExtensionUpper(titleStr) + ")");
                    inspSize.setText(subInfoStr);
                } else {
                    row.setBackground(makeBox(COL_ROW_NORMAL, COL_ROW_BORDER, dp(2), dp(10)));
                    focusArrow.setVisibility(View.GONE);
                    titleView.setTextColor(isDirectory ? COL_ACCENT_CYAN : COL_TEXT_PRIMARY);
                    badgeView.setTextColor(COL_TEXT_SECONDARY);
                    row.setScaleX(1.0f);
                    row.setScaleY(1.0f);
                }
            }
        });

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClickAction.run();
            }
        });

        row.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        onClickAction.run();
                        return true;
                    }
                }
                return false;
            }
        });

        return row;
    }

    private static String getExtensionUpper(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot + 1 < name.length()) {
            return name.substring(dot + 1).toUpperCase(Locale.US);
        }
        return "ARCHIVO";
    }

    private void checkAndRequestUsbPermission(boolean manualRescan) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            diskBadgeText.setText("  •  ❌ Error UsbManager");
            return;
        }

        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        List<UsbDevice> allMassStorage = new ArrayList<UsbDevice>();
        List<UsbDevice> candidates = new ArrayList<UsbDevice>();

        for (UsbDevice dev : devices.values()) {
            if (ScsiBlockDevice.isMassStorageDevice(dev)) {
                allMassStorage.add(dev);
                if (!BridgeService.isKnownNonExfat(this, dev)) {
                    candidates.add(dev);
                }
            }
        }

        // If user clicked manual rescan and all connected USB drives were previously marked non-exFAT,
        // clear the cache in case the user reformatted one of the drives to exFAT.
        if (candidates.isEmpty() && manualRescan && !allMassStorage.isEmpty() && BridgeService.getActiveVolume() == null) {
            BridgeService.clearNonExfatCache(this);
            candidates.addAll(allMassStorage);
        }

        if (candidates.isEmpty()) {
            if (allMassStorage.isEmpty()) {
                diskBadgeText.setText("  •  ⚠️ Sin disco USB exFAT detectado");
            } else {
                diskBadgeText.setText("  •  ℹ️ USB nativo detectado (esperando unidad exFAT)");
            }
            return;
        }

        // Prioritize known exFAT devices first
        Collections.sort(candidates, new Comparator<UsbDevice>() {
            @Override
            public int compare(UsbDevice a, UsbDevice b) {
                boolean aEx = BridgeService.isKnownExfat(MainActivity.this, a);
                boolean bEx = BridgeService.isKnownExfat(MainActivity.this, b);
                if (aEx == bEx) return 0;
                return aEx ? -1 : 1;
            }
        });

        boolean anyPermitted = false;
        UsbDevice firstUnpermitted = null;
        for (UsbDevice dev : candidates) {
            if (usbManager.hasPermission(dev)) {
                anyPermitted = true;
            } else if (firstUnpermitted == null) {
                firstUnpermitted = dev;
            }
        }

        if (anyPermitted) {
            BridgeService.tryMountAnyPermittedUsb(this);
            refreshUi(null);
        } else if (firstUnpermitted != null) {
            diskBadgeText.setText("  •  🔐 Solicitando permiso USB...");
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
            usbManager.requestPermission(firstUnpermitted, pi);
        }
    }

    private void refreshUi(String focusTargetName) {
        String ip = getLocalIpAddress();
        ExFatVolume vol = BridgeService.getActiveVolume();
        FastBridgeHttpServer srv = BridgeService.getServer();

        inspUrl.setText(
                "[OK] Abrir / Reproducir\n" +
                "[ATRÁS] Carpeta anterior\n\n" +
                "LAN:  http://" + ip + ":8888\n" +
                "Kodi: http://127.0.0.1:8888/kodi/"
        );

        listContainer.removeAllViews();

        if (vol == null) {
            String msg = (srv != null) ? srv.getStatusMessage() : "Esperando montaje USB...";
            diskBadgeText.setText("  •  ⏳ " + msg);
            breadcrumbText.setText("Conecta el disco exFAT y acepta el permiso USB");
            countBadgeText.setText("0 elementos");
            return;
        }

        double gbTotal = vol.getDevice().getTotalBytes() / (1024.0 * 1024.0 * 1024.0);
        diskBadgeText.setText(String.format(Locale.US, "  •  💾 %s (%s • %.0f GB exFAT)",
                vol.getVolumeLabel(), vol.getDevice().getVendorProduct(), gbTotal));

        String prettyBreadcrumb = "/".equals(currentPath)
                ? "📍 Raíz (/)"
                : ("📍 Raíz  ›  " + currentPath.substring(1).replace("/", "  ›  "));
        breadcrumbText.setText(prettyBreadcrumb);

        View viewToFocus = null;

        try {
            if (!"/".equals(currentPath)) {
                View upRow = createMediaRow(
                        "⬅️",
                        ".. (Regresar a carpeta anterior)",
                        "SUBIR",
                        "Ruta padre de " + currentPath,
                        true,
                        new Runnable() {
                            @Override
                            public void run() {
                                navigateUp();
                            }
                        }
                );
                listContainer.addView(upRow);
                viewToFocus = upRow;
            }

            List<ExFatVolume.ExFatEntry> items = vol.listDirectory(currentPath);
            countBadgeText.setText(items.size() + " elementos");

            for (final ExFatVolume.ExFatEntry item : items) {
                String icon = item.isDirectory ? "📁" : "🎬";
                String badge = item.isDirectory ? "ABRIR ❯" : formatSizeShort(item.size);
                String details = item.isDirectory
                        ? ("Ruta: " + item.path + "\nPresiona OK para ver su contenido")
                        : String.format(Locale.US, "Tamaño: %s\nModo: %s\nRuta: %s",
                                formatSizeShort(item.size),
                                item.noFatChain ? "Contiguo O(1) Directo" : "Cadena FAT",
                                item.path);

                View row = createMediaRow(icon, item.name, badge, details, item.isDirectory, new Runnable() {
                    @Override
                    public void run() {
                        if (item.isDirectory) {
                            lastSelectedNameStack.push(item.name);
                            currentPath = item.path;
                            refreshUi(null);
                        } else {
                            playFile(item);
                        }
                    }
                });
                listContainer.addView(row);

                if (viewToFocus == null) {
                    viewToFocus = row;
                }
                if (focusTargetName != null && focusTargetName.equals(item.name)) {
                    viewToFocus = row;
                }
            }

            if (viewToFocus != null) {
                final View finalFocus = viewToFocus;
                listScrollView.post(new Runnable() {
                    @Override
                    public void run() {
                        finalFocus.requestFocus();
                    }
                });
            }
        } catch (Exception e) {
            TextView err = new TextView(this);
            err.setText("Error leyendo directorio: " + e.getMessage());
            err.setTextColor(Color.RED);
            listContainer.addView(err);
        }
    }

    private static String formatSizeShort(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void navigateUp() {
        int lastSlash = currentPath.lastIndexOf('/');
        currentPath = (lastSlash <= 0) ? "/" : currentPath.substring(0, lastSlash);
        String restoreName = lastSelectedNameStack.isEmpty() ? null : lastSelectedNameStack.pop();
        refreshUi(restoreName);
    }

    @Override
    public void onBackPressed() {
        if (!"/".equals(currentPath)) {
            navigateUp();
        } else {
            super.onBackPressed();
        }
    }

    private void playFile(ExFatVolume.ExFatEntry file) {
        try {
            StringBuilder encoded = new StringBuilder();
            for (String seg : file.path.split("/")) {
                if (!seg.isEmpty()) {
                    encoded.append("/").append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
                }
            }
            String url = "http://127.0.0.1:8888/stream" + encoded.toString();
            String mime = "video/*";
            String low = file.name.toLowerCase(Locale.US);
            if (low.endsWith(".mp4")) mime = "video/mp4";
            else if (low.endsWith(".mkv")) mime = "video/x-matroska";
            else if (low.endsWith(".avi")) mime = "video/x-msvideo";

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), mime);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir reproductor: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
