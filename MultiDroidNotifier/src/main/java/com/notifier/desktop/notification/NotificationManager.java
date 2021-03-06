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
package com.notifier.desktop.notification;

public interface NotificationManager {

	void notificationReceived(Notification notification);

	void setPrivateMode(boolean enabled);

	void setNotificationEnabled(Notification.Type type, boolean enabled);

	void setNotificationClipboard(Notification.Type type, boolean enabled);

	void setNotificationExecuteCommand(Notification.Type type, boolean enabled);

	void setNotificationCommand(Notification.Type type, String command);

}
