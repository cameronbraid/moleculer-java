/**
 * This software is licensed under MIT license.<br>
 * <br>
 * Copyright 2017 Andras Berkes [andras.berkes@programmer.net]<br>
 * <br>
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:<br>
 * <br>
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.<br>
 * <br>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package services.moleculer.cacher;

import static services.moleculer.util.CommonUtils.nameOf;
import static services.moleculer.util.CommonUtils.serializerTypeToClass;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.lambdaworks.redis.SetArgs;
import com.lambdaworks.redis.event.Event;
import com.lambdaworks.redis.event.EventBus;
import com.lambdaworks.redis.event.connection.ConnectedEvent;
import com.lambdaworks.redis.event.connection.DisconnectedEvent;

import io.datatree.Tree;
import rx.Observable;
import services.moleculer.Promise;
import services.moleculer.ServiceBroker;
import services.moleculer.serializer.JsonSerializer;
import services.moleculer.serializer.Serializer;
import services.moleculer.service.Name;
import services.moleculer.util.redis.RedisGetSetClient;

/**
 * Redis-based cache implementation. Supports SSL, clustering and password
 * authentication.
 */
@Name("Redis Cacher")
public final class RedisCacher extends Cacher implements EventBus {

	// --- LIST OF STATUS CODES ---

	private static final int STATUS_DISCONNECTING = 1;
	private static final int STATUS_DISCONNECTED = 2;
	private static final int STATUS_CONNECTING = 3;
	private static final int STATUS_CONNECTED = 4;
	private static final int STATUS_STOPPED = 5;

	// --- CONNECTION STATUS ---

	private final AtomicInteger status = new AtomicInteger(STATUS_DISCONNECTED);

	// --- PROPERTIES ---

	private String[] urls = new String[] { "localhost" };
	private String password;
	private boolean useSSL;
	private boolean startTLS;
	private int ttl;

	// --- REDIS CLIENT ---

	private RedisGetSetClient client;

	// --- SERIALIZER / DESERIALIZER ---

	protected Serializer serializer;

	// --- COMPONENTS ---

	protected ExecutorService executor;
	protected ScheduledExecutorService scheduler;

	// --- CONSTUCTORS ---

	public RedisCacher() {
	}

	public RedisCacher(String... urls) {
		this(false, false, null, 0, urls);
	}

	public RedisCacher(boolean useSSL, boolean startTLS, String password, int ttl, String... urls) {
		this.useSSL = useSSL;
		this.startTLS = startTLS;
		this.password = password;
		this.ttl = ttl;
		this.urls = urls;
	}

	// --- INIT CACHE INSTANCE ---

	private SetArgs expiration;

	/**
	 * Initializes cacher instance.
	 * 
	 * @param broker
	 *            parent ServiceBroker
	 * @param config
	 *            optional configuration of the current component
	 */
	@Override
	public final void start(ServiceBroker broker, Tree config) throws Exception {

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
		password = config.get(PASSWORD, password);
		useSSL = config.get(USE_SSL, useSSL);
		startTLS = config.get(START_TLS, startTLS);
		ttl = config.get(TTL, ttl);
		if (ttl > 0) {

			// Set the default expire time, in seconds.
			expiration = SetArgs.Builder.ex(ttl);
		} else {
			expiration = null;
		}

		// Create serializer
		Tree serializerNode = config.get(SERIALIZER);
		if (serializerNode != null) {
			String type;
			if (serializerNode.isPrimitive()) {
				type = serializerNode.asString();
			} else {
				type = serializerNode.get(TYPE, "json");
			}

			@SuppressWarnings("unchecked")
			Class<? extends Serializer> c = (Class<? extends Serializer>) Class.forName(serializerTypeToClass(type));
			serializer = c.newInstance();
		} else {
			serializerNode = config.putMap(SERIALIZER);
		}
		if (serializer == null) {
			serializer = new JsonSerializer();
		}

		// Get components
		executor = broker.components().executor();
		scheduler = broker.components().scheduler();

		// Start serializer
		logger.info(nameOf(this, true) + " will use " + nameOf(serializer, true) + '.');
		serializer.start(broker, serializerNode);

		// Connect to Redis server
		connect();
	}

	// --- CONNECT ---

	private final void connect() {
		status.set(STATUS_CONNECTING);

		// Create redis client
		client = new RedisGetSetClient(urls, password, useSSL, startTLS, executor, this);

		// Connect Redis
		try {
			client.connect();
		} catch (Exception cause) {
			String msg = cause.getMessage();
			if (msg == null || msg.isEmpty()) {
				msg = "Unable to connect to Redis server!";
			} else if (!msg.endsWith("!") && !msg.endsWith(".")) {
				msg += "!";
			}
			logger.warn(msg);
			reconnect();
			return;
		}
	}

	// --- DISCONNECT ---

	private final Promise disconnect() {
		if (client == null) {
			status.set(STATUS_DISCONNECTED);
			return Promise.resolve();
		}
		status.set(STATUS_DISCONNECTING);
		return client.disconnect().then(ok -> {
			status.set(STATUS_DISCONNECTED);
		});
	}

	// --- RECONNECT ---

	private final void reconnect() {
		disconnect().then(ok -> {
			logger.info("Trying to reconnect...");
			scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
		}).Catch(cause -> {
			logger.warn("Unable to disconnect from Redis server!", cause);
			scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
		});
	}

	// --- CLOSE CACHE INSTANCE ---

	@Override
	public final void stop() {
		int s = status.getAndSet(STATUS_STOPPED);
		if (s != STATUS_STOPPED) {
			disconnect();
		} else {
			throw new IllegalStateException("Redis Cacher is already stopped!");
		}
	}

	// --- CACHE METHODS ---

	@Override
	public final Promise get(String key) {
		if (status.get() == STATUS_CONNECTED) {
			try {
				return client.get(key).then(in -> {
					byte[] source = in.asBytes();
					if (source != null) {
						try {
							return serializer.read(source);
						} catch (Exception cause) {
							logger.warn("Unable to deserialize cached data!", cause);
						}
					}
					return Promise.resolve();
				});
			} catch (Exception cause) {
				logger.warn("Unable to get data from Redis!", cause);
			}
		}
		return Promise.resolve();
	}

	@Override
	public final void set(String key, Tree value, int ttl) {
		if (status.get() == STATUS_CONNECTED) {
			try {
				SetArgs args;
				if (ttl > 0) {
					
					// Entry-level TTL (in seconds)
					args = SetArgs.Builder.ex(ttl);
				} else {
					
					// Use the default TTL
					args = expiration;
				}
				client.set(key, serializer.write(value), args);
			} catch (Exception cause) {
				logger.warn("Unable to put data into Redis!", cause);
			}
		}
	}

	@Override
	public final void del(String key) {
		if (status.get() == STATUS_CONNECTED) {
			try {
				client.del(key);
			} catch (Exception cause) {
				logger.warn("Unable to delete data from Redis!", cause);
			}
		}
	}

	@Override
	public final void clean(String match) {
		if (status.get() == STATUS_CONNECTED) {
			try {
				client.clean(match);
			} catch (Exception cause) {
				logger.warn("Unable to delete data from Redis!", cause);
			}
		}
	}

	// --- REDIS EVENT LISTENER METHODS ---

	@Override
	public final void publish(Event event) {

		// Check state
		if (status.get() == STATUS_STOPPED) {
			return;
		}

		// Connected
		if (event instanceof ConnectedEvent) {
			if (status.compareAndSet(STATUS_CONNECTING, STATUS_CONNECTED)) {

				// Redis connection is Ok
				logger.info("Redis get-set connection are estabilished.");
			}
			return;
		}

		// Disconnected
		if (event instanceof DisconnectedEvent) {
			int s = status.getAndSet(STATUS_DISCONNECTED);
			if (s != STATUS_DISCONNECTED) {
				logger.info("Redis get-set connection aborted.");
				reconnect();
			}
		}

	}

	@Override
	public final Observable<Event> get() {
		return null;
	}

	// --- GETTERS / SETTERS ---

	public final String[] getUrls() {
		return urls;
	}

	public final void setUrls(String[] urls) {
		this.urls = urls;
	}

	public final String getPassword() {
		return password;
	}

	public final void setPassword(String password) {
		this.password = password;
	}

	public final boolean isUseSSL() {
		return useSSL;
	}

	public final void setUseSSL(boolean useSSL) {
		this.useSSL = useSSL;
	}

	public final boolean isStartTLS() {
		return startTLS;
	}

	public final void setStartTLS(boolean startTLS) {
		this.startTLS = startTLS;
	}

	public final int getTtl() {
		return ttl;
	}

	public final void setTtl(int ttl) {
		this.ttl = ttl;
	}

	public final Serializer getSerializer() {
		return serializer;
	}

	public final void setSerializer(Serializer serializer) {
		this.serializer = Objects.requireNonNull(serializer);
	}

}