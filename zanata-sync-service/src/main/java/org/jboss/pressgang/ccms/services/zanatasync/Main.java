package org.jboss.pressgang.ccms.services.zanatasync;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;
import org.jboss.pressgang.ccms.rest.v1.client.PressGangCCMSProxyFactoryV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTStringConstantV1;
import org.jboss.pressgang.ccms.rest.v1.jaxrsinterfaces.RESTInterfaceV1;
import org.jboss.pressgang.ccms.utils.common.CollectionUtilities;
import org.jboss.pressgang.ccms.utils.constants.CommonConstants;
import org.jboss.pressgang.ccms.zanata.ZanataConstants;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.zanata.common.LocaleId;

public class Main {
    private static final Logger log = Logger.getLogger("PressGangCCMSZanataSyncService");
    /**
     * The Default amount of time that should be waited between Zanata REST API Calls.
     */
    private static final Double DEFAULT_ZANATA_CALL_INTERVAL = 0.2;

    /* Get the system properties */
    private static final String PRESS_GANG_SERVER = System.getProperty(CommonConstants.PRESS_GANG_REST_SERVER_SYSTEM_PROPERTY);
    private static final String ZANATA_SERVER = System.getProperty(ZanataConstants.ZANATA_SERVER_PROPERTY);
    private static final String ZANATA_TOKEN = System.getProperty(ZanataConstants.ZANATA_TOKEN_PROPERTY);
    private static final String ZANATA_USERNAME = System.getProperty(ZanataConstants.ZANATA_USERNAME_PROPERTY);
    private static final String ZANATA_PROJECT = System.getProperty(ZanataConstants.ZANATA_PROJECT_PROPERTY);
    private static final String ZANATA_VERSION = System.getProperty(ZanataConstants.ZANATA_PROJECT_VERSION_PROPERTY);
    private static final String MIN_ZANATA_CALL_INTERVAL = System.getProperty(ZanataConstants.MIN_ZANATA_CALL_INTERNAL_PROPERTY);

    public static void main(final String[] args) {
        RegisterBuiltin.register(ResteasyProviderFactory.getInstance());

        /* Parse the specified time from the System Variables. If no time is set or is invalid then use the default value */
        Double zanataRESTCallInterval;
        try {
            zanataRESTCallInterval = Double.parseDouble(MIN_ZANATA_CALL_INTERVAL);
        } catch (NumberFormatException ex) {
            zanataRESTCallInterval = DEFAULT_ZANATA_CALL_INTERVAL;
        } catch (NullPointerException ex) {
            zanataRESTCallInterval = DEFAULT_ZANATA_CALL_INTERVAL;
        }

        /* Exit if the system properties have not been set */
        if (!checkEnvironment(zanataRESTCallInterval)) return;

        /* Get an instance of the zanata interface */
        final ZanataInterface zanataInterface = new ZanataInterface(zanataRESTCallInterval);

        /* Get in instance of the CCMS interface */
        final RESTInterfaceV1 client = PressGangCCMSProxyFactoryV1.create(PRESS_GANG_SERVER).getRESTClient();

        final SyncMaster syncMaster = new SyncMaster(PRESS_GANG_SERVER, client, zanataInterface);

        try {
            /* Get the locales */
            final List<LocaleId> locales = getLocales(client);
            zanataInterface.getLocaleManager().setLocales(locales);

            /* Remove the default locale as it won't have any translations */
            zanataInterface.getLocaleManager().removeLocale(new LocaleId(CommonConstants.DEFAULT_LOCALE));

            /* Get the existing zanata resources */
            final Set<String> zanataResources = syncMaster.getZanataResources();

            /* Sync the zanata resources to the CCMS */
            syncMaster.processZanataResources(zanataResources);

        } catch (final Exception ex) {
            log.error(ex.toString());
        }
    }

    private static List<LocaleId> getLocales(final RESTInterfaceV1 client) {

        /* Get the Locale constants */
        final RESTStringConstantV1 localeConstant = client.getJSONStringConstant(CommonConstants.LOCALES_STRING_CONSTANT_ID, "");
        final List<String> locales = CollectionUtilities.replaceStrings(CollectionUtilities.sortAndReturn(
                CollectionUtilities.toArrayList(localeConstant.getValue().split("[\\s\r\n]*,[\\s\r\n]*"))), "_", "-");
        final List<LocaleId> localeIds = new ArrayList<LocaleId>();
        for (final String locale : locales) {
            localeIds.add(LocaleId.fromJavaName(locale));
        }

        return localeIds;
    }

    /**
     * @return true if all environment variables were set, false otherwise
     */
    private static boolean checkEnvironment(final Double zanataRESTCallInterval) {
        log.info("Skynet REST: " + PRESS_GANG_SERVER);
        log.info("Zanata Server: " + ZANATA_SERVER);
        log.info("Zanata Username: " + ZANATA_USERNAME);
        log.info("Zanata Token: " + ZANATA_TOKEN);
        log.info("Zanata Project: " + ZANATA_PROJECT);
        log.info("Zanata Project Version: " + ZANATA_VERSION);
        log.info("Default Locale: " + CommonConstants.DEFAULT_LOCALE);
        log.info("Rate Limiting: " + zanataRESTCallInterval + " seconds per REST call");

        /* Some sanity checking */
        if (PRESS_GANG_SERVER == null || PRESS_GANG_SERVER.trim().isEmpty() || ZANATA_SERVER == null || ZANATA_SERVER.trim().isEmpty() || ZANATA_TOKEN
                == null || ZANATA_TOKEN.trim().isEmpty() || ZANATA_USERNAME == null || ZANATA_USERNAME.trim().isEmpty() || ZANATA_PROJECT
                == null || ZANATA_PROJECT.trim().isEmpty() || ZANATA_VERSION == null || ZANATA_VERSION.trim().isEmpty()) {
            log.error(
                    "The " + CommonConstants.PRESS_GANG_REST_SERVER_SYSTEM_PROPERTY + ", " + ZanataConstants.ZANATA_SERVER_PROPERTY + ", " +
                            "" + ZanataConstants.ZANATA_TOKEN_PROPERTY + ", " + ZanataConstants.ZANATA_USERNAME_PROPERTY + ", " + ZanataConstants.ZANATA_SERVER_PROPERTY + " and " + ZanataConstants.ZANATA_PROJECT_VERSION_PROPERTY + " system properties need to be defined.");
            return false;
        }

        return true;
    }
}
