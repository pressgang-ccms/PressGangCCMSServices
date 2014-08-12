/*
  Copyright 2011-2014 Red Hat, Inc

  This file is part of PressGang CCMS.

  PressGang CCMS is free software: you can redistribute it and/or modify
  it under the terms of the GNU Lesser General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  PressGang CCMS is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public License
  along with PressGang CCMS.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.jboss.pressgang.ccms.services.zanatasync;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import com.beust.jcommander.IVariableArity;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.jboss.pressgang.ccms.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.provider.LocaleProvider;
import org.jboss.pressgang.ccms.provider.RESTProviderFactory;
import org.jboss.pressgang.ccms.provider.RESTTopicProvider;
import org.jboss.pressgang.ccms.provider.ServerSettingsProvider;
import org.jboss.pressgang.ccms.utils.constants.CommonConstants;
import org.jboss.pressgang.ccms.wrapper.LocaleWrapper;
import org.jboss.pressgang.ccms.wrapper.ServerSettingsWrapper;
import org.jboss.pressgang.ccms.wrapper.collection.CollectionWrapper;
import org.jboss.pressgang.ccms.zanata.ETagCache;
import org.jboss.pressgang.ccms.zanata.ETagInterceptor;
import org.jboss.pressgang.ccms.zanata.ZanataConstants;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zanata.common.LocaleId;
import org.zanata.rest.client.ITranslatedDocResource;
import org.zanata.rest.service.TranslatedDocResource;

public class Main implements IVariableArity {
    private static final Logger log = LoggerFactory.getLogger("ZanataSyncService");

    /**
     * The Default amount of time that should be waited between Zanata REST API Calls.
     */
    private static final Double DEFAULT_ZANATA_CALL_INTERVAL = 0.2;

    private static final List<Class<?>> ALLOWED_RESOURCES = Arrays.<Class<?>>asList(ITranslatedDocResource.class,
            TranslatedDocResource.class);

    /* Get the system properties */
    private static final String PRESS_GANG_SERVER = System.getProperty(CommonConstants.PRESS_GANG_REST_SERVER_SYSTEM_PROPERTY);
    private static final String ZANATA_SERVER = System.getProperty(ZanataConstants.ZANATA_SERVER_PROPERTY);
    private static final String ZANATA_TOKEN = System.getProperty(ZanataConstants.ZANATA_TOKEN_PROPERTY);
    private static final String ZANATA_USERNAME = System.getProperty(ZanataConstants.ZANATA_USERNAME_PROPERTY);
    private static final String ZANATA_PROJECT = System.getProperty(ZanataConstants.ZANATA_PROJECT_PROPERTY);
    private static final String ZANATA_VERSION = System.getProperty(ZanataConstants.ZANATA_PROJECT_VERSION_PROPERTY);
    private static final String MIN_ZANATA_CALL_INTERVAL = System.getProperty(ZanataConstants.MIN_ZANATA_CALL_INTERNAL_PROPERTY,
            DEFAULT_ZANATA_CALL_INTERVAL.toString());

    @Parameter(names = {"--topics", "-t"}, description = "Sync a single or list of Topics from Zanata to PressGang.", variableArity = true)
    private LinkedHashSet<String> topicIds = new LinkedHashSet<String>();

    @Parameter(names = {"--all", "-a"}, description = "Sync all documents in Zanata to PressGang.")
    private boolean syncAll = false;

    @Parameter(names = {"--content-specs", "-c"}, description = "Sync a single or list of Content Specs from Zanata to PressGang.",
            variableArity = true)
    private LinkedHashSet<String> contentSpecIds = new LinkedHashSet<String>();

    @Parameter(names = "--locales", variableArity = true, converter = LocaleIdConverter.class)
    private List<LocaleId> locales = new ArrayList<LocaleId>();

    final ETagCache eTagCache = new ETagCache();
    final File eTagCacheFile = new File(".zanata-cache");

    /**
     * The minimum amount of time in seconds between calls to the Zanata REST API
     */
    private Double zanataRESTCallInterval = null;
    private ZanataInterface zanataInterface = null;
    private DataProviderFactory providerFactory = null;
    private ZanataSyncService syncService = null;
    private ServerSettingsWrapper serverSettings = null;

    public static void main(final String[] args) {
        final Main main = new Main();
        final JCommander jCommander = new JCommander(main, args);
        main.setUp();
        main.process();
        main.cleanUp();
    }

    public Main() {
        // Parse the specified time from the System Variables. If no time is set or is invalid then use the default value
        try {
            zanataRESTCallInterval = Double.parseDouble(MIN_ZANATA_CALL_INTERVAL);
        } catch (NumberFormatException ex) {
            zanataRESTCallInterval = DEFAULT_ZANATA_CALL_INTERVAL;
        }
    }

    private void setUp() {
        // Exit if the system properties have not been set
        if (!checkEnvironment()) return;

        // Load the cache data
        try {
            eTagCache.load(eTagCacheFile);
        } catch (IOException e) {
            log.error("Failed to load the ETag cache data from file", e);
        }

        providerFactory = RESTProviderFactory.create(PRESS_GANG_SERVER);
        providerFactory.getProvider(RESTTopicProvider.class).setExpandTranslations(true);
        final ETagInterceptor interceptor = new ETagInterceptor(eTagCache, ALLOWED_RESOURCES);
        ResteasyProviderFactory.getInstance().getClientExecutionInterceptorRegistry().register(interceptor);
        zanataInterface = new ZanataInterface(zanataRESTCallInterval);
        serverSettings = providerFactory.getProvider(ServerSettingsProvider.class).getServerSettings();
        syncService = new ZanataSyncService(providerFactory, zanataInterface, serverSettings);

        // Initialise the locales to use
        initLocales(providerFactory);
        zanataInterface.getLocaleManager().setLocales(new ArrayList<LocaleId>(locales));

        // Remove the default locale as it won't have any translations
        zanataInterface.getLocaleManager().removeLocale(new LocaleId(serverSettings.getDefaultLocale().getTranslationValue()));
    }

    private void process() {
        if (!syncAll) {
            syncService.sync(contentSpecIds, topicIds, locales);
        } else {
            syncService.syncAll(locales);
        }
    }

    private void cleanUp() {
        try {
            eTagCache.save(eTagCacheFile);
        } catch (IOException e) {
            log.error("Failed to save the ETag Cache to file", e);
        }
    }

    /**
     * @return true if all environment variables were set, false otherwise
     */
    private boolean checkEnvironment() {
        log.info("PressGang REST: " + PRESS_GANG_SERVER);
        log.info("Zanata Server: " + ZANATA_SERVER);
        log.info("Zanata Username: " + ZANATA_USERNAME);
        log.info("Zanata Token: " + ZANATA_TOKEN);
        log.info("Zanata Project: " + ZANATA_PROJECT);
        log.info("Zanata Project Version: " + ZANATA_VERSION);
        log.info("Rate Limiting: " + zanataRESTCallInterval + " seconds per REST call");

        // Some sanity checking
        if (PRESS_GANG_SERVER == null || PRESS_GANG_SERVER.trim().isEmpty() || ZANATA_SERVER == null || ZANATA_SERVER.trim().isEmpty() ||
                ZANATA_TOKEN == null || ZANATA_TOKEN.trim().isEmpty() || ZANATA_USERNAME == null || ZANATA_USERNAME.trim().isEmpty() ||
                ZANATA_PROJECT == null || ZANATA_PROJECT.trim().isEmpty() || ZANATA_VERSION == null || ZANATA_VERSION.trim().isEmpty()) {
            log.error(
                    "The " + CommonConstants.PRESS_GANG_REST_SERVER_SYSTEM_PROPERTY + ", " + ZanataConstants.ZANATA_SERVER_PROPERTY + ", " +
                            ZanataConstants.ZANATA_TOKEN_PROPERTY + ", " + ZanataConstants.ZANATA_USERNAME_PROPERTY + ", " +
                            ZanataConstants.ZANATA_SERVER_PROPERTY + " and " + ZanataConstants.ZANATA_PROJECT_VERSION_PROPERTY +
                            " system properties need to be defined.");
            return false;
        }

        return true;
    }

    private void initLocales(final DataProviderFactory providerFactory) {
        if (locales.isEmpty()) {
            // Get the Locales
            final CollectionWrapper<LocaleWrapper> locales = providerFactory.getProvider(LocaleProvider.class).getLocales();
            for (final LocaleWrapper locale : locales.getItems()) {
                this.locales.add(LocaleId.fromJavaName(locale.getTranslationValue()));
            }
        }
    }

    @Override
    public int processVariableArity(String optionName, String[] options) {
        int i = 0;
        while (i < options.length && !options[i].startsWith("-")) {
            i++;
        }
        return i;
    }
}
