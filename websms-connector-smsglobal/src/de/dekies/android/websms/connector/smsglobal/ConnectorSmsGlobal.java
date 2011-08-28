/*
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; If not, see <http://www.gnu.org/licenses/>.
 */
package de.dekies.android.websms.connector.smsglobal;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;

import org.apache.http.HttpResponse;
import org.apache.http.message.BasicNameValuePair;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.format.DateFormat;
import de.ub0r.android.websms.connector.common.Connector;
import de.ub0r.android.websms.connector.common.ConnectorCommand;
import de.ub0r.android.websms.connector.common.ConnectorSpec;
import de.ub0r.android.websms.connector.common.ConnectorSpec.SubConnectorSpec;
import de.ub0r.android.websms.connector.common.Log;
import de.ub0r.android.websms.connector.common.Utils;
import de.ub0r.android.websms.connector.common.WebSMSException;

/**
 * AsyncTask to manage IO to smsglobal.com API.
 * 
 * @see http://www.smsglobal.com/docs/HTTP.pdf
 * @author Dennis Kieselhorst
 */
public class ConnectorSmsGlobal extends Connector {
	/** Tag for output. */
	private static final String TAG = "smsglobal";

	/** Sms Global Gateway URL. */
	private static final String URL_SEND = "http://www.smsglobal.com/http-api.php";
	/** Sms Global Gateway URL. */
	private static final String URL_BALANCE = "http://www.smsglobal.com/credit-api.php";

	/** Custom Dateformater. */
	private static final String DATEFORMAT = "yyyy-mm-dd hh:mm:ss";

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final ConnectorSpec initSpec(final Context context) {
		final String name = context
				.getString(R.string.connector_smsglobal_name);
		ConnectorSpec c = new ConnectorSpec(name);
		c.setAuthor(context.getString(R.string.connector_smsglobal_author));
		c.setBalance(null);
		c.setCapabilities(ConnectorSpec.CAPABILITIES_UPDATE
				| ConnectorSpec.CAPABILITIES_SEND
				| ConnectorSpec.CAPABILITIES_PREFS);
		c.addSubConnector(
				TAG,
				name,
				(SubConnectorSpec.FEATURE_CUSTOMSENDER | SubConnectorSpec.FEATURE_SENDLATER));
		return c;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final ConnectorSpec updateSpec(final Context context,
			final ConnectorSpec connectorSpec) {
		final SharedPreferences p = PreferenceManager
				.getDefaultSharedPreferences(context);
		if (p.getBoolean(Preferences.PREFS_ENABLED, false)) {
			if (p.getString(Preferences.PREFS_USER, "").length() > 0
					&& p.getString(Preferences.PREFS_PASSWORD, "")// .
							.length() > 0) {
				connectorSpec.setReady();
			} else {
				connectorSpec.setStatus(ConnectorSpec.STATUS_ENABLED);
			}
		} else {
			connectorSpec.setStatus(ConnectorSpec.STATUS_INACTIVE);
		}
		return connectorSpec;
	}

	/**
	 * Check return code from smsglobal.com.
	 * 
	 * @param context
	 *            {@link Context}
	 * @param ret
	 *            return code
	 * @return true if no error code
	 */
	private boolean checkReturnCode(final Context context, final int ret) {
		Log.d(TAG, "ret=" + ret);
		if (ret < 200) {
			return true;
		} else if (ret < 300) {
			throw new WebSMSException(context, R.string.error_input);
		} else {
			if (ret == 401) {
				throw new WebSMSException(context, R.string.error_pw);
			}
			throw new WebSMSException(context, R.string.error_server, // .
					" " + ret);
		}
	}

	/**
	 * Send data.
	 * 
	 * @param context
	 *            {@link Context}
	 * @param command
	 *            {@link ConnectorCommand}
	 */
	private void sendData(final Context context, // .
			final ConnectorCommand command) {
		// do IO
		try { // get Connection
			final String text = command.getText();
			final boolean checkOnly = (text == null || text.length() == 0);
			final StringBuilder url = new StringBuilder();
			final ConnectorSpec cs = this.getSpec(context);
			final SharedPreferences p = PreferenceManager
					.getDefaultSharedPreferences(context);
			if (checkOnly) {
				url.append(URL_BALANCE);
			} else {
				url.append(URL_SEND);
			}
			ArrayList<BasicNameValuePair> postData = // TODO: use interface
														// after api is adapted:
														// List<NameValuePair>
			new ArrayList<BasicNameValuePair>();
			// mandatory arguments

			postData.add(new BasicNameValuePair("user", p.getString(
					Preferences.PREFS_USER, "")));
			postData.add(new BasicNameValuePair("password", p.getString(
					Preferences.PREFS_PASSWORD, "")));

			if (checkOnly) {
				// 2 digit ISO country code of SMS destination
				// ISO country codes can be found at
				// http://en.wikipedia.org/wiki/ISO_3166‐1 the ISO code is a
				// 2‐digit
				// alpha representation of the country. An example is, Australia
				// = AU, United Kingdom = GB
				postData.add(new BasicNameValuePair("country", "DE")); // TODO
																		// determine
																		// via
																		// recipient
																		// number
			} else {
				postData.add(new BasicNameValuePair("action", "sendsms"));
				final String customSender = command.getCustomSender();
				if (customSender == null) {
					postData.add(new BasicNameValuePair("from", Utils
							.national2international(
									command.getDefPrefix(),
									Utils.getSender(context,
											command.getDefSender()))));
				} else {
					postData.add(new BasicNameValuePair("from", customSender));
				}
				final long sendLater = command.getSendLater();
				if (sendLater > 0) {
					postData.add(new BasicNameValuePair("scheduledatetime",
							DateFormat.format(DATEFORMAT, sendLater).toString()));
				}
				postData.add(new BasicNameValuePair("text", URLEncoder.encode(
						text, "ISO-8859-15")));
				postData.add(new BasicNameValuePair("to", Utils
						.joinRecipientsNumbers(command.getRecipients(), ",",
								false)));

				// TODO: optional, handle messages > 160 signs
				// postData.add(new BasicNameValuePair("maxsplit",
				// "number of times allowed to split"));
			}
			// send data
			HttpResponse response = Utils.getHttpClient(url.toString(), null,
					postData, null, null, null, false);
			int resp = response.getStatusLine().getStatusCode();
			if (resp != HttpURLConnection.HTTP_OK) {
				this.checkReturnCode(context, resp);
				throw new WebSMSException(context, R.string.error_http, " "
						+ resp);
			}
			String htmlText = Utils.stream2str(
					response.getEntity().getContent()).trim();
			String[] lines = htmlText.split("\n");
			Log.d(TAG, "--HTTP RESPONSE--");
			Log.d(TAG, htmlText);
			Log.d(TAG, "--HTTP RESPONSE--");
			htmlText = null;
			for (String s : lines) {
				if (s.startsWith("CREDITS: ")) {
					cs.setBalance(s.split(" ")[1].trim() + "\u20AC"); // TODO
																		// line
																		// split
				}
			}
		} catch (IOException e) {
			Log.e(TAG, null, e);
			throw new WebSMSException(e.getMessage());
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doUpdate(final Context context, final Intent intent) {
		this.sendData(context, new ConnectorCommand(intent));
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doSend(final Context context, final Intent intent) {
		this.sendData(context, new ConnectorCommand(intent));
	}
}
