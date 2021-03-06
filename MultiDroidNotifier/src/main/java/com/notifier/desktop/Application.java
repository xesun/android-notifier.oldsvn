/*
 * Android Notifier Desktop is a multiplatform remote notification client for Android devices.
 *
 * Copyright (C) 2010  Leandro Aparecido
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.notifier.desktop;

import java.util.*;
import java.util.concurrent.*;

import javax.swing.*;

import org.slf4j.*;

import com.google.common.base.*;
import com.google.common.base.Service.State;
import com.google.common.collect.*;
import com.google.common.util.concurrent.*;
import com.google.inject.*;
import com.notifier.desktop.annotation.*;
import com.notifier.desktop.device.*;
import com.notifier.desktop.network.*;
import com.notifier.desktop.notification.*;
import com.notifier.desktop.notification.broadcast.*;
import com.notifier.desktop.notification.parsing.*;
import com.notifier.desktop.os.*;
import com.notifier.desktop.service.*;
import com.notifier.desktop.transport.*;
import com.notifier.desktop.transport.bluetooth.*;
import com.notifier.desktop.transport.usb.*;
import com.notifier.desktop.transport.wifi.*;
import com.notifier.desktop.tray.*;
import com.notifier.desktop.update.*;
import com.notifier.desktop.upnp.*;
import com.notifier.desktop.view.*;

import static java.util.concurrent.TimeUnit.*;

@Singleton
public class Application {

	public static final String NAME = "Android Notifier";
	public static final String ARTIFACT_ID = "android-notifier-desktop";
	public static final String ICON_NAME = "icon.png";
	public static final String ICON_NAME_MAC = "icon-mac.png";
	public static final String LICENSE = "license.txt";

	private static final int OK_EXIT_CODE = 0;
	private static final int TRAY_ERROR_EXIT_CODE = 1;
	private static final int SWT_ERROR_EXIT_CODE = 8;
	
	private static final String BROADCASTER = "broadcaster";
	private static final String TRANSPORT = "transport";
	private static final int SHUTDOWN_TIMEOUT = 5;

	private static final Logger logger = LoggerFactory.getLogger(Application.class);

	private @Inject SwtManager swtManager;
	private @Inject TrayManager trayManager;
	private @Inject Provider<ApplicationPreferences> preferencesProvider;

	private @Inject NotificationManager notificationManager;
	private @Inject NotificationParser<byte[]> notificationParser;
	private @Inject DeviceManager deviceManager;

	private @Inject @Tray NotificationBroadcaster trayBroadcaster;
	private @Inject @Growl NotificationBroadcaster growlBroadcaster;
	private @Inject @Libnotify NotificationBroadcaster libnotifyBroadcaster;
	private @Inject @Msn InstantMessagingNotificationBroadcaster msnBroadcaster;

	private @Inject WifiTransport wifiTransport;
	private @Inject UsbTransport usbTransport;
	private @Inject BluetoothTransport bluetoothTransport;

	private @Inject NetworkManager networkManager;
	private @Inject UpnpManager upnpManager;
	private @Inject UpdateManager updateManager;
	private @Inject ServiceServer serviceServer;
	private @Inject ScheduledExecutorService executorService;

	private @Inject Provider<PreferencesDialog> preferencesDialogProvider;

	private boolean showingTrayIcon;

	public void start(boolean showTrayIcon, boolean showPreferencesWindow) {
		try {
			logger.info("Starting SWT");
			swtManager.start();
		} catch (Throwable t) {
			logger.error("Error starting SWT", t);
			notifyUiNotLoaded();
			System.exit(SWT_ERROR_EXIT_CODE);
		}

		if (showTrayIcon) {
			try {
				logger.info("Showing tray icon");
				if (!trayManager.start()) {
					notifyTrayIconNotSupported();
					System.exit(TRAY_ERROR_EXIT_CODE);
				}
				showingTrayIcon = showTrayIcon;
			} catch (Throwable t) {
				logger.error("Error showing tray icon", t);
				notifyUiNotLoaded();
				System.exit(TRAY_ERROR_EXIT_CODE);
			}
		}
		startService(networkManager, null);
		startService(serviceServer, null);
		startService(deviceManager, null);

		ApplicationPreferences preferences = preferencesProvider.get();
		startServices(getBroadcasters(preferences), BROADCASTER, "Broadcaster Error", "Could not start at least one notification broadcaster. You will not be able to get notifications.");
		startServices(getTransports(preferences), TRANSPORT, "Receiver Error", "Could not start at least one notification receiver. You will not be able to get notifications.");
		if (preferences.isReceptionWithUpnp()) {
			startService(upnpManager, null);;
		}

		adjustStartAtLogin(preferences.isStartAtLogin(), true);
		if (showPreferencesWindow) {
			showPreferencesWindow();
		}

		swtManager.runEventLoop();
	}

	protected void showPreferencesWindow() {
		swtManager.update(new Runnable() {
			@Override
			public void run() {
				PreferencesDialog preferencesDialog = preferencesDialogProvider.get();
				preferencesDialog.open();
			}
		});
	}

	public boolean adjustStartAtLogin(boolean enabled, boolean silent) {
		return adjustStartup(enabled, silent);
	}

	public Future<State> adjustWifiTransport(boolean enabled) {
		return adjustService(wifiTransport, enabled, TRANSPORT);
	}

	public Future<State> adjustUpnpManager(boolean enabled) {
		return adjustService(upnpManager, enabled, null);
	}

	public Future<State> adjustBluetoothTransport(boolean enabled) {
		return adjustService(bluetoothTransport, enabled, TRANSPORT);
	}

	public Future<State> adjustUsbTransport(boolean enabled) {
		return adjustService(usbTransport, enabled, TRANSPORT);
	}

	public Future<State> adjustSystemDefaultBroadcaster(boolean enabled) {
		return adjustService(trayBroadcaster, enabled, BROADCASTER);
	}

	public Future<State> adjustLibnotifyBroadcaster(boolean enabled) {
		return adjustService(libnotifyBroadcaster, enabled, BROADCASTER);
	}

	public Future<State> adjustGrowlBroadcaster(boolean enabled) {
		return adjustService(growlBroadcaster, enabled, BROADCASTER);
	}

	public Future<State> adjustMsnBroadcaster(boolean enabled) {
		return adjustService(msnBroadcaster, enabled, BROADCASTER);
	}

	public void showError(String title, String message) {
		Dialogs.showError(swtManager, title, message, true);
	}

	public void shutdown() {
		logger.info("Shutting down...");
		int exitCode = OK_EXIT_CODE;

		if (showingTrayIcon) {
			try {
				logger.info("Hiding tray icon");
				trayManager.stop();
			} catch (Throwable t) {
				logger.error("Error hiding tray icon", t);
				exitCode += TRAY_ERROR_EXIT_CODE;
			}
		}
		stopService(serviceServer, null);

		ApplicationPreferences preferences = preferencesProvider.get();
		stopServices(getTransports(preferences), TRANSPORT);
		stopServices(getBroadcasters(preferences), BROADCASTER);
		if (preferences.isReceptionWithUpnp()) {
			stopService(upnpManager, null);
		}
		stopService(networkManager, null);
		stopService(deviceManager, null);

		try {
			logger.info("Stopping SWT");
			swtManager.stop();
		} catch (Throwable t) {
			logger.error("Error stopping SWT", t);
			exitCode += SWT_ERROR_EXIT_CODE;
		}
		executorService.shutdown();
		try {
			if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT, SECONDS)) {
				executorService.shutdownNow();
			}
		} catch (InterruptedException e) {
			// Shutting down already
		}

		System.exit(exitCode);
	}

	public Version getVersion() {
		try {
			return updateManager.getCurrentVersion();
		} catch (Exception e) {
			logger.error("Error loading current version", e);
			showError(Application.NAME + " Error Loading Current Version", "An error ocurred while loading current version.");
			return null;
		}
	}

	public void checkForUpdates() {
		executorService.execute(new Runnable() {
			@Override
			public void run() {
				try {
					boolean isLatest = updateManager.isLatestVersion();
					if (isLatest) {
						Dialogs.showInfo(swtManager, "Check for Updates", "Android Notifier Desktop is up-to-date.", true);
					} else {
						Version current = updateManager.getCurrentVersion();
						Version latest = updateManager.getCachedLatestVersion();
						Dialogs.showInfo(swtManager, "Check for Updates", "There is a newer version available, current is " + current + " and latest is " + latest + ".", true);
					}
				} catch (Exception e) {
					logger.error("Error checking for latest version", e);
					showError(Application.NAME + " Error Checking for Updates", "An error ocurred while checking for updates. Please, try again.");
				}
			}
		});
	}

	protected <T extends Service & Named> void startServices(Map<T, Boolean> services, String category, final String errorTitle, final String errorMessage) {
		final List<Future<State>> futures = Lists.newArrayListWithCapacity(services.size());
		for (Map.Entry<T, Boolean> entry : services.entrySet()) {
			if (Boolean.TRUE.equals(entry.getValue())) {
				futures.add(startService(entry.getKey(), category));
			}
		}
		executorService.execute(new Runnable() {
			@Override
			public void run() {
				boolean startedAtLeastOne = false;
				for (Future<State> future : futures) {
					try {
						future.get();
						startedAtLeastOne = true;
						break;
					} catch (InterruptedException e) {
						return;
					} catch (ExecutionException e) {
						// Handled by startService
					}
				}
				if (!startedAtLeastOne) {
					showError(errorTitle, errorMessage);
				}
			}
		});
	}

	protected <T extends Service & Named> Future<State> startService(final T service, final String category) {
		logger.info("Starting [{}] {}", service.getName(), category == null ? "" : category);
		final Future<Service.State> future = service.start();
		ListenableFuture<State> listenableFuture = Futures.makeListenable(future);
		listenableFuture.addListener(new Runnable() {
			@Override
			public void run() {
				try {
					future.get();
				} catch (InterruptedException e) {
					return;
				} catch (ExecutionException e) {
					logger.error("Error starting [" + service.getName() + "]" + (category == null ? "" : " " + category), e.getCause());
					notifyStartupError(e.getCause());
				}
			}
		}, executorService);
		return future;
	}

	protected <T extends Service & Named> void stopServices(Map<T, Boolean> services, String category) {
		for (Map.Entry<T, Boolean> entry : services.entrySet()) {
			if (Boolean.TRUE.equals(entry.getValue())) {
				stopService(entry.getKey(), category);
			}
		}
	}

	protected <T extends Service & Named> Future<State> stopService(final T service, final String category) {
		logger.info("Stopping [{}] {}", service.getName(), category == null ? "" : category);
		final Future<State> future = service.stop();
		ListenableFuture<State> listenableFuture = Futures.makeListenable(future);
		listenableFuture.addListener(new Runnable() {
			@Override
			public void run() {
				try {
					future.get();
				} catch (InterruptedException e) {
					return;
				} catch (ExecutionException e) {
					logger.error("Error stopping [" + service.getName() + "]" + (category == null ? "" : " " + category), e.getCause());
				}
			}
		}, executorService);
		return future;
	}
	
	protected boolean adjustStartup(boolean startAtLogin, boolean silent) {
		try {
			if (startAtLogin) {
				logger.info("Adding to startup");
				OperatingSystems.addToStartup();
			} else {
				logger.info("Removing from startup");
				OperatingSystems.removeFromStartup();
			}
			return true;
		} catch (Exception e) {
			logger.error("Error adjusting startup", e);
			if (!silent) {
				Dialogs.showError(swtManager, "Error", "An error ocurred while updating startup status.", true);
			}
			return false;
		}
	}

	protected <T extends Service & Named> Future<State> adjustService(T service, boolean enabled, String category) {
		if (enabled) {
			return startService(service, category);
		} else {
			return stopService(service, category);
		}
	}

	protected Map<NotificationBroadcaster, Boolean> getBroadcasters(ApplicationPreferences preferences) {
		return ImmutableMap.of(trayBroadcaster, preferences.isDisplayWithSystemDefault(),
							   growlBroadcaster, preferences.isDisplayWithGrowl(),
							   libnotifyBroadcaster, preferences.isDisplayWithLibnotify(),
							   msnBroadcaster, preferences.isDisplayWithMsn());
	}

	private Map<? extends Transport, Boolean> getTransports(ApplicationPreferences preferences) {
		return ImmutableMap.of(wifiTransport, preferences.isReceptionWithWifi(),
							   usbTransport, preferences.isReceptionWithUsb(),
							   bluetoothTransport, preferences.isReceptionWithBluetooth());
	}

	protected void notifyUiNotLoaded() {
		JOptionPane.showMessageDialog(null, "Could not load UI. Make sure you are using the correct version (32 or 64 bit) for your system.\nBoth Operating System and Java have to be 64 bit if you want to run the 64 bit version of this program.", "UI Not Loaded", JOptionPane.ERROR_MESSAGE);
	}

	protected void notifyTrayIconNotSupported() {
		showError("Tray Icon Not Supported", "System tray icon is not supported or the tray is gone.");
	}

	protected void notifyStartupError(Throwable t) {
		showError(Application.NAME + " Error", t.getMessage());
	}

	protected void notifyErrorSavingPreferences() {
		showError("Error saving preferences", "Error saving preferences, please try again");
	}

}
