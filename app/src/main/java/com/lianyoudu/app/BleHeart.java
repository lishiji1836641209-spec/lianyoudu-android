package com.lianyoudu.app;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.*;

/** Standard Bluetooth Heart Rate Service only; no vendor API or simulated readings. */
public final class BleHeart {
  public static final UUID SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"),
      MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"),
      CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  private final FitnessApp app;
  private final HeartDiagnostics diagnostics;
  private BluetoothGatt gatt;
  private BluetoothLeScanner scanner;
  private ScanCallback scan;
  private int generation, scanGeneration;
  public String status = "未连接";
  private String connectionStatus = "未连接";
  public String connectionStatus() { return connectionStatus; }
  private String searchStatus = "尚未搜索设备";
  private int latestHeart;

  public boolean scanning() { return scanner != null; }
  public String searchStatus() { return searchStatus; }
  public String targetAddress() {
    try { return presentationTarget == null ? "" : presentationTarget.getAddress(); }
    catch (SecurityException denied) { return ""; }
  }
  public String targetName() { return presentationTarget == null ? "" : displayName(presentationTarget); }
  public int freshHeart() {
    return ready && readingAt > 0 && SystemClock.elapsedRealtime() - readingAt < 15_000
        ? latestHeart : 0;
  }
  public final Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
  private final Map<String, String> advertisedNames = new HashMap<>();

  public String displayName(BluetoothDevice device) {
    try {
      String name = advertisedNames.get(device.getAddress());
      if (name == null && device.getAddress().equals(app.store.setting("heart.device.address", "")))
        name = rememberedName();
      if (name == null || name.isBlank()) name = device.getName();
      return cleanName(name);
    } catch (SecurityException denied) {
      return "心率设备";
    }
  }

  static String cleanName(String raw) {
    if (raw == null || raw.isBlank()) return "心率设备";
    String name = raw.trim();
    // Some Bluetooth stacks return a duplicated, truncated cached name before the full name.
    // Drop only a long repeated prefix; never infer a device name from a partial match.
    for (int i = 8; i < name.length() - 8; i++) {
      int overlap = 0;
      while (i + overlap < name.length()
          && overlap < i
          && Character.toLowerCase(name.charAt(overlap))
              == Character.toLowerCase(name.charAt(i + overlap))) overlap++;
      if (overlap >= 8 && overlap >= i - 2) return name.substring(i).trim();
    }
    return name;
  }

  private boolean ready, connecting;
  private BluetoothDevice target, presentationTarget;
  private int retries, silentReconnects;
  private Runnable retryConnection;
  private long readingAt, subscribedAt;
  private boolean silentNotice;
  private final Runnable clearStaleDisplay = this::clearStaleDisplay;

  private void clearStaleDisplay() {
    if (ready && readingAt > 0 && SystemClock.elapsedRealtime() - readingAt >= 15_000) {
      status = "已连接，等待心率";
      connectionStatus = status;
      app.changed();
    }
  }

  public boolean connecting() {
    return connecting;
  }

  public String rememberedName() {
    return cleanName(app.store.setting("heart.device.name", ""));
  }

  public boolean hasRemembered() {
    return !app.store.setting("heart.device.address", "").isEmpty();
  }

  public void forget() {
    close();
    app.store.setSetting("heart.device.address", "");
    app.store.setSetting("heart.device.name", "");
    status = "未连接";
    connectionStatus = status;
    app.changed();
  }

  public void reconnectRemembered() {
    Domain.require(permitted(), "请允许附近设备权限");
    BluetoothAdapter adapter = app.getSystemService(BluetoothManager.class).getAdapter();
    Domain.require(adapter != null && adapter.isEnabled(), "请开启手机蓝牙");
    String address = app.store.setting("heart.device.address", "");
    Domain.require(BluetoothAdapter.checkBluetoothAddress(address), "请重新搜索设备");
    connect(adapter.getRemoteDevice(address));
  }

  private void lost(String message) {
    diagnostics.event("connection_lost attempt=" + retries);
    BluetoothDevice previous = target;
    int attempt = retries;
    close();
    presentationTarget = previous;
    status = message;
    connectionStatus = status;
    long delay =
        HeartReconnectPolicy.delayMillis(
            attempt, app.active != null && app.active.running, permitted());
    if (previous != null && delay >= 0) {
      final int token = generation;
      connecting = true;
      status = attempt < 3 ? "连接已断开，正在尝试重连" : "连接已断开，将自动重连";
      connectionStatus = status;
      retryConnection =
          () -> {
            if (generation != token) return;
            retryConnection = null;
            if (app.active == null || !app.active.running) {
              connecting = false;
              status = "连接已断开，可重新连接";
              connectionStatus = status;
              app.changed();
              return;
            }
            if (!permitted()) {
              fail("附近设备权限已关闭");
              return;
            }
            connect(previous, HeartReconnectPolicy.nextAttempt(attempt));
          };
      app.main.postDelayed(retryConnection, delay);
    }
    app.changed();
  }

  BleHeart(FitnessApp a) {
    app = a;
    diagnostics = new HeartDiagnostics(a);
  }

  public boolean connected() {
    return ready;
  }

  public boolean permitted() {
    return app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED
        && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED;
  }

  public void scan() {
    try {
      Domain.require(permitted(), "请允许附近设备权限");
      BluetoothAdapter a = app.getSystemService(BluetoothManager.class).getAdapter();
      Domain.require(a != null && a.isEnabled(), "请开启手机蓝牙");
      stopScan();
      final int scanId = ++scanGeneration;
      devices.clear();
      scanner = a.getBluetoothLeScanner();
      Domain.require(scanner != null, "蓝牙扫描暂不可用");
      status = "正在搜索";
      searchStatus = "正在搜索";
      scan = callbackFor(scanId);
      scanner.startScan(
          Collections.singletonList(
              new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
          new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
          scan);
      app.changed();
      app.main.postDelayed(
          () -> {
            if (scanId != scanGeneration) return;
            stopScan();
            searchStatus = devices.isEmpty() ? "未找到心率设备" : "选择设备连接";
            status = ready ? "已连接" : devices.isEmpty() ? "未找到设备，请开启手表心率广播" : "请选择设备";
            app.changed();
          },
          12000);
    } catch (SecurityException e) {
      searchStatus = "附近设备权限已关闭";
      fail(searchStatus);
    } catch (RuntimeException e) {
      stopScan();
      searchStatus = "搜索失败，" + (e.getMessage() == null ? "请稍后重试" : e.getMessage());
      status = searchStatus;
      app.changed();
    }
  }

  private ScanCallback callbackFor(int scanId) {
    return new ScanCallback() {
      @Override
      public void onScanResult(int t, ScanResult r) {
        app.main.post(
            () -> {
              if (scanId != scanGeneration || scanner == null) return;
              String id = r.getDevice().getAddress();
              String broadcast =
                  r.getScanRecord() == null ? null : r.getScanRecord().getDeviceName();
              if (broadcast != null && !broadcast.isBlank()) advertisedNames.put(id, broadcast);
              if (!devices.containsKey(id) && devices.size() < 64) {
                devices.put(id, r.getDevice());
                app.changed();
              }
            });
      }

      @Override
      public void onScanFailed(int code) {
        app.main.post(
            () -> {
              if (scanId != scanGeneration) return;
              stopScan();
              status = "搜索失败（" + code + "），请稍后重试";
              searchStatus = status;
              app.changed();
            });
      }
    };
  }

  public void stopScan() {
    scanGeneration++;
    if (scanner != null) {
      try {
        scanner.stopScan(scan);
      } catch (SecurityException ignored) {
      }
      scanner = null;
    }
    scan = null;
  }

  public void connect(BluetoothDevice device) {
    silentReconnects = 0;
    connect(device, 0);
  }

  private void connect(BluetoothDevice device, int attempt) {
    diagnostics.event("connect attempt=" + attempt);
    try {
      Domain.require(permitted(), "请允许附近设备权限");
      close();
      target = device;
      presentationTarget = device;
      retries = attempt;
      connecting = true;
      final int gen = ++generation;
      status = "正在连接";
      connectionStatus = status;
      app.changed();
      gatt =
          device.connectGatt(
              app,
              false,
              new BluetoothGattCallback() {
                private void post(Runnable r) {
                  app.main.post(
                      () -> {
                        if (gen == generation) {
                          try {
                            r.run();
                          } catch (SecurityException e) {
                            fail("附近设备权限已关闭");
                          }
                        }
                      });
                }

                @Override
                public void onConnectionStateChange(BluetoothGatt g, int code, int state) {
                  diagnostics.event("connection_callback code=" + code + " state=" + state);
                  post(
                      () -> {
                        try {
                          if (code == BluetoothGatt.GATT_SUCCESS
                              && state == BluetoothProfile.STATE_CONNECTED) {
                            status = "正在读取服务";
                            connectionStatus = status;
                            if (!g.discoverServices()) fail("无法读取心率服务");
                          } else lost("连接已断开，可重新连接");
                          app.changed();
                        } catch (SecurityException e) {
                          fail("附近设备权限已关闭");
                        }
                      });
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int code) {
                  diagnostics.event("services code=" + code);
                  post(
                      () -> {
                        try {
                          BluetoothGattService service = g.getService(SERVICE);
                          BluetoothGattCharacteristic c =
                              service == null ? null : service.getCharacteristic(MEASUREMENT);
                          BluetoothGattDescriptor d = c == null ? null : c.getDescriptor(CCC);
                          if (code != 0
                              || c == null
                              || d == null
                              || !g.setCharacteristicNotification(c, true)) {
                            fail("设备未提供标准心率广播");
                            return;
                          }
                          if (Build.VERSION.SDK_INT >= 33) {
                            if (g.writeDescriptor(
                                    d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                != BluetoothStatusCodes.SUCCESS) fail("订阅心率失败");
                          } else {
                            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            if (!g.writeDescriptor(d)) fail("订阅心率失败");
                          }
                        } catch (SecurityException e) {
                          fail("附近设备权限已关闭");
                        }
                      });
                }

                @Override
                public void onDescriptorWrite(
                    BluetoothGatt g, BluetoothGattDescriptor d, int code) {
                  diagnostics.event("subscription code=" + code);
                  post(
                      () -> {
                        if (code == 0) {
                          ready = true;
                          connecting = false;
                          retries = 0;
                          subscribedAt = SystemClock.elapsedRealtime();
                          readingAt = 0;
                          silentNotice = false;
                          app.store.setSetting("heart.device.address", device.getAddress());
                          String name = displayName(device);
                          app.store.setSetting("heart.device.name", name);
                          status = "已连接，等待心率";
                          connectionStatus = status;
                          app.changed();
                        } else fail("订阅心率失败");
                      });
                }

                @Override
                public void onCharacteristicChanged(
                    BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
                  receive(c, value);
                }

                @Override
                public void onCharacteristicChanged(
                    BluetoothGatt g, BluetoothGattCharacteristic c) {
                  if (Build.VERSION.SDK_INT < 33) receive(c, c.getValue());
                }

                private void receive(BluetoothGattCharacteristic c, byte[] b) {
                  if (!c.getUuid().equals(MEASUREMENT)) return;
                  int hr = Domain.heartRate(b);
                  long receivedAt = SystemClock.elapsedRealtime();
                  diagnostics.notification(receivedAt, hr > 0);
                  post(
                      () -> {
                        diagnostics.dispatched(receivedAt);
                        if (hr > 0) {
                          latestHeart = hr;
                          readingAt = SystemClock.elapsedRealtime();
                          app.main.removeCallbacks(clearStaleDisplay);
                          app.main.postDelayed(clearStaleDisplay, 15_100);
                          silentReconnects = 0;
                          silentNotice = false;
                          if (app.active != null)
                            app.active.heart(hr, SystemClock.elapsedRealtime());
                          status = app.active == null ? "已连接 · " + hr + " 次/分钟" : "已连接";
                          connectionStatus = status;
                          app.changed();
                        }
                      });
                }
              },
              BluetoothDevice.TRANSPORT_LE);
      app.main.postDelayed(
          () -> {
            if (gen == generation && !ready) lost("连接超时，请重试");
          },
          20000);
    } catch (SecurityException e) {
      fail("附近设备权限已关闭");
    }
  }

  void checkSilent(long now) {
    diagnostics.report(now, ready);
    if (!ready || app.active == null || !app.active.running) return;
    long last = readingAt > 0 ? readingAt : subscribedAt;
    if (last <= 0 || now - last < 45_000) return;
    if (silentReconnects < 3) {
      silentReconnects++;
      lost("心率暂未更新，尝试重新连接");
    } else if (!silentNotice) {
      silentNotice = true;
      status = "已连接，暂无心率，请检查手表佩戴和广播";
      connectionStatus = status;
      app.changed();
    }
  }

  private void fail(String message) {
    BluetoothDevice previous = target;
    close();
    presentationTarget = previous;
    status = message;
    connectionStatus = status;
    app.changed();
  }

  public void close() {
    if (retryConnection != null) app.main.removeCallbacks(retryConnection);
    retryConnection = null;
    app.main.removeCallbacks(clearStaleDisplay);
    generation++;
    ready = false;
    connecting = false;
    readingAt = 0;
    subscribedAt = 0;
    silentNotice = false;
    target = null;
    presentationTarget = null;
    stopScan();
    if (gatt != null) {
      try {
        gatt.disconnect();
        gatt.close();
      } catch (SecurityException ignored) {
      }
      gatt = null;
    }
    if (app.active != null) app.active.disconnect();
    status = "未连接";
    connectionStatus = status;
  }
}
