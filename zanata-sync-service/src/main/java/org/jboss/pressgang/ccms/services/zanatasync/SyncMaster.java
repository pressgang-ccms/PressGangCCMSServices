package org.jboss.pressgang.ccms.services.zanatasync;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;
import org.jboss.pressgang.ccms.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.zanata.common.LocaleId;

/**
 * The class that actually does the synchronising
 *
 * @author Matthew Casperson
 */
public class SyncMaster extends BaseZanataSync {
    private static final Logger log = Logger.getLogger("ZanataSyncService");

    private TopicSync topicSync;
    private ContentSpecSync contentSpecSync;
    private ContentSpecTopicSync contentSpecTopicSync;

    public SyncMaster(final DataProviderFactory providerFactory, final ZanataInterface zanataInterface) {
        super(providerFactory, zanataInterface);
        try {
            topicSync = new TopicSync(providerFactory, zanataInterface);
            contentSpecSync = new ContentSpecSync(providerFactory, zanataInterface);
            contentSpecTopicSync = new ContentSpecTopicSync(providerFactory, zanataInterface);
        } catch (final Exception ex) {
            log.error("", ex);
        }
    }

    /**
     * Sync the translated resources
     *
     * @param zanataIds
     */
    @SuppressWarnings("deprecation")
    public void processZanataResources(final Set<String> zanataIds) {
        // Get a list of the locales available to sync with.
        final List<LocaleId> locales = getZanataInterface().getZanataLocales();

        processZanataResources(zanataIds, locales);
    }

    /**
     * Sync the translated resources
     *
     * @param zanataIds
     */
    @SuppressWarnings("deprecation")
    public void processZanataResources(final Set<String> zanataIds, final List<LocaleId> locales) {
        final Set<String> contentSpecZanataIds = new HashSet<String>();
        final Set<String> contentSpecTopicZanataIds = new HashSet<String>();
        final Set<String> topicZanataIds = new HashSet<String>();

        // iterate over the list and separate the Topic and Content Specs
        for (final String zanataId : zanataIds) {
            if (zanataId.startsWith("CS")) {
                contentSpecZanataIds.add(zanataId);
            } else if (zanataId.matches("^\\d+-\\d+-\\d+$")) {
                contentSpecTopicZanataIds.add(zanataId);
            } else {
                topicZanataIds.add(zanataId);
            }
        }

        log.info("Starting to sync...");

        topicSync.processZanataResources(topicZanataIds, locales);
        contentSpecTopicSync.processZanataResources(contentSpecTopicZanataIds, locales);
        contentSpecSync.processZanataResources(contentSpecZanataIds, locales);
    }
}
