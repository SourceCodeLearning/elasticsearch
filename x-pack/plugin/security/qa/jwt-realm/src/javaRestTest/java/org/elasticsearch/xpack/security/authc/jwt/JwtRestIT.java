/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.authc.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.PathUtils;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.TestSecurityClient;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.security.user.User;
import org.hamcrest.Matchers;
import org.junit.BeforeClass;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.elasticsearch.test.TestMatchers.hasStatusCode;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class JwtRestIT extends ESRestTestCase {

    private static final Optional<String> VALID_SHARED_SECRET = Optional.of("test-secret");
    private static Path httpCertificateAuthority;
    private TestSecurityClient adminSecurityClient;

    @BeforeClass
    public static void findTrustStore() throws Exception {
        final URL resource = JwtRestIT.class.getResource("/ssl/ca.crt");
        if (resource == null) {
            throw new FileNotFoundException("Cannot find classpath resource /ssl/ca.crt");
        }
        httpCertificateAuthority = PathUtils.get(resource.toURI());
    }

    @Override
    protected String getProtocol() {
        // Because this QA project uses https
        return "https";
    }

    @Override
    protected Settings restAdminSettings() {
        String token = basicAuthHeaderValue("admin_user", new SecureString("admin-password".toCharArray()));
        return Settings.builder().put(ThreadContext.PREFIX + ".Authorization", token).put(restSslSettings()).build();
    }

    @Override
    protected Settings restClientSettings() {
        return Settings.builder().put(super.restClientSettings()).put(restSslSettings()).build();
    }

    private Settings restSslSettings() {
        return Settings.builder().put(CERTIFICATE_AUTHORITIES, httpCertificateAuthority).build();
    }

    protected TestSecurityClient getAdminSecurityClient() {
        if (adminSecurityClient == null) {
            adminSecurityClient = new TestSecurityClient(adminClient());
        }
        return adminSecurityClient;
    }

    /**
     * Tests against realm "jwt1" in build.gradle
     * This realm
     * - use the "sub" claim as the principal
     * - performs role mapping
     * - supports RSA signed keys
     * - has no client authentication
     */
    public void testAuthenticateWithRsaSignedJWTAndRoleMappingByPrincipal() throws Exception {
        final String principal = randomPrincipal();
        final String rules = """
            { "all": [
                { "field": { "realm.name": "jwt1" } },
                { "field": { "username": "%s" } }
            ] }
            """.formatted(principal);

        authenticateToRealm1WithRoleMapping(principal, List.of(), rules);
    }

    public void testAuthenticateWithRsaSignedJWTAndRoleMappingByGroups() throws Exception {
        final String principal = randomPrincipal();
        final List<String> groups = randomList(1, 12, () -> randomAlphaOfLengthBetween(4, 12));
        final String mappedGroup = randomFrom(groups);

        final String rules = """
            { "all": [
                { "field": { "realm.name": "jwt1" } },
                { "field": { "groups": "%s" } }
            ] }
            """.formatted(mappedGroup);

        authenticateToRealm1WithRoleMapping(principal, groups, rules);
    }

    public void testAuthenticateWithRsaSignedJWTAndRoleMappingByMetadata() throws Exception {
        final String principal = randomPrincipal();
        final String rules = """
            { "all": [
                { "field": { "realm.name": "jwt1" } },
                { "field": { "metadata.jwt_claim_sub": "%s" } }
            ] }
            """.formatted(principal);
        authenticateToRealm1WithRoleMapping(principal, List.of(), rules);
    }

    private void authenticateToRealm1WithRoleMapping(String principal, List<String> groups, String roleMappingRules) throws Exception {
        final List<String> roles = randomRoles();
        final String roleMappingName = createRoleMapping(roles, roleMappingRules);

        try {
            final SignedJWT jwt = buildAndSignJwtForRealm1(principal, groups, Instant.now());
            final TestSecurityClient client = getSecurityClient(jwt, Optional.empty());

            final Map<String, Object> response = client.authenticate();

            final String description = "Authentication response [" + response + "]";
            assertThat(description, response, hasEntry(User.Fields.USERNAME.getPreferredName(), principal));
            assertThat(
                description,
                assertMap(response, User.Fields.AUTHENTICATION_REALM),
                hasEntry(User.Fields.REALM_NAME.getPreferredName(), "jwt1")
            );
            assertThat(description, assertList(response, User.Fields.ROLES), Matchers.containsInAnyOrder(roles.toArray(String[]::new)));

            // The user has no real role (we never define them) so everything they try to do will be FORBIDDEN
            final ResponseException exception = expectThrows(
                ResponseException.class,
                () -> client.getRoleDescriptors(new String[] { "*" })
            );
            assertThat(exception.getResponse(), hasStatusCode(RestStatus.FORBIDDEN));
        } finally {
            deleteRoleMapping(roleMappingName);
        }
    }

    public void testFailureOnExpiredJwt() throws Exception {
        final String principal = randomPrincipal();
        { // Test with valid time
            final SignedJWT jwt = buildAndSignJwtForRealm1(principal, List.of(), Instant.now());
            final TestSecurityClient client = getSecurityClient(jwt, Optional.empty());
            assertThat(client.authenticate(), hasEntry(User.Fields.USERNAME.getPreferredName(), principal));
        }

        { // Test with expired time
            final SignedJWT jwt = buildAndSignJwtForRealm1(principal, List.of(), Instant.now().minus(12, ChronoUnit.HOURS));
            TestSecurityClient client = getSecurityClient(jwt, Optional.empty());

            // This fails because the JWT is expired
            final ResponseException exception = expectThrows(ResponseException.class, client::authenticate);
            assertThat(exception.getResponse(), hasStatusCode(RestStatus.UNAUTHORIZED));
        }
    }

    public void testFailureOnNonMatchingRsaSignature() throws Exception {
        final String originalPrincipal = randomPrincipal();
        final SignedJWT originalJwt = buildAndSignJwtForRealm1(originalPrincipal, List.of(), Instant.now());
        {
            // Test with valid signed JWT
            final TestSecurityClient client = getSecurityClient(originalJwt, Optional.empty());
            assertThat(client.authenticate(), hasEntry(User.Fields.USERNAME.getPreferredName(), originalPrincipal));
        }

        {
            // Create a new JWT with the header + signature from the original JWT, but a falsified set of claim
            final JWTClaimsSet falsifiedClaims = new JWTClaimsSet.Builder(originalJwt.getJWTClaimsSet()).claim("roles", "superuser")
                .build();
            final SignedJWT falsifiedJwt = new SignedJWT(
                originalJwt.getHeader().toBase64URL(),
                falsifiedClaims.toPayload().toBase64URL(),
                originalJwt.getSignature()
            );
            final TestSecurityClient client = getSecurityClient(falsifiedJwt, Optional.empty());

            // This fails because the JWT signature does not match the payload
            final ResponseException exception = expectThrows(ResponseException.class, client::authenticate);
            assertThat(exception.getResponse(), hasStatusCode(RestStatus.UNAUTHORIZED));
        }
    }

    /**
     * Tests against realm "jwt2" in build.gradle
     * This realm
     * - use the "email" claim as the principal (with the domain removed)
     * - performs lookup on the native realm
     * - supports HMAC signed keys (using a OIDC style passphrase)
     * - uses a shared-secret for client authentication
     */
    public void testAuthenticateWithHmacSignedJWTAndDelegatedAuthorization() throws Exception {
        final String principal = randomPrincipal();
        final List<String> roles = randomRoles();
        final String randomMetadata = randomAlphaOfLengthBetween(6, 18);
        createUser(principal, roles, Map.of("test_key", randomMetadata));

        try {
            final SignedJWT jwt = buildAndSignJwtForRealm2(principal);
            final TestSecurityClient client = getSecurityClient(jwt, VALID_SHARED_SECRET);

            final Map<String, Object> response = client.authenticate();

            assertThat(response.get(User.Fields.USERNAME.getPreferredName()), is(principal));
            assertThat(assertMap(response, User.Fields.AUTHENTICATION_REALM), hasEntry(User.Fields.REALM_NAME.getPreferredName(), "jwt2"));
            assertThat(assertList(response, User.Fields.ROLES), Matchers.containsInAnyOrder(roles.toArray(String[]::new)));
            assertThat(assertMap(response, User.Fields.METADATA), hasEntry("test_key", randomMetadata));

            // The user has no real role (we never define them) so everything they try to do will be FORBIDDEN
            final ResponseException exception = expectThrows(
                ResponseException.class,
                () -> client.getRoleDescriptors(new String[] { "*" })
            );
            assertThat(exception.getResponse(), hasStatusCode(RestStatus.FORBIDDEN));
        } finally {
            deleteUser(principal);
        }
    }

    public void testFailureOnInvalidHMACSignature() throws Exception {
        final String principal = randomPrincipal();
        final List<String> roles = randomRoles();
        createUser(principal, roles, Map.of());

        try {
            final JWTClaimsSet claimsSet = buildJwtForRealm2(principal, Instant.now());

            {
                // This is the correct HMAC passphrase (from build.gradle)
                final SignedJWT jwt = signHmacJwt(claimsSet, "test-HMAC/secret passphrase-value");
                final TestSecurityClient client = getSecurityClient(jwt, VALID_SHARED_SECRET);
                assertThat(client.authenticate(), hasEntry(User.Fields.USERNAME.getPreferredName(), principal));
            }
            {
                // This is not the correct HMAC passphrase
                final SignedJWT invalidJwt = signHmacJwt(claimsSet, "invalid-HMAC-passphrase-" + randomAlphaOfLength(12));
                final TestSecurityClient client = getSecurityClient(invalidJwt, VALID_SHARED_SECRET);
                // This fails because the HMAC is wrong
                final ResponseException exception = expectThrows(ResponseException.class, client::authenticate);
                assertThat(exception.getResponse(), hasStatusCode(RestStatus.UNAUTHORIZED));
            }
        } finally {
            deleteUser(principal);
        }

    }

    public void testAuthenticationFailureIfDelegatedAuthorizationFails() throws Exception {
        final String principal = randomPrincipal();
        final SignedJWT jwt = buildAndSignJwtForRealm2(principal);
        final TestSecurityClient client = getSecurityClient(jwt, VALID_SHARED_SECRET);

        // This fails because we didn't create a native user
        final ResponseException exception = expectThrows(ResponseException.class, client::authenticate);
        assertThat(exception.getResponse(), hasStatusCode(RestStatus.UNAUTHORIZED));

        createUser(principal, List.of(), Map.of());
        try {
            // Now it works
            assertThat(client.authenticate(), hasEntry(User.Fields.USERNAME.getPreferredName(), principal));
        } finally {
            deleteUser(principal);
        }
    }

    public void testFailureOnInvalidClientAuthentication() throws Exception {
        final String principal = randomPrincipal();
        final List<String> roles = randomRoles();
        createUser(principal, roles, Map.of());

        try {
            final SignedJWT jwt = buildAndSignJwtForRealm2(principal);
            final TestSecurityClient client = getSecurityClient(jwt, Optional.of("not-the-correct-secret"));

            // This fails because we didn't use the correct shared-secret
            final ResponseException exception = expectThrows(ResponseException.class, client::authenticate);
            assertThat(exception.getResponse(), hasStatusCode(RestStatus.UNAUTHORIZED));

        } finally {
            deleteUser(principal);
        }
    }

    /**
     * Tests against realm "jwt3" in build.gradle
     * This realm
     * - use the "sub" claim as the principal
     * - uses role mapping
     * - supports HMAC signed keys(using a JWKSet)
     * - uses a shared-secret for client authentication
     */
    public void testAuthenticateWithHmacSignedJWTAndMissingRoleMapping() throws Exception {
        final String principal = randomPrincipal();
        final SignedJWT jwt = buildAndSignJwtForRealm3(principal);
        final TestSecurityClient client = getSecurityClient(jwt, VALID_SHARED_SECRET);

        final Map<String, Object> response = client.authenticate();

        assertThat(response.get(User.Fields.USERNAME.getPreferredName()), is(principal));
        assertThat(assertMap(response, User.Fields.AUTHENTICATION_REALM), hasEntry(User.Fields.REALM_NAME.getPreferredName(), "jwt3"));
        assertThat(assertList(response, User.Fields.ROLES), empty());
        assertThat(assertMap(response, User.Fields.METADATA), hasEntry("jwt_claim_sub", principal));
        assertThat(assertMap(response, User.Fields.METADATA), hasEntry("jwt_claim_aud", List.of("jwt3-audience")));
        assertThat(assertMap(response, User.Fields.METADATA), hasEntry("jwt_claim_iss", "jwt3-issuer"));
    }

    private String randomPrincipal() {
        // We append _test so that it cannot randomly conflict with builtin user
        return randomAlphaOfLengthBetween(4, 12) + "_test";
    }

    private List<String> randomRoles() {
        // We append _test so that it cannot randomly conflict with builtin roles
        return randomList(1, 3, () -> randomAlphaOfLengthBetween(4, 12) + "_test");
    }

    private SignedJWT buildAndSignJwtForRealm1(String principal, List<String> groups, Instant issueTime) throws JOSEException,
        ParseException, IOException {
        final JWTClaimsSet claimsSet = buildJwt(
            Map.ofEntries(
                Map.entry("iss", "https://issuer.example.com/"),
                Map.entry("aud", "https://audience.example.com/"),
                Map.entry("sub", principal),
                Map.entry("roles", groups) // Realm realm config has `claim.groups: "roles"`
            ),
            issueTime
        );
        return signJwtForRealm1(claimsSet);
    }

    private SignedJWT buildAndSignJwtForRealm2(String principal) throws JOSEException, ParseException {
        return buildAndSignJwtForRealm2(principal, Instant.now());
    }

    private SignedJWT buildAndSignJwtForRealm2(String principal, Instant issueTime) throws JOSEException, ParseException {
        final JWTClaimsSet claimsSet = buildJwtForRealm2(principal, issueTime);
        return signJwtForRealm2(claimsSet);
    }

    private JWTClaimsSet buildJwtForRealm2(String principal, Instant issueTime) {
        final String emailAddress = principal + "@" + randomAlphaOfLengthBetween(3, 6) + ".example.com";
        // The "jwt2" realm, supports 3 audiences (es01/02/03)
        final String audience = "es0" + randomIntBetween(1, 3);
        final JWTClaimsSet claimsSet = buildJwt(
            Map.ofEntries(Map.entry("iss", "my-issuer"), Map.entry("aud", audience), Map.entry("email", emailAddress)),
            issueTime
        );
        return claimsSet;
    }

    private SignedJWT buildAndSignJwtForRealm3(String principal) throws Exception {
        return buildAndSignJwtForRealm3(principal, Instant.now());
    }

    private SignedJWT buildAndSignJwtForRealm3(String principal, Instant issueTime) throws Exception {
        final JWTClaimsSet claimsSet = buildJwt(
            Map.ofEntries(Map.entry("iss", "jwt3-issuer"), Map.entry("aud", "jwt3-audience"), Map.entry("sub", principal)),
            issueTime
        );
        return signJwtForRealm3(claimsSet);
    }

    private SignedJWT signJwtForRealm1(JWTClaimsSet claimsSet) throws IOException, JOSEException, ParseException {
        final RSASSASigner signer = loadRsaSigner();
        return signJWT(signer, "RS256", claimsSet);
    }

    private SignedJWT signJwtForRealm2(JWTClaimsSet claimsSet) throws JOSEException, ParseException {
        // Input string is configured in build.gradle
        return signHmacJwt(claimsSet, "test-HMAC/secret passphrase-value");
    }

    private SignedJWT signJwtForRealm3(JWTClaimsSet claimsSet) throws JOSEException, ParseException, IOException {
        final int bitSize = randomFrom(384, 512);
        final MACSigner signer = loadHmacSigner("test-hmac-" + bitSize);
        return signJWT(signer, "HS" + bitSize, claimsSet);
    }

    private RSASSASigner loadRsaSigner() throws IOException, ParseException, JOSEException {
        // The "jwt1" realm is configured using public JWKSet (in build.gradle)
        try (var in = getDataInputStream("/jwk/rsa-private-jwkset.json")) {
            final JWKSet jwkSet = JWKSet.load(in);
            final JWK key = jwkSet.getKeyByKeyId("test-rsa-key");
            assertThat(key, instanceOf(RSAKey.class));
            return new RSASSASigner((RSAKey) key);
        }
    }

    private MACSigner loadHmacSigner(String keyId) throws IOException, ParseException, JOSEException {
        // The "jwt3" realm is configured using secret JWKSet (in build.gradle)
        try (var in = getDataInputStream("/jwk/hmac-jwkset.json")) {
            final JWKSet jwkSet = JWKSet.load(in);
            final JWK key = jwkSet.getKeyByKeyId(keyId);
            assertThat("Key [" + keyId + "] from [" + jwkSet.getKeys() + "]", key, instanceOf(OctetSequenceKey.class));
            return new MACSigner((OctetSequenceKey) key);
        }
    }

    private SignedJWT signHmacJwt(JWTClaimsSet claimsSet, String hmacPassphrase) throws JOSEException {
        final OctetSequenceKey hmac = JwkValidateUtil.buildHmacKeyFromString(hmacPassphrase);
        final JWSSigner signer = new MACSigner(hmac);
        return signJWT(signer, "HS256", claimsSet);
    }

    // JWT construction
    private JWTClaimsSet buildJwt(Map<String, Object> claims, Instant issueTime) {
        final JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        builder.issuer(randomAlphaOfLengthBetween(4, 24));
        builder.subject(randomAlphaOfLengthBetween(4, 24));
        builder.audience(randomList(1, 6, () -> randomAlphaOfLengthBetween(4, 12)));
        if (randomBoolean()) {
            builder.jwtID(UUIDs.randomBase64UUID(random()));
        }

        issueTime = issueTime.truncatedTo(ChronoUnit.SECONDS);
        builder.issueTime(Date.from(issueTime));
        if (randomBoolean()) {
            builder.claim("auth_time", Date.from(issueTime.minusSeconds(randomLongBetween(0, 1800))));
        }
        builder.expirationTime(Date.from(issueTime.plusSeconds(randomLongBetween(180, 1800))));
        if (randomBoolean()) {
            builder.notBeforeTime(Date.from(issueTime.minusSeconds(randomLongBetween(10, 90))));
        }

        // This may overwrite the aud/sub/iss set above. That is an intended behaviour
        for (String key : claims.keySet()) {
            builder.claim(key, claims.get(key));
        }
        return builder.build();
    }

    private SignedJWT signJWT(JWSSigner signer, String algorithm, JWTClaimsSet claimsSet) throws JOSEException {
        final JWSHeader.Builder builder = new JWSHeader.Builder(JWSAlgorithm.parse(algorithm));
        if (randomBoolean()) {
            builder.type(JOSEObjectType.JWT);
        }
        final JWSHeader jwtHeader = builder.build();
        final SignedJWT jwt = new SignedJWT(jwtHeader, claimsSet);
        jwt.sign(signer);
        return jwt;
    }

    private TestSecurityClient getSecurityClient(SignedJWT jwt, Optional<String> sharedSecret) {
        final String bearerHeader = "Bearer " + jwt.serialize();

        final RequestOptions.Builder options = RequestOptions.DEFAULT.toBuilder().addHeader("Authorization", bearerHeader);
        sharedSecret.ifPresent(secret -> options.addHeader("X-Client-Authentication", "SharedSecret " + secret));

        return new TestSecurityClient(client(), options.build());
    }

    // Utility methods
    private Map<?, ?> assertMap(Map<String, ?> response, ParseField field) {
        assertThat(response, hasKey(field.getPreferredName()));
        assertThat(response, hasEntry(is(field.getPreferredName()), instanceOf(Map.class)));
        return (Map<?, ?>) response.get(field.getPreferredName());
    }

    private List<?> assertList(Map<String, ?> response, ParseField field) {
        assertThat(response, hasKey(field.getPreferredName()));
        assertThat(response, hasEntry(is(field.getPreferredName()), instanceOf(List.class)));
        return (List<?>) response.get(field.getPreferredName());
    }

    private void createUser(String principal, List<String> roles, Map<String, Object> metadata) throws IOException {
        createUser(principal, new SecureString(randomAlphaOfLength(12).toCharArray()), roles, metadata);
    }

    private void createUser(String username, SecureString password, List<String> roles, Map<String, Object> metadata) throws IOException {
        final String realName = randomAlphaOfLengthBetween(6, 18);
        final User user = new User(username, roles.toArray(String[]::new), realName, null, metadata, true);
        getAdminSecurityClient().putUser(user, password);
    }

    private void deleteUser(String username) throws IOException {
        getAdminSecurityClient().deleteUser(username);
    }

    private String createRoleMapping(List<String> roles, String rules) throws IOException {
        Map<String, Object> mapping = new HashMap<>();
        mapping.put("enabled", true);
        mapping.put("roles", roles);
        mapping.put("rules", XContentHelper.convertToMap(XContentType.JSON.xContent(), rules, true));
        final String mappingName = "test-" + getTestName() + "-" + randomAlphaOfLength(8);
        getAdminSecurityClient().putRoleMapping(mappingName, mapping);
        return mappingName;
    }

    private void deleteRoleMapping(String name) throws IOException {
        getAdminSecurityClient().deleteRoleMapping(name);
    }

}
