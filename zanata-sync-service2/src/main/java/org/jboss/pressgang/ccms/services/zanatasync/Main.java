package org.jboss.pressgang.ccms.services.zanatasync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.jboss.pressgang.ccms.contentspec.ContentSpec;
import org.jboss.pressgang.ccms.contentspec.SpecTopic;
import org.jboss.pressgang.ccms.contentspec.utils.CSTransformer;
import org.jboss.pressgang.ccms.provider.ContentSpecProvider;
import org.jboss.pressgang.ccms.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.provider.RESTProviderFactory;
import org.jboss.pressgang.ccms.provider.StringConstantProvider;
import org.jboss.pressgang.ccms.provider.TopicProvider;
import org.jboss.pressgang.ccms.utils.common.CollectionUtilities;
import org.jboss.pressgang.ccms.utils.common.DocBookUtilities;
import org.jboss.pressgang.ccms.utils.constants.CommonConstants;
import org.jboss.pressgang.ccms.wrapper.ContentSpecWrapper;
import org.jboss.pressgang.ccms.wrapper.StringConstantWrapper;
import org.jboss.pressgang.ccms.wrapper.TopicWrapper;
import org.jboss.pressgang.ccms.zanata.ZanataConstants;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.zanata.common.LocaleId;
import org.zanata.rest.dto.resource.ResourceMeta;

public class Main {
    private static final Logger log = LoggerFactory.getLogger("ZanataSyncService");

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
    private static final String MIN_ZANATA_CALL_INTERVAL = System.getProperty(ZanataConstants.MIN_ZANATA_CALL_INTERNAL_PROPERTY,
            DEFAULT_ZANATA_CALL_INTERVAL.toString());

    @Parameter(names = {"--topics", "-t"}, description = "Sync a single or list of Topics from Zanata to PressGang.", variableArity = true)
    private List<String> topicIds = new ArrayList<String>();

    @Parameter(names = {"--all", "-a"}, description = "Sync all documents in Zanata to PressGang.")
    private boolean syncAll = false;

    @Parameter(names = {"--content-specs", "-c"}, description = "Sync a single or list of Content Specs from Zanata to PressGang.",
            variableArity = true)
    private List<String> contentSpecIds = new ArrayList<String>();

    @Parameter(names = "--locales", variableArity = true, converter = LocaleIdConverter.class)
    private List<LocaleId> locales = new ArrayList<LocaleId>();

    /**
     * The minimum amount of time in seconds between calls to the Zanata REST API
     */
    private Double zanataRESTCallInterval = null;
    private ZanataInterface zanataInterface = null;
    private DataProviderFactory providerFactory = null;

    public static void main(final String[] args) {
        final Main main = new Main();
        final JCommander jCommander = new JCommander(main, args);
        main.setUp();
        main.process();
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

        providerFactory = RESTProviderFactory.create(PRESS_GANG_SERVER);
        zanataInterface = new ZanataInterface(zanataRESTCallInterval);

        // Get the possible locales from the server
        final List<LocaleId> locales = getLocales(providerFactory);
        zanataInterface.getLocaleManager().setLocales(locales);

        // Remove the default locale as it won't have any translations
        zanataInterface.getLocaleManager().removeLocale(new LocaleId(CommonConstants.DEFAULT_LOCALE));
    }

    private void process() {
        final Set<String> zanataResources = new HashSet<String>();
        final BaseZanataSync zanataSync = new SyncMaster(providerFactory, zanataInterface);
        if (!syncAll) {
            // Add the content spec and it's topics
            if (!contentSpecIds.isEmpty()) {
                for (final String contentSpecStringId : contentSpecIds) {
                    zanataResources.addAll(getContentSpecZanataResources(providerFactory, contentSpecStringId));
                }
            }

            // Add any topics to be synced
            if (!topicIds.isEmpty()) {
                zanataResources.addAll(topicIds);
            }
        } else {
            zanataResources.addAll(getAllZanataResources(zanataInterface));
        }

        // Sync the zanata resources to the CCMS
        zanataSync.processZanataResources(zanataResources, this.locales.isEmpty() ? zanataInterface.getZanataLocales() : this.locales);
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
        log.info("Default Locale: " + CommonConstants.DEFAULT_LOCALE);
        log.info("Rate Limiting: " + zanataRESTCallInterval + " seconds per REST call");

        // Some sanity checking
        if (PRESS_GANG_SERVER == null || PRESS_GANG_SERVER.trim().isEmpty() || ZANATA_SERVER == null || ZANATA_SERVER.trim().isEmpty() ||
                ZANATA_TOKEN == null || ZANATA_TOKEN.trim().isEmpty() || ZANATA_USERNAME == null || ZANATA_USERNAME.trim().isEmpty() ||
                ZANATA_PROJECT == null || ZANATA_PROJECT.trim().isEmpty() || ZANATA_VERSION == null || ZANATA_VERSION.trim().isEmpty()) {
            log.error(
                    "The " + CommonConstants.PRESS_GANG_REST_SERVER_SYSTEM_PROPERTY + ", " + ZanataConstants.ZANATA_SERVER_PROPERTY + ", " +
                            "" + ZanataConstants.ZANATA_TOKEN_PROPERTY + ", " + ZanataConstants.ZANATA_USERNAME_PROPERTY + ", " +
                            "" + ZanataConstants.ZANATA_SERVER_PROPERTY + " and " + ZanataConstants.ZANATA_PROJECT_VERSION_PROPERTY + " " +
                            "system properties need to be defined.");
            return false;
        }

        return true;
    }

    private List<LocaleId> getLocales(final DataProviderFactory providerFactory) {
        // Get the Locale constants
        final StringConstantWrapper localeConstant = providerFactory.getProvider(StringConstantProvider.class).getStringConstant(
                CommonConstants.LOCALES_STRING_CONSTANT_ID);
        final List<String> locales = CollectionUtilities.replaceStrings(CollectionUtilities.sortAndReturn(
                CollectionUtilities.toArrayList(localeConstant.getValue().split("[\\s\r\n]*,[\\s\r\n]*"))), "_", "-");
        final List<LocaleId> localeIds = new ArrayList<LocaleId>();
        for (final String locale : locales) {
            localeIds.add(LocaleId.fromJavaName(locale));
        }

        return localeIds;
    }

    /**
     * Gets the zanata translated resources
     *
     * @return
     */
    private Set<String> getAllZanataResources(final ZanataInterface zanataInterface) {
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

    private Set<String> getContentSpecZanataResources(final DataProviderFactory providerFactory, final String contentSpecIdString) {
        final ContentSpecProvider contentSpecProvider = providerFactory.getProvider(ContentSpecProvider.class);
        final TopicProvider topicProvider = providerFactory.getProvider(TopicProvider.class);

        String[] vars = contentSpecIdString.split("-");
        Integer contentSpecId = Integer.parseInt(vars[0]);
        Integer contentSpecRevision = Integer.parseInt(vars[1]);

        final ContentSpecWrapper contentSpecEntity = contentSpecProvider.getContentSpec(contentSpecId, contentSpecRevision);
        final ContentSpec contentSpec = new CSTransformer().transform(contentSpecEntity, providerFactory);

        final Set<String> zanataIds = new HashSet<String>();

        final List<SpecTopic> specTopics = contentSpec.getSpecTopics();
        for (final SpecTopic specTopic : specTopics) {
            final TopicWrapper topic = topicProvider.getTopic(specTopic.getDBId(), specTopic.getRevision());
            specTopic.setTopic(topic);
            zanataIds.add(getTopicZanataId(specTopic));
        }

        return zanataIds;
    }

    /**
     * Gets the Zanata ID for a topic based on whether or not the topic has any conditional text.
     *
     * @param specTopic The topic to create the Zanata ID for.
     * @return The unique Zanata ID that can be used to create a document in Zanata.
     */
    private String getTopicZanataId(final SpecTopic specTopic) {
        final TopicWrapper topic = (TopicWrapper) specTopic.getTopic();
        Map<Node, List<String>> conditionNodes = DocBookUtilities.getConditionNodes(specTopic.getXmlDocument());

        // Create the zanata id based on whether a condition has been specified or not
        final String zanataId;
        if (specTopic.getConditionStatement(true) != null && !conditionNodes.isEmpty()) {
            zanataId = topic.getId() + "-" + topic.getRevision() + "-" + specTopic.getUniqueId();
        } else {
            zanataId = topic.getId() + "-" + topic.getRevision();
        }

        return zanataId;
    }
}
