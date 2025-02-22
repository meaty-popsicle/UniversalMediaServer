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

import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.factories.Borders;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import com.sun.jna.Platform;
import java.awt.Component;
import java.awt.ComponentOrientation;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.database.MediaDatabase;
import net.pms.database.MediaTableFiles;
import net.pms.database.MediaTableFilesStatus;
import net.pms.dlna.LibraryScanner;
import net.pms.network.HTTPResource;
import static net.pms.dlna.RootFolder.parseFeedKey;
import static net.pms.dlna.RootFolder.parseFeedValue;
import net.pms.newgui.components.AnimatedIcon;
import net.pms.newgui.components.JAnimatedButton;
import net.pms.newgui.components.JImageButton;
import net.pms.util.FormLayoutUtil;
import net.pms.util.ShortcutFileSystemView;
import static org.apache.commons.lang3.StringUtils.isBlank;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SharedContentTab {
	private static final Vector<String> FOLDERS_COLUMN_NAMES = new Vector<>(
		Arrays.asList(new String[] {Messages.getString("Generic.Folder"), Messages.getString("FoldTab.65")})
	);
	public static final String ALL_DRIVES = Messages.getString("FoldTab.0");
	private static final Logger LOGGER = LoggerFactory.getLogger(SharedContentTab.class);

	private JPanel sharedPanel;
	private JPanel sharedFoldersPanel;
	private JPanel sharedWebContentPanel;
	private JTable sharedFolders;
	public static JTable webContentList;
	private SharedFoldersTableModel folderTableModel;
	public static WebContentTableModel webContentTableModel;
	public static JCheckBox itunes;
	private static final JCheckBox IS_SCAN_SHARED_FOLDERS_ON_STARTUP = new JCheckBox(Messages.getString("NetworkTab.StartupScan"));
	private static final JAnimatedButton SCAN_BUTTON = new JAnimatedButton("button-scan.png");
	private static final AnimatedIcon SCAN_NORMAL_ICON = (AnimatedIcon) SCAN_BUTTON.getIcon();
	private static final AnimatedIcon SCAN_ROLLOVER_ICON = (AnimatedIcon) SCAN_BUTTON.getRolloverIcon();
	private static final AnimatedIcon SCAN_PRESSED_ICON = (AnimatedIcon) SCAN_BUTTON.getPressedIcon();
	private static final AnimatedIcon SCAN_DISABLED_ICON = (AnimatedIcon) SCAN_BUTTON.getDisabledIcon();
	private static final AnimatedIcon SCAN_BUSY_ICON = new AnimatedIcon(SCAN_BUTTON, "button-scan-busy.png");
	private static final AnimatedIcon SCAN_BUSY_ROLLOVER_ICON = new AnimatedIcon(SCAN_BUTTON, "button-cancel.png");
	private static final AnimatedIcon SCAN_BUSY_PRESSED_ICON = new AnimatedIcon(SCAN_BUTTON, "button-cancel_pressed.png");
	private static final AnimatedIcon SCAN_BUSY_DISABLED_ICON = new AnimatedIcon(SCAN_BUTTON, "button-scan-busy_disabled.png");
	private static final JImageButton ADD_BUTTON = new JImageButton("button-add-folder.png");
	private static final JImageButton REMOVE_BUTTON = new JImageButton("button-remove-folder.png");
	private static final JImageButton ARROW_DOWN_BUTTON = new JImageButton("button-arrow-down.png");
	private static final JImageButton ARROW_UP_BUTTON = new JImageButton("button-arrow-up.png");

	private static final String[] TYPES_READABLE = new String[]{
		Messages.getString("SharedContentTab.AudioFeed"),
		Messages.getString("SharedContentTab.VideoFeed"),
		Messages.getString("SharedContentTab.ImageFeed"),
		Messages.getString("SharedContentTab.AudioStream"),
		Messages.getString("SharedContentTab.VideoStream"),
	};

	private static final String READABLE_TYPE_IMAGE_FEED   = TYPES_READABLE[2];
	private static final String READABLE_TYPE_VIDEO_FEED   = TYPES_READABLE[1];
	private static final String READABLE_TYPE_AUDIO_FEED   = TYPES_READABLE[0];
	private static final String READABLE_TYPE_AUDIO_STREAM = TYPES_READABLE[3];
	private static final String READABLE_TYPE_VIDEO_STREAM = TYPES_READABLE[4];

	public SharedFoldersTableModel getDf() {
		return folderTableModel;
	}

	private final PmsConfiguration configuration;
	private LooksFrame looksFrame;

	SharedContentTab(PmsConfiguration configuration, LooksFrame looksFrame) {
		this.configuration = configuration;
		this.looksFrame = looksFrame;
	}

	public static long lastWebContentUpdate = 1L;

	private void updateWebContentModel() {
		List<String> entries = new ArrayList<>();

		for (int i = 0; i < webContentTableModel.getRowCount(); i++) {
			String readableType = (String) webContentTableModel.getValueAt(i, 1);
			String folders = (String) webContentTableModel.getValueAt(i, 2);
			String configType;

			if (readableType.equals(READABLE_TYPE_IMAGE_FEED)) {
				configType = "imagefeed";
			} else if (readableType.equals(READABLE_TYPE_VIDEO_FEED)) {
				configType = "videofeed";
			} else if (readableType.equals(READABLE_TYPE_AUDIO_FEED)) {
				configType = "audiofeed";
			} else if (readableType.equals(READABLE_TYPE_AUDIO_STREAM)) {
				configType = "audiostream";
			} else if (readableType.equals(READABLE_TYPE_VIDEO_STREAM)) {
				configType = "videostream";
			} else {
				// Skip the whole row if another value was used
				continue;
			}

			String source = (String) webContentTableModel.getValueAt(i, 3);
			String resourceName = (String) webContentTableModel.getValueAt(i, 0);

			StringBuilder entryToAdd = new StringBuilder();
			entryToAdd.append(configType).append(".").append(folders).append("=");

			switch (configType) {
				case "imagefeed":
				case "videofeed":
				case "audiofeed":
					entryToAdd.append(source);

					if (resourceName != null) {
						entryToAdd.append(",,,").append(resourceName);
					}
					break;
				default:
					if (resourceName != null) {
						entryToAdd.append(resourceName).append(",").append(source);
					}
					break;
			}

			entries.add(entryToAdd.toString());
		}

		configuration.writeWebConfigurationFile(entries);
		lastWebContentUpdate = System.currentTimeMillis();
	}

	private static final String PANEL_COL_SPEC = "left:pref,          50dlu,                pref, 150dlu,                       pref, 25dlu,               pref, 9dlu, pref, default:grow, pref, 25dlu";
	private static final String PANEL_ROW_SPEC = "fill:default:grow, 9dlu, fill:default:grow";
	private static final String SHARED_FOLDER_COL_SPEC = "left:pref, left:pref, pref, pref, pref, pref, 0:grow";
	private static final String SHARED_FOLDER_ROW_SPEC = "2*(p, 3dlu), fill:default:grow";

	public JComponent build() {
		// Apply the orientation for the locale
		ComponentOrientation orientation = ComponentOrientation.getOrientation(PMS.getLocale());
		String colSpec = FormLayoutUtil.getColSpec(PANEL_COL_SPEC, orientation);

		// Set basic layout
		FormLayout layout = new FormLayout(colSpec, PANEL_ROW_SPEC);
		PanelBuilder builder = new PanelBuilder(layout);
		builder.border(Borders.DLU4);
		builder.opaque(true);

		CellConstraints cc = new CellConstraints();

		// Init all gui components
		sharedFoldersPanel = initSharedFoldersGuiComponents(cc).build();
		sharedWebContentPanel = initWebContentGuiComponents(cc).build();

		// Load WEB.conf after we are sure the GUI has initialized
		String webConfPath = configuration.getWebConfPath();
		File webConf = new File(webConfPath);
		if (!webConf.exists()) {
			configuration.writeWebConfigurationFile();
		}
		if (webConf.exists() && configuration.getExternalNetwork()) {
			setWebContentGUIFromWebConfFile(webConf, webContentList.getSelectedRow());
		}

		builder.add(sharedFoldersPanel,    FormLayoutUtil.flip(cc.xyw(1, 1, 12), colSpec, orientation));
		builder.add(sharedWebContentPanel, FormLayoutUtil.flip(cc.xyw(1, 3, 12), colSpec, orientation));

		JPanel panel = builder.getPanel();

		// Apply the orientation to the panel and all components in it
		panel.applyComponentOrientation(orientation);

		JScrollPane scrollPane = new JScrollPane(
			panel,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
		);

		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		return scrollPane;
	}

	private PanelBuilder initSharedFoldersGuiComponents(CellConstraints cc) {
		// Apply the orientation for the locale
		ComponentOrientation orientation = ComponentOrientation.getOrientation(PMS.getLocale());
		String colSpec = FormLayoutUtil.getColSpec(SHARED_FOLDER_COL_SPEC, orientation);

		FormLayout layoutFolders = new FormLayout(colSpec, SHARED_FOLDER_ROW_SPEC);
		PanelBuilder builderFolder = new PanelBuilder(layoutFolders);
		builderFolder.opaque(true);

		JComponent cmp = builderFolder.addSeparator(Messages.getString("FoldTab.7"), FormLayoutUtil.flip(cc.xyw(1, 1, 7), colSpec, orientation));
		cmp = (JComponent) cmp.getComponent(0);
		cmp.setFont(cmp.getFont().deriveFont(Font.BOLD));

		folderTableModel = new SharedFoldersTableModel();
		sharedFolders = new JTable(folderTableModel);

		JPopupMenu popupMenu = new JPopupMenu();
		JMenuItem menuItemMarkPlayed = new JMenuItem(Messages.getString("FoldTab.75"));
		JMenuItem menuItemMarkUnplayed = new JMenuItem(Messages.getString("FoldTab.76"));

		menuItemMarkPlayed.addActionListener((ActionEvent e) -> {
			Connection connection = null;
			try {
				connection = MediaDatabase.getConnectionIfAvailable();
				if (connection != null) {
					String path = (String) sharedFolders.getValueAt(sharedFolders.getSelectedRow(), 0);
					MediaTableFilesStatus.setDirectoryFullyPlayed(connection, path, true);
				}
			} finally {
				MediaDatabase.close(connection);
			}
		});

		menuItemMarkUnplayed.addActionListener((ActionEvent e) -> {
			Connection connection = null;
			try {
				connection = MediaDatabase.getConnectionIfAvailable();
				if (connection != null) {
					String path = (String) sharedFolders.getValueAt(sharedFolders.getSelectedRow(), 0);
					MediaTableFilesStatus.setDirectoryFullyPlayed(connection, path, false);
				}
			} finally {
				MediaDatabase.close(connection);
			}
		});

		popupMenu.add(menuItemMarkPlayed);
		popupMenu.add(menuItemMarkUnplayed);

		sharedFolders.setComponentPopupMenu(popupMenu);

		/* An attempt to set the correct row height adjusted for font scaling.
		 * It sets all rows based on the font size of cell (0, 0). The + 4 is
		 * to allow 2 pixels above and below the text. */
		DefaultTableCellRenderer cellRenderer = (DefaultTableCellRenderer) sharedFolders.getCellRenderer(0, 0);
		FontMetrics metrics = cellRenderer.getFontMetrics(cellRenderer.getFont());
		sharedFolders.setRowHeight(metrics.getLeading() + metrics.getMaxAscent() + metrics.getMaxDescent() + 4);
		sharedFolders.setIntercellSpacing(new Dimension(8, 2));

		final JPanel tmpsharedPanel = sharedPanel;

		ADD_BUTTON.setToolTipText(Messages.getString("FoldTab.9"));
		ADD_BUTTON.addActionListener((ActionEvent e) -> {
			JFileChooser chooser;
			try {
				chooser = new JFileChooser();
				if (Platform.isWindows()) {
					chooser.setFileSystemView(new ShortcutFileSystemView());
				}
			} catch (Exception ee) {
				chooser = new JFileChooser(new RestrictedFileSystemView());
				LOGGER.debug("Using RestrictedFileSystemView because {}", ee.getMessage());
			}
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			int returnVal = chooser.showOpenDialog((Component) e.getSource());
			if (returnVal == JFileChooser.APPROVE_OPTION) {
				int firstSelectedRow = sharedFolders.getSelectedRow();
				if (firstSelectedRow >= 0) {
					((SharedFoldersTableModel) sharedFolders.getModel()).insertRow(
						firstSelectedRow,
						new Object[]{chooser.getSelectedFile().getAbsolutePath(), true}
					);
				} else {
					((SharedFoldersTableModel) sharedFolders.getModel()).addRow(
						new Object[]{chooser.getSelectedFile().getAbsolutePath(), true}
					);
				}
			}
		});
		builderFolder.add(ADD_BUTTON, FormLayoutUtil.flip(cc.xy(2, 3), colSpec, orientation));

		REMOVE_BUTTON.setToolTipText(Messages.getString("FoldTab.36"));
		REMOVE_BUTTON.addActionListener((ActionEvent e) -> {
			int[] rows = sharedFolders.getSelectedRows();
			if (rows.length > 0) {
				if (rows.length > 1) {
					if (
						JOptionPane.showConfirmDialog(
							tmpsharedPanel,
							String.format(Messages.getString("SharedFolders.ConfirmRemove"), rows.length),
							Messages.getString("Dialog.Confirm"),
							JOptionPane.YES_NO_OPTION,
							JOptionPane.WARNING_MESSAGE
						) != JOptionPane.YES_OPTION
						) {
						return;
					}
				}
				Connection connection = null;
				try {
					connection = MediaDatabase.getConnectionIfAvailable();
					for (int i = rows.length - 1; i >= 0; i--) {
						if (connection != null) {
							MediaTableFiles.removeMediaEntriesInFolder(connection, (String) sharedFolders.getValueAt(sharedFolders.getSelectedRow(), 0));
						}
						((SharedFoldersTableModel) sharedFolders.getModel()).removeRow(rows[i]);
					}
				} finally {
					MediaDatabase.close(connection);
				}
			}
		});
		builderFolder.add(REMOVE_BUTTON, FormLayoutUtil.flip(cc.xy(3, 3), colSpec, orientation));

		ARROW_DOWN_BUTTON.setToolTipText(Messages.getString("SharedContentTab.ArrowDown"));
		ARROW_DOWN_BUTTON.addActionListener((ActionEvent e) -> {
			for (int i = 0; i < sharedFolders.getRowCount() - 1; i++) {
				if (sharedFolders.isRowSelected(i)) {
					Object  value1 = sharedFolders.getValueAt(i, 0);
					boolean value2 = (boolean) sharedFolders.getValueAt(i, 1);

					sharedFolders.setValueAt(sharedFolders.getValueAt(i + 1, 0), i, 0);
					sharedFolders.setValueAt(value1, i + 1, 0);
					sharedFolders.setValueAt(sharedFolders.getValueAt(i + 1, 1), i, 1);
					sharedFolders.setValueAt(value2, i + 1, 1);
					sharedFolders.changeSelection(i + 1, 1, false, false);

					break;
				}
			}
		});
		builderFolder.add(ARROW_DOWN_BUTTON, FormLayoutUtil.flip(cc.xy(4, 3), colSpec, orientation));

		ARROW_UP_BUTTON.setToolTipText(Messages.getString("SharedContentTab.ArrowUp"));
		ARROW_UP_BUTTON.addActionListener((ActionEvent e) -> {
			for (int i = 1; i < sharedFolders.getRowCount(); i++) {
				if (sharedFolders.isRowSelected(i)) {
					Object  value1 = sharedFolders.getValueAt(i, 0);
					boolean value2 = (boolean) sharedFolders.getValueAt(i, 1);

					sharedFolders.setValueAt(sharedFolders.getValueAt(i - 1, 0), i, 0);
					sharedFolders.setValueAt(value1, i - 1, 0);
					sharedFolders.setValueAt(sharedFolders.getValueAt(i - 1, 1), i, 1);
					sharedFolders.setValueAt(value2, i - 1, 1);
					sharedFolders.changeSelection(i - 1, 1, false, false);

					break;

				}
			}
		});
		builderFolder.add(ARROW_UP_BUTTON, FormLayoutUtil.flip(cc.xy(5, 3), colSpec, orientation));

		SCAN_BUTTON.setToolTipText(Messages.getString("FoldTab.2"));
		SCAN_BUSY_ICON.start();
		SCAN_BUSY_DISABLED_ICON.start();
		SCAN_BUTTON.addActionListener((ActionEvent e) -> {
			if (configuration.getUseCache()) {
				if (LibraryScanner.isScanLibraryRunning()) {
					int option = JOptionPane.showConfirmDialog(
						looksFrame,
						Messages.getString("FoldTab.10"),
						Messages.getString("Dialog.Question"),
						JOptionPane.YES_NO_OPTION);
					if (option == JOptionPane.YES_OPTION) {
						LibraryScanner.stopScanLibrary();
						looksFrame.setStatusLine(Messages.getString("FoldTab.41"));
						SCAN_BUTTON.setEnabled(false);
						SCAN_BUTTON.setToolTipText(Messages.getString("FoldTab.41"));
					}
				} else {
					LibraryScanner.scanLibrary();
					SCAN_BUTTON.setIcon(SCAN_BUSY_ICON);
					SCAN_BUTTON.setRolloverIcon(SCAN_BUSY_ROLLOVER_ICON);
					SCAN_BUTTON.setPressedIcon(SCAN_BUSY_PRESSED_ICON);
					SCAN_BUTTON.setDisabledIcon(SCAN_BUSY_DISABLED_ICON);
					SCAN_BUTTON.setToolTipText(Messages.getString("FoldTab.40"));
				}
			}
		});

		/*
		 * Hide the scan button in basic mode since it's better to let it be done in
		 * realtime.
		 */
		if (!configuration.isHideAdvancedOptions()) {
			builderFolder.add(SCAN_BUTTON, FormLayoutUtil.flip(cc.xy(6, 3), colSpec, orientation));
		}

		SCAN_BUTTON.setEnabled(configuration.getUseCache());

		IS_SCAN_SHARED_FOLDERS_ON_STARTUP.setSelected(configuration.isScanSharedFoldersOnStartup());
		IS_SCAN_SHARED_FOLDERS_ON_STARTUP.setContentAreaFilled(false);
		IS_SCAN_SHARED_FOLDERS_ON_STARTUP.addItemListener((ItemEvent e) -> {
			configuration.setScanSharedFoldersOnStartup((e.getStateChange() == ItemEvent.SELECTED));
		});

		setScanLibraryEnabled(configuration.getUseCache());

		builderFolder.add(IS_SCAN_SHARED_FOLDERS_ON_STARTUP, FormLayoutUtil.flip(cc.xy(7, 3), colSpec, orientation));

		updateSharedFolders();

		JScrollPane pane = new JScrollPane(sharedFolders);
		Dimension d = sharedFolders.getPreferredSize();
		pane.setPreferredSize(new Dimension(d.width, sharedFolders.getRowHeight() * 2));
		builderFolder.add(pane, FormLayoutUtil.flip(
			cc.xyw(1, 5, 7, CellConstraints.DEFAULT, CellConstraints.FILL),
			colSpec,
			orientation
		));

		return builderFolder;
	}

	private PanelBuilder initWebContentGuiComponents(CellConstraints cc) {
		// Apply the orientation for the locale
		ComponentOrientation orientation = ComponentOrientation.getOrientation(PMS.getLocale());
		String colSpec = FormLayoutUtil.getColSpec(SHARED_FOLDER_COL_SPEC, orientation);

		FormLayout layoutFolders = new FormLayout(colSpec, SHARED_FOLDER_ROW_SPEC);
		PanelBuilder builderFolder = new PanelBuilder(layoutFolders);
		builderFolder.opaque(true);

		JComponent cmp = builderFolder.addSeparator(Messages.getString("SharedContentTab.WebContent"), FormLayoutUtil.flip(cc.xyw(1, 1, 7), colSpec, orientation));
		cmp = (JComponent) cmp.getComponent(0);
		cmp.setFont(cmp.getFont().deriveFont(Font.BOLD));

		webContentTableModel = new WebContentTableModel();
		webContentList = new JTable(webContentTableModel);
		TableColumn column = webContentList.getColumnModel().getColumn(3);
		column.setMinWidth(500);

		webContentList.addMouseListener(new TableMouseListener(webContentList));

		/*
		 * An attempt to set the correct row height adjusted for font scaling.
		 * It sets all rows based on the font size of cell (0, 0). The + 4 is
		 * to allow 2 pixels above and below the text.
		 */
		DefaultTableCellRenderer cellRenderer = (DefaultTableCellRenderer) webContentList.getCellRenderer(0, 0);
		FontMetrics metrics = cellRenderer.getFontMetrics(cellRenderer.getFont());
		webContentList.setRowHeight(metrics.getLeading() + metrics.getMaxAscent() + metrics.getMaxDescent() + 4);
		webContentList.setIntercellSpacing(new Dimension(8, 2));

		JImageButton but = new JImageButton("button-add-webcontent.png");
		but.setToolTipText(Messages.getString("SharedContentTab.AddNewWebContent"));
		but.addActionListener((ActionEvent e) -> {
			JTextField newEntryName = new JTextField(25);
			newEntryName.setEnabled(false);
			newEntryName.setText(Messages.getString("SharedContentTab.NamesSetAutomaticallyFeeds"));

			JComboBox<String> newEntryType = new JComboBox<>(TYPES_READABLE);
			newEntryType.setEditable(false);
			newEntryType.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					if (
						e.getItem().toString() == READABLE_TYPE_AUDIO_FEED ||
						e.getItem().toString() == READABLE_TYPE_VIDEO_FEED ||
						e.getItem().toString() == READABLE_TYPE_IMAGE_FEED
					) {
						newEntryName.setEnabled(false);
						newEntryName.setText(Messages.getString("SharedContentTab.NamesSetAutomaticallyFeeds"));
					} else if (
						e.getItem().toString() == READABLE_TYPE_AUDIO_STREAM ||
						e.getItem().toString() == READABLE_TYPE_VIDEO_STREAM
					) {
						newEntryName.setEnabled(true);
						newEntryName.setText("");
					}
				}
			});

			JTextField newEntryFolders = new JTextField(25);
			newEntryFolders.setText("Web,");

			JTextField newEntrySource = new JTextField(50);

			JPanel addNewWebContentPanel = new JPanel();

			JLabel labelName = new JLabel(Messages.getString("SharedContentTab.NameColon"));
			JLabel labelType = new JLabel(Messages.getString("SharedContentTab.TypeColon"));
			JLabel labelFolders = new JLabel(Messages.getString("SharedContentTab.FoldersColon"));
			JLabel labelSource = new JLabel(Messages.getString("SharedContentTab.SourceURLColon"));

			labelName.setLabelFor(newEntryName);
			labelType.setLabelFor(newEntryType);
			labelFolders.setLabelFor(newEntryFolders);
			labelSource.setLabelFor(newEntrySource);

			GroupLayout layout = new GroupLayout(addNewWebContentPanel);
			addNewWebContentPanel.setLayout(layout);

			layout.setHorizontalGroup(
				layout
					.createParallelGroup(GroupLayout.Alignment.LEADING)
					.addGroup(
						layout
							.createSequentialGroup()
							.addContainerGap()
							.addGroup(
								layout
									.createParallelGroup()
									.addComponent(labelName)
									.addComponent(newEntryName, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE)
									.addComponent(labelType)
									.addComponent(newEntryType, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE)
									.addComponent(labelFolders)
									.addComponent(newEntryFolders, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE)
									.addComponent(labelSource)
									.addComponent(newEntrySource)
							)
							.addContainerGap()
					)
			);

			layout.setVerticalGroup(
				layout
					.createParallelGroup(GroupLayout.Alignment.LEADING)
					.addGroup(
						layout
							.createSequentialGroup()
							.addContainerGap()
							.addComponent(labelName)
							.addComponent(newEntryName)
							.addPreferredGap(LayoutStyle.ComponentPlacement.UNRELATED)
							.addComponent(labelType)
							.addComponent(newEntryType)
							.addPreferredGap(LayoutStyle.ComponentPlacement.UNRELATED)
							.addComponent(labelFolders)
							.addComponent(newEntryFolders)
							.addPreferredGap(LayoutStyle.ComponentPlacement.UNRELATED)
							.addComponent(labelSource)
							.addComponent(newEntrySource)
							.addContainerGap()
					)
			);

			int result = JOptionPane.showConfirmDialog(null, addNewWebContentPanel, Messages.getString("SharedContentTab.AddNewWebContent"), JOptionPane.OK_CANCEL_OPTION);
			if (result == JOptionPane.OK_OPTION) {
				SharedContentTab.webContentList.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
				SharedContentTab.webContentList.setEnabled(false);

				try {
					String resourceName = null;
					if (!isBlank(newEntrySource.getText())) {
						try {
							if (
								newEntryType.getSelectedItem().toString() == READABLE_TYPE_IMAGE_FEED ||
								newEntryType.getSelectedItem().toString() == READABLE_TYPE_AUDIO_FEED ||
								newEntryType.getSelectedItem().toString() == READABLE_TYPE_VIDEO_FEED
							) {
								String temporarySource = newEntrySource.getText();
								// Convert YouTube channel URIs to their feed URIs
								if (temporarySource.contains("youtube.com/channel/")) {
									temporarySource = temporarySource.replaceAll("youtube.com/channel/", "youtube.com/feeds/videos.xml?channel_id=");
								}

								resourceName = getFeedTitle(temporarySource);
							} else if (
								newEntryType.getSelectedItem().toString() == READABLE_TYPE_VIDEO_STREAM ||
								newEntryType.getSelectedItem().toString() == READABLE_TYPE_AUDIO_STREAM
							) {
								resourceName = newEntryName.getText();
							}
						} catch (Exception e2) {
							LOGGER.debug("Error while getting feed title on add: " + e);
						}
					}
					((WebContentTableModel) webContentList.getModel()).addRow(new Object[]{resourceName, newEntryType.getSelectedItem(), newEntryFolders.getText(), newEntrySource.getText()});
					webContentList.changeSelection(((WebContentTableModel) webContentList.getModel()).getRowCount() - 1, 1, false, false);
					updateWebContentModel();
				} finally {
					SharedContentTab.webContentList.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
					SharedContentTab.webContentList.setEnabled(true);
				}
			}
		});
		builderFolder.add(but, FormLayoutUtil.flip(cc.xy(1, 3), colSpec, orientation));

		JImageButton but2 = new JImageButton("button-remove-folder.png");
		but2.setToolTipText(Messages.getString("SharedContentTab.RemoveSelectedWebContent"));
		but2.addActionListener((ActionEvent e) -> {
			int currentlySelectedRow = webContentList.getSelectedRow();
			if (currentlySelectedRow > -1) {
				if (currentlySelectedRow > 0) {
					webContentList.changeSelection(currentlySelectedRow - 1, 1, false, false);
				}
				((WebContentTableModel) webContentList.getModel()).removeRow(currentlySelectedRow);
				updateWebContentModel();
			}
		});
		builderFolder.add(but2, FormLayoutUtil.flip(cc.xy(2, 3), colSpec, orientation));

		JImageButton but3 = new JImageButton("button-arrow-down.png");
		but3.setToolTipText(Messages.getString("SharedContentTab.MoveSelectedWebContentDown"));
		but3.addActionListener((ActionEvent e) -> {
			for (int i = 0; i < webContentList.getRowCount() - 1; i++) {
				if (webContentList.isRowSelected(i)) {
					Object name   = webContentList.getValueAt(i, 0);
					Object type   = webContentList.getValueAt(i, 1);
					Object folder = webContentList.getValueAt(i, 2);
					Object source = webContentList.getValueAt(i, 3);

					webContentList.setValueAt(webContentList.getValueAt(i + 1, 0), i, 0);
					webContentList.setValueAt(name, i + 1, 0);
					webContentList.setValueAt(webContentList.getValueAt(i + 1, 1), i, 1);
					webContentList.setValueAt(type, i + 1, 1);
					webContentList.setValueAt(webContentList.getValueAt(i + 1, 2), i, 2);
					webContentList.setValueAt(folder, i + 1, 2);
					webContentList.setValueAt(webContentList.getValueAt(i + 1, 3), i, 3);
					webContentList.setValueAt(source, i + 1, 3);
					webContentList.changeSelection(i + 1, 1, false, false);

					break;
				}
			}
		});
		builderFolder.add(but3, FormLayoutUtil.flip(cc.xy(3, 3), colSpec, orientation));

		JImageButton but4 = new JImageButton("button-arrow-up.png");
		but4.setToolTipText(Messages.getString("SharedContentTab.MoveSelectedWebContentUp"));
		but4.addActionListener((ActionEvent e) -> {
			for (int i = 1; i < webContentList.getRowCount(); i++) {
				if (webContentList.isRowSelected(i)) {
					Object name   = webContentList.getValueAt(i, 0);
					Object type   = webContentList.getValueAt(i, 1);
					Object folder = webContentList.getValueAt(i, 2);
					Object source = webContentList.getValueAt(i, 3);

					webContentList.setValueAt(webContentList.getValueAt(i - 1, 0), i, 0);
					webContentList.setValueAt(name, i - 1, 0);
					webContentList.setValueAt(webContentList.getValueAt(i - 1, 1), i, 1);
					webContentList.setValueAt(type, i - 1, 1);
					webContentList.setValueAt(webContentList.getValueAt(i - 1, 2), i, 2);
					webContentList.setValueAt(folder, i - 1, 2);
					webContentList.setValueAt(webContentList.getValueAt(i - 1, 3), i, 3);
					webContentList.setValueAt(source, i - 1, 3);
					webContentList.changeSelection(i - 1, 1, false, false);

					break;
				}
			}
		});
		builderFolder.add(but4, FormLayoutUtil.flip(cc.xy(4, 3), colSpec, orientation));

		JScrollPane pane = new JScrollPane(webContentList);
		Dimension d = webContentList.getPreferredSize();
		pane.setPreferredSize(new Dimension(d.width, webContentList.getRowHeight() * 2));
		builderFolder.add(pane, FormLayoutUtil.flip(cc.xyw(1, 5, 7), colSpec, orientation));

		return builderFolder;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void updateSharedFolders() {
		List<Path> folders = configuration.getSharedFolders();
		Vector<Vector<?>> newDataVector = new Vector<>();
		if (!folders.isEmpty()) {
			List<Path> foldersMonitored = configuration.getMonitoredFolders();
			for (Path folder : folders) {
				Vector rowVector = new Vector();
				rowVector.add(folder.toString());
				rowVector.add(Boolean.valueOf(foldersMonitored.contains(folder)));
				newDataVector.add(rowVector);
			}
		}
		folderTableModel.setDataVector(newDataVector, FOLDERS_COLUMN_NAMES);
		TableColumn column = sharedFolders.getColumnModel().getColumn(0);
		column.setMinWidth(600);
	}

	public static void setScanLibraryEnabled(boolean enabled) {
		SCAN_BUTTON.setEnabled(enabled);
		SCAN_BUTTON.setIcon(SCAN_NORMAL_ICON);
		SCAN_BUTTON.setRolloverIcon(SCAN_ROLLOVER_ICON);
		SCAN_BUTTON.setPressedIcon(SCAN_PRESSED_ICON);
		SCAN_BUTTON.setDisabledIcon(SCAN_DISABLED_ICON);
		SCAN_BUTTON.setToolTipText(Messages.getString("FoldTab.2"));

		if (enabled) {
			IS_SCAN_SHARED_FOLDERS_ON_STARTUP.setEnabled(true);
			IS_SCAN_SHARED_FOLDERS_ON_STARTUP.setToolTipText(Messages.getString("NetworkTab.StartupScanTooltipEnabled"));
		} else {
			IS_SCAN_SHARED_FOLDERS_ON_STARTUP.setEnabled(false);
			IS_SCAN_SHARED_FOLDERS_ON_STARTUP.setToolTipText(Messages.getString("General.ThisFeatureRequiresTheCache"));
		}
	}

	/**
	 * @todo combine with setScanLibraryEnabled after we are in sync with DMS
	 */
	public static void setScanLibraryBusy() {
		SCAN_BUTTON.setIcon(SCAN_BUSY_ICON);
		SCAN_BUTTON.setRolloverIcon(SCAN_BUSY_ROLLOVER_ICON);
		SCAN_BUTTON.setPressedIcon(SCAN_BUSY_PRESSED_ICON);
		SCAN_BUTTON.setDisabledIcon(SCAN_BUSY_DISABLED_ICON);
		SCAN_BUTTON.setToolTipText(Messages.getString("FoldTab.40"));
	}

	public class SharedFoldersTableModel extends DefaultTableModel {
		private static final long serialVersionUID = -4247839506937958655L;

		public SharedFoldersTableModel() {
			super(FOLDERS_COLUMN_NAMES, 0);
		}

		@Override
		public Class<?> getColumnClass(int columnIndex) {
			return columnIndex == 1 ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return column == 1;
		}

		@SuppressWarnings({ "rawtypes", "unchecked" })
		@Override
		public void setValueAt(Object aValue, int row, int column) {
			Vector rowVector = (Vector) dataVector.elementAt(row);
			if (aValue instanceof Boolean && column == 1) {
				rowVector.setElementAt(aValue, 1);
			} else {
				rowVector.setElementAt(aValue, column);
			}
			fireTableCellUpdated(row, column);
			configuration.setSharedFolders((Vector) folderTableModel.getDataVector());
		}

		@Override
		public void insertRow(int row, Vector rowData) {
			super.insertRow(row, rowData);
			configuration.setSharedFolders((Vector) folderTableModel.getDataVector());
		}

		@Override
		public void removeRow(int row) {
			super.removeRow(row);
			configuration.setSharedFolders((Vector) folderTableModel.getDataVector());
		}
	}

	public class WebContentTableModel extends DefaultTableModel {
		private static final long serialVersionUID = -4247839506937958655L;

		public WebContentTableModel() {
			// Column headings
			super(new String[]{
				Messages.getString("SharedContentTab.Name"),
				Messages.getString("SharedContentTab.Type"),
				Messages.getString("SharedContentTab.VirtualFolders"),
				Messages.getString("SharedContentTab.Source"),
			}, 0);
		}

		@Override
		public Class<?> getColumnClass(int columnIndex) {
			return String.class;
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return false;
		}

		@Override
		public void setValueAt(Object aValue, int row, int column) {
			Vector rowVector = (Vector) dataVector.elementAt(row);
			rowVector.setElementAt(aValue, column);
			fireTableCellUpdated(row, column);
			updateWebContentModel();
		}
	}

	public class TableMouseListener extends MouseAdapter {
		private JTable table;

		public TableMouseListener(JTable table) {
			this.table = table;
		}

		@Override
		public void mousePressed(MouseEvent event) {
			// selects the row at which point the mouse is clicked
			Point point = event.getPoint();
			int currentRow = table.rowAtPoint(point);
			table.setRowSelectionInterval(currentRow, currentRow);

			// more than one click in the same event triggers edit mode
			if (event.getClickCount() == 2) {
				String currentName    = (String) webContentList.getValueAt(currentRow, 0);
				String currentType    = (String) webContentList.getValueAt(currentRow, 1);
				String currentFolders = (String) webContentList.getValueAt(currentRow, 2);
				String currentSource  = (String) webContentList.getValueAt(currentRow, 3);

				int currentTypeIndex = Arrays.asList(TYPES_READABLE).indexOf(currentType);

				JTextField newEntryName = new JTextField(25);
				if (
					currentType == READABLE_TYPE_AUDIO_FEED ||
					currentType == READABLE_TYPE_VIDEO_FEED ||
					currentType == READABLE_TYPE_IMAGE_FEED
				) {
					newEntryName.setEnabled(false);
					newEntryName.setText(Messages.getString("SharedContentTab.NamesSetAutomaticallyFeeds"));
				} else {
					newEntryName.setEnabled(true);
					newEntryName.setText(currentName);
				}

				JComboBox<String> newEntryType = new JComboBox<>(TYPES_READABLE);
				newEntryType.setEditable(false);
				newEntryType.setSelectedIndex(currentTypeIndex);
				newEntryType.addItemListener(new ItemListener() {
					@Override
					public void itemStateChanged(ItemEvent e) {
						if (
							e.getItem().toString() == READABLE_TYPE_AUDIO_FEED ||
							e.getItem().toString() == READABLE_TYPE_VIDEO_FEED ||
							e.getItem().toString() == READABLE_TYPE_IMAGE_FEED
						) {
							newEntryName.setEnabled(false);
							newEntryName.setText(Messages.getString("SharedContentTab.NamesSetAutomaticallyFeeds"));
						} else if (
							e.getItem().toString() == READABLE_TYPE_AUDIO_STREAM ||
							e.getItem().toString() == READABLE_TYPE_VIDEO_STREAM
						) {
							newEntryName.setEnabled(true);
							newEntryName.setText("");
						}
					}
				});

				JTextField newEntryFolders = new JTextField(25);
				newEntryFolders.setText(currentFolders);

				JTextField newEntrySource = new JTextField(50);
				newEntrySource.setText(currentSource);

				JPanel addNewWebContentPanel = new JPanel();

				JLabel labelName = new JLabel(Messages.getString("SharedContentTab.NameColon"));
				JLabel labelType = new JLabel(Messages.getString("SharedContentTab.TypeColon"));
				JLabel labelFolders = new JLabel(Messages.getString("SharedContentTab.FoldersColon"));
				JLabel labelSource = new JLabel(Messages.getString("SharedContentTab.SourceURLColon"));

				labelName.setLabelFor(newEntryName);
				labelType.setLabelFor(newEntryType);
				labelFolders.setLabelFor(newEntryFolders);
				labelSource.setLabelFor(newEntrySource);

				GroupLayout layout = new GroupLayout(addNewWebContentPanel);
				addNewWebContentPanel.setLayout(layout);

				layout.setHorizontalGroup(
					layout
						.createParallelGroup(GroupLayout.Alignment.LEADING)
						.addGroup(
							layout
								.createSequentialGroup()
								.addContainerGap()
								.addGroup(
									layout
										.createParallelGroup()
										.addComponent(labelName)
										.addComponent(newEntryName, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE)
										.addComponent(labelType)
										.addComponent(newEntryType, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE)
										.addComponent(labelFolders)
										.addComponent(newEntryFolders, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE)
										.addComponent(labelSource)
										.addComponent(newEntrySource)
								)
								.addContainerGap()
					)
				);

				layout.setVerticalGroup(
					layout
						.createParallelGroup(GroupLayout.Alignment.LEADING)
						.addGroup(
							layout
								.createSequentialGroup()
								.addContainerGap()
								.addComponent(labelName)
								.addComponent(newEntryName)
								.addPreferredGap(LayoutStyle.ComponentPlacement.UNRELATED)
								.addComponent(labelType)
								.addComponent(newEntryType)
								.addPreferredGap(LayoutStyle.ComponentPlacement.UNRELATED)
								.addComponent(labelFolders)
								.addComponent(newEntryFolders)
								.addPreferredGap(LayoutStyle.ComponentPlacement.UNRELATED)
								.addComponent(labelSource)
								.addComponent(newEntrySource)
								.addContainerGap()
						)
				);

				int result = JOptionPane.showConfirmDialog(null, addNewWebContentPanel, Messages.getString("SharedContentTab.AddNewWebContent"), JOptionPane.OK_CANCEL_OPTION);
				if (result == JOptionPane.OK_OPTION) {
					webContentList.setValueAt(newEntryName.getText(),         currentRow, 0);
					webContentList.setValueAt(newEntryType.getSelectedItem(), currentRow, 1);
					webContentList.setValueAt(newEntryFolders.getText(),      currentRow, 2);
					webContentList.setValueAt(newEntrySource.getText(),       currentRow, 3);
					updateWebContentModel();
				}
			}
		}
	}

	/**
	 * This parses the web config and populates the web section of this tab.
	 *
	 * @param webConf
	 * @param previouslySelectedRow the row that was selected before this parsing
	 */
	public static synchronized void setWebContentGUIFromWebConfFile(File webConf, Integer previouslySelectedRow) {
		SharedContentTab.webContentList.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		SharedContentTab.webContentList.setEnabled(false);

		try {
			// Remove any existing rows
			((WebContentTableModel) webContentList.getModel()).setRowCount(0);

			try (LineNumberReader br = new LineNumberReader(new InputStreamReader(new FileInputStream(webConf), StandardCharsets.UTF_8))) {
				String line;
				while ((line = br.readLine()) != null) {
					line = line.trim();

					if (line.length() > 0 && !line.startsWith("#") && line.indexOf('=') > -1) {
						String key = line.substring(0, line.indexOf('='));
						String value = line.substring(line.indexOf('=') + 1);
						String[] keys = parseFeedKey(key);
						String sourceType = keys[0];
						String folderName = keys[1] == null ? null : keys[1];

						try {
							if (
								sourceType.equals("imagefeed") ||
								sourceType.equals("audiofeed") ||
								sourceType.equals("videofeed") ||
								sourceType.equals("audiostream") ||
								sourceType.equals("videostream")
							) {
								String[] values = parseFeedValue(value);
								String uri = values[0];

								String readableType = "";
								switch (sourceType) {
									case "imagefeed":
										readableType = READABLE_TYPE_IMAGE_FEED;
										break;
									case "videofeed":
										readableType = READABLE_TYPE_VIDEO_FEED;
										break;
									case "audiofeed":
										readableType = READABLE_TYPE_AUDIO_FEED;
										break;
									case "audiostream":
										readableType = READABLE_TYPE_AUDIO_STREAM;
										break;
									case "videostream":
										readableType = READABLE_TYPE_VIDEO_STREAM;
										break;
									default:
										break;
								}

								// If the resource does not yet have a name, attempt to get one now
								String resourceName = values.length > 3 && values[3] != null ? values[3] : null;
								if (isBlank(resourceName)) {
									try {
										switch (sourceType) {
											case "imagefeed":
											case "videofeed":
											case "audiofeed":
												resourceName = values.length > 3 && values[3] != null ? values[3] : null;

												// Convert YouTube channel URIs to their feed URIs
												if (uri.contains("youtube.com/channel/")) {
													uri = uri.replaceAll("youtube.com/channel/", "youtube.com/feeds/videos.xml?channel_id=");
												}
												resourceName = getFeedTitle(uri);
												break;
											case "videostream":
											case "audiostream":
												resourceName = values.length > -1 && values[0] != null ? values[0] : null;
												uri = values.length > 1 && values[1] != null ? values[1] : null;
												break;
											default:
												break;
										}
									} catch (Exception e) {
										LOGGER.debug("Error while getting feed title: " + e);
									}
								}

								webContentTableModel.addRow(new Object[]{resourceName, readableType, folderName, uri});
							}
						} catch (ArrayIndexOutOfBoundsException e) {
							// catch exception here and go with parsing
							LOGGER.info("Error at line " + br.getLineNumber() + " of WEB.conf: " + e.getMessage());
							LOGGER.debug(null, e);
						}
					}
				}
			}

			// Re-select any row that was selected before we (re)parsed the config
			if (previouslySelectedRow != null) {
				webContentList.changeSelection(previouslySelectedRow, 1, false, false);
				Rectangle selectionToScrollTo = webContentList.getCellRect(previouslySelectedRow, 1, true);
				if (!selectionToScrollTo.isEmpty()) {
					webContentList.scrollRectToVisible(selectionToScrollTo);
				}
			}
		} catch (FileNotFoundException e) {
			LOGGER.debug("Can't read web configuration file {}", e.getMessage());
		} catch (IOException e) {
			LOGGER.warn("Unexpected error in WEB.conf: " + e.getMessage());
			LOGGER.debug("", e);
		} finally {
			SharedContentTab.webContentList.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
			SharedContentTab.webContentList.setEnabled(true);
		}
	}

	private static Map<String, String> feedTitlesCache = Collections.synchronizedMap(new HashMap<>());

	/**
	 * @param url feed URL
	 * @return a feed title from its URL
	 * @throws Exception
	 */
	public static String getFeedTitle(String url) throws Exception {
		// Check cache first
		String feedTitle = feedTitlesCache.get(url);
		if (feedTitle != null) {
			return feedTitle;
		}

		SyndFeedInput input = new SyndFeedInput();
		byte[] b = HTTPResource.downloadAndSendBinary(url);
		if (b != null) {
			SyndFeed feed = input.build(new XmlReader(new ByteArrayInputStream(b)));
			feedTitle = feed.getTitle();
			if (StringUtils.isNotBlank(feedTitle)) {
				feedTitlesCache.put(url, feedTitle);
				return feedTitle;
			}
		}

		return null;
	}
}
