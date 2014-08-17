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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.beust.jcommander.IVariableArity;
import com.beust.jcommander.JCommander;
import org.jboss.pressgang.ccms.provider.ContentSpecProvider;
import org.jboss.pressgang.ccms.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.provider.RESTContentSpecProvider;
import org.jboss.pressgang.ccms.provider.RESTProviderFactory;
import org.jboss.pressgang.ccms.provider.RESTTopicProvider;
import org.jboss.pressgang.ccms.provider.ServerSettingsProvider;
import org.jboss.pressgang.ccms.rest.v1.query.RESTContentSpecQueryBuilderV1;
import org.jboss.pressgang.ccms.utils.constants.CommonConstants;
import org.jboss.pressgang.ccms.wrapper.ContentSpecWrapper;
import org.jboss.pressgang.ccms.wrapper.ServerSettingsWrapper;
import org.jboss.pressgang.ccms.wrapper.collection.CollectionWrapper;
import org.jboss.pressgang.ccms.zanata.ETagCache;
import org.jboss.pressgang.ccms.zanata.ETagInterceptor;
import org.jboss.pressgang.ccms.zanata.ZanataConstants;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final String MIN_ZANATA_CALL_INTERVAL = System.getProperty(ZanataConstants.MIN_ZANATA_CALL_INTERNAL_PROPERTY,
            DEFAULT_ZANATA_CALL_INTERVAL.toString());

    final ETagCache eTagCache = new ETagCache();
    final File eTagCacheFile = new File(".zanata-cache");

    /**
     * The minimum amount of time in seconds between calls to the Zanata REST API
     */
    private Double zanataRESTCallInterval = null;
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
        providerFactory.getProvider(RESTContentSpecProvider.class).setExpandTranslationDetails(true);
        final ETagInterceptor interceptor = new ETagInterceptor(eTagCache, ALLOWED_RESOURCES);
        ResteasyProviderFactory.getInstance().getClientExecutionInterceptorRegistry().register(interceptor);
        serverSettings = providerFactory.getProvider(ServerSettingsProvider.class).getServerSettings();
        syncService = new ZanataSyncService(providerFactory, serverSettings, zanataRESTCallInterval);
    }

    private void process() {
        final ContentSpecProvider contentSpecProvider = providerFactory.getProvider(ContentSpecProvider.class);

        // Build the query to find the translations
        final RESTContentSpecQueryBuilderV1 queryBuilder = new RESTContentSpecQueryBuilderV1();
        queryBuilder.setContentSpecTranslationEnabled(true);

        // Get the content specs to sync
        final CollectionWrapper<ContentSpecWrapper> contentSpecs = contentSpecProvider.getContentSpecsWithQuery(queryBuilder.getQuery());
        final Set<String> contentSpecIds = new HashSet<String>();
        for (final ContentSpecWrapper contentSpec : contentSpecs.getItems()) {
            contentSpecIds.add(contentSpec.getId().toString());
        }

        // Sync the translations
        syncService.syncContentSpecs(contentSpecIds, null);
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
        log.info("Rate Limiting: " + zanataRESTCallInterval + " seconds per REST call");

        // Some sanity checking
        if (PRESS_GANG_SERVER == null || PRESS_GANG_SERVER.trim().isEmpty()) {
            log.error("The " + CommonConstants.PRESS_GANG_REST_SERVER_SYSTEM_PROPERTY + " system property need to be defined.");
            return false;
        }

        return true;
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
