package org.jboss.pressgang.ccms.services.zanatasync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.pressgang.ccms.contentspec.utils.EntityUtilities;
import org.jboss.pressgang.ccms.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.provider.TopicProvider;
import org.jboss.pressgang.ccms.provider.exception.NotFoundException;
import org.jboss.pressgang.ccms.wrapper.CSNodeWrapper;
import org.jboss.pressgang.ccms.wrapper.TopicWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedCSNodeWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedContentSpecWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedTopicWrapper;
import org.jboss.pressgang.ccms.wrapper.collection.CollectionWrapper;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zanata.common.LocaleId;
import org.zanata.rest.dto.resource.ResourceMeta;

public class ZanataSyncService {
    private static final Logger log = LoggerFactory.getLogger("ZanataSyncService");

    private final DataProviderFactory providerFactory;
    private final ZanataInterface zanataInterface;

    public ZanataSyncService(final DataProviderFactory providerFactory, final ZanataInterface zanataInterface) {
        this.providerFactory = providerFactory;
        this.zanataInterface = zanataInterface;
    }

    public void syncAll(final List<LocaleId> locales) {
        final Set<String> zanataResources = getAllZanataResources(zanataInterface);
        final BaseZanataSync zanataSync = new SyncMaster(providerFactory, zanataInterface);

        // Sync the zanata resources to the CCMS
        zanataSync.processZanataResources(zanataResources,
                locales == null || locales.isEmpty() ? zanataInterface.getZanataLocales() : locales);
    }

    public void sync(final Set<String> contentSpecIds, final Set<String> topicIds, final List<LocaleId> locales) {
        final BaseZanataSync zanataSync = new SyncMaster(providerFactory, zanataInterface);

        // Sync all the topics
        if (topicIds != null && !topicIds.isEmpty()) {
            // Sync the zanata resources to the CCMS
            zanataSync.processZanataResources(topicIds,
                    locales == null || locales.isEmpty() ? zanataInterface.getZanataLocales() : locales);
        }

        // Sync each content spec one at a time
        if (contentSpecIds != null && !contentSpecIds.isEmpty()) {
            for (final String contentSpecIdString : contentSpecIds) {
                final Set<String> zanataResources = getContentSpecZanataResource(providerFactory, contentSpecIdString);

                if (zanataResources != null && !zanataResources.isEmpty()) {
                    log.info("Syncing " + zanataResources.size() + " translations for content spec " + contentSpecIdString + ".");
                    // Sync the zanata resources to the CCMS
                    zanataSync.processZanataResources(zanataResources,
                            locales == null || locales.isEmpty() ? zanataInterface.getZanataLocales() : locales);
                }
            }
        }
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
     * @param contentSpecIdString The Content Spec ID to sync.
     * @return A Set of Zanata IDs that represent the topics to be synced from the list of Content Specs.
     */
    protected Set<String> getContentSpecZanataResource(final DataProviderFactory providerFactory, final String contentSpecIdString) {
        final List<TranslatedContentSpecWrapper> translatedContentSpecs = new ArrayList<TranslatedContentSpecWrapper>();
        final String[] vars = contentSpecIdString.split("-");
        final Integer contentSpecId = Integer.parseInt(vars[0]);
        final Integer contentSpecRevision = vars.length > 1 ? Integer.parseInt(vars[1]) : null;

        // Get the latest pushed content spec
        try {
            final TranslatedContentSpecWrapper translatedContentSpec = EntityUtilities.getClosestTranslatedContentSpecById(providerFactory,
                    contentSpecId, contentSpecRevision);
            if (translatedContentSpec != null) {
                translatedContentSpecs.add(translatedContentSpec);
            } else {
                // If we don't have a translation then move onto the next content spec
                log.info("Ignoring " + contentSpecIdString + " because it doesn't have any translations.");
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
            for (final TranslatedCSNodeWrapper translatedCSNode : translatedCSNodes.getItems()) {
                final CSNodeWrapper csNode = translatedCSNode.getCSNode();
                // Make sure the node is a topic
                if (EntityUtilities.isNodeATopic(csNode)) {
                    final TopicWrapper topic = topicProvider.getTopic(csNode.getEntityId(), csNode.getEntityRevision());

                    // Try and see if it was pushed with a condition
                    TranslatedTopicWrapper pushedTopic = EntityUtilities.returnPushedTranslatedTopic(topic, translatedCSNode);
                    // If pushed topic is null then it means no condition was used
                    if (pushedTopic == null) {
                        pushedTopic = EntityUtilities.returnPushedTranslatedTopic(topic);
                    }

                    // If a pushed topic was found then add it
                    if (pushedTopic != null) {
                        zanataIds.add(pushedTopic.getZanataId());
                    }
                }
            }
        }

        return zanataIds;
    }
}
