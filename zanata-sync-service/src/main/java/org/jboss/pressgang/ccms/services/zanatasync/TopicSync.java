package org.jboss.pressgang.ccms.services.zanatasync;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.jboss.pressgang.ccms.utils.common.CollectionUtilities;
import org.jboss.pressgang.ccms.utils.common.XMLUtilities;
import org.jboss.pressgang.ccms.utils.constants.CommonConstants;
import org.jboss.pressgang.ccms.utils.structures.StringToNodeCollection;
import org.jboss.pressgang.ccms.wrapper.StringConstantWrapper;
import org.jboss.pressgang.ccms.wrapper.TopicWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedTopicStringWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedTopicWrapper;
import org.jboss.pressgang.ccms.wrapper.collection.CollectionWrapper;
import org.jboss.pressgang.ccms.wrapper.collection.UpdateableCollectionWrapper;
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
    private static final String XML_ENCODING = "UTF-8";
    private static final Logger log = LoggerFactory.getLogger("ZanataTopicSync");
    protected final XMLFormatProperties xmlFormatProperties = new XMLFormatProperties();

    public TopicSync(final DataProviderFactory providerFactory, final ZanataInterface zanataInterface) {
        super(providerFactory, zanataInterface);

        final StringConstantWrapper xmlElementsProperties = providerFactory.getProvider(StringConstantProvider.class).getStringConstant(
                CommonConstants.XML_ELEMENTS_STRING_CONSTANT_ID);

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
        if (zanataIds == null || zanataIds.isEmpty()) {
            return;
        }

        final TranslatedTopicProvider translatedTopicProvider = getProviderFactory().getProvider(TranslatedTopicProvider.class);
        final double resourceSize = zanataIds.size();
        double resourceCount = 0;

        for (final String zanataId : zanataIds) {
            try {
                // Work out progress
                setProgress(Math.round(resourceCount / resourceSize * 100.0));
                resourceCount++;

                if (!zanataId.matches("^\\d+-\\d+(-\\d+)?$")) continue;

                // The original Zanata Document Text Resources. This will be populated later.
                Resource originalTextResource = null;

                final int localesSize = locales.size();
                int localeCount = 0;

                for (final LocaleId locale : locales) {
                    try {
                        // Work out progress
                        long localeProgress = Math.round(resourceCount + (localeCount / localesSize * 100.0 / resourceSize));
                        setProgress(localeProgress);
                        ++localeCount;

                        log.info(localeProgress + "% Synchronising " + zanataId + " for locale " + locale.toString());

                        // find a translation
                        final TranslationsResource translationsResource = getZanataInterface().getTranslations(zanataId, locale);

                        // Check that a translation exists
                        if (translationsResource != null) {
                            if (originalTextResource == null) {
                                // find the original resource
                                originalTextResource = getZanataInterface().getZanataResource(zanataId);
                            }

                            // The translated topic to store the results
                            final TranslatedTopicWrapper translatedTopic = getTranslatedTopic(getProviderFactory(), zanataId, locale);
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
        if (translatedTopic.hasTag(268)) {
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

    protected TranslatedTopicWrapper getTranslatedTopic(final DataProviderFactory providerFactory, final String zanataId,
            final LocaleId locale) {
        final TranslatedTopicProvider translatedTopicProvider = providerFactory.getProvider(TranslatedTopicProvider.class);
        final TopicProvider topicProvider = providerFactory.getProvider(TopicProvider.class);

        // Get the translated topic in the CCMS
        final CollectionWrapper<TranslatedTopicWrapper> translatedTopics = translatedTopicProvider.getTranslatedTopicsWithQuery(
                "query;" + CommonFilterConstants.ZANATA_IDS_FILTER_VAR + "=" + zanataId);

        TranslatedTopicWrapper translatedTopic = null;
        if (translatedTopics.getItems().size() != 0) {
            // Find the original translation (the query results will return all locales)
            for (final TranslatedTopicWrapper transTopic : translatedTopics.getItems()) {
                if (transTopic.getLocale().equals(locale.toString())) {
                    translatedTopic = transTopic;
                    break;
                }
            }
        }

        // If the TranslatedTopic doesn't exist in PressGang then we need to create it
        if (translatedTopic == null) {
            final String[] zanataNameSplit = zanataId.split("-");
            final Integer topicId = Integer.parseInt(zanataNameSplit[0]);
            final Integer topicRevision = Integer.parseInt(zanataNameSplit[1]);

            // We need the historical topic here as well.
            final TopicWrapper historicalTopic = topicProvider.getTopic(topicId, topicRevision);

            translatedTopic = translatedTopicProvider.newTranslatedTopic();
            translatedTopic.setLocale(locale.toString());
            translatedTopic.setTopicId(topicId);
            translatedTopic.setTopicRevision(topicRevision);
            translatedTopic.setTopic(historicalTopic);
            translatedTopic.setTags(historicalTopic.getTags());
        }

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
        final Document xml = XMLUtilities.convertStringToDocument(translatedTopic.getTopic().getXml());
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
            final List<StringToNodeCollection> stringToNodeCollectionsV2 = XMLUtilities.getTranslatableStringsV2(xml, false);
            final List<StringToNodeCollection> stringToNodeCollectionsV1 = XMLUtilities.getTranslatableStringsV1(xml, false);

            // Used to hold the list of StringToNode's that match the translations pulled form Zanata
            final List<StringToNodeCollection> stringToNodeCollections = new ArrayList<StringToNodeCollection>();
            // Used to hold a duplicate list of StringToNode's so we can remove the originals from the above List
            final List<StringToNodeCollection> tempStringToNodeCollection = new ArrayList<StringToNodeCollection>();

            // Add any StringToNode's that match the original translations */
            for (final StringToNodeCollection stringToNodeCollectionV2 : stringToNodeCollectionsV2) {
                for (final String originalString : translations.keySet()) {
                    if (originalString.equals(stringToNodeCollectionV2.getTranslationString())) {
                        stringToNodeCollections.add(stringToNodeCollectionV2);
                        tempStringToNodeCollection.add(stringToNodeCollectionV2);
                    }
                }
            }

            // Add any StringToNode's that match the original translations and weren't added already by the V2 method
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
                XMLUtilities.replaceTranslatedStrings(xml, translations, stringToNodeCollections);
                translatedTopic.setXml(XMLUtilities.convertNodeToString(xml, xmlFormatProperties.getVerbatimElements(),
                        xmlFormatProperties.getInlineElements(), xmlFormatProperties.getContentsInlineElements(), true));
            }
        }

        return changed;
    }
}
