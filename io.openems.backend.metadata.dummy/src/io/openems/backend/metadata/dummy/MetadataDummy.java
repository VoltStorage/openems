package io.openems.backend.metadata.dummy;

import static java.util.stream.Collectors.joining;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.openems.backend.common.metadata.AbstractMetadata;
import io.openems.backend.common.metadata.AlertingSetting;
import io.openems.backend.common.metadata.Edge;
import io.openems.backend.common.metadata.EdgeHandler;
import io.openems.backend.common.metadata.Metadata;
import io.openems.backend.common.metadata.SimpleEdgeHandler;
import io.openems.backend.common.metadata.User;
import io.openems.common.OpenemsOEM;
import io.openems.common.channel.Level;
import io.openems.common.event.EventReader;
import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.request.GetEdgesRequest.PaginationOptions;
import io.openems.common.jsonrpc.response.GetEdgesResponse.EdgeMetadata;
import io.openems.common.session.Language;
import io.openems.common.session.Role;
import io.openems.common.utils.StringUtils;
import io.openems.common.utils.ThreadPoolUtils;

@Designate(ocd = Config.class, factory = false)
@Component(//
		name = "Metadata.Dummy", //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		immediate = true //
)
@EventTopics({ //
		Edge.Events.ON_SET_CONFIG //
})
public class MetadataDummy extends AbstractMetadata implements Metadata, EventHandler {

	private static final Pattern NAME_NUMBER_PATTERN = Pattern.compile("[^0-9]+([0-9]+)$");

	private final Logger log = LoggerFactory.getLogger(MetadataDummy.class);

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final EventAdmin eventAdmin;
	private final AtomicInteger nextUserId = new AtomicInteger(-1);
	private final AtomicInteger nextEdgeId = new AtomicInteger(-1);

	private final Map<String, User> users = new HashMap<>();
	private final Map<String, MyEdge> edges = new HashMap<>();
	private final SimpleEdgeHandler edgeHandler = new SimpleEdgeHandler();

	private Language defaultLanguage = Language.DE;

	@Activate
	public MetadataDummy(@Reference EventAdmin eventadmin) {
		super("Metadata.Dummy");
		this.eventAdmin = eventadmin;
		this.logInfo(this.log, "Activate");

		// Allow the services some time to settle
		this.executor.schedule(() -> {
			this.setInitialized();
		}, 10, TimeUnit.SECONDS);
	}

	@Deactivate
	private void deactivate() {
		ThreadPoolUtils.shutdownAndAwaitTermination(this.executor, 0);
		this.logInfo(this.log, "Deactivate");
	}

	@Override
	public User authenticate(String username, String password) throws OpenemsNamedException {
		var name = "User #" + this.nextUserId.incrementAndGet();
		var token = UUID.randomUUID().toString();
		var user = new User(username, name, token, this.defaultLanguage, Role.ADMIN, this.hasMultipleEdges());
		this.users.put(user.getId(), user);
		return user;
	}

	@Override
	public User authenticate(String token) throws OpenemsNamedException {
		for (var user : this.users.values()) {
			if (!user.getToken().equals(token)) {
				continue;
			}
			final var hasMultipleEdges = this.hasMultipleEdges();
			final User returnUser;
			if (user.hasMultipleEdges() != hasMultipleEdges) {
				returnUser = this.createUser(user.getId(), user.getName(), user.getToken(), hasMultipleEdges);
				this.users.put(token, returnUser);
			} else {
				returnUser = user;
			}

			return returnUser;
		}
		throw OpenemsError.COMMON_AUTHENTICATION_FAILED.exception();
	}

	private User createUser(String username, String name, String token, boolean hasMultipleEdges) {
		return new User(username, name, token, this.defaultLanguage, Role.ADMIN, this.hasMultipleEdges());
	}

	private boolean hasMultipleEdges() {
		return this.edges.size() > 1;
	}

	@Override
	public void logout(User user) {
		this.users.remove(user.getId(), user);
	}

	@Override
	public Optional<String> getEdgeIdForApikey(String apikey) {
		var edgeOpt = this.edges.values().stream() //
				.filter(edge -> apikey.equals(edge.getApikey())) //
				.findFirst();
		if (edgeOpt.isPresent()) {
			return Optional.ofNullable(edgeOpt.get().getId());
		}
		// not found. Is apikey a valid Edge-ID?
		var idOpt = MetadataDummy.parseNumberFromName(apikey);
		int id;
		String edgeId;
		String setupPassword;
		if (idOpt.isPresent()) {
			edgeId = apikey;
			id = idOpt.get();
		} else {
			// create new ID
			id = this.nextEdgeId.incrementAndGet();
			edgeId = "edge" + id;
		}
		setupPassword = edgeId;
		var edge = new MyEdge(this, edgeId, apikey, setupPassword, "OpenEMS Edge #" + id, "", "");
		this.edges.put(edgeId, edge);
		return Optional.ofNullable(edgeId);

	}

	@Override
	public Optional<Edge> getEdgeBySetupPassword(String setupPassword) {
		var edgeOpt = this.edges.values().stream().filter(edge -> edge.getSetupPassword().equals(setupPassword))
				.findFirst();

		if (edgeOpt.isPresent()) {
			var edge = edgeOpt.get();
			return Optional.of(edge);
		}

		return Optional.empty();
	}

	@Override
	public Optional<Edge> getEdge(String edgeId) {
		Edge edge = this.edges.get(edgeId);
		return Optional.ofNullable(edge);
	}

	@Override
	public Optional<User> getUser(String userId) {
		return Optional.ofNullable(this.users.get(userId));
	}

	@Override
	public Collection<Edge> getAllOfflineEdges() {
		return this.edges.values().stream().filter(Edge::isOffline).collect(Collectors.toUnmodifiableList());
	}

	private static Optional<Integer> parseNumberFromName(String name) {
		try {
			var matcher = MetadataDummy.NAME_NUMBER_PATTERN.matcher(name);
			if (matcher.find()) {
				var nameNumberString = matcher.group(1);
				return Optional.ofNullable(Integer.parseInt(nameNumberString));
			}
		} catch (NullPointerException e) {
			/* ignore */
		}
		return Optional.empty();
	}

	@Override
	public void addEdgeToUser(User user, Edge edge) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.addEdgeToUser() is not implemented");
	}

	@Override
	public Map<String, Object> getUserInformation(User user) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.getUserInformation() is not implemented");
	}

	@Override
	public void setUserInformation(User user, JsonObject jsonObject) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.setUserInformation() is not implemented");
	}

	@Override
	public byte[] getSetupProtocol(User user, int setupProtocolId) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.getSetupProtocol() is not implemented");
	}

	@Override
	public JsonObject getSetupProtocolData(User user, String edgeId) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.getSetupProtocolData() is not implemented");
	}

	@Override
	public int submitSetupProtocol(User user, JsonObject jsonObject) {
		throw new UnsupportedOperationException("DummyMetadata.submitSetupProtocol() is not implemented");
	}

	@Override
	public void registerUser(JsonObject jsonObject, OpenemsOEM.Manufacturer oem) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.registerUser() is not implemented");
	}

	@Override
	public void updateUserLanguage(User user, Language language) throws OpenemsNamedException {
		this.defaultLanguage = language;
	}

	@Override
	public EventAdmin getEventAdmin() {
		return this.eventAdmin;
	}

	@Override
	public void handleEvent(Event event) {
		var reader = new EventReader(event);

		switch (event.getTopic()) {
		case Edge.Events.ON_SET_CONFIG:
			this.edgeHandler.setEdgeConfigFromEvent(reader);
			break;
		}
	}

	@Override
	public EdgeHandler edge() {
		return this.edgeHandler;
	}

	@Override
	public Optional<String> getSerialNumberForEdge(Edge edge) {
		throw new UnsupportedOperationException("DummyMetadata.getSerialNumberForEdge() is not implemented");
	}

	@Override
	public List<AlertingSetting> getUserAlertingSettings(String edgeId) {
		throw new UnsupportedOperationException("DummyMetadata.getUserAlertingSettings() is not implemented");
	}

	@Override
	public AlertingSetting getUserAlertingSettings(String edgeId, String userId) throws OpenemsException {
		throw new UnsupportedOperationException("DummyMetadata.getUserAlertingSettings() is not implemented");
	}

	@Override
	public void setUserAlertingSettings(User user, String edgeId, List<AlertingSetting> users) {
		throw new UnsupportedOperationException("DummyMetadata.setUserAlertingSettings() is not implemented");
	}

	@Override
	public List<EdgeMetadata> getPageDevice(User user, PaginationOptions paginationOptions)
			throws OpenemsNamedException {
		var pagesStream = this.edges.values().stream();
		final var query = paginationOptions.getQuery();
		if (query != null) {
			pagesStream = pagesStream.filter(//
					edge -> StringUtils.containsWithNullCheck(edge.getId(), query) //
							|| StringUtils.containsWithNullCheck(edge.getComment(), query) //
							|| StringUtils.containsWithNullCheck(edge.getProducttype(), query) //
			);
		}
		final var searchParams = paginationOptions.getSearchParams();
		if (searchParams != null) {
			if (searchParams.searchIsOnline()) {
				pagesStream = pagesStream.filter(edge -> edge.isOnline() == searchParams.isOnline());
			}
			if (searchParams.productTypes() != null) {
				pagesStream = pagesStream.filter(edge -> searchParams.productTypes().contains(edge.getProducttype()));
			}
			// TODO sum state filter
		}

		return pagesStream //
				.sorted((s1, s2) -> s1.getId().compareTo(s2.getId())) //
				.skip(paginationOptions.getPage() * paginationOptions.getLimit()) //
				.limit(paginationOptions.getLimit()) //
				.peek(t -> user.setRole(t.getId(), Role.ADMIN)) //
				.map(myEdge -> {
					return new EdgeMetadata(//
							myEdge.getId(), //
							myEdge.getComment(), //
							myEdge.getProducttype(), //
							myEdge.getVersion(), //
							Role.ADMIN, //
							myEdge.isOnline(), //
							myEdge.getLastmessage(), //
							null, // firstSetupProtocol
							Level.OK);
				}).toList();
	}

	@Override
	public EdgeMetadata getEdgeMetadataForUser(User user, String edgeId) throws OpenemsNamedException {
		final var edge = this.edges.get(edgeId);
		if (edge == null) {
			return null;
		}
		user.setRole(edgeId, Role.ADMIN);

		return new EdgeMetadata(//
				edge.getId(), //
				edge.getComment(), //
				edge.getProducttype(), //
				edge.getVersion(), //
				Role.ADMIN, //
				edge.isOnline(), //
				edge.getLastmessage(), //
				null, // firstSetupProtocol
				Level.OK //
		);
	}

	@Override
	public void logGenericSystemLog(GenericSystemLog systemLog) {
		this.logInfo(this.log,
				"%s on %s executed %s [%s]".formatted(systemLog.user().getId(), systemLog.edgeId(), systemLog.teaser(),
						systemLog.getValues().entrySet().stream() //
								.map(t -> t.getKey() + "=" + t.getValue()) //
								.collect(joining(", "))));
	}

}
