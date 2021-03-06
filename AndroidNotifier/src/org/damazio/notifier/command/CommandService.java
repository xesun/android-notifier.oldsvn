/*
 * Copyright 2010 Rodrigo Damazio <rodrigo@damazio.org>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.damazio.notifier.command;

import org.damazio.notifier.NotifierConstants;
import org.damazio.notifier.NotifierPreferences;
import org.damazio.notifier.R;
import org.damazio.notifier.command.listeners.BluetoothCommandListener;
import org.damazio.notifier.command.listeners.IpCommandListener;
import org.damazio.notifier.command.listeners.UsbCommandListener;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

/**
 * Main command service.
 * This is responsible for setting up isteners.
 *
 * @author Rodrigo Damazio
 */
public class CommandService implements OnSharedPreferenceChangeListener {
  private static final String COMMANDS_LOCK_TAG = CommandService.class.getName();

  private final Context context;
  private final NotifierPreferences preferences;
  private final CommandHistory history;

  private DiscoveryService discoveryService;
  private BluetoothCommandListener bluetoothListener;
  private IpCommandListener ipListener;
  private UsbCommandListener usbListener;
  private WakeLock wakeLock;

  public CommandService(Context context, NotifierPreferences preferences) {
    this.context = context;
    this.preferences = preferences;
    this.history = new CommandHistory();
  }

  public void start() {
    synchronized (this) {
      if (!preferences.isCommandEnabled()) {
        Log.w(NotifierConstants.LOG_TAG, "Commands disabled, not starting");
        return;
      }

      // Ensure the CPU keeps running while we listen for connections
      if (wakeLock == null) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, COMMANDS_LOCK_TAG);
        wakeLock.acquire();
      }

      if (preferences.isBluetoothCommandEnabled()) {
        startBluetoothListener();
      }

      if (preferences.isIpCommandEnabled()) {
        startIpListener();
      }

      if (preferences.isUsbCommandEnabled()) {
        startUsbListener();
      }

      startDiscoveryListener();

      preferences.registerOnSharedPreferenceChangeListener(this);
    }
  }

  private void startDiscoveryListener() {
    if (discoveryService == null) {
      discoveryService = new DiscoveryService(context);
      discoveryService.start();
    }
  }

  private void startUsbListener() {
    if (usbListener == null) {
      usbListener = new UsbCommandListener(context, history);
    }
  }

  private void startIpListener() {
    if (ipListener == null) {
      ipListener = new IpCommandListener(context, history);
      ipListener.start();
    }
  }

  private void startBluetoothListener() {
    if (bluetoothListener == null) {
      bluetoothListener = new BluetoothCommandListener(context, history, preferences);
      bluetoothListener.start();
    }
  }

  public void shutdown() {
    synchronized (this) {
      preferences.unregisterOnSharedPreferenceChangeListener(this);

      shutdownDiscoveryListener();
      shutdownIpListener();
      shutdownBluetoothListener();
      shutdownUsbListener();

      wakeLock.release();
      wakeLock = null;
    }
  }

  private void shutdownUsbListener() {
    if (usbListener != null) {
      usbListener.shutdown();
      usbListener = null;
    }
  }

  private void shutdownDiscoveryListener() {
    if (discoveryService != null) {
      discoveryService.shutdown();
      discoveryService = null;
    }
  }

  private void shutdownBluetoothListener() {
    if (bluetoothListener != null) {
      bluetoothListener.shutdown();
      bluetoothListener = null;
    }
  }

  private void shutdownIpListener() {
    if (ipListener != null) {
      ipListener.shutdown();
      ipListener = null;
    }
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    synchronized (this) {
      if (key.equals(context.getString(R.string.command_bluetooth_key))) {
        if (preferences.isBluetoothCommandEnabled()) {
          startBluetoothListener();
        } else {
          shutdownBluetoothListener();
        }
      } else if (key.equals(context.getString(R.string.command_wifi_key))) {
        if (preferences.isIpCommandEnabled()) {
          startIpListener();
        } else {
          shutdownIpListener();
        }
      } else if (key.equals(context.getString(R.string.command_usb_key))) {
        if (preferences.isUsbCommandEnabled()) {
          startUsbListener();
        } else {
          shutdownUsbListener();
        }
      }
    }
  }
}
