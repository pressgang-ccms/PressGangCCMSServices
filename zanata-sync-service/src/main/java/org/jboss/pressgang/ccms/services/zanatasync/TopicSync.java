package org.jboss.pressgang.ccms.services.zanatasync;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.jboss.pressgang.ccms.contentspec.structures.XMLFormatProperties;
import org.jboss.pressgang.ccms.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.provider.StringConstantProvider;
import org.jboss.pressgang.ccms.provider.TopicProvider;
import org.jboss.pressgang.ccms.provider.TranslatedTopicProvider;
import org.jboss.pressgang.ccms.provider.TranslatedTopicStringProvider;
import org.jboss.pressgang.ccms.rest.v1.constants.CommonFilterConstants;
import org.jboss.pressgang.ccms.rest.v1.query.RESTTranslatedTopicQueryBuilderV1;
import org.jboss.pressgang.ccms.utils.common.CollectionUtilities;
import org.jboss.pressgang.ccms.utils.common.DocBookUtilities;
import org.jboss.pressgang.ccms.utils.common.TopicUtilities;
import org.jboss.pressgang.ccms.utils.common.XMLUtilities;
import org.jboss.pressgang.ccms.utils.constants.CommonConstants;
import org.jboss.pressgang.ccms.utils.structures.StringToNodeCollection;
import org.jboss.pressgang.ccms.wrapper.ServerSettingsWrapper;
import org.jboss.pressgang.ccms.wrapper.StringConstantWrapper;
import org.jboss.pressgang.ccms.wrapper.TopicWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedTopicStringWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedTopicWrapper;
import org.jboss.pressgang.ccms.wrapper.collection.CollectionWrapper;
import org.jboss.pressgang.ccms.wrapper.collection.UpdateableCollectionWrapper;
import org.jboss.pressgang.ccms.zanata.NotModifiedException;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.jboss.pressgang.ccms.zanata.ZanataTranslation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.zanata.common.LocaleId;
import org.zanata.rest.dto.resource.Resource;
import org.zanata.rest.dto.resource.TextFlow;
import org.zanata.rest.dto.resource.TextFlowTarget;
import org.zanata.rest.dto.resource.TranslationsResource;

public class TopicSync extends BaseZanataSync {
    private static int MAX_DOWNLOAD_SIZE = 250;
    private static final Logger log = LoggerFactory.getLogger(TopicSync.class);
    protected final XMLFormatProperties xmlFormatProperties = new XMLFormatProperties();
    private final int contentSpecTagId;

    public TopicSync(final DataProviderFactory providerFactory, final ZanataInterface zanataInterface,
            final ServerSettingsWrapper serverSettings) {
        super(providerFactory, zanataInterface);

        contentSpecTagId = serverSettings.getEntities().getContentSpecTagId();
        final StringConstantWrapper xmlElementsProperties = providerFactory.getProvider(StringConstantProvider.class).getStringConstant(
                serverSettings.getEntities().getXmlFormattingStringConstantId());

        /*
         * Get the XML formatting details. These are used to pretty-print the XML when it is converted into a String.
         */
        final Properties prop = new Properties();
        try {
            prop.load(new StringReader(xmlElementsProperties.getValue()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        final String verbatimElementsString = prop.getProperty(CommonConstants.VERBATIM_XML_ELEMENTS_PROPERTY_KEY);
        final String inlineElementsString = prop.getProperty(CommonConstants.INLINE_XML_ELEMENTS_PROPERTY_KEY);
        final String contentsInlineElementsString = prop.getProperty(CommonConstants.CONTENTS_INLINE_XML_ELEMENTS_PROPERTY_KEY);

        xmlFormatProperties.setVerbatimElements(CollectionUtilities.toArrayList(verbatimElementsString.split("[\\s]*,[\\s]*")));
        xmlFormatProperties.setInlineElements(CollectionUtilities.toArrayList(inlineElementsString.split("[\\s]*,[\\s]*")));
        xmlFormatProperties.setContentsInlineElements(CollectionUtilities.toArrayList(contentsInlineElementsString.split("[\\s]*,[\\s]*")));
    }

    @Override
    public void processZanataResources(final Set<String> zanataIds, final List<LocaleId> locales) {
        if (zanataIds == null || zanataIds.isEmpty() || locales == null || locales.isEmpty()) {
            return;
        }

        // Validate and remove any invalid zanata ids
        validateZanataIds(zanataIds);

        final TranslatedTopicProvider translatedTopicProvider = getProviderFactory().getProvider(TranslatedTopicProvider.class);
        final double resourceSize = zanataIds.size() * locales.size();
        double resourceCount = 0;

        // Get all the existing translated topics for the zanata ids
        Map<String, Map<LocaleId, TranslatedTopicWrapper>> allTranslatedTopics = null;
        try {
            allTranslatedTopics = getTranslatedTopics(zanataIds, locales);
        } catch (Exception e) {
            log.debug("Failed to download all the existing translated topics", e);
            return;
        }

        for (final String zanataId : zanataIds) {
            try {
                // The original Zanata Document Text Resources. This will be populated later.
                Resource originalTextResource = null;

                final Map<LocaleId, TranslatedTopicWrapper> translatedTopics = allTranslatedTopics.get(zanataId);
                for (final LocaleId locale : locales) {
                    try {
                        // Work out progress
                        long localeProgress = Math.round(resourceCount / resourceSize * 100.0);
                        setProgress(localeProgress);
                        resourceCount++;

                        // Check that the locale is still valid. ie It hasn't been removed in the locale manager
                        if (!getZanataInterface().getZanataLocales().contains(locale)) {
                            continue;
                        }

                        log.info(localeProgress + "% Synchronising " + zanataId + " for locale " + locale.toString());

                        // Find a translation
                        final TranslationsResource translationsResource;
                        try {
                            translationsResource = getZanataInterface().getTranslations(zanataId, locale);
                        } catch (NotModifiedException e) {
                            // The translation hasn't been modified so move to the next locale
                            log.info(localeProgress + "% No changes were found for " + zanataId + " locale " + locale);
                            continue;
                        }

                        // Check that a translation exists
                        if (translationsResource != null) {
                            if (originalTextResource == null) {
                                // find the original resource
                                originalTextResource = getZanataInterface().getZanataResource(zanataId);
                            }

                            // The translated topic to store the results
                            final TranslatedTopicWrapper translatedTopic;
                            if (translatedTopics != null && translatedTopics.containsKey(locale)) {
                                translatedTopic = translatedTopics.get(locale);
                            } else {
                                translatedTopic = createTranslatedTopic(zanataId, locale);
                            }
                            boolean newTranslation = translatedTopic.getId() == null;

                            if (translatedTopic != null) {
                                boolean changed = false;

                                // Sync the changes to XML
                                if (syncTranslatedTopic(translatedTopic, originalTextResource, translationsResource)) {
                                    changed = true;
                                }

                                // Only save the data if the content has changed
                                if (newTranslation || changed) {
                                    // Apply anything that may have changed
                                    translatedTopic.setXml(translatedTopic.getXml());
                                    translatedTopic.setTranslatedTopicStrings(translatedTopic.getTranslatedTopicStrings());
                                    translatedTopic.setTranslationPercentage(translatedTopic.getTranslationPercentage());

                                    // Save all the changed Translated Topic Datas
                                    if (newTranslation) {
                                        translatedTopicProvider.createTranslatedTopic(translatedTopic);
                                    } else {
                                        translatedTopicProvider.updateTranslatedTopic(translatedTopic);
                                    }

                                    log.info(localeProgress + "% Finished synchronising translations for " + zanataId + " locale " +
                                            locale);
                                } else {
                                    log.info(localeProgress + "% No changes were found for " + zanataId + " locale " + locale);
                                }
                            }
                        } else {
                            log.info(localeProgress + "% No translations found for " + zanataId + " locale " + locale);
                        }
                    } catch (final Exception ex) {
                        // Error with the locale
                        log.error("Failed to sync Locale " + locale.toString() + " for Zanata ID " + zanataId, ex);
                    }
                }
            } catch (final Exception ex) {
                // Error with the resource
                log.error("Failed to sync Zanata ID " + zanataId, ex);
            }
        }

        log.info("100% Finished synchronising all Topic translations");
    }

    /**
     * Validate and remove any invalid zanata ids
     *
     * @param zanataIds The set of zanata ids to be validated
     */
    protected void validateZanataIds(final Set<String> zanataIds) {
        final Iterator<String> iter = zanataIds.iterator();
        while (iter.hasNext()) {
            final String zanataId = iter.next();
            if (!zanataId.matches("^\\d+-\\d+(-\\d+)?$")) {
                log.info("Skipping " + zanataId + " as it isn't a Translated Topic");
                iter.remove();
            }
        }
    }

    protected boolean syncTranslatedTopic(final TranslatedTopicWrapper translatedTopic, final Resource originalTextResource,
            final TranslationsResource translationsResource) throws SAXException {
        boolean changed = false;

        // Set the current xml of the translated topic data so we can see if it has changed
        final String translatedXML = translatedTopic.getXml() == null ? "" : translatedTopic.getXml();

        // a mapping of the original strings to their translations
        final Map<String, ZanataTranslation> translationDetails = new HashMap<String, ZanataTranslation>();
        final Map<String, String> translations = new HashMap<String, String>();

        final List<TextFlowTarget> textFlowTargets = translationsResource.getTextFlowTargets();
        final List<TextFlow> textFlows = originalTextResource.getTextFlows();

        double wordCount = 0;
        double totalWordCount = 0;

        // map the translation to the original resource
        for (final TextFlow textFlow : textFlows) {
            for (final TextFlowTarget textFlowTarget : textFlowTargets) {
                if (textFlowTarget.getResId().equals(textFlow.getId()) && !textFlowTarget.getContent().isEmpty()) {
                    translationDetails.put(textFlow.getContent(), new ZanataTranslation(textFlowTarget));
                    translations.put(textFlow.getContent(), textFlowTarget.getContent());
                    wordCount += textFlow.getContent().split(" ").length;
                    break;
                }
            }
            totalWordCount += textFlow.getContent().split(" ").length;
        }

        // Set the translation completion status
        int translationPercentage = (int) (wordCount / totalWordCount * 100.0f);
        if (translatedTopic.getTranslationPercentage() == null || translationPercentage != translatedTopic.getTranslationPercentage()) {
            changed = true;
            translatedTopic.setTranslationPercentage(translationPercentage);
        }

        // Process the Translations and apply the changes to the XML
        if (translatedTopic.hasTag(contentSpecTagId)) {
            // Ignore syncing Content Specs
            return false;
        } else {
            if (processTranslatedTopicXML(translatedTopic, translationDetails, translations)) {
                changed = true;
            }
        }

        // Check if the XML was changed at all
        if (!translatedXML.equals(translatedTopic.getXml())) {
            changed = true;
        }

        return changed;
    }

    /**
     * Get all the Translated Topics for a list of Zanata Ids and Locales.
     *
     * @param zanataIds A list of Zanata Ids to get existing translated topics for.
     * @param locales   A list of locales to get Translated Topics for.
     * @return A list of Translated Topics that match the Zanata ID/Locale search criteria.
     */
    protected Map<String, Map<LocaleId, TranslatedTopicWrapper>> getTranslatedTopics(final Set<String> zanataIds,
            final List<LocaleId> locales) {
        log.info("Downloading all the existing translated topics from PressGang...");
        final TranslatedTopicProvider translatedTopicProvider = getProviderFactory().getProvider(TranslatedTopicProvider.class);

        // Get the translated topics in the CCMS
        final List<TranslatedTopicWrapper> allTranslatedTopics = new ArrayList<TranslatedTopicWrapper>();
        final RESTTranslatedTopicQueryBuilderV1 queryBuilder = new RESTTranslatedTopicQueryBuilderV1();
        for (final LocaleId localeId : locales) {
            queryBuilder.setLocale(localeId.toString(), CommonFilterConstants.MATCH_LOCALE_STATE);
        }

        if (zanataIds.size() > MAX_DOWNLOAD_SIZE) {
            int start = 0;
            while (start < zanataIds.size()) {
                final List<String> subList = new LinkedList<String>(zanataIds).subList(start, Math.min(start + MAX_DOWNLOAD_SIZE,
                        zanataIds.size()));
                queryBuilder.setZanataIds(subList);
                final CollectionWrapper<TranslatedTopicWrapper> translatedTopics = translatedTopicProvider.getTranslatedTopicsWithQuery(
                        queryBuilder.getQuery());
                if (translatedTopics != null) {
                    allTranslatedTopics.addAll(translatedTopics.getItems());
                }

                start += MAX_DOWNLOAD_SIZE;
            }
        } else {
            queryBuilder.setZanataIds(zanataIds);
            final CollectionWrapper<TranslatedTopicWrapper> translatedTopics = translatedTopicProvider.getTranslatedTopicsWithQuery(
                    queryBuilder.getQuery());
            if (translatedTopics != null) {
                allTranslatedTopics.addAll(translatedTopics.getItems());
            }
        }

        // Populate the return value
        final Map<String, Map<LocaleId, TranslatedTopicWrapper>> retValue = new HashMap<String, Map<LocaleId, TranslatedTopicWrapper>>();
        for (final TranslatedTopicWrapper transTopic : allTranslatedTopics) {
            final String zanataId = transTopic.getZanataId();
            if (!retValue.containsKey(zanataId)) {
                retValue.put(zanataId, new HashMap<LocaleId, TranslatedTopicWrapper>());
            }
            retValue.get(zanataId).put(LocaleId.fromJavaName(transTopic.getLocale()), transTopic);
        }

        return retValue;
    }

    /**
     * Create a new Translated Topic from a Zanata ID and Locale.
     *
     * @param zanataId The Zanata ID the Translated Topic should be created from.
     * @param locale   The Locale for the new Translated Topic.
     * @return A new Translated Topic for the specified Zanata ID and Locale.
     */
    protected TranslatedTopicWrapper createTranslatedTopic(final String zanataId, final LocaleId locale) {
        final TranslatedTopicProvider translatedTopicProvider = getProviderFactory().getProvider(TranslatedTopicProvider.class);
        final TopicProvider topicProvider = getProviderFactory().getProvider(TopicProvider.class);

        // Get the id and revision from the zanata id
        final String[] zanataNameSplit = zanataId.split("-");
        final Integer topicId = Integer.parseInt(zanataNameSplit[0]);
        final Integer topicRevision = Integer.parseInt(zanataNameSplit[1]);

        // We need the historical topic here as well.
        final TopicWrapper historicalTopic = topicProvider.getTopic(topicId, topicRevision);

        final TranslatedTopicWrapper translatedTopic = translatedTopicProvider.newTranslatedTopic();
        translatedTopic.setLocale(locale.toString());
        translatedTopic.setTopicId(topicId);
        translatedTopic.setTopicRevision(topicRevision);
        translatedTopic.setTopic(historicalTopic);
        translatedTopic.setTags(historicalTopic.getTags());

        return translatedTopic;
    }

    /**
     * Processes a Translated Topic and updates or removes the translation strings in that topic to match the new values pulled
     * down from Zanata. It also updates the XML using the strings pulled from Zanata.
     *
     * @param translatedTopic    The Translated Topic to update.
     * @param translationDetails The mapping of Original Translation strings to Zanata Translation information.
     * @param translations       A direct mapping of Original strings to Translation strings.
     * @return True if anything in the translated topic changed, otherwise false.
     * @throws org.xml.sax.SAXException Thrown if the XML in the historical topic has invalid XML and can't be parsed.
     */
    protected boolean processTranslatedTopicXML(final TranslatedTopicWrapper translatedTopic,
            final Map<String, ZanataTranslation> translationDetails, final Map<String, String> translations) throws SAXException {
        // Get a Document from the stored historical XML
        final Document xml = TopicUtilities.convertXMLStringToDocument(translatedTopic.getTopic().getXml(),
                translatedTopic.getTopic().getXmlFormat());

        // Process any conditions
        DocBookUtilities.processConditions(translatedTopic.getTranslatedXMLCondition(), xml);

        return processTranslatedTopicXML(translatedTopic, xml, translationDetails, translations);
    }

    /**
     * Processes a Translated Topic and updates or removes the translation strings in that topic to match the new values pulled
     * down from Zanata. It also updates the XML using the strings pulled from Zanata.
     *
     * @param translatedTopic    The Translated Topic to update.
     * @param translationDetails The mapping of Original Translation strings to Zanata Translation information.
     * @param translations       A direct mapping of Original strings to Translation strings.
     * @return True if anything in the translated topic changed, otherwise false.
     * @throws org.xml.sax.SAXException Thrown if the XML in the historical topic has invalid XML and can't be parsed.
     */
    @SuppressWarnings("deprecation")
    protected boolean processTranslatedTopicXML(final TranslatedTopicWrapper translatedTopic, final Document xml,
            final Map<String, ZanataTranslation> translationDetails, final Map<String, String> translations) throws SAXException {
        final TranslatedTopicStringProvider translatedTopicStringProvider = getProviderFactory().getProvider(TranslatedTopicStringProvider
                .class);

        // The collection used to modify the TranslatedTopicString entities
        final UpdateableCollectionWrapper<TranslatedTopicStringWrapper> translatedTopicStrings =
                (UpdateableCollectionWrapper<TranslatedTopicStringWrapper>) translatedTopicStringProvider
                        .newTranslatedTopicStringCollection(
                translatedTopic);
        boolean changed = false;

        if (xml != null) {
            final List<StringToNodeCollection> stringToNodeCollectionsV3 = DocBookUtilities.getTranslatableStringsV3(xml, false);
            final List<StringToNodeCollection> stringToNodeCollectionsV2 = DocBookUtilities.getTranslatableStringsV2(xml, false);
            final List<StringToNodeCollection> stringToNodeCollectionsV1 = DocBookUtilities.getTranslatableStringsV1(xml, false);

            // Used to hold the list of StringToNode's that match the translations pulled form Zanata
            final List<StringToNodeCollection> stringToNodeCollections = new ArrayList<StringToNodeCollection>();
            // Used to hold a duplicate list of StringToNode's so we can remove the originals from the above List
            final List<StringToNodeCollection> tempStringToNodeCollection = new ArrayList<StringToNodeCollection>();

            // Add any StringToNode's that match the original translations
            for (final StringToNodeCollection stringToNodeCollectionV3 : stringToNodeCollectionsV3) {
                for (final String originalString : translations.keySet()) {
                    if (originalString.equals(stringToNodeCollectionV3.getTranslationString())) {
                        stringToNodeCollections.add(stringToNodeCollectionV3);
                        tempStringToNodeCollection.add(stringToNodeCollectionV3);
                    }
                }
            }

            // Add any StringToNode's that match the original translations and weren't added already by the V3 method
            for (final StringToNodeCollection stringToNodeCollectionV2 : stringToNodeCollectionsV2) {
                for (final String originalString : translations.keySet()) {
                    if (originalString.equals(stringToNodeCollectionV2.getTranslationString()) && !stringToNodeCollections.contains(
                            stringToNodeCollectionV2)) {
                        stringToNodeCollections.add(stringToNodeCollectionV2);
                        tempStringToNodeCollection.add(stringToNodeCollectionV2);
                    }
                }
            }

            // Add any StringToNode's that match the original translations and weren't added already by the V2/V3 method
            for (final StringToNodeCollection stringToNodeCollectionV1 : stringToNodeCollectionsV1) {
                for (final String originalString : translations.keySet()) {
                    if (originalString.equals(stringToNodeCollectionV1.getTranslationString()) && !stringToNodeCollections.contains(
                            stringToNodeCollectionV1)) {
                        stringToNodeCollections.add(stringToNodeCollectionV1);
                        tempStringToNodeCollection.add(stringToNodeCollectionV1);
                    }
                }
            }

            // Remove or update any existing translation strings
            if (translatedTopic.getTranslatedTopicStrings() != null && translatedTopic.getTranslatedTopicStrings().getItems() != null) {
                for (final TranslatedTopicStringWrapper existingString : translatedTopic.getTranslatedTopicStrings().getItems()) {
                    boolean found = false;

                    for (final StringToNodeCollection original : stringToNodeCollections) {
                        final String originalText = original.getTranslationString();

                        if (originalText.equals(existingString.getOriginalString())) {
                            final ZanataTranslation translation = translationDetails.get(originalText);

                            // Ensure that the Translation still exists in zanata
                            if (translation != null) {
                                found = true;
                                tempStringToNodeCollection.remove(original);

                                // Check the translations still match
                                if (!translation.getTranslation().equals(
                                        existingString.getTranslatedString()) || translation.isFuzzy() != existingString.isFuzzy()) {
                                    changed = true;

                                    existingString.setTranslatedString(translation.getTranslation());
                                    existingString.setFuzzy(translation.isFuzzy());

                                    translatedTopicStrings.addUpdateItem(existingString);
                                } else {
                                    translatedTopicStrings.addItem(existingString);
                                }
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

            // Save the new strings to TranslatedTopicString entities
            for (final StringToNodeCollection original : tempStringToNodeCollection) {
                final String originalText = original.getTranslationString();
                final ZanataTranslation translation = translationDetails.get(originalText);

                if (translation != null) {
                    changed = true;

                    final TranslatedTopicStringWrapper translatedTopicString = translatedTopicStringProvider.newTranslatedTopicString(
                            translatedTopic);
                    translatedTopicString.setOriginalString(originalText);
                    translatedTopicString.setTranslatedString(translation.getTranslation());
                    translatedTopicString.setFuzzy(translation.isFuzzy());

                    translatedTopicStrings.addNewItem(translatedTopicString);
                }
            }

            // Set any strings to be Added or Removed */
            if (translatedTopicStrings.getItems() != null && !translatedTopicStrings.getItems().isEmpty()) {
                translatedTopic.setTranslatedTopicStrings(translatedTopicStrings);
            }

            // Replace the translated strings, and save the result into the TranslatedTopicData entity
            if (xml != null) {
                DocBookUtilities.replaceTranslatedStrings(xml, translations, stringToNodeCollections);

                // Remove content added for docbook 5
                if (translatedTopic.getTopic().getXmlFormat() == CommonConstants.DOCBOOK_50) {
                    xml.getDocumentElement().removeAttribute("xmlns");
                    xml.getDocumentElement().removeAttribute("xmlns:xlink");
                    xml.getDocumentElement().removeAttribute("version");
                }

                translatedTopic.setXml(XMLUtilities.convertNodeToString(xml, xmlFormatProperties.getVerbatimElements(),
                        xmlFormatProperties.getInlineElements(), xmlFormatProperties.getContentsInlineElements(), true));
            }
        }

        return changed;
    }
}
