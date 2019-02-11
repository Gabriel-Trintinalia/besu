/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.jsonrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import tech.pegasys.pantheon.config.StubGenesisConfigOptions;
import tech.pegasys.pantheon.ethereum.blockcreation.EthHashMiningCoordinator;
import tech.pegasys.pantheon.ethereum.core.PrivacyParameters;
import tech.pegasys.pantheon.ethereum.core.Synchronizer;
import tech.pegasys.pantheon.ethereum.core.TransactionPool;
import tech.pegasys.pantheon.ethereum.eth.EthProtocol;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.filter.FilterManager;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.methods.JsonRpcMethod;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.queries.BlockchainQueries;
import tech.pegasys.pantheon.ethereum.mainnet.MainnetProtocolSchedule;
import tech.pegasys.pantheon.ethereum.p2p.api.P2PNetwork;
import tech.pegasys.pantheon.ethereum.p2p.wire.Capability;
import tech.pegasys.pantheon.ethereum.privacy.PrivateTransactionHandler;
import tech.pegasys.pantheon.metrics.noop.NoOpMetricsSystem;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.KeyStoreOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.SecretOptions;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.auth.jwt.impl.JWTAuthProviderImpl;
import io.vertx.ext.jwt.JWK;
import io.vertx.ext.jwt.JWT;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.StrictStubs.class)
public class JsonRpcHttpServiceLoginTest {
  @ClassRule public static final TemporaryFolder folder = new TemporaryFolder();

  private static final Vertx vertx = Vertx.vertx();

  protected static Map<String, JsonRpcMethod> rpcMethods;
  protected static JsonRpcHttpService service;
  protected static OkHttpClient client;
  protected static String baseUrl;
  protected static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
  protected static final String CLIENT_VERSION = "TestClientVersion/0.1.0";
  protected static final int CHAIN_ID = 123;
  protected static P2PNetwork peerDiscoveryMock;
  protected static BlockchainQueries blockchainQueries;
  protected static Synchronizer synchronizer;
  protected static final Collection<RpcApi> JSON_RPC_APIS =
      Arrays.asList(RpcApis.ETH, RpcApis.NET, RpcApis.WEB3, RpcApis.ADMIN);
  private static JWTAuth jwtAuth;

  @BeforeClass
  public static void initServerAndClient() throws Exception {
    peerDiscoveryMock = mock(P2PNetwork.class);
    blockchainQueries = mock(BlockchainQueries.class);
    synchronizer = mock(Synchronizer.class);

    final Set<Capability> supportedCapabilities = new HashSet<>();
    supportedCapabilities.add(EthProtocol.ETH62);
    supportedCapabilities.add(EthProtocol.ETH63);

    rpcMethods =
        spy(
            new JsonRpcMethodsFactory()
                .methods(
                    CLIENT_VERSION,
                    peerDiscoveryMock,
                    blockchainQueries,
                    synchronizer,
                    MainnetProtocolSchedule.fromConfig(
                        new StubGenesisConfigOptions().constantinopleBlock(0).chainId(CHAIN_ID),
                        PrivacyParameters.noPrivacy()),
                    mock(FilterManager.class),
                    mock(TransactionPool.class),
                    mock(EthHashMiningCoordinator.class),
                    new NoOpMetricsSystem(),
                    supportedCapabilities,
                    Optional.empty(),
                    JSON_RPC_APIS,
                    mock(PrivateTransactionHandler.class)));
    service = createJsonRpcHttpService();
    jwtAuth = service.jwtAuthProvider.get();
    service.start().join();

    // Build an OkHttp client.
    client = new OkHttpClient();
    baseUrl = service.url();
  }

  private static JsonRpcHttpService createJsonRpcHttpService() throws Exception {
    final String authTomlPath =
        Paths.get(ClassLoader.getSystemResource("JsonRpcHttpService/auth.toml").toURI())
            .toAbsolutePath()
            .toString();

    final JsonRpcConfiguration config = createJsonRpcConfig();
    config.setAuthenticationEnabled(true);
    config.setAuthenticationCredentialsFile(authTomlPath);

    return new JsonRpcHttpService(
        vertx, folder.newFolder().toPath(), config, new NoOpMetricsSystem(), rpcMethods);
  }

  private static JsonRpcConfiguration createJsonRpcConfig() {
    final JsonRpcConfiguration config = JsonRpcConfiguration.createDefault();
    config.setPort(0);
    config.setHostsWhitelist(Collections.singletonList("*"));
    return config;
  }

  /** Tears down the HTTP server. */
  @AfterClass
  public static void shutdownServer() {
    service.stop().join();
  }

  @Test
  public void loginWithBadCredentials() throws IOException {
    final RequestBody body =
        RequestBody.create(JSON, "{\"username\":\"user\",\"password\":\"badpass\"}");
    final Request request = new Request.Builder().post(body).url(baseUrl + "/login").build();
    try (final Response resp = client.newCall(request).execute()) {
      assertThat(resp.code()).isEqualTo(401);
      assertThat(resp.message()).isEqualTo("Unauthorized");
    }
  }

  @Test
  public void loginWithGoodCredentials() throws IOException {
    final RequestBody body =
        RequestBody.create(JSON, "{\"username\":\"user\",\"password\":\"pegasys\"}");
    final Request request = new Request.Builder().post(body).url(baseUrl + "/login").build();
    try (final Response resp = client.newCall(request).execute()) {
      assertThat(resp.code()).isEqualTo(200);
      assertThat(resp.message()).isEqualTo("OK");
      assertThat(resp.body().contentType()).isNotNull();
      assertThat(resp.body().contentType().type()).isEqualTo("application");
      assertThat(resp.body().contentType().subtype()).isEqualTo("json");
      final String bodyString = resp.body().string();
      assertThat(bodyString).isNotNull();
      assertThat(bodyString).isNotBlank();

      final JsonObject respBody = new JsonObject(bodyString);
      final String token = respBody.getString("token");
      assertThat(token).isNotNull();

      jwtAuth.authenticate(
          new JsonObject().put("jwt", token),
          (r) -> {
            assertThat(r.succeeded()).isTrue();
            final User user = r.result();
            user.isAuthorized(
                "noauths",
                (authed) -> {
                  assertThat(authed.succeeded()).isTrue();
                  assertThat(authed.result()).isFalse();
                });
          });
    }
  }

  @Test
  public void loginWithGoodCredentialsAndPermissions() throws IOException {
    final RequestBody body =
        RequestBody.create(JSON, "{\"username\":\"user\",\"password\":\"pegasys\"}");
    final Request request = new Request.Builder().post(body).url(baseUrl + "/login").build();
    try (final Response resp = client.newCall(request).execute()) {
      assertThat(resp.code()).isEqualTo(200);
      assertThat(resp.message()).isEqualTo("OK");
      assertThat(resp.body().contentType()).isNotNull();
      assertThat(resp.body().contentType().type()).isEqualTo("application");
      assertThat(resp.body().contentType().subtype()).isEqualTo("json");
      final String bodyString = resp.body().string();
      assertThat(bodyString).isNotNull();
      assertThat(bodyString).isNotBlank();

      final JsonObject respBody = new JsonObject(bodyString);
      final String token = respBody.getString("token");
      assertThat(token).isNotNull();

      jwtAuth.authenticate(
          new JsonObject().put("jwt", token),
          (r) -> {
            assertThat(r.succeeded()).isTrue();
            final User user = r.result();
            user.isAuthorized(
                "noauths",
                (authed) -> {
                  assertThat(authed.succeeded()).isTrue();
                  assertThat(authed.result()).isFalse();
                });
            user.isAuthorized(
                "fakePermission",
                (authed) -> {
                  assertThat(authed.succeeded()).isTrue();
                  assertThat(authed.result()).isTrue();
                });
          });
    }
  }

  private JWT makeJwt(final JWTAuthOptions config)
      throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
    final KeyStoreOptions keyStoreOptions = config.getKeyStore();
    if (keyStoreOptions != null) {
      final KeyStore ks = KeyStore.getInstance(keyStoreOptions.getType());

      // synchronize on the class to avoid the case where multiple file accesses will overlap
      synchronized (JWTAuthProviderImpl.class) {
        final Buffer keystore = vertx.fileSystem().readFileBlocking(keyStoreOptions.getPath());

        try (InputStream in = new ByteArrayInputStream(keystore.getBytes())) {
          ks.load(in, keyStoreOptions.getPassword().toCharArray());
        }
      }

      return new JWT(ks, keyStoreOptions.getPassword().toCharArray());
    } else {
      // no key file attempt to load pem keys
      final JWT jwt = new JWT();

      final List<PubSecKeyOptions> keys = config.getPubSecKeys();

      if (keys != null) {
        for (final PubSecKeyOptions pubSecKey : config.getPubSecKeys()) {
          if (pubSecKey.isSymmetric()) {
            jwt.addJWK(new JWK(pubSecKey.getAlgorithm(), pubSecKey.getPublicKey()));
          } else {
            jwt.addJWK(
                new JWK(
                    pubSecKey.getAlgorithm(),
                    pubSecKey.isCertificate(),
                    pubSecKey.getPublicKey(),
                    pubSecKey.getSecretKey()));
          }
        }
      }

      // TODO: remove once the deprecation ends!
      final List<SecretOptions> secrets = config.getSecrets();

      if (secrets != null) {
        for (final SecretOptions secret : secrets) {
          jwt.addSecret(secret.getType(), secret.getSecret());
        }
      }

      final List<JsonObject> jwks = config.getJwks();

      if (jwks != null) {
        for (final JsonObject jwk : jwks) {
          jwt.addJWK(new JWK(jwk));
        }
      }
      return jwt;
    }
  }

  @Test
  public void loginDoesntPopulateJWTPayloadWithPassword()
      throws IOException, KeyStoreException, CertificateException, NoSuchAlgorithmException {
    final RequestBody body =
        RequestBody.create(JSON, "{\"username\":\"user\",\"password\":\"pegasys\"}");
    final Request request = new Request.Builder().post(body).url(baseUrl + "/login").build();
    try (final Response resp = client.newCall(request).execute()) {
      assertThat(resp.code()).isEqualTo(200);
      assertThat(resp.message()).isEqualTo("OK");
      assertThat(resp.body().contentType()).isNotNull();
      assertThat(resp.body().contentType().type()).isEqualTo("application");
      assertThat(resp.body().contentType().subtype()).isEqualTo("json");
      final String bodyString = resp.body().string();
      assertThat(bodyString).isNotNull();
      assertThat(bodyString).isNotBlank();

      final JsonObject respBody = new JsonObject(bodyString);
      final String token = respBody.getString("token");
      assertThat(token).isNotNull();
      final JWT jwt = makeJwt(service.jwtAuthOptions.get());

      final JsonObject jwtPayload = jwt.decode(token);
      final String jwtPayloadString = jwtPayload.encode();
      assertThat(jwtPayloadString.contains("password")).isFalse();
      assertThat(jwtPayloadString.contains("pegasys")).isFalse();
    }
  }
}
