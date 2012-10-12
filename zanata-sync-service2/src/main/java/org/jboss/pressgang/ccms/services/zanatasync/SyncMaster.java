package org.jboss.pressgang.ccms.services.zanatasync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.PathSegment;

import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.pressgang.ccms.contentspec.ContentSpec;
import org.jboss.pressgang.ccms.contentspec.structures.StringToCSNodeCollection;
import org.jboss.pressgang.ccms.contentspec.utils.ContentSpecUtilities;
import org.jboss.pressgang.ccms.rest.v1.client.PressGangCCMSProxyFactoryV1;
import org.jboss.pressgang.ccms.rest.v1.collections.RESTTranslatedTopicCollectionV1;
import org.jboss.pressgang.ccms.rest.v1.collections.RESTTranslatedTopicStringCollectionV1;
import org.jboss.pressgang.ccms.rest.v1.components.ComponentBaseTopicV1;
import org.jboss.pressgang.ccms.rest.v1.constants.CommonFilterConstants;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTStringConstantV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTopicV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTranslatedTopicStringV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTranslatedTopicV1;
import org.jboss.pressgang.ccms.rest.v1.exceptions.InternalProcessingException;
import org.jboss.pressgang.ccms.rest.v1.exceptions.InvalidParameterException;
import org.jboss.pressgang.ccms.rest.v1.expansion.ExpandDataDetails;
import org.jboss.pressgang.ccms.rest.v1.expansion.ExpandDataTrunk;
import org.jboss.pressgang.ccms.rest.v1.jaxrsinterfaces.RESTInterfaceV1;
import org.jboss.pressgang.ccms.utils.common.CollectionUtilities;
import org.jboss.pressgang.ccms.utils.common.ExceptionUtilities;
import org.jboss.pressgang.ccms.utils.common.XMLUtilities;
import org.jboss.pressgang.ccms.utils.constants.CommonConstants;
import org.jboss.pressgang.ccms.utils.structures.StringToNodeCollection;
import org.jboss.pressgang.ccms.zanata.ZanataConstants;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.jboss.pressgang.ccms.zanata.ZanataTranslation;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.specimpl.PathSegmentImpl;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.zanata.common.LocaleId;
import org.zanata.rest.dto.resource.Resource;
import org.zanata.rest.dto.resource.ResourceMeta;
import org.zanata.rest.dto.resource.TextFlow;
import org.zanata.rest.dto.resource.TextFlowTarget;
import org.zanata.rest.dto.resource.TranslationsResource;

import com.redhat.contentspec.processor.ContentSpecParser;

/**
 * The class that actually does the synchronising
 * 
 * @author Matthew Casperson
 * 
 */
public class SyncMaster {

    private static final String XML_ENCODING = "UTF-8";
    private static final Logger log = Logger.getLogger("SkynetZanataSyncService");

    /** Jackson object mapper */
    private final static ObjectMapper mapper = new ObjectMapper();

    /* Get the system properties */
    private static final String skynetServer = System.getProperty(CommonConstants.SKYNET_SERVER_SYSTEM_PROPERTY);
    private static final String zanataServer = System.getProperty(ZanataConstants.ZANATA_SERVER_PROPERTY);
    private static final String zanataToken = System.getProperty(ZanataConstants.ZANATA_TOKEN_PROPERTY);
    private static final String zanataUsername = System.getProperty(ZanataConstants.ZANATA_USERNAME_PROPERTY);
    private static final String zanataProject = System.getProperty(ZanataConstants.ZANATA_PROJECT_PROPERTY);
    private static final String zanataVersion = System.getProperty(ZanataConstants.ZANATA_PROJECT_VERSION_PROPERTY);
    private static Integer numLocales = 1;
    /** The total time it should take to sync with Zanata */
    private static Integer syncTime = 0;

    private ZanataInterface zanataInterface;

    /* Create a custom ObjectMapper to handle the mapping between the interfaces and the concrete classes */
    private RESTInterfaceV1 client;

    public SyncMaster() {
        try {

            RegisterBuiltin.register(ResteasyProviderFactory.getInstance());

            /* Exit if the system properties have not been set */
            if (!checkEnvironment())
                return;

            /* Get an instance of the zanata interface */
            zanataInterface = new ZanataInterface();

            /* Get in instance of the CCMS interface */
            client = PressGangCCMSProxyFactoryV1.create(skynetServer).getRESTClient();

            /* Get the existing zanata resources */
            final List<ResourceMeta> zanataResources = getZanataResources();

            /* Get the locales */
            final List<LocaleId> locales = getLocales();
            zanataInterface.getLocaleManager().setLocales(locales);

            /* Sync the zanata resources to the CCMS */
            processZanataResources(zanataResources);
            
        } catch (final Exception ex) {
            log.error(ex.toString());
        }
    }

    /** Get the expansion required for the translatedTopics */
    private String getExpansion() {
        final ExpandDataTrunk expand = new ExpandDataTrunk();
        final ExpandDataTrunk expandTranslatedTopic = new ExpandDataTrunk(new ExpandDataDetails("translatedTopics"));
        final ExpandDataTrunk expandTopicTags = new ExpandDataTrunk(new ExpandDataDetails(RESTTopicV1.TAGS_NAME));
        final ExpandDataTrunk expandTopicTranslationStrings = new ExpandDataTrunk(new ExpandDataDetails(
                RESTTranslatedTopicV1.TRANSLATEDTOPICSTRING_NAME));

        expandTranslatedTopic.setBranches(CollectionUtilities.toArrayList(expandTopicTranslationStrings, expandTopicTags));
        expand.setBranches(CollectionUtilities.toArrayList(expandTranslatedTopic));

        try {
            return mapper.writeValueAsString(expand);
        } catch (final Exception ex) {
            return "";
        }
    }

    private List<LocaleId> getLocales() throws InvalidParameterException, InternalProcessingException {

        /* Get the Locale constants */
        final RESTStringConstantV1 localeConstant = client
                .getJSONStringConstant(CommonConstants.LOCALES_STRING_CONSTANT_ID, "");
        final List<String> locales = CollectionUtilities.replaceStrings(
                CollectionUtilities.sortAndReturn(CollectionUtilities.toArrayList(localeConstant.getValue().split(
                        "[\\s\r\n]*,[\\s\r\n]*"))), "_", "-");
        final List<LocaleId> localeIds = new ArrayList<LocaleId>();
        for (final String locale : locales) {
            localeIds.add(LocaleId.fromJavaName(locale));
        }

        return localeIds;
    }

    /**
     * Sync the translated resources
     * 
     * @param zanataResources
     */
    @SuppressWarnings("deprecation")
    private void processZanataResources(final List<ResourceMeta> zanataResources) {

        final float resourceSize = zanataResources.size();
        float resourceProgress = 0;

        for (final ResourceMeta resource : zanataResources) {
            try {

                /* Work out progress */
                float progress = Math.round(resourceProgress / resourceSize * 100.0f);
                ++resourceProgress;

                /* Get the translated topics in the CCMS */
                final PathSegment query = new PathSegmentImpl("query", false);
                query.getMatrixParameters().add(CommonFilterConstants.ZANATA_IDS_FILTER_VAR, resource.getName());

                /* get the translated topic in the CCMS */
                final RESTTranslatedTopicCollectionV1 translatedTopics = client.getJSONTranslatedTopicsWithQuery(query,
                        getExpansion());

                /*
                 * Get a list of the locales available to sync with.
                 */
                final List<LocaleId> locales = zanataInterface.getZanataLocales();

                final int localesSise = locales.size();
                int localesProgress = 0;

                for (final LocaleId locale : locales) {

                    try {
                        /* Work out progress */
                        int thisLocalesProgress = Math
                                .round(progress + (localesProgress / localesSise * 100.0f / resourceSize));
                        ++localesProgress;

                        log.info(thisLocalesProgress + "% Synchronising " + resource.getName() + " for locale "
                                + locale.toString());

                        if (zanataInterface.getTranslationsExists(resource.getName(), locale)) {
                            /* find a translation */
                            final TranslationsResource translationsResource = zanataInterface.getTranslations(
                                    resource.getName(), locale);
                            /* and find the original resource */
                            final Resource originalTextResource = zanataInterface.getZanataResource(resource.getName());

                            /* The translated topic to store the results */
                            RESTTranslatedTopicV1 translatedTopic = null;
                            boolean newTranslation = false;

                            if (translatedTopics.returnItems().size() != 0) {

                                /* Find the original translation (the query results will return all locales */
                                for (final RESTTranslatedTopicV1 transTopic : translatedTopics.returnItems()) {
                                    if (transTopic.getLocale().equals(locale.toString())) {
                                        translatedTopic = transTopic;
                                        break;
                                    }
                                }

                            } else {
                                final String[] zanataNameSplit = resource.getName().split("-");
                                final Integer topicId = Integer.parseInt(zanataNameSplit[0]);
                                final Integer topicRevision = Integer.parseInt(zanataNameSplit[1]);
                                
                                // We need the historical topic here as well.
                                final RESTTopicV1 historicalTopic = client.getJSONTopicRevision(topicId, topicRevision, "");
                                
                                translatedTopic = new RESTTranslatedTopicV1();
                                translatedTopic.setLocale(locale.toString());
                                translatedTopic.setTopicId(topicId);
                                translatedTopic.setTopicRevision(topicRevision);
                                translatedTopic.setTopic(historicalTopic);
                                
                                newTranslation = true;
                            }

                            if (translatedTopic != null) {

                                /* Set the current xml of the translated topic data so we can see if it has changed */
                                final String translatedXML = translatedTopic.getXml() == null ? "" : translatedTopic.getXml();

                                /*
                                 * a mapping of the original strings to their translations
                                 */
                                final Map<String, ZanataTranslation> translationDetails = new HashMap<String, ZanataTranslation>();
                                final Map<String, String> translations = new HashMap<String, String>();

                                final List<TextFlowTarget> textFlowTargets = translationsResource.getTextFlowTargets();
                                final List<TextFlow> textFlows = originalTextResource.getTextFlows();

                                double wordCount = 0;
                                double totalWordCount = 0;

                                /* map the translation to the original resource */
                                for (final TextFlow textFlow : textFlows) {
                                    for (final TextFlowTarget textFlowTarget : textFlowTargets) {
                                        if (textFlowTarget.getResId().equals(textFlow.getId())) {
                                            translationDetails
                                                    .put(textFlow.getContent(), new ZanataTranslation(textFlowTarget));
                                            translations.put(textFlow.getContent(), textFlowTarget.getContent());
                                            wordCount += textFlow.getContent().split(" ").length;
                                            break;
                                        }
                                    }
                                    totalWordCount += textFlow.getContent().split(" ").length;
                                }

                                /* Set the translation completion status */
                                translatedTopic.explicitSetTranslationPercentage((int) (wordCount / totalWordCount * 100.0f));

                                final boolean changed;
                                if (ComponentBaseTopicV1
                                        .hasTag(translatedTopic.getTopic(), CommonConstants.CONTENT_SPEC_TAG_ID)) {
                                    changed = processContentSpec(translatedTopic, translationDetails, translations);
                                } else {
                                    changed = processTopic(translatedTopic, translationDetails, translations);
                                }

                                /* Only save the data if the content has changed */
                                if (newTranslation || changed || !translatedXML.equals(translatedTopic.getXml())) {
                                    /*
                                     * make a note of the TranslatedTopicData entities that have been changed, so we can render
                                     * them
                                     */
                                    final RESTTranslatedTopicV1 updatedTranslatedTopic = new RESTTranslatedTopicV1();
                                    updatedTranslatedTopic.setId(translatedTopic.getId());
                                    updatedTranslatedTopic.explicitSetLocale(translatedTopic.getLocale());
                                    updatedTranslatedTopic.explicitSetTopicId(translatedTopic.getTopicId());
                                    updatedTranslatedTopic.explicitSetTopicRevision(translatedTopic.getTopicRevision());
                                    updatedTranslatedTopic.explicitSetXml(translatedTopic.getXml());
                                    updatedTranslatedTopic.explicitSetTranslatedTopicString_OTM(translatedTopic
                                            .getTranslatedTopicStrings_OTM());

                                    /* Save all the changed Translated Topic Datas */
                                    if (newTranslation) {
                                        client.createJSONTranslatedTopic("", updatedTranslatedTopic);
                                    } else {
                                        client.updateJSONTranslatedTopic("", updatedTranslatedTopic);
                                    }

                                    log.info(thisLocalesProgress + "% Finished synchronising translations for "
                                            + resource.getName() + " locale " + locale);
                                }
                                else
                                {
                                    log.info(thisLocalesProgress + "% No changes were found for " + resource.getName() + " locale "
                                        + locale);
                                }
                            }

                        } else {
                            log.info(thisLocalesProgress + "% No translations found for " + resource.getName() + " locale "
                                    + locale);
                        }
                    } catch (final Exception ex) {
                        /* Error with the locale */
                        log.error(ExceptionUtilities.getStackTrace(ex));
                    }

                }

            } catch (final Exception ex) {
                /* Error with the resource */
                log.error(ExceptionUtilities.getStackTrace(ex));
            }
        }

        log.info(resourceProgress + "% Finished synchronising all translations");
    }

    /**
     * Processes a Translated Topic and updates or removes the translation strings in that topic to match the new values pulled
     * down from Zanata. It also updates the XML using the strings pulled from Zanata.
     * 
     * @param translatedTopic The Translated Topic to update.
     * @param translationDetails The mapping of Original Translation strings to Zanata Translation information.
     * @param translations A direct mapping of Original strings to Translation strings.
     * @return True if anything in the translated topic changed, otherwise false.
     * @throws SAXException Thrown if the XML in the historical topic has invalid XML and can't be parsed.
     */
    @SuppressWarnings("deprecation")
    private boolean processTopic(final RESTTranslatedTopicV1 translatedTopic,
            final Map<String, ZanataTranslation> translationDetails, final Map<String, String> translations)
            throws SAXException {
        /* The collection used to modify the TranslatedTopicString entities */
        final RESTTranslatedTopicStringCollectionV1 translatedTopicStrings = new RESTTranslatedTopicStringCollectionV1();
        boolean changed = false;

        /* get a Document from the stored historical XML */
        final Document xml = XMLUtilities.convertStringToDocument(translatedTopic.getTopic().getXml());
        if (xml != null) {

            final List<StringToNodeCollection> stringToNodeCollectionsV2 = XMLUtilities.getTranslatableStringsV2(xml, false);
            final List<StringToNodeCollection> stringToNodeCollectionsV1 = XMLUtilities.getTranslatableStringsV1(xml, false);

            /* Used to hold the list of StringToNode's that match the translations pulled form Zanata */
            final List<StringToNodeCollection> stringToNodeCollections = new ArrayList<StringToNodeCollection>();
            /* Used to hold a duplicate list of StringToNode's so we can remove the originals from the above List */
            final List<StringToNodeCollection> tempStringToNodeCollection = new ArrayList<StringToNodeCollection>();

            /* Add any StringToNode's that match the original translations */
            for (final StringToNodeCollection stringToNodeCollectionV2 : stringToNodeCollectionsV2) {
                for (final String originalString : translations.keySet()) {
                    if (originalString.equals(stringToNodeCollectionV2.getTranslationString())) {
                        stringToNodeCollections.add(stringToNodeCollectionV2);
                        tempStringToNodeCollection.add(stringToNodeCollectionV2);
                    }
                }
            }

            /* Add any StringToNode's that match the original translations and weren't added already by the V2 method */
            for (final StringToNodeCollection stringToNodeCollectionV1 : stringToNodeCollectionsV1) {
                for (final String originalString : translations.keySet()) {
                    if (originalString.equals(stringToNodeCollectionV1.getTranslationString())
                            && !stringToNodeCollections.contains(stringToNodeCollectionV1)) {
                        stringToNodeCollections.add(stringToNodeCollectionV1);
                        tempStringToNodeCollection.add(stringToNodeCollectionV1);
                    }
                }
            }

            // Remove or update any existing translation strings
            if (translatedTopic.getTranslatedTopicStrings_OTM() != null
                    && translatedTopic.getTranslatedTopicStrings_OTM().returnItems() != null) {
                for (final RESTTranslatedTopicStringV1 existingString : translatedTopic.getTranslatedTopicStrings_OTM()
                        .returnItems()) {
                    boolean found = false;

                    for (final StringToNodeCollection original : stringToNodeCollections) {
                        final String originalText = original.getTranslationString();

                        if (existingString.getOriginalString().equals(originalText)) {
                            found = true;
                            tempStringToNodeCollection.remove(original);

                            final ZanataTranslation translation = translationDetails.get(originalText);

                            // Check the translations still match
                            if (!translation.getTranslation().equals(existingString.getTranslatedString())
                                    || translation.isFuzzy() != existingString.getFuzzyTranslation()) {
                                changed = true;

                                existingString.explicitSetTranslatedString(translation.getTranslation());
                                existingString.explicitSetFuzzyTranslation(translation.isFuzzy());

                                translatedTopicStrings.addUpdateItem(existingString);
                            }
                        }
                    }

                    // If the original String no longer exists then remove it (this shouldn't happen)
                    if (!found) {
                        changed = true;

                        translatedTopicStrings.addRemoveItem(existingString);
                    }
                }
            }

            /* save the new strings to TranslatedTopicString entities */
            for (final StringToNodeCollection original : tempStringToNodeCollection) {
                final String originalText = original.getTranslationString();
                final ZanataTranslation translation = translationDetails.get(originalText);

                if (translation != null) {
                    changed = true;

                    final RESTTranslatedTopicStringV1 translatedTopicString = new RESTTranslatedTopicStringV1();
                    translatedTopicString.explicitSetOriginalString(originalText);
                    translatedTopicString.explicitSetTranslatedString(translation.getTranslation());
                    translatedTopicString.explicitSetFuzzyTranslation(translation.isFuzzy());

                    translatedTopicStrings.addNewItem(translatedTopicString);
                }
            }

            /* Set any strings to be Added or Removed */
            if (translatedTopicStrings.getItems() != null && !translatedTopicStrings.getItems().isEmpty()) {
                translatedTopic.explicitSetTranslatedTopicString_OTM(translatedTopicStrings);
            }

            /*
             * replace the translated strings, and save the result into the TranslatedTopicData entity
             */
            if (xml != null) {
                XMLUtilities.replaceTranslatedStrings(xml, translations, stringToNodeCollections);
                translatedTopic.explicitSetXml(XMLUtilities.convertDocumentToString(xml, XML_ENCODING));
            }
        }

        return changed;
    }

    /**
     * Processes a Translated Topic and updates or removes the translation strings in that topic to match the new values pulled
     * down from Zanata. It also updates the Content Spec using the strings pulled from Zanata.
     * 
     * @param translatedTopic The Translated Topic to update.
     * @param translationDetails The mapping of Original Translation strings to Zanata Translation information.
     * @param translations A direct mapping of Original strings to Translation strings.
     * @return True if anything in the translated topic changed, otherwise false.
     * @throws Exception Thrown if there is an error in the Content Specification syntax.
     */
    protected boolean processContentSpec(final RESTTranslatedTopicV1 translatedTopic,
            final Map<String, ZanataTranslation> translationDetails, final Map<String, String> translations) throws Exception {
        /* The collection used to modify the TranslatedTopicString entities */
        final RESTTranslatedTopicStringCollectionV1 translatedTopicStrings = new RESTTranslatedTopicStringCollectionV1();
        boolean changed = false;

        /* Parse the Content Spec stored in the XML Field */
        final ContentSpecParser parser = new ContentSpecParser(skynetServer);

        /*
         * replace the translated strings, and save the result into the TranslatedTopicData entity
         */
        if (parser.parse(translatedTopic.getTopic().getXml())) {
            final ContentSpec spec = parser.getContentSpec();

            if (spec != null) {
                final List<StringToCSNodeCollection> stringToNodeCollections = ContentSpecUtilities.getTranslatableStrings(
                        spec, false);

                /* create a temporary collection that we can freely remove items from */
                final List<StringToCSNodeCollection> tempStringToNodeCollection = new ArrayList<StringToCSNodeCollection>();
                for (final StringToCSNodeCollection stringToNodeCollection : stringToNodeCollections) {
                    tempStringToNodeCollection.add(stringToNodeCollection);
                }

                // Remove or update any existing translation strings
                if (translatedTopic.getTranslatedTopicStrings_OTM() != null
                        && translatedTopic.getTranslatedTopicStrings_OTM().returnItems() != null) {
                    for (final RESTTranslatedTopicStringV1 existingString : translatedTopic.getTranslatedTopicStrings_OTM()
                            .returnItems()) {
                        boolean found = false;

                        for (final StringToCSNodeCollection original : tempStringToNodeCollection) {
                            final String originalText = original.getTranslationString();

                            if (existingString.getOriginalString().equals(originalText)) {
                                found = true;
                                tempStringToNodeCollection.remove(original);

                                final ZanataTranslation translation = translationDetails.get(originalText);

                                // Check the translations still match
                                if (!translation.getTranslation().equals(existingString.getTranslatedString())
                                        || translation.isFuzzy() != existingString.getFuzzyTranslation()) {
                                    changed = true;

                                    existingString.explicitSetTranslatedString(translation.getTranslation());
                                    existingString.explicitSetFuzzyTranslation(translation.isFuzzy());

                                    translatedTopicStrings.addUpdateItem(existingString);
                                }
                            }
                        }

                        // If the original String no longer exists then remove it (this shouldn't happen)
                        if (!found) {
                            changed = true;

                            translatedTopicStrings.addRemoveItem(existingString);
                        }
                    }
                }

                /* save the strings to TranslatedTopicString entities */
                for (final StringToCSNodeCollection original : tempStringToNodeCollection) {
                    final String originalText = original.getTranslationString();
                    final ZanataTranslation translation = translationDetails.get(originalText);

                    if (translation != null) {
                        final RESTTranslatedTopicStringV1 translatedTopicString = new RESTTranslatedTopicStringV1();
                        translatedTopicString.explicitSetOriginalString(originalText);
                        translatedTopicString.explicitSetTranslatedString(translation.getTranslation());
                        translatedTopicString.explicitSetFuzzyTranslation(translation.isFuzzy());

                        translatedTopicStrings.addNewItem(translatedTopicString);
                    }
                }

                /* Set any strings to be Added or Removed */
                if (translatedTopicStrings.returnItems() != null && !translatedTopicStrings.returnItems().isEmpty()) {
                    translatedTopic.explicitSetTranslatedTopicString_OTM(translatedTopicStrings);
                }

                ContentSpecUtilities.replaceTranslatedStrings(spec, translations);
                translatedTopic.explicitSetXml(spec.toString());
            }
        }

        return changed;
    }

    /**
     * Gets the zanata translated resources
     * 
     * @return
     */
    private List<ResourceMeta> getZanataResources() {
        /* Get the Zanata resources */
        final List<ResourceMeta> zanataResources = zanataInterface.getZanataResources();

        final int numberZanataTopics = zanataResources.size();

        System.out.println("Found " + numberZanataTopics + " topics in Zanata.");

        return zanataResources;
    }

    /**
     * 
     * @return true if all environment variables were set, false otherwise
     */
    private boolean checkEnvironment() {
        log.info("Skynet REST: " + skynetServer);
        log.info("Zanata Server: " + zanataServer);
        log.info("Zanata Username: " + zanataUsername);
        log.info("Zanata Token: " + zanataToken);
        log.info("Zanata Project: " + zanataProject);
        log.info("Zanata Project Version: " + zanataVersion);
        log.info("Total Sync Time: " + syncTime / 1000.0 + " seconds or " + syncTime / 1000.0 / 60.0 + " minutes");
        log.info("Estimated Number Of Locales: " + numLocales);

        /* Some sanity checking */
        if (skynetServer == null || skynetServer.trim().isEmpty() || zanataServer == null || zanataServer.trim().isEmpty()
                || zanataToken == null || zanataToken.trim().isEmpty() || zanataUsername == null
                || zanataUsername.trim().isEmpty() || zanataProject == null || zanataProject.trim().isEmpty()
                || zanataVersion == null || zanataVersion.trim().isEmpty()) {
            log.error("The " + CommonConstants.SKYNET_SERVER_SYSTEM_PROPERTY + ", " + ZanataConstants.ZANATA_SERVER_PROPERTY
                    + ", " + ZanataConstants.ZANATA_TOKEN_PROPERTY + ", " + ZanataConstants.ZANATA_USERNAME_PROPERTY + ", "
                    + ZanataConstants.ZANATA_SERVER_PROPERTY + " and " + ZanataConstants.ZANATA_PROJECT_VERSION_PROPERTY
                    + " system properties need to be defined.");
            return false;
        }

        return true;
    }
}
