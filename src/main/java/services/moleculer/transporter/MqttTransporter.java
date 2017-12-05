package services.moleculer.transporter;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.fusesource.mqtt.client.Callback;
import org.fusesource.mqtt.client.CallbackConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;

import io.datatree.Tree;
import services.moleculer.Promise;
import services.moleculer.ServiceBroker;
import services.moleculer.service.Name;

@Name("MQTT Transporter")
public final class MqttTransporter extends Transporter {

	// --- PROPERTIES ---

	private String username;
	private String password;
	private String[] urls = new String[] { "127.0.0.1" };

	// --- OTHER MQTT PROPERTIES ---

	private boolean cleanSession = true;
	private short keepAliveSeconds = 60;
	private String version = "3.1";
	private QoS qos = QoS.AT_LEAST_ONCE;

	// --- MQTT CONNECTION ---

	private CallbackConnection client;

	// --- CONSTUCTORS ---

	public MqttTransporter() {
		super();
	}

	public MqttTransporter(String prefix) {
		super(prefix);
	}

	public MqttTransporter(String prefix, String... urls) {
		super(prefix);
		this.urls = urls;
	}

	public MqttTransporter(String prefix, String username, String password, String... urls) {
		super(prefix);
		this.username = username;
		this.password = password;
		this.urls = urls;
	}

	// --- START TRANSPORTER ---

	/**
	 * Initializes transporter instance.
	 * 
	 * @param broker
	 *            parent ServiceBroker
	 * @param config
	 *            optional configuration of the current component
	 */
	@Override
	public final void start(ServiceBroker broker, Tree config) throws Exception {

		// Process basic properties (eg. "prefix")
		super.start(broker, config);

		// Process config
		Tree urlNode = config.get(URL);
		if (urlNode != null) {
			List<String> urlList;
			if (urlNode.isPrimitive()) {
				urlList = new LinkedList<>();
				String url = urlNode.asString().trim();
				if (!url.isEmpty()) {
					urlList.add(url);
				}
			} else {
				urlList = urlNode.asList(String.class);
			}
			if (!urlList.isEmpty()) {
				urls = new String[urlList.size()];
				urlList.toArray(urls);
			}
		}
		username = config.get("username", username);
		password = config.get(PASSWORD, password);
		cleanSession = config.get("cleanSession", cleanSession);
		keepAliveSeconds = config.get("keepAliveSeconds", keepAliveSeconds);
		version = config.get("version", version);

		// Connect to MQTT server
		connect();
	}

	// --- CONNECT ---

	private final void connect() {
		try {

			// Create MQTT client options
			MQTT options = new MQTT();
			if (password != null) {
				options.setPassword(this.password);
			}
			if (username != null) {
				options.setUserName(this.username);
			}
			String[] array = new String[urls.length];
			for (int i = 0; i < urls.length; i++) {
				String url = urls[i];
				if (url.indexOf(':') == -1) {
					url = url + ":1883";
				}

				url = url.replace("mqtt://", "tcp://");

				if (!url.startsWith("tcp://")) {
					url = "tcp://" + url;
				}
				array[i] = url;
			}
			if (array.length > 0) {
				options.setHost(array[0]);
			}
			options.setReconnectAttemptsMax(0);
			options.setCleanSession(cleanSession);
			options.setKeepAlive(keepAliveSeconds);
			options.setVersion(version);

			// Create MQTT client
			disconnect();
			client = options.callbackConnection();
			client.connect(new Callback<Void>() {

				@Override
				public final void onSuccess(Void value) {
					logger.info("MQTT pub-sub client is estabilished.");
					connected();
				}

				@Override
				public final void onFailure(Throwable cause) {
					String msg = cause.getMessage();
					if (msg == null || msg.isEmpty()) {
						msg = "Unable to connect to NATS server!";
					} else if (!msg.endsWith("!") && !msg.endsWith(".")) {
						msg += "!";
					}
					logger.warn(msg);
					reconnect();
				}
			});
		} catch (Exception cause) {
			String msg = cause.getMessage();
			if (msg == null || msg.isEmpty()) {
				msg = "Unable to connect to MQTT server!";
			} else if (!msg.endsWith("!") && !msg.endsWith(".")) {
				msg += "!";
			}
			logger.warn(msg);
			reconnect();
		}
	}

	// --- DISCONNECT ---

	private final void disconnect() {
		if (client != null) {
			try {
				client.disconnect(publishCallback);
			} catch (Throwable cause) {
				logger.warn("Unexpected error occured while closing MQTT client!", cause);
			} finally {
				client = null;
			}
		}
	}

	// --- RECONNECT ---

	private final void reconnect() {
		disconnect();
		logger.info("Trying to reconnect...");
		scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
	}

	// --- ANY I/O ERROR ---

	@Override
	protected final void error(Throwable cause) {
		logger.warn("Unexpected communication error occured!", cause);
		reconnect();
	}

	// --- STOP TRANSPORTER ---

	/**
	 * Closes transporter.
	 */
	@Override
	public final void stop() {
		disconnect();
	}

	// --- SUBSCRIBE ---

	@Override
	public final Promise subscribe(String channel) {
		if (client != null) {
			try {
				client.subscribe(new Topic[] { new Topic(channel, qos) }, new Callback<byte[]>() {

					@Override
					public final void onSuccess(byte[] bytes) {
						received(channel, bytes);
					}

					@Override
					public final void onFailure(Throwable cause) {
						logger.error("Unexpected error occured!", cause);
					}

				});
			} catch (Exception cause) {
				return Promise.reject(cause);
			}
		}
		return Promise.resolve();
	}

	// --- PUBLISH ---

	@Override
	public final void publish(String channel, Tree message) {
		if (client != null) {
			try {
				if (debug) {
					logger.info("Submitting message to channel \"" + channel + "\":\r\n" + message.toString());
				}
				client.publish(channel, serializer.write(message), qos, false, publishCallback);
			} catch (Exception cause) {
				logger.warn("Unable to send message to MQTT server!", cause);
				reconnect();
			}
		}
	}

	// --- EMPTY CALLBACK INSTANCES ---

	private final Callback<Void> publishCallback = new Callback<Void>() {

		@Override
		public final void onSuccess(Void value) {
		}

		@Override
		public final void onFailure(Throwable cause) {
			logger.error("Unexpected error occured!", cause);
		}

	};

	// --- GETTERS / SETTERS ---

	public final String[] getUrls() {
		return urls;
	}

	public final void setUrls(String[] urls) {
		this.urls = urls;
	}

	public final String getUsername() {
		return username;
	}

	public final void setUsername(String username) {
		this.username = username;
	}

	public final String getPassword() {
		return password;
	}

	public final void setPassword(String password) {
		this.password = password;
	}

	public final boolean isCleanSession() {
		return cleanSession;
	}

	public final void setCleanSession(boolean cleanSession) {
		this.cleanSession = cleanSession;
	}

	public final short getKeepAliveSeconds() {
		return keepAliveSeconds;
	}

	public final void setKeepAliveSeconds(short keepAliveInterval) {
		this.keepAliveSeconds = keepAliveInterval;
	}

	public final String getVersion() {
		return version;
	}

	public final void setVersion(String version) {
		this.version = version;
	}

}