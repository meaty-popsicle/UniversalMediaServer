/*
 * This file is part of Universal Media Server, based on PS3 Media Server.
 *
 * This program is a free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; version 2 of the License only.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */

package net.pms.util;

import static net.pms.util.FileUtil.indexOf;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.sql.Connection;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.database.MediaDatabase;
import net.pms.database.MediaTableFailedLookups;
import net.pms.database.MediaTableFiles;
import net.pms.database.MediaTableMetadata;
import net.pms.database.MediaTableTVSeries;
import net.pms.database.MediaTableThumbnails;
import net.pms.database.MediaTableVideoMetadataActors;
import net.pms.database.MediaTableVideoMetadataAwards;
import net.pms.database.MediaTableVideoMetadataCountries;
import net.pms.database.MediaTableVideoMetadataDirectors;
import net.pms.database.MediaTableVideoMetadataGenres;
import net.pms.database.MediaTableVideoMetadataIMDbRating;
import net.pms.database.MediaTableVideoMetadataPosters;
import net.pms.database.MediaTableVideoMetadataProduction;
import net.pms.database.MediaTableVideoMetadataRated;
import net.pms.database.MediaTableVideoMetadataRatings;
import net.pms.database.MediaTableVideoMetadataReleased;
import net.pms.dlna.DLNAMediaInfo;
import net.pms.dlna.DLNAThumbnail;
import net.pms.image.ImageFormat;
import net.pms.image.ImagesUtil.ScaleType;
import net.pms.newgui.IFrame;
import net.pms.util.OpenSubtitle.OpenSubtitlesBackgroundWorkerThreadFactory;

/**
 * This class contains utility methods for API to get the Metadata info.
 */
public class APIUtils {
	private static final Logger LOGGER = LoggerFactory.getLogger(APIUtils.class);
	private static final PmsConfiguration CONFIGURATION = PMS.getConfiguration();
	private static IFrame frame = PMS.get().getFrame();
	private static final String VERBOSE_UA = "Universal Media Server " + PMS.getVersion();

	// Minimum number of threads in pool
	private static final ThreadPoolExecutor BACKGROUND_EXECUTOR = new ThreadPoolExecutor(0,
		5, // Maximum number of threads in pool
		30, // Number of seconds before an idle thread is terminated

		// The queue holding the tasks waiting to be processed
		TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
			new OpenSubtitlesBackgroundWorkerThreadFactory() // The ThreadFactory
	);

	private static final UriFileRetriever URI_FILE_RETRIEVER = new UriFileRetriever();
	private static Gson gson = new Gson();

	// Do not instantiate
	private APIUtils() {
	}

	/**
	 * These versions are returned to us from the API server. The versions are
	 * bumped when that endpoint has received a fix that warrants the client
	 * re-fetching its results.
	 */
	private static String apiDataVideoVersion = null;
	private static String apiDataSeriesVersion = null;

	/**
	 * These versions are used to manually invalidate API data. They should be
	 * bumped when we want to re-fetch valid API data, for example if we fixed
	 * a bug that caused some data to not be stored properly.
	 * The values will be appended to the versions above on startup.
	 */
	private static String apiDataVideoVersionLocal = "1";
	private static String apiDataSeriesVersionLocal = "1";

	/**
	 * The base URL for all images from TMDB
	 */
	private static String apiImageBaseURL = null;

	public static String getApiDataVideoVersion() {
		if (apiDataVideoVersion == null) {
			setApiMetadataVersions();
		}

		return apiDataVideoVersion;
	}

	public static String getApiDataSeriesVersion() {
		if (apiDataSeriesVersion == null) {
			setApiMetadataVersions();
		}

		return apiDataSeriesVersion;
	}

	/**
	 * Populates the apiDataSeriesVersion and apiDataVideoVersion
	 * variables, preferably from the API, but falling back to
	 * the local database, and appended with our local values.
	 * For example:
	 * A value of "3-2" means the remote version is 3 and the local
	 * version is 2.
	 */
	public static void setApiMetadataVersions() {
		Connection connection = null;
		try {
			connection = MediaDatabase.getConnectionIfAvailable();
			HashMap<String, String> jsonData = new HashMap<>();

			if (CONFIGURATION.getExternalNetwork()) {
				URL domain = new URL("https://api.universalmediaserver.com");
				URL url = new URL(domain, "/api/subversions");
				String apiResult = getJson(url);

				try {
					jsonData = gson.fromJson(apiResult, jsonData.getClass());
				} catch (JsonSyntaxException e) {
					LOGGER.debug("API Result was not JSON. Received: {}, full stack: {}", apiResult, e);
				}
			}

			if (jsonData == null || jsonData.isEmpty() || jsonData.containsKey("statusCode")) {
				if (jsonData != null && jsonData.containsKey("statusCode") && "500".equals(jsonData.get("statusCode"))) {
					LOGGER.debug("Got a 500 error while looking for metadata subversions");
				}
				LOGGER.trace("Did not get metadata subversions, will attempt to use the database version");
				if (connection != null) {
					apiDataSeriesVersion = MediaTableMetadata.getMetadataValue(connection, "SERIES_VERSION") + "-" + apiDataSeriesVersionLocal;
					apiDataVideoVersion = MediaTableMetadata.getMetadataValue(connection, "VIDEO_VERSION") + "-" + apiDataVideoVersionLocal;
				}
				if (apiDataSeriesVersion == null) {
					LOGGER.trace("API versions could not be fetched from the API or the local database");
				}
				return;
			}

			apiDataSeriesVersion = jsonData.get("series");
			apiDataVideoVersion = jsonData.get("video");

			// Persist the values to the database to be used as fallbacks
			if (connection != null) {
				if (apiDataSeriesVersion != null) {
					MediaTableMetadata.setOrUpdateMetadataValue(connection, "SERIES_VERSION", apiDataSeriesVersion);
					MediaTableMetadata.setOrUpdateMetadataValue(connection, "VIDEO_VERSION", apiDataVideoVersion);
				}
			}

			apiDataSeriesVersion += "-" + apiDataSeriesVersionLocal;
			apiDataVideoVersion += "-" + apiDataVideoVersionLocal;
		} catch (IOException e) {
			LOGGER.trace("Error while setting API metadata versions", e);
		} finally {
			MediaDatabase.close(connection);
		}
	}

	public static String getApiImageBaseURL() {
		if (apiImageBaseURL == null) {
			setApiImageBaseURL();
		}

		return apiImageBaseURL;
	}

	/**
	 * Populates the apiImageBaseURL variable, preferably from the API,
	 * but falling back to the local database.
	 */
	public static void setApiImageBaseURL() {
		Connection connection = null;
		try {
			connection = MediaDatabase.getConnectionIfAvailable();
			HashMap<String, String> jsonData = new HashMap<>();

			if (CONFIGURATION.getExternalNetwork()) {
				URL domain = new URL("https://api.universalmediaserver.com");
				URL url = new URL(domain, "/api/configuration");
				String apiResult = getJson(url);

				try {
					jsonData = gson.fromJson(apiResult, jsonData.getClass());
				} catch (JsonSyntaxException e) {
					LOGGER.debug("API Result was not JSON. Received: {}, full stack: {}", apiResult, e);
				}
			}

			if (jsonData == null || jsonData.isEmpty() || jsonData.containsKey("statusCode")) {
				if (jsonData != null && jsonData.containsKey("statusCode") && "500".equals(jsonData.get("statusCode"))) {
					LOGGER.debug("Got a 500 error while looking for imageBaseURL");
				}
				LOGGER.trace("Did not get imageBaseURL, will attempt to use the database version");
				if (connection != null) {
					apiImageBaseURL = MediaTableMetadata.getMetadataValue(connection, "IMAGE_BASE_URL");
				}
				if (apiImageBaseURL == null) {
					LOGGER.trace("imageBaseURL could not be fetched from the API or the local database");
				}
				return;
			}

			apiImageBaseURL = jsonData.get("imageBaseURL");

			// Persist the values to the database to be used as fallbacks
			if (connection != null) {
				if (apiImageBaseURL != null) {
					MediaTableMetadata.setOrUpdateMetadataValue(connection, "IMAGE_BASE_URL", apiImageBaseURL);
				}
			}
		} catch (Exception e) {
			LOGGER.trace("Error while setting imageBaseURL", e);
		} finally {
			MediaDatabase.close(connection);
		}
	}

	/**
	 * Enhances existing metadata attached to this media by querying our API.
	 *
	 * @param file
	 * @param media
	 */
	public static void backgroundLookupAndAddMetadata(final File file, final DLNAMediaInfo media) {
		Runnable r = () -> {
			// wait until the realtime lock is released before starting
			PMS.REALTIME_LOCK.lock();
			PMS.REALTIME_LOCK.unlock();

			if (!CONFIGURATION.getExternalNetwork()) {
				LOGGER.trace("Not doing background API lookup because external network is disabled");
				return;
			}

			if (!CONFIGURATION.isUseInfoFromIMDb()) {
				LOGGER.trace("Not doing background API lookup because isUseInfoFromIMDb is disabled");
				return;
			}

			if (!CONFIGURATION.getUseCache()) {
				LOGGER.trace("Not doing background API lookup because cache/database is disabled");
				return;
			}

			if (!MediaDatabase.isAvailable()) {
				LOGGER.trace("Database is closed");
				return;
			}

			Connection connection = null;
			try {
				connection = MediaDatabase.getConnectionIfAvailable();
				if (connection == null) {
					return;
				}

				connection.setAutoCommit(false);

				if (MediaTableFiles.doesLatestApiMetadataExist(connection, file.getAbsolutePath(), file.lastModified())) {
					LOGGER.trace("The latest metadata already exists for {}", file.getName());
					return;
				}

				if (MediaTableFailedLookups.hasLookupFailedRecently(connection, file.getAbsolutePath(), true)) {
					return;
				}

				frame.setSecondaryStatusLine(Messages.getString("StatusBar.GettingAPIInfoFor") + " " + file.getName());
				HashMap<?, ?> metadataFromAPI;

				String year                        = media.getYear();
				String titleFromFilename           = media.getMovieOrShowName();
				String titleSimplifiedFromFilename = FileUtil.getSimplifiedShowName(titleFromFilename);
				String tvSeasonFromFilename        = media.getTVSeason();
				String tvEpisodeNumberFromFilename = media.getTVEpisodeNumber();
				String tvSeriesStartYear           = media.getTVSeriesStartYear();

				// unset tvSeriesStartYear if it is NOT in the title because it must have come from the API earlier and will mess up the matching logic
				// todo: use better matching logic
				if (isNotBlank(tvSeriesStartYear)) {
					int yearIndex = indexOf(Pattern.compile("\\s\\(" + tvSeriesStartYear + "\\)"), titleFromFilename);
					if (yearIndex == -1) {
						tvSeriesStartYear = null;
					}
				}

				Boolean isTVEpisode = media.isTVEpisode();

				try {
					if (isTVEpisode) {
						metadataFromAPI = getAPIMetadata(file, titleFromFilename, tvSeriesStartYear, tvSeasonFromFilename, media.getTVEpisodeNumberUnpadded());
					} else {
						metadataFromAPI = getAPIMetadata(file, titleFromFilename, year, null, null);
					}

					if (metadataFromAPI == null || metadataFromAPI.containsKey("statusCode")) {
						LOGGER.trace("Failed lookup for " + file.getName());
						MediaTableFailedLookups.set(connection, file.getAbsolutePath(), (metadataFromAPI != null ? (String) metadataFromAPI.get("serverResponse") : ""), true);

						// File lookup failed, but before we return, attempt to enhance TV series data
						if (isTVEpisode) {
							setTVSeriesInfo(connection, null, titleFromFilename, tvSeriesStartYear, titleSimplifiedFromFilename, file, media);
						}

						return;
					} else {
						LOGGER.trace("Found an API match for " + file.getName());
					}
				} catch (IOException ex) {
					// this likely means a transient error so don't store the failure, to allow retries
					LOGGER.debug("Likely transient error", ex);
					return;
				}

				String typeFromAPI = (String) metadataFromAPI.get("type");
				String yearFromAPI = (String) metadataFromAPI.get("year");
				boolean isTVEpisodeFromAPI = isNotBlank(typeFromAPI) && typeFromAPI.equals("episode");

				// At this point, this is the episode title if it is an episode
				String titleFromAPI = null;
				String tvEpisodeTitleFromAPI = null;
				if (isTVEpisodeFromAPI) {
					tvEpisodeTitleFromAPI = (String) metadataFromAPI.get("title");
				} else {
					titleFromAPI = (String) metadataFromAPI.get("title");
				}

				String tvSeasonFromAPI = (String) metadataFromAPI.get("season");
				String tvEpisodeNumberFromAPI = (String) metadataFromAPI.get("episode");
				if (tvEpisodeNumberFromAPI != null && tvEpisodeNumberFromAPI.length() == 1) {
					tvEpisodeNumberFromAPI = "0" + tvEpisodeNumberFromAPI;
				}
				String seriesIMDbIDFromAPI = (String) metadataFromAPI.get("seriesIMDbID");

				/**
				 * Only continue if the API returned a result that agrees with our filename.
				 * Specifically, fail early if:
				 * - the filename and API do not agree about it being a TV episode
				 * - for TV episodes, the season and episode number must exist and match, and
				 *   must have a series IMDb ID.
				 * - for movies, if we got a year from the filename, the API must agree
				 */
				if (
					(isTVEpisode && !isTVEpisodeFromAPI) ||
					(!isTVEpisode && isTVEpisodeFromAPI) ||
					(
						isTVEpisode &&
						(
							isBlank(tvSeasonFromFilename) ||
							isBlank(tvSeasonFromAPI) ||
							!tvSeasonFromFilename.equals(tvSeasonFromAPI) ||
							isBlank(tvEpisodeNumberFromFilename) ||
							isBlank(tvEpisodeNumberFromAPI) ||
							!tvEpisodeNumberFromFilename.startsWith(tvEpisodeNumberFromAPI) ||
							isBlank(seriesIMDbIDFromAPI)
						)
					) ||
					(
						!isTVEpisode &&
						(
							isNotBlank(year) &&
							isNotBlank(yearFromAPI) &&
							!year.equals(yearFromAPI)
						)
					)
				) {
					LOGGER.debug("API data was different to our parsed data, not storing it.");
					MediaTableFailedLookups.set(connection, file.getAbsolutePath(), "Data mismatch", true);

					LOGGER.trace("Filename data: " + media);
					LOGGER.trace("API data: " + metadataFromAPI);

					// Before we return, attempt to enhance TV series data
					if (isTVEpisode) {
						setTVSeriesInfo(connection, null, titleFromFilename, tvSeriesStartYear, titleSimplifiedFromFilename, file, media);
					}

					return;
				}

				LOGGER.trace("API data matches filename data for " + file.getName());

				// Now that we are happy with the API data, let's make some clearer variables
				String title = null;
				String tvEpisodeNumber = tvEpisodeNumberFromAPI;
				String tvEpisodeTitle  = tvEpisodeTitleFromAPI;
				String tvSeason        = tvSeasonFromAPI;
				year                   = yearFromAPI;

				if (isTVEpisode) {
					String titleFromDatabase = setTVSeriesInfo(connection, seriesIMDbIDFromAPI, titleFromFilename, tvSeriesStartYear, titleSimplifiedFromFilename, file, media);
					if (titleFromDatabase != null) {
						title = titleFromDatabase;
					}
				} else {
					title = isNotBlank(titleFromAPI) ? titleFromAPI : titleFromFilename;
				}

				if (isBlank(title)) {
					title = titleFromFilename;
				}
				String titleSimplified = FileUtil.getSimplifiedShowName(title);

				media.setMovieOrShowName(title);
				media.setSimplifiedMovieOrShowName(titleSimplified);
				media.setYear(year);

				media.setIMDbID((String) metadataFromAPI.get("imdbID"));

				// Set the poster as the thumbnail
				String posterFromApi = getPosterUrlFromApiInfo(
					(String) metadataFromAPI.get("poster"),
					(String) metadataFromAPI.get("posterRelativePath")
				);
				if (posterFromApi != null) {
					try {
						byte[] image = URI_FILE_RETRIEVER.get(posterFromApi);
						media.setThumb(DLNAThumbnail.toThumbnail(image, 640, 480, ScaleType.MAX, ImageFormat.JPEG, false));
					} catch (EOFException e) {
						LOGGER.debug(
							"Error reading \"{}\" thumbnail from API: Unexpected end of stream, probably corrupt or read error.",
							file.getName()
						);
					} catch (UnknownFormatException e) {
						LOGGER.debug("Could not read \"{}\" thumbnail from API: {}", file.getName(), e.getMessage());
					} catch (IOException e) {
						LOGGER.error("Error reading \"{}\" thumbnail from API: {}", file.getName(), e.getMessage());
						LOGGER.trace("", e);
					}
				}

				// unused metadata from our api
//					media.setTagline((String) metadataFromAPI.get("tagline"));
//					media.setTrivia((String) metadataFromAPI.get("trivia"));
//					media.setVotes((String) metadataFromAPI.get("votes"));
//					media.setBoxOffice((String) metadataFromAPI.get("boxoffice"));
//					media.setGoofs((String) metadataFromAPI.get("goofs"));

				if (isTVEpisode) {
					media.setTVSeason(tvSeason);
					media.setTVEpisodeNumber(tvEpisodeNumber);
					if (isNotBlank(tvEpisodeTitle)) {
						LOGGER.trace("Setting episode name from api: " + tvEpisodeTitle);
						media.setTVEpisodeName(tvEpisodeTitle);
					}

					media.setIsTVEpisode(true);
				}

				LOGGER.trace("setting metadata for " + file.getName());
				MediaTableFiles.insertVideoMetadata(connection, file.getAbsolutePath(), file.lastModified(), media, metadataFromAPI);

				if (media.getThumb() != null) {
					MediaTableThumbnails.setThumbnail(connection, media.getThumb(), file.getAbsolutePath(), -1, false);
				}

				if (metadataFromAPI.get("actors") != null) {
					MediaTableVideoMetadataActors.set(connection, file.getAbsolutePath(), new HashSet<Object>((ArrayList<?>) metadataFromAPI.get("actors")), -1);
				}
				MediaTableVideoMetadataAwards.set(connection, file.getAbsolutePath(), (String) metadataFromAPI.get("awards"), -1);
				MediaTableVideoMetadataCountries.set(connection, file.getAbsolutePath(), (String) metadataFromAPI.get("country"), -1);
				if (metadataFromAPI.get("directors") != null) {
					MediaTableVideoMetadataDirectors.set(connection, file.getAbsolutePath(), new HashSet<Object>((ArrayList<?>) metadataFromAPI.get("directors")), -1);
				}
				if (metadataFromAPI.get("rating") != null && (Double) metadataFromAPI.get("rating") != 0.0) {
					MediaTableVideoMetadataIMDbRating.set(connection, file.getAbsolutePath(), Double.toString((Double) metadataFromAPI.get("rating")), -1);
				}
				if (metadataFromAPI.get("genres") != null) {
					MediaTableVideoMetadataGenres.set(connection, file.getAbsolutePath(), new HashSet<Object>((ArrayList<?>) metadataFromAPI.get("genres")), -1);
				}
				if (posterFromApi != null) {
					MediaTableVideoMetadataPosters.set(connection, file.getAbsolutePath(), posterFromApi, -1);
				}
				MediaTableVideoMetadataProduction.set(connection, file.getAbsolutePath(), (String) metadataFromAPI.get("production"), -1);
				MediaTableVideoMetadataRated.set(connection, file.getAbsolutePath(), (String) metadataFromAPI.get("rated"), -1);
				if (metadataFromAPI.get("ratings") != null) {
					MediaTableVideoMetadataRatings.set(connection, file.getAbsolutePath(), new HashSet<Object>((ArrayList<?>) metadataFromAPI.get("ratings")), -1);
				}
				MediaTableVideoMetadataReleased.set(connection, file.getAbsolutePath(), (String) metadataFromAPI.get("released"), -1);
			} catch (Exception ex) {
				LOGGER.trace("Error in API parsing:", ex);
			} finally {
				try {
					if (connection != null) {
						connection.commit();
					}

					MediaDatabase.close(connection);
					frame.setSecondaryStatusLine(null);
				} catch (Exception e) {
					LOGGER.error("Error in commit in APIUtils.backgroundLookupAndAdd: {}", e.getMessage());
					LOGGER.trace("", e);
				}
			}
		};
		BACKGROUND_EXECUTOR.execute(r);
	}

	/**
	 * Performs a database lookup for the TV series, and an API lookup if it
	 * does not already exist with API data.
	 * Also writes the poster from the API to the thumbnail in the database.
	 * Also standardizes the series name across the episode records in the
	 * FILES table.
	 *
	 * @param connection
	 * @param seriesIMDbIDFromAPI
	 * @param titleFromFilename
	 * @param startYear
	 * @param titleSimplifiedFromFilename
	 * @param file
	 * @param media
	 * @return the title of the series.
	 */
	private static String setTVSeriesInfo(final Connection connection, String seriesIMDbIDFromAPI, String titleFromFilename, String startYear, String titleSimplifiedFromFilename, File file, DLNAMediaInfo media) {
		long tvSeriesDatabaseId;
		String title;
		String titleSimplified;

		String failedLookupKey = titleSimplifiedFromFilename;
		if (seriesIMDbIDFromAPI != null) {
			failedLookupKey += seriesIMDbIDFromAPI;
		}

		/*
		 * Get the TV series title from our database, or from our API if it's not
		 * in our database yet, and persist it to our database.
		 */
		try {
			HashMap<String, Object> seriesMetadataFromDatabase = null;
			if (seriesIMDbIDFromAPI != null) {
				seriesMetadataFromDatabase = MediaTableTVSeries.getByIMDbID(connection, seriesIMDbIDFromAPI);
			}

			if (seriesMetadataFromDatabase != null) {
				LOGGER.trace("TV series with API data already found in database {}", seriesMetadataFromDatabase.get("TITLE"));
				return (String) seriesMetadataFromDatabase.get("TITLE");
			}

			/*
			 * This either means there is no entry in the TV Series table for this series, or
			 * there is but it only contains filename info - not API yet.
			 */
			LOGGER.trace("API metadata for TV series {} (IMDb ID: {}) does not already exist in the database", titleFromFilename, seriesIMDbIDFromAPI);

			// Start by checking if we have already failed this lookup recently
			if (MediaTableFailedLookups.hasLookupFailedRecently(connection, failedLookupKey, false)) {
				return null;
			}

			HashMap<String, Object> seriesMetadataFromAPI = getTVSeriesInfo(titleFromFilename, seriesIMDbIDFromAPI, startYear);
			if (seriesMetadataFromAPI == null || seriesMetadataFromAPI.containsKey("statusCode")) {
				if (seriesMetadataFromAPI != null && seriesMetadataFromAPI.containsKey("statusCode") && seriesMetadataFromAPI.get("statusCode") == "500") {
					LOGGER.debug("Got a 500 error while looking for TV series with title {} and IMDb API {}", titleFromFilename, seriesIMDbIDFromAPI);
				} else {
					LOGGER.trace("Did not find matching series for the episode in our API for {}", file.getName());
					MediaTableFailedLookups.set(connection, titleSimplifiedFromFilename, "No API result - expected ", false);
				}
				return null;
			}

			title = (String) seriesMetadataFromAPI.get("title");
			if (isNotBlank(startYear)) {
				title += " (" + startYear + ")";
			}
			titleSimplified = FileUtil.getSimplifiedShowName(title);
			String typeFromAPI = (String) seriesMetadataFromAPI.get("type");
			boolean isSeriesFromAPI = isNotBlank(typeFromAPI) && typeFromAPI.equals("series");

			boolean isAPIDataValid = true;
			String validationFailedPrepend = "not storing the series API lookup result because ";
			// Only continue if the simplified titles match
			if (!titleSimplified.equalsIgnoreCase(titleSimplifiedFromFilename)) {
				isAPIDataValid = false;
				LOGGER.debug(validationFailedPrepend + "file and API TV series titles do not match. {} vs {}", titleSimplified, titleSimplifiedFromFilename);
				MediaTableFailedLookups.set(connection, titleSimplifiedFromFilename, "Title mismatch - expected " + titleSimplifiedFromFilename + " but got " + titleSimplified, false);
			} else if (!isSeriesFromAPI) {
				isAPIDataValid = false;
				LOGGER.debug(validationFailedPrepend + "we received a non-series from API");
				MediaTableFailedLookups.set(connection, titleSimplifiedFromFilename, "Type mismatch - expected series but got " + typeFromAPI, false);
			}

			if (!isAPIDataValid) {
				return null;
			}

			/*
			 * Now we have an API result for the TV series, we need to see whether
			 * to insert it or update existing data, so we attempt to find an entry
			 * based on the title.
			 */
			seriesMetadataFromDatabase = MediaTableTVSeries.getByTitle(connection, title);

			// Restore the startYear appended to the title if it is in the filename
			if (isNotBlank(startYear)) {
				String titleFromAPI = seriesMetadataFromAPI.get("title") + " (" + startYear + ")";
				seriesMetadataFromAPI.replace("title", titleFromAPI);
			}

			if (seriesMetadataFromDatabase == null) {
				LOGGER.trace("No title match, so let's make a new entry for {}", seriesMetadataFromAPI.get("title"));
				tvSeriesDatabaseId = MediaTableTVSeries.set(connection, seriesMetadataFromAPI, null);
			} else {
				LOGGER.trace("There is an existing entry, so let's fill it in with API data for {}", seriesMetadataFromDatabase.get("TITLE"));
				tvSeriesDatabaseId = (long) seriesMetadataFromDatabase.get("ID");
				MediaTableTVSeries.insertAPIMetadata(connection, seriesMetadataFromAPI);
			}

			if (tvSeriesDatabaseId == -1) {
				LOGGER.debug("tvSeriesDatabaseId was not set, something went wrong");
				return null;
			}

			// Now we insert the TV series data into the other tables
			HashSet<?> actorsFromAPI = new HashSet<Object>((ArrayList<?>) seriesMetadataFromAPI.get("actors"));
			if (!actorsFromAPI.isEmpty()) {
				MediaTableVideoMetadataActors.set(connection, "", actorsFromAPI, tvSeriesDatabaseId);
			}
			MediaTableVideoMetadataAwards.set(connection, "", (String) seriesMetadataFromAPI.get("awards"), tvSeriesDatabaseId);
			MediaTableVideoMetadataCountries.set(connection, "", (String) seriesMetadataFromAPI.get("country"), tvSeriesDatabaseId);
			HashSet<?> directorsFromAPI = new HashSet<Object>((ArrayList<?>) seriesMetadataFromAPI.get("directors"));
			if (!directorsFromAPI.isEmpty()) {
				MediaTableVideoMetadataDirectors.set(connection, "", directorsFromAPI, tvSeriesDatabaseId);
			}
			HashSet<?> genresFromAPI = new HashSet<Object>((ArrayList<?>) seriesMetadataFromAPI.get("genres"));
			if (!genresFromAPI.isEmpty()) {
				MediaTableVideoMetadataGenres.set(connection, "", genresFromAPI, tvSeriesDatabaseId);
			}
			MediaTableVideoMetadataProduction.set(connection, "", (String) seriesMetadataFromAPI.get("production"), tvSeriesDatabaseId);

			String posterFromApi = getPosterUrlFromApiInfo(
				(String) seriesMetadataFromAPI.get("poster"),
				(String) seriesMetadataFromAPI.get("posterRelativePath")
			);
			if (posterFromApi != null) {
				try {
					byte[] image = URI_FILE_RETRIEVER.get(posterFromApi);
					MediaTableThumbnails.setThumbnail(connection, DLNAThumbnail.toThumbnail(image, 640, 480, ScaleType.MAX, ImageFormat.JPEG, false), null, tvSeriesDatabaseId, false);
				} catch (EOFException e) {
					LOGGER.debug(
						"Error reading \"{}\" thumbnail from API: Unexpected end of stream, probably corrupt or read error.",
						file.getName()
					);
				} catch (UnknownFormatException e) {
					LOGGER.debug("Could not read \"{}\" thumbnail from API: {}", file.getName(), e.getMessage());
				} catch (IOException e) {
					LOGGER.error("Error reading \"{}\" thumbnail from API: {}", file.getName(), e.getMessage());
					LOGGER.trace("", e);
				}
				MediaTableVideoMetadataPosters.set(connection, "", posterFromApi, tvSeriesDatabaseId);
			}

			MediaTableVideoMetadataRated.set(connection, "", (String) seriesMetadataFromAPI.get("rated"), tvSeriesDatabaseId);
			if (seriesMetadataFromAPI.get("rating") != null && (Double) seriesMetadataFromAPI.get("rating") != 0.0) {
				MediaTableVideoMetadataIMDbRating.set(connection, "", Double.toString((Double) seriesMetadataFromAPI.get("rating")), tvSeriesDatabaseId);
			}
			HashSet<?> ratingsFromAPI = new HashSet<Object>((ArrayList<?>) seriesMetadataFromAPI.get("ratings"));
			if (!ratingsFromAPI.isEmpty()) {
				MediaTableVideoMetadataRatings.set(connection, "", ratingsFromAPI, tvSeriesDatabaseId);
			}
			MediaTableVideoMetadataReleased.set(connection, "", (String) seriesMetadataFromAPI.get("released"), tvSeriesDatabaseId);

			// Replace any close-but-not-exact titles in the FILES table
			if (
				titleFromFilename != null &&
				titleSimplifiedFromFilename != null &&
				!title.equals(titleFromFilename) &&
				titleSimplified.equals(titleSimplifiedFromFilename)
			) {
				LOGGER.trace("Converting rows in FILES table with the show name " + titleFromFilename + " to " + title);
				MediaTableFiles.updateMovieOrShowName(connection, titleFromFilename, title);
			}

			return title;
		} catch (IOException e) {
			LOGGER.error("Error getting \"{}\" TV series info from API: {}", file.getName(), e.getMessage());
			LOGGER.trace("", e);
		}

		return null;
	}

	/**
	 * Attempts to get metadata about a file from our API.
	 *
	 * @param file the {@link File} to lookup.
	 * @param movieOrTVSeriesTitle the title of the movie or TV series
	 * @param year optional year to include with title lookups
	 * @param season
	 * @param episode
	 * @return The parameter {@link String}.
	 * @throws IOException If an I/O error occurs during the operation.
	 */
	public static HashMap<?, ?> getAPIMetadata(File file, String movieOrTVSeriesTitle, String year, String season, String episode) throws IOException {
		Path path = null;
		String apiResult = null;

		String imdbID = null;
		String osdbHash = null;
		long filebytesize = 0L;

		if (file != null) {
			path = file.toPath();
			osdbHash = OpenSubtitle.getHash(path);
			if (isBlank(osdbHash)) {
				LOGGER.trace("OSDb hash was blank for " + path);
			}
			filebytesize = file.length();

			imdbID = ImdbUtil.extractImdbId(path, false);
		}

		// Remove the year from the title before lookup if it exists
		String yearRegex = "(?:19|20)\\d{2}";
		if (isNotBlank(year)) {
			yearRegex = year;
		}
		int yearIndex = indexOf(Pattern.compile("\\s\\(" + yearRegex + "\\)"), movieOrTVSeriesTitle);
		if (yearIndex > -1) {
			movieOrTVSeriesTitle = movieOrTVSeriesTitle.substring(0, yearIndex);
		}

		apiResult = getInfoFromAllExtractedData(movieOrTVSeriesTitle, false, year, season, episode, imdbID, osdbHash, filebytesize);

		String notFoundMessage = "Metadata not found on OpenSubtitles";
		if (apiResult == null || Objects.equals(notFoundMessage, apiResult)) {
			LOGGER.trace("no result for " + movieOrTVSeriesTitle + ", received: " + apiResult);
			return null;
		}

		HashMap<?, ?> data = new HashMap<Object, Object>();

		try {
			data = gson.fromJson(apiResult, data.getClass());
		} catch (JsonSyntaxException e) {
			LOGGER.debug("API Result was not JSON. Received: {}, full stack: {}", apiResult, e);
		}

		if (data.isEmpty()) {
			return null;
		}

		return data;
	}

	/**
	 * Initiates a series of API lookups, from most to least desirable, until
	 * one succeeds.
	 *
	 * @param formattedName the name to use in the name search
	 * @param imdbID
	 * @param year
	 * @return The API result or null
	 * @throws IOException If an I/O error occurs during the operation.
	 */
	public static HashMap<String, Object> getTVSeriesInfo(String formattedName, String imdbID, String startYear) throws IOException {
		String apiResult = null;

		// Remove the startYear from the title if it exists
		String startYearRegex = "(?:19|20)\\d{2}";
		if (isNotBlank(startYear)) {
			startYearRegex = startYear;
		}
		int startYearIndex = indexOf(Pattern.compile("\\s\\(" + startYearRegex + "\\)"), formattedName);
		if (startYearIndex > -1) {
			formattedName = formattedName.substring(0, startYearIndex);
		}

		apiResult = getInfoFromAllExtractedData(formattedName, true, startYear, null, null, imdbID, null, 0L);

		HashMap<String, Object> data = new HashMap<String, Object>();
		try {
			data = gson.fromJson(apiResult, data.getClass());
		} catch (JsonSyntaxException e) {
			LOGGER.debug("API Result was not JSON. Received: {}, full stack: {}", apiResult, e);
		}

		if (data != null && data.isEmpty()) {
			return null;
		}

		return data;
	}

	/**
	 * Attempt to return information from our API about the file based on
	 * all data we have extracted about it.
	 *
	 * @param title title or filename
	 * @param isSeries whether we are looking for a TV series (not a video itself)
	 * @param year for movies this is the release year, for TV episodes or series this
	 *             is the year of the first episode release.
	 * @param season
	 * @param episode
	 * @param imdbID
	 * @param osdbHash
	 * @param filebytesize
	 *
	 * @return a string array including the IMDb ID, episode title, season number,
	 *         episode number relative to the season, and the show name, or null
	 *         if we couldn't find it.
	 *
	 * @throws IOException
	 */
	private static String getInfoFromAllExtractedData(
		String title,
		boolean isSeries,
		String year,
		String season,
		String episode,
		String imdbID,
		String osdbHash,
		long filebytesize
	) throws IOException {
		URL domain = new URL("https://api.universalmediaserver.com");
		String endpoint = isSeries ? "series/v2" : "video/v2";
		ArrayList<String> getParameters = new ArrayList<>();
		if (isNotBlank(title)) {
			title = URLEncoder.encode(title, StandardCharsets.UTF_8.toString());
			getParameters.add("title=" + title);
		}
		if (isNotBlank(year)) {
			getParameters.add("year=" + year);
		}
		if (isNotBlank(season)) {
			getParameters.add("season=" + season);
		}
		if (isNotBlank(episode)) {
			getParameters.add("episode=" + episode);
		}
		if (isNotBlank(imdbID)) {
			getParameters.add("imdbID=" + imdbID);
		}
		if (isNotBlank(osdbHash)) {
			getParameters.add("osdbHash=" + osdbHash);
		}
		if (filebytesize != 0L) {
			getParameters.add("filebytesize=" + filebytesize);
		}
		String getParametersJoined = StringUtils.join(getParameters, "&");
		URL url = new URL(domain, "/api/media/" + endpoint + "?" + getParametersJoined);

		LOGGER.trace("Getting API data from: {}", url);

		return getJson(url);
	}

	private static String getJson(URL url) throws IOException {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) url.openConnection();
			connection.setAllowUserInteraction(false);
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("Content-length", "0");
			connection.setRequestProperty("User-Agent", VERBOSE_UA);
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			connection.connect();

			int status = connection.getResponseCode();
			String response;

			switch (status) {
				case 200:
				case 201:
					StringBuilder sb = new StringBuilder();
					try (
						InputStreamReader instream = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);
						BufferedReader br = new BufferedReader(instream)
					) {
						String line;
						while ((line = br.readLine()) != null) {
							sb.append(line.trim()).append("\n");
						}
					} catch (Exception e) {
						LOGGER.info("API lookup error for {}, {}", connection.getURL(), e.getMessage());
					}
					LOGGER.debug("API URL was {}", connection.getURL());
					response = sb.toString().trim();
					break;
				default:
					StringBuilder errorMessage = new StringBuilder();
					if (connection.getErrorStream() != null) {
						try (
							InputStreamReader instream = new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8);
							BufferedReader br = new BufferedReader(instream)
						) {
							String line;
							while ((line = br.readLine()) != null) {
								errorMessage.append(line.trim()).append("\n");
							}
						} catch (Exception e) {
							LOGGER.info("API lookup error for {}, {}", connection.getURL(), e.getMessage());
						}
					}

					LOGGER.debug("API status was {} for {}, {}", status, errorMessage, connection.getURL());
					response = "{ statusCode: \"" + status + "\", serverResponse: " + gson.toJson(errorMessage) + " }";
			}

			return response;
		} catch (Exception e) {
			LOGGER.debug("Error while parsing JSON response: {}", e);
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception ex) {
					LOGGER.debug("Error while disconnecting connection: {}", ex);
				}
			}
		}
		return null;
	}

	/**
	 * @param posterFromApi a full URL of a poster from OMDb
	 * @param posterRelativePathFromApi this is either a "poster_path" or "still_path" from TMDB
	 * @return a full URL to a poster or meaningful screenshot
	 */
	private static String getPosterUrlFromApiInfo(String posterFromApi, String posterRelativePathFromApi) {
		if (posterRelativePathFromApi != null) {
			return getApiImageBaseURL() + "w500" + posterRelativePathFromApi;
		}

		return posterFromApi;
	}
}
