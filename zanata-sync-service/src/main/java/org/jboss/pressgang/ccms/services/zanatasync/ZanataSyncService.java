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

import static com.google.common.base.Strings.isNullOrEmpty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.pressgang.ccms.contentspec.utils.EntityUtilities;
import org.jboss.pressgang.ccms.provider.ContentSpecProvider;
import org.jboss.pressgang.ccms.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.provider.TopicProvider;
import org.jboss.pressgang.ccms.provider.exception.NotFoundException;
import org.jboss.pressgang.ccms.wrapper.CSInfoNodeWrapper;
import org.jboss.pressgang.ccms.wrapper.CSNodeWrapper;
import org.jboss.pressgang.ccms.wrapper.CSTranslationDetailWrapper;
import org.jboss.pressgang.ccms.wrapper.ContentSpecWrapper;
import org.jboss.pressgang.ccms.wrapper.LocaleWrapper;
import org.jboss.pressgang.ccms.wrapper.ServerSettingsWrapper;
import org.jboss.pressgang.ccms.wrapper.TopicWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedCSNodeWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedContentSpecWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedTopicWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslationServerExtendedWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslationServerWrapper;
import org.jboss.pressgang.ccms.wrapper.collection.CollectionWrapper;
import org.jboss.pressgang.ccms.zanata.ZanataDetails;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zanata.common.LocaleId;
import org.zanata.rest.dto.resource.ResourceMeta;

public class ZanataSyncService {
    private static final Logger log = LoggerFactory.getLogger(ZanataSyncService.class);

    private final DataProviderFactory providerFactory;
    private final ServerSettingsWrapper serverSettings;
    private final double zanataRESTCallInterval;
    private final ZanataDetails defaultZanataDetails;
    private final Map<ZanataDetails, ZanataInterface> cachedZanataInterface = new HashMap<ZanataDetails, ZanataInterface>();

    public ZanataSyncService(final DataProviderFactory providerFactory, final ServerSettingsWrapper serverSettings,
            double zanataRESTCallInterval) {
        this(providerFactory, serverSettings, zanataRESTCallInterval, null);
    }

    public ZanataSyncService(final DataProviderFactory providerFactory, final ServerSettingsWrapper serverSettings,
            double zanataRESTCallInterval, final ZanataDetails defaultZanataDetails) {
        this.providerFactory = providerFactory;
        this.serverSettings = serverSettings;
        this.zanataRESTCallInterval = zanataRESTCallInterval;
        this.defaultZanataDetails = defaultZanataDetails;
    }

    public void syncAll(final List<LocaleId> locales) {
        if (defaultZanataDetails == null) {
            throw new IllegalArgumentException("No Zanata Details provided");
        }

        final ZanataInterface zanataInterface = initZanataInterface(defaultZanataDetails);
        final Set<String> zanataResources = getAllZanataResources(zanataInterface);
        final BaseZanataSync zanataSync = new SyncMaster(providerFactory, zanataInterface, serverSettings);

        // Sync the zanata resources to the CCMS
        zanataSync.processZanataResources(zanataResources,
                locales == null || locales.isEmpty() ? zanataInterface.getZanataLocales() : locales);
    }

    public void syncTopics(final Set<String> topicIds, final List<LocaleId> locales) {
        if (defaultZanataDetails == null) {
            throw new IllegalArgumentException("No Zanata Details provided");
        }

        final ZanataInterface zanataInterface = initZanataInterface(defaultZanataDetails);
        final BaseZanataSync zanataSync = new SyncMaster(providerFactory, zanataInterface, serverSettings);

        // Sync all the topics
        if (topicIds != null && !topicIds.isEmpty()) {
            // Sync the zanata resources to the CCMS
            zanataSync.processZanataResources(topicIds,
                    locales == null || locales.isEmpty() ? zanataInterface.getZanataLocales() : locales);
        }
    }

    public void syncContentSpecs(final Set<String> contentSpecIds, final List<LocaleId> locales) {
        // Sync each content spec one at a time
        if (contentSpecIds != null && !contentSpecIds.isEmpty()) {
            for (final String contentSpecIdString : contentSpecIds) {
                final String[] vars = contentSpecIdString.split("-");
                final Integer contentSpecId = Integer.parseInt(vars[0]);
                final Integer contentSpecRevision = vars.length > 1 ? Integer.parseInt(vars[1]) : null;

                final ContentSpecWrapper contentSpec = providerFactory.getProvider(ContentSpecProvider.class).getContentSpec
                        (contentSpecId, contentSpecRevision);
                final CSTranslationDetailWrapper translationDetails = contentSpec.getTranslationDetails();

                if (translationDetails != null && translationDetails.getTranslationServer() != null) {
                    // Initial the zanata details and connection
                    final ZanataDetails zanataDetails = generateZanataDetailsFromCSTranslationDetail(translationDetails);
                    final ZanataInterface zanataInterface = initZanataInterface(zanataDetails);
                    final BaseZanataSync zanataSync = new SyncMaster(providerFactory, zanataInterface, serverSettings);

                    // Get the content specs zanata resource ids
                    final Set<String> zanataResources = getContentSpecZanataResource(providerFactory, contentSpecId, contentSpecRevision);

                    if (zanataResources != null && !zanataResources.isEmpty()) {
                        log.info("Syncing " + zanataResources.size() + " translations for content spec " + contentSpecIdString + ".");

                        // Find the actual locales to use
                        final List<LocaleId> contentSpecLocales = initLocales(translationDetails.getLocales());
                        final List<LocaleId> fixedLocales;
                        if (locales == null || locales.isEmpty()) {
                            fixedLocales = contentSpecLocales;
                        } else {
                            fixedLocales = new ArrayList<LocaleId>();
                            for (final LocaleId locale : locales) {
                                if (contentSpecLocales.contains(locale)) {
                                    fixedLocales.add(locale);
                                }
                            }
                        }

                        // Sync the zanata resources to the CCMS
                        zanataSync.processZanataResources(zanataResources, fixedLocales);
                    }
                } else {
                    log.info("Skipping " + contentSpecIdString + " because it has missing or incorrect translation details");
                }
            }
        }
    }

    /**
     * Generates the Zanata details from a content specifications translation properties.
     *
     * @param translationDetail
     * @return
     */
    protected ZanataDetails generateZanataDetailsFromCSTranslationDetail(CSTranslationDetailWrapper translationDetail) {
        final ZanataDetails zanataDetails;
        if (defaultZanataDetails == null) {
            zanataDetails = new ZanataDetails();
        } else {
            zanataDetails = new ZanataDetails(defaultZanataDetails);
        }

        if (translationDetail.getTranslationServer() != null) {
            zanataDetails.setServer(translationDetail.getTranslationServer().getUrl());

            final TranslationServerExtendedWrapper extendedTranslationServer = getExtendedTranslationServer(serverSettings,
                    translationDetail.getTranslationServer());
            if (!isNullOrEmpty(extendedTranslationServer.getUsername()) && !isNullOrEmpty(extendedTranslationServer.getApiKey())) {
                zanataDetails.setUsername(extendedTranslationServer.getUsername());
                zanataDetails.setToken(extendedTranslationServer.getApiKey());
            }
        }
        zanataDetails.setProject(translationDetail.getProject());
        zanataDetails.setVersion(translationDetail.getProjectVersion());

        return zanataDetails;
    }

    /**
     * Gets the zanata translated resources
     *
     * @return
     */
    protected Set<String> getAllZanataResources(final ZanataInterface zanataInterface) {
        /* Get the Zanata resources */
        final List<ResourceMeta> zanataResources = zanataInterface.getZanataResources();
        final int numberZanataTopics = zanataResources.size();

        System.out.println("Found " + numberZanataTopics + " topics in Zanata.");

        final Set<String> retValue = new HashSet<String>();
        for (final ResourceMeta resourceMeta : zanataResources) {
            retValue.add(resourceMeta.getName());
        }

        return retValue;
    }

    /**
     * Get the Zanata IDs to be synced from a list of content specifications.
     *
     * @param providerFactory
     * @param contentSpecId       The Content Spec ID to sync.
     * @param contentSpecRevision The Content Spec revision to sync.
     * @return A Set of Zanata IDs that represent the topics to be synced from the list of Content Specs.
     */
    protected Set<String> getContentSpecZanataResource(final DataProviderFactory providerFactory, final Integer contentSpecId,
            final Integer contentSpecRevision) {
        final List<TranslatedContentSpecWrapper> translatedContentSpecs = new ArrayList<TranslatedContentSpecWrapper>();

        // Get the latest pushed content spec
        try {
            final TranslatedContentSpecWrapper translatedContentSpec = EntityUtilities.getClosestTranslatedContentSpecById(providerFactory,
                    contentSpecId, contentSpecRevision);
            if (translatedContentSpec != null) {
                translatedContentSpecs.add(translatedContentSpec);
            } else {
                // If we don't have a translation then move onto the next content spec
                if (contentSpecRevision != null) {
                    log.info("Ignoring " + contentSpecId + "-" + contentSpecRevision + " because it doesn't have any translations.");
                } else {
                    log.info("Ignoring " + contentSpecId + " because it doesn't have any translations.");
                }
                return new HashSet<String>();
            }
        } catch (NotFoundException e) {
            // Do nothing as this is handled below
        }

        return getZanataIds(providerFactory, translatedContentSpecs);
    }

    /**
     * Get the Zanata IDs that represent a Collection of Content Specs and their Topics.
     *
     * @param providerFactory
     * @param translatedContentSpecs
     * @return The Set of Zanata IDs that represent the content specs and topics.
     */
    protected Set<String> getZanataIds(final DataProviderFactory providerFactory,
            final List<TranslatedContentSpecWrapper> translatedContentSpecs) {
        final TopicProvider topicProvider = providerFactory.getProvider(TopicProvider.class);
        final Set<String> zanataIds = new HashSet<String>();

        // Get the zanata ids for each content spec
        for (final TranslatedContentSpecWrapper translatedContentSpec : translatedContentSpecs) {
            zanataIds.add(translatedContentSpec.getZanataId());
            final CollectionWrapper<TranslatedCSNodeWrapper> translatedCSNodes = translatedContentSpec.getTranslatedNodes();

            log.info("Downloading topics...");
            final int showPercent = 10;
            final float total = translatedCSNodes.getItems().size();
            float current = 0;
            int lastPercent = 0;

            for (final TranslatedCSNodeWrapper translatedCSNode : translatedCSNodes.getItems()) {
                final CSNodeWrapper csNode = translatedCSNode.getCSNode();
                // Make sure the node is a topic
                if (EntityUtilities.isNodeATopic(csNode)) {
                    final TranslatedTopicWrapper pushedTopic = getTranslatedTopic(topicProvider, csNode.getEntityId(),
                            csNode.getEntityRevision(), translatedCSNode);

                    // If a pushed topic was found then add it
                    if (pushedTopic != null) {
                        zanataIds.add(pushedTopic.getZanataId());
                    }
                }

                // Add the info topic if one exists
                if (csNode.getInfoTopicNode() != null) {
                    final CSInfoNodeWrapper csNodeInfo = csNode.getInfoTopicNode();
                    final TranslatedTopicWrapper pushedTopic = getTranslatedTopic(topicProvider, csNodeInfo.getTopicId(),
                            csNodeInfo.getTopicRevision(), translatedCSNode);

                    // If a pushed topic was found then add it
                    if (pushedTopic != null) {
                        zanataIds.add(pushedTopic.getZanataId());
                    }
                }

                ++current;
                final int percent = Math.round(current / total * 100);
                if (percent - lastPercent >= showPercent) {
                    lastPercent = percent;
                    log.info("Downloading topics {}% Done", percent);
                }
            }
        }

        return zanataIds;
    }

    protected TranslatedTopicWrapper getTranslatedTopic(final TopicProvider topicProvider, final Integer topicId,
            final Integer topicRevision, final TranslatedCSNodeWrapper translatedCSNode) {
        final TopicWrapper topic = topicProvider.getTopic(topicId, topicRevision);

        // Try and see if it was pushed with a condition
        TranslatedTopicWrapper pushedTopic = EntityUtilities.returnPushedTranslatedTopic(topic, translatedCSNode);
        // If pushed topic is null then it means no condition was used
        if (pushedTopic == null) {
            pushedTopic = EntityUtilities.returnPushedTranslatedTopic(topic);
        }

        return pushedTopic;
    }

    private ZanataInterface initZanataInterface(final ZanataDetails zanataDetails) {
        if (cachedZanataInterface.containsKey(zanataDetails)) {
            return cachedZanataInterface.get(zanataDetails);
        } else {
            final ZanataInterface zanataInterface = new ZanataInterface(zanataRESTCallInterval, zanataDetails);

            // Initialise the locales to use
            final List<LocaleId> locales = initLocales(serverSettings.getLocales());
            zanataInterface.getLocaleManager().setLocales(new ArrayList<LocaleId>(locales));

            // Remove the default locale as it won't have any translations
            zanataInterface.getLocaleManager().removeLocale(new LocaleId(serverSettings.getDefaultLocale().getTranslationValue()));

            // Cache the interface and return
            cachedZanataInterface.put(zanataDetails, zanataInterface);
            return zanataInterface;
        }
    }

    private List<LocaleId> initLocales(final CollectionWrapper<LocaleWrapper> locales) {
        final List<LocaleId> retValue = new ArrayList<LocaleId>();

        // Get the Locales
        for (final LocaleWrapper locale : locales.getItems()) {
            retValue.add(LocaleId.fromJavaName(locale.getTranslationValue()));
        }

        return retValue;
    }

    private TranslationServerExtendedWrapper getExtendedTranslationServer(final ServerSettingsWrapper serverSettings,
            final TranslationServerWrapper translationServer) {
        for (final TranslationServerExtendedWrapper translationServerExtended : serverSettings.getTranslationServers().getItems()) {
            if (translationServer.getId().equals(translationServerExtended.getId())) {
                return translationServerExtended;
            }
        }

        return null;
    }
}
