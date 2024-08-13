package fi.csc.shibboleth.plugin.candourid.impl;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.annotation.Nonnull;

import org.apache.hc.core5.net.URIBuilder;
import org.opensaml.profile.action.ActionSupport;
import org.opensaml.profile.action.EventIds;
import org.opensaml.profile.context.ProfileRequestContext;
import org.slf4j.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;

import fi.csc.shibboleth.plugin.candourid.CandourEventIds;
import fi.csc.shibboleth.plugin.candourid.context.CandourContext;
import fi.csc.shibboleth.plugin.candourid.messaging.impl.CandourInvitationRequest;
import fi.csc.shibboleth.plugin.candourid.messaging.impl.CandourInvitationRequestPayload;
import fi.csc.shibboleth.plugin.candourid.messaging.impl.CandourInvitationSuccessResponsePayload;
import fi.csc.shibboleth.plugin.candourid.messaging.impl.CandourResponse;
import jakarta.servlet.http.HttpServletRequest;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.shared.annotation.constraint.NonnullAfterInit;
import net.shibboleth.shared.component.ComponentInitializationException;
import net.shibboleth.shared.primitive.LoggerFactory;

/**
 * An {@link AbstractCandourHttpAuthenticationAction action} that forms and
 * sends a invitation request to Candour and stores the response to
 * {@link CandourContext}.
 *
 * @event {@link org.opensaml.profile.action.EventIds#PROCEED_EVENT_ID}
 * @event {@link org.opensaml.profile.action.EventIdsEventIds.INVALID_PROFILE_CTX}
 * @event {@link fi.csc.shibboleth.plugin.candourid.CandourEventIds.CANDOUR_API_COMM_FAILURE}
 * @event {@link fi.csc.shibboleth.plugin.candourid.CandourEventIds.CANDOUR_API_RESP_FAILURE}
 * @event {@link fi.csc.shibboleth.plugin.candourid.CandourEventIds.CANDOUR_API_RESP_MALFORMED}
 * @post {@link CandourContext#getInvitationResponse()} returns invitation
 *       response.
 * @post {@link CandourContext#getAuthenticationUri()} returns authentication
 *       uri.
 */
public class CreateSession extends AbstractCandourHttpAuthenticationAction {

    /** Class logger. */
    @Nonnull
    private final Logger log = LoggerFactory.getLogger(CreateSession.class);

    /** Candour API location. */
    @NonnullAfterInit
    private URI candouridURI;

    /** Candour API client public key. */
    @NonnullAfterInit
    private String clientPublicKey;

    /** Candour API client hmac key. */
    @NonnullAfterInit
    private String clientHmacKey;

    /** The payload to send to Candour. */
    @NonnullAfterInit
    private CandourInvitationRequestPayload payload;

    /**
     * Set Candour API location.
     * 
     * @param uri Candour API location
     * @throws URISyntaxException
     */
    public void setCandouridURI(@Nonnull String uri) throws URISyntaxException {
        assert uri != null;
        candouridURI = new URI(uri);
    }

    /**
     * Set Candour API client public key.
     * 
     * @param publicKey Candour API client public key
     */
    public void setClientPublicKey(@Nonnull String publicKey) {
        assert publicKey != null;
        clientPublicKey = publicKey;
    }

    /**
     * Set Candour API client hmac key.
     * 
     * @param hmacKey Candour API client hmac key
     */
    public void setClientHmacKey(@Nonnull String hmacKey) {
        assert hmacKey != null;
        clientHmacKey = hmacKey;
    }

    /**
     * Set the payload to send to Candour.
     * 
     * @param content the payload to send to Candour. Implement a strategy to set it
     */
    public void setPayload(@Nonnull CandourInvitationRequestPayload content) {
        assert content != null;
        payload = content;
    }

    @Override
    protected void doInitialize() throws ComponentInitializationException {
        super.doInitialize();

        if (candouridURI == null) {
            throw new ComponentInitializationException("CandouridURI cannot be null");
        }
        if (clientPublicKey == null) {
            throw new ComponentInitializationException("ClientPublicKey cannot be null");
        }
        if (clientHmacKey == null) {
            throw new ComponentInitializationException("ClientHmacKey cannot be null");
        }
        if (payload == null) {
            throw new ComponentInitializationException("Payload cannot be null");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void doExecute(@Nonnull final ProfileRequestContext profileRequestContext,
            @Nonnull final AuthenticationContext authenticationContext) {

        CandourInvitationRequest message = new CandourInvitationRequest(candouridURI, clientPublicKey, clientHmacKey);
        message.setPayload(payload);
        String uri = buildCallbackUri();
        if (uri == null) {
            ActionSupport.buildEvent(profileRequestContext, EventIds.INVALID_PROFILE_CTX);
            return;
        }
        message.getPayload().setCallbackUrl(buildCallbackUri());

        CandourResponse response = null;
        try {
            response = executeHttpRequest(message.toHttpRequest());
        } catch (IOException | InvalidKeyException | NoSuchAlgorithmException | IllegalStateException e) {
            log.error("{} Exception occurred", getLogPrefix(), e);
            ActionSupport.buildEvent(profileRequestContext, CandourEventIds.CANDOUR_API_COMM_FAILURE);
            return;
        }
        if (!response.indicateSuccess()) {
            log.error("{} Candour invitation response indicates error. Status code {}, payload {}", getLogPrefix(),
                    response.getCode(), response.getPayload());
            ActionSupport.buildEvent(profileRequestContext, CandourEventIds.CANDOUR_API_RESP_FAILURE);
            return;
        }
        // Parse success response
        CandourInvitationSuccessResponsePayload payload = null;
        try {
            payload = CandourInvitationSuccessResponsePayload.parse(response.getPayload());
        } catch (JsonProcessingException e) {
            log.error("{} Candour response parsing failed.", getLogPrefix(), e);
            ActionSupport.buildEvent(profileRequestContext, CandourEventIds.CANDOUR_API_RESP_MALFORMED);
            return;
        }
        candourContext.setInvitationResponse(response.getPayload());
        candourContext.setAuthenticationUri(payload.getRedirectUrl());
    }

    /**
     * Builds callback Uri.
     * 
     * @return callback Uri
     */
    private String buildCallbackUri() {

        HttpServletRequest request = getHttpServletRequestSupplier().get();
        final String scheme = request.getScheme();
        assert scheme != null;
        final String serverName = request.getServerName();
        assert serverName != null;
        String callbackUri = null;
        try {
            callbackUri = URLDecoder.decode(buildURIIgnoreDefaultPorts(scheme, serverName, request.getServerPort(),
                    candourContext.getCallbackUri()).toString(), StandardCharsets.UTF_8.name());
        } catch (URISyntaxException | UnsupportedEncodingException e) {
            log.error("{} Exception occurred", getLogPrefix(), e);
            return null;
        }
        return callbackUri;
    }

    /**
     * Build a {@link URI} from the given parameters. If the scheme is either 'http'
     * or 'https' with their respective default port, the port is set to -1.
     * 
     * @param scheme the scheme
     * @param host   the hostname
     * @param port   the port
     * @param path   the path
     * 
     * @return a fully built URI from the given parameters.
     * 
     * @throws URISyntaxException if the URI can not be constructed.
     */
    @Nonnull
    private final URI buildURIIgnoreDefaultPorts(@Nonnull final String scheme, @Nonnull final String host,
            final int port, @Nonnull final String path) throws URISyntaxException {

        int usedPort = port;
        if ("http".equalsIgnoreCase(scheme)) {
            // ignore port iff using the default http port
            if (port == 80) {
                usedPort = -1;
            }
        } else if ("https".equalsIgnoreCase(scheme)) {
            // ignore port iff using the default https port
            if (port == 443) {
                usedPort = -1;
            }
        }
        final URI builtUri = new URIBuilder().setScheme(scheme).setHost(host).setPort(usedPort).setPath(path).build();
        assert builtUri != null;
        return builtUri;
    }

}
