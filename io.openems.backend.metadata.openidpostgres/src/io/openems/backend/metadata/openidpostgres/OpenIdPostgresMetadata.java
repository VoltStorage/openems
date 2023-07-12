package io.openems.backend.metadata.openidpostgres;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.gson.JsonObject;
import io.openems.backend.common.metadata.AbstractMetadata;
import io.openems.backend.common.metadata.AlertingSetting;
import io.openems.backend.common.metadata.Edge;
import io.openems.backend.common.metadata.EdgeHandler;
import io.openems.backend.common.metadata.Metadata;
import io.openems.backend.common.metadata.SimpleEdgeHandler;
import io.openems.backend.common.metadata.User;
import io.openems.common.OpenemsOEM.Manufacturer;
import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.request.GetEdgesRequest.PaginationOptions;
import io.openems.common.session.Language;
import io.openems.common.session.Role;
import io.openems.common.utils.ThreadPoolUtils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.time.ZonedDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Designate(ocd = Config.class, factory = false)
@Component(//
    name = "Metadata.OpenIdPostgres", //
    configurationPolicy = ConfigurationPolicy.REQUIRE, //
    immediate = true //
)
public class OpenIdPostgresMetadata extends AbstractMetadata implements Metadata {
  private final Logger log = LoggerFactory.getLogger(OpenIdPostgresMetadata.class);

  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

  private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<String, Edge> edges = new ConcurrentHashMap<>();

  private final SimpleEdgeHandler edgeHandler = new SimpleEdgeHandler();


  @Reference
  private EventAdmin eventAdmin;

  public OpenIdPostgresMetadata() {
    super("Metadata.OpenIdPostgres");

    Edge chiemseeEdge = new Edge(this, "100700012", "Schönwälder Chiemsee", "", "", ZonedDateTime.now());
    edges.put(chiemseeEdge.getId(), chiemseeEdge);

    Edge tuerkenfeldEdge = new Edge(this, "100700006", "Kaller Türkenfeld", "", "", ZonedDateTime.now());
    edges.put(tuerkenfeldEdge.getId(), tuerkenfeldEdge);
  }

  @Activate
  public void activate(Config config) {
    this.logInfo(this.log, "Activate");

    // WTF: there is a race condition with the UiWebsocketImpl
    // which is waiting for the random AFTER_IS_INITIALIZED event which every service component can broadcast
    // before it starts the websocket server
    // AFTER_IS_INITIALIZED is sent by the abstract this.setInitialized() from this service component also
    // but it is sent before the UiWebSocketImpl object is finishing constructing
    // This "fix" is from taken form MetadataDummy
    // Side node: Even if a component is already created via the Felix console
    // it might not be constructed and put into the OSGi context because of missing @References
    // e.g. the UiWebsocket is not constructed as long as there is no Metadata object
    // Also the UiWebsocket gets destroyed if the Metadata object gets destroyed
    this.executor.schedule(this::setInitialized, 10, TimeUnit.SECONDS);
  }

  @Deactivate
  public void deactivate() {
    ThreadPoolUtils.shutdownAndAwaitTermination(this.executor, 0);
    this.logInfo(this.log, "Deactivate");
  }

  @Override
  public EventAdmin getEventAdmin() {
    return this.eventAdmin;
  }

  @Override
  public User authenticate(String username, String password) throws OpenemsNamedException {
    throw new OpenemsException("authenticate with username & password is not implemented");
  }

  private Role lookupRoleForKeycloakRole(String role) {
    return switch (role) {
      case "admin" -> Role.ADMIN;
      case "installer" -> Role.INSTALLER;
      case "owner" -> Role.OWNER;
      case "guest" -> Role.GUEST;
      default -> null;
    };
  }

  private Role lookupRoleForAuth0(String role) {
    return switch (role) {
      case "admin" -> Role.ADMIN;
      case "install" -> Role.INSTALLER;
      case "owner" -> Role.OWNER;
      case "guest" -> Role.GUEST;
      default -> null;
    };
  }

  public Map.Entry<Optional<Role>, NavigableMap<String, Role>> processJwt(String token) throws OpenemsNamedException {
    try {
      DecodedJWT jwt = JWT.decode(token);

      String jwkProviderUrl = jwt.getIssuer().contains("auth0.com")
          ? "https://dev-dripbmlnz2xh60k8.eu.auth0.com/.well-known/jwks.json"
          : "http://localhost:8080/realms/voltstorage-customers/protocol/openid-connect/certs";

      // Get pub key for signature verification from JWK endpoint
      JwkProvider provider =
          new UrlJwkProvider(new URL(jwkProviderUrl));
      Jwk jwk = provider.get(jwt.getKeyId());
      Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null);

      //Verify the token; esp regarding expiry date, valid certs from JWK etc.
      JWTVerifier verifier = JWT
          .require(algorithm)
          .build();
      verifier.verify(jwt);

      //Extract claims
      return jwt.getIssuer().contains("auth0.com") ? mapAuth0Claims(jwt) : mapKeycloakClaims(jwt);
    } catch (TokenExpiredException exception) {
      throw OpenemsError.COMMON_AUTHENTICATION_FAILED.exception();
    } catch (JWTVerificationException | JwkException | MalformedURLException exception) {
      throw new RuntimeException(exception);
    }
  }

  private Map.Entry<Optional<Role>, NavigableMap<String, Role>> mapAuth0Claims(DecodedJWT jwt) {
    List<String> permissions = jwt.getClaim("permissions").asList(String.class);

    NavigableMap<String, Role> roles = new TreeMap<>();
    permissions.forEach(permission -> {
      String[] roleAtEdge = permission.split(":");
      Role role = lookupRoleForAuth0(roleAtEdge[0]);
      String edgeId = roleAtEdge[1];

      if (role != null) {
        roles.put(edgeId, role);
      }
    });

    Optional<Role> globalRole = Optional.ofNullable(roles.firstEntry().getValue());
    return new AbstractMap.SimpleEntry<>(globalRole, roles);
  }

  private AbstractMap.SimpleEntry<Optional<Role>, NavigableMap<String, Role>> mapKeycloakClaims(DecodedJWT jwt) {
    List<String> groups = jwt.getClaim("groups").asList(String.class);
    ArrayList<String> rolesClaim = (ArrayList<String>) (jwt.getClaim("realm_access").asMap()).get("roles");

    Optional<Role> globalRole = rolesClaim.stream().map(this::lookupRoleForKeycloakRole).filter(Objects::nonNull).sorted().findFirst();

    NavigableMap<String, Role> roles = new TreeMap<>();
    groups.stream().map(groupPath -> {
      String[] groupHierarchy = groupPath.split("/");
      String edgeId = groupHierarchy[1];
      Role role = lookupRoleForKeycloakRole(groupHierarchy[2].substring(0, groupHierarchy[2].length() - 1));
      return new AbstractMap.SimpleEntry<>(edgeId, role);
    }).forEach(entry -> roles.put(entry.getKey(), entry.getValue()));

    return new AbstractMap.SimpleEntry<>(globalRole, roles);
  }

  @Override
  public User authenticate(String token) throws OpenemsNamedException {
    // FIXME: Once the websocket connection is authenticated, it will stay authenticated forever;
    // even is the access token expires in the the meantime; authentication is never rechecked on requests!
    this.logInfo(this.log, "Authenticate with token" + token); //FIXME do not log tokens

    //If invalid
    if (token.isEmpty()) {
      throw OpenemsError.COMMON_AUTHENTICATION_FAILED.exception();
    }

    //If already exists
    for (User user : this.users.values()) {
      //FIXME With every expired accessToken a new user object will be added to the map
      if (user.getToken().equals(token)) {
        return user;
      }
    }

    Map.Entry<Optional<Role>, NavigableMap<String, Role>> roles = processJwt(token);
    //create new user
    User user = new User(
        UUID.randomUUID().toString(),
        "Süüperrman", // from token
        token,
        Language.EN,
        roles.getKey().orElse(Role.GUEST), // from token claim,
        roles.getValue() //roles from token claims -> important: will be used to look up edges
    );

    this.users.put(user.getId(), user);

    return user;
  }

  @Override
  public void logout(User user) {
    this.users.remove(user.getId(), user);
  }

  @Override
  public EdgeHandler edge() {
    return this.edgeHandler;
  }

  @Override
  public Optional<String> getEdgeIdForApikey(String apikey) {
    //FIXME: Dummy implementation apiKey == edgeId

    Optional<Edge> foundEdge = Optional.ofNullable(this.edges.get(apikey));

    if (foundEdge.isEmpty()) {
      return Optional.empty();
    }

    return Optional.ofNullable(foundEdge.get().getId());
  }

  @Override
  public Optional<Edge> getEdge(String edgeId) {
    return Optional.ofNullable(this.edges.get(edgeId));
  }

  @Override
  public Optional<Edge> getEdgeBySetupPassword(String setupPassword) {
    throw new UnsupportedOperationException("getEdgeBySetupPassword is not implemented");
  }

  @Override
  public Optional<User> getUser(String userId) {
    return Optional.ofNullable(this.users.get(userId));
  }

  @Override
  public Collection<Edge> getAllOfflineEdges() {
    throw new UnsupportedOperationException("getAllOfflineEdges is not implemented");
  }

  @Override
  public void addEdgeToUser(User user, Edge edge) throws OpenemsNamedException {
    throw new OpenemsException("addEdgeToUser is not implemented");
  }

  @Override
  public Map<String, Object> getUserInformation(User user) throws OpenemsNamedException {
    throw new OpenemsException("getUserInformation is not implemented");
  }

  @Override
  public byte[] getSetupProtocol(User user, int setupProtocolId) throws OpenemsNamedException {
    throw new OpenemsException("getSetupProtocol is not implemented");
  }

  @Override
  public JsonObject getSetupProtocolData(User user, String edgeId) throws OpenemsNamedException {
    throw new OpenemsException("getSetupProtocolData is not implemented");
  }

  @Override
  public List<AlertingSetting> getUserAlertingSettings(String edgeId) throws OpenemsException {
    throw new OpenemsException("getUserAlertingSettings is not implemented");
  }

  @Override
  public AlertingSetting getUserAlertingSettings(String edgeId, String userId) throws OpenemsException {
    throw new OpenemsException("getUserAlertingSettings is not implemented");
  }

  @Override
  public void setUserAlertingSettings(User user, String edgeId, List<AlertingSetting> users) throws OpenemsException {
    throw new OpenemsException("setUserAlertingSettings is not implemented");
  }

  @Override
  public Optional<String> getSerialNumberForEdge(Edge edge) {
    throw new UnsupportedOperationException("getRoleForEdge is not implemented");
  }

  @Override
  public Role getRoleForEdge(User user, String edgeId) throws OpenemsNamedException {
    Optional<User> verifiedUser = Optional.ofNullable(this.users.get(user.getId()));

    if (verifiedUser.isEmpty()) {
      throw OpenemsError.COMMON_USER_UNDEFINED.exception(user.getId());
    }

    Optional<Role> role = Optional.ofNullable(verifiedUser.get().getEdgeRoles().get(edgeId));

    if (role.isEmpty()) {
      throw OpenemsError.COMMON_ROLE_ACCESS_DENIED.exception(edgeId, "no valid role found");
    }

    return role.get();
  }

  @Override
  public Map<String, Role> getPageDevice(User user, PaginationOptions paginationOptions) {
    Optional<User> verifiedUser = Optional.ofNullable(this.users.get(user.getId()));
    return verifiedUser.isEmpty() ? new HashMap<>() : verifiedUser.get().getEdgeRoles();
  }

  @Override
  public void updateUserLanguage(User user, Language language) throws OpenemsNamedException {
    throw new OpenemsException("updateUserLanguage is not implemented");
  }

  @Override
  public void registerUser(JsonObject user, Manufacturer oem) throws OpenemsNamedException {
    throw new OpenemsException("registerUser is not implemented");
  }

  @Override
  public int submitSetupProtocol(User user, JsonObject jsonObject) throws OpenemsNamedException {
    throw new OpenemsException("submitSetupProtocol is not implemented");
  }

  @Override
  public void setUserInformation(User user, JsonObject jsonObject) throws OpenemsNamedException {
    throw new OpenemsException("setUserInformation is not implemented");
  }
}
