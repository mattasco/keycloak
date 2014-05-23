package org.keycloak.services.resources;

import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.jboss.resteasy.spi.BadRequestException;
import org.jboss.resteasy.spi.NotFoundException;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.jboss.resteasy.spi.UnauthorizedException;
import org.keycloak.audit.Audit;
import org.keycloak.models.ApplicationModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderSession;
import org.keycloak.services.ClientConnection;
import org.keycloak.services.managers.AuditManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.BruteForceProtector;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.managers.SocialRequestManager;
import org.keycloak.services.managers.TokenManager;
import org.keycloak.util.StreamUtil;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Path("/realms")
public class RealmsResource {
    protected static Logger logger = Logger.getLogger(RealmsResource.class);

    @Context
    protected UriInfo uriInfo;

    @Context
    protected HttpHeaders headers;

    /*
    @Context
    protected ResourceContext resourceContext;
    */

    @Context
    protected KeycloakSession session;

    @Context
    protected ProviderSession providers;

    @Context
    protected ClientConnection clientConnection;

    @Context
    protected BruteForceProtector protector;

    protected TokenManager tokenManager;
    protected SocialRequestManager socialRequestManager;

    public RealmsResource(TokenManager tokenManager, SocialRequestManager socialRequestManager) {
        this.tokenManager = tokenManager;
        this.socialRequestManager = socialRequestManager;
    }

    public static UriBuilder realmBaseUrl(UriInfo uriInfo) {
        return uriInfo.getBaseUriBuilder().path(RealmsResource.class).path(RealmsResource.class, "getRealmResource");
    }

    public static UriBuilder realmBaseUrl(UriBuilder base) {
        return base.path(RealmsResource.class).path(RealmsResource.class, "getRealmResource");
    }

    public static UriBuilder accountUrl(UriBuilder base) {
        return base.path(RealmsResource.class).path(RealmsResource.class, "getAccountService");
    }

    /**
     *
     *
     * @param name
     * @param client_id
     * @return
     */
    @Path("{realm}/login-status-iframe.html")
    @GET
    @Produces(MediaType.TEXT_HTML)
    @NoCache
    public String getLoginStatusIframe(final @PathParam("realm") String name,
                                       @QueryParam("client_id") String client_id,
                                       @QueryParam("origin") String origin) {
        logger.info("getLoginStatusIframe");
        AuthenticationManager auth = new AuthenticationManager(providers);

        //logger.info("getting login-status-iframe.html for client_id: " + client_id);
        RealmManager realmManager = new RealmManager(session);
        RealmModel realm = locateRealm(name, realmManager);
        ClientModel client = realm.findClient(client_id);
        if (client == null) {
            throw new NotFoundException("could not find client: " + client_id);
        }
        AuthenticationManager.AuthResult result = auth.authenticateIdentityCookie(realm, uriInfo, headers);
        if (result == null) {
            throw new UnauthorizedException("not logged in, can't get page");
        }

        InputStream is = getClass().getClassLoader().getResourceAsStream("login-status-iframe.html");
        if (is == null) throw new NotFoundException("Could not find login-status-iframe.html ");

        boolean valid = false;
        for (String o : client.getWebOrigins()) {
            if (o.equals("*") || o.equals(origin)) {
                valid = true;
                break;
            }
        }

        for (String r : TokenService.resolveValidRedirects(uriInfo, client.getRedirectUris())) {
            r = r.substring(0, r.indexOf('/', 8));
            if (r.equals(origin)) {
                valid = true;
                break;
            }
        }

        if (!valid) {
            throw new BadRequestException("Invalid origin");
        }

        try {
            String file = StreamUtil.readString(is);
            return file.replace("ORIGIN", origin);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Path("{realm}/tokens")
    public TokenService getTokenService(final @PathParam("realm") String name) {
        RealmManager realmManager = new RealmManager(session);
        RealmModel realm = locateRealm(name, realmManager);
        Audit audit = new AuditManager(realm, providers, clientConnection).createAudit();
        AuthenticationManager authManager = new AuthenticationManager(providers, protector);
        TokenService tokenService = new TokenService(realm, tokenManager, audit, authManager);
        ResteasyProviderFactory.getInstance().injectProperties(tokenService);
        //resourceContext.initResource(tokenService);
        return tokenService;
    }

    protected RealmModel locateRealm(String name, RealmManager realmManager) {
        RealmModel realm = realmManager.getRealmByName(name);
        if (realm == null) {
            throw new NotFoundException("Realm " + name + " not found");
        }
        return realm;
    }

    @Path("{realm}/account")
    public AccountService getAccountService(final @PathParam("realm") String name) {
        RealmManager realmManager = new RealmManager(session);
        RealmModel realm = locateRealm(name, realmManager);

        ApplicationModel application = realm.getApplicationNameMap().get(Constants.ACCOUNT_MANAGEMENT_APP);
        if (application == null || !application.isEnabled()) {
            logger.debug("account management not enabled");
            throw new NotFoundException("account management not enabled");
        }

        Audit audit = new AuditManager(realm, providers, clientConnection).createAudit();
        AccountService accountService = new AccountService(realm, application, tokenManager, socialRequestManager, audit);
        ResteasyProviderFactory.getInstance().injectProperties(accountService);
        //resourceContext.initResource(accountService);
        accountService.init();
        return accountService;
    }

    @Path("{realm}")
    public PublicRealmResource getRealmResource(final @PathParam("realm") String name) {
        RealmManager realmManager = new RealmManager(session);
        RealmModel realm = locateRealm(name, realmManager);
        PublicRealmResource realmResource = new PublicRealmResource(realm);
        ResteasyProviderFactory.getInstance().injectProperties(realmResource);
        //resourceContext.initResource(realmResource);
        return realmResource;
    }




}
