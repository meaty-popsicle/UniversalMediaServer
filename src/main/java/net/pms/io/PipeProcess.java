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
package net.pms.io;

import com.sun.jna.Platform;
import java.io.*;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process to create a platform specific communications pipe that provides
 * an input stream and output stream. Other processes can then transmit
 * content via this pipe.
 */
public class PipeProcess {
	private static final Logger LOGGER = LoggerFactory.getLogger(PipeProcess.class);
	private PmsConfiguration configuration;

	private String linuxPipeName;
	private WindowsNamedPipe mk;
	private boolean forcereconnect;

	public PipeProcess(String pipeName, OutputParams params, String... extras) {
		// Use device-specific pms conf
		configuration = PMS.getConfiguration(params);
		forcereconnect = false;
		boolean in = true;

		if (extras != null && extras.length > 0 && extras[0].equals("out")) {
			in = false;
		}

		if (extras != null) {
			for (String extra : extras) {
				if (extra.equals("reconnect")) {
					forcereconnect = true;
				}
			}
		}

		if (Platform.isWindows()) {
			mk = new WindowsNamedPipe(pipeName, forcereconnect, in, params);
		} else {
			linuxPipeName = getPipeName(pipeName);
		}
	}

	public PipeProcess(String pipeName, String... extras) {
		this(pipeName, null, extras);
	}

	private static String getPipeName(String pipeName) {
		try {
			return PMS.getConfiguration().getTempFolder() + "/" + pipeName;
		} catch (IOException e) {
			LOGGER.error("Pipe may not be in temporary directory: {}", e.getMessage());
			LOGGER.trace("", e);
			return pipeName;
		}
	}

	public String getInputPipe() {
		if (!Platform.isWindows()) {
			return linuxPipeName;
		}
		return mk.getPipeName();
	}

	public String getOutputPipe() {
		if (!Platform.isWindows()) {
			return linuxPipeName;
		}
		return mk.getPipeName();
	}

	public ProcessWrapper getPipeProcess() {
		if (!Platform.isWindows()) {
			OutputParams mkfifoVidParams = new OutputParams(configuration);
			mkfifoVidParams.setMaxBufferSize(0.1);
			mkfifoVidParams.setLog(true);
			String[] cmdArray;

			if (Platform.isMac() || Platform.isFreeBSD() || Platform.isSolaris()) {
				cmdArray = new String[] {"mkfifo", "-m", "777", linuxPipeName};
			} else {
				cmdArray = new String[] {"mkfifo", "--mode=777", linuxPipeName};
			}

			ProcessWrapperImpl mkfifoVidProcess = new ProcessWrapperImpl(cmdArray, mkfifoVidParams);
			return mkfifoVidProcess;
		}

		return mk;
	}

	public void deleteLater() {
		if (!Platform.isWindows()) {
			File f = new File(linuxPipeName);
			f.deleteOnExit();
		}
	}

	public BufferedOutputFile getDirectBuffer() {
		if (!Platform.isWindows()) {
			return null;
		}

		return mk.getDirectBuffer();
	}

	public InputStream getInputStream() throws IOException {
		if (!Platform.isWindows()) {
			LOGGER.trace("Opening file {} for reading...", linuxPipeName);
			RandomAccessFile raf = new RandomAccessFile(linuxPipeName, "r");

			return new FileInputStream(raf.getFD());
		}

		return mk.getReadable();
	}

	public OutputStream getOutputStream() throws IOException {
		if (!Platform.isWindows()) {
			LOGGER.trace("Opening file {} for writing...", linuxPipeName);
			RandomAccessFile raf = new RandomAccessFile(linuxPipeName, "rw");

			return new FileOutputStream(raf.getFD());
		}

		return mk.getWritable();
	}
}
