/*
 * This file is part of Universal Media Server, based on PS3 Media Server.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.newgui;

import net.pms.configuration.RendererConfiguration;
import net.pms.newgui.StatusTab.ConnectionState;

public interface IFrame {
	public void append(String msg);
	public void updateBuffer();
	public void setReadValue(long v, String msg);
	public void setConnectionState(ConnectionState connectionState);
	public void addRenderer(RendererConfiguration renderer);
	public void updateRenderer(RendererConfiguration renderer);
	public void setReloadable(boolean reload);
	public void addEngines();
	public void setStatusLine(String line);
	public void setSecondaryStatusLine(String line);
	public void serverReady();
	public void updateServerStatus();
	public void setScanLibraryEnabled(boolean flag);
	public String getLog();
	public void enableWebUiButton();
}
