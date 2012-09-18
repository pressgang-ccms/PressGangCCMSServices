package org.jboss.pressgang.ccms.services.zanatasync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;
import org.jboss.pressgang.ccms.contentspec.ContentSpec;
import org.jboss.pressgang.ccms.contentspec.structures.StringToCSNodeCollection;
import org.jboss.pressgang.ccms.contentspec.utils.ContentSpecUtilities;
import org.jboss.pressgang.ccms.rest.v1.collections.RESTTranslatedTopicCollectionV1;
import org.jboss.pressgang.ccms.rest.v1.collections.RESTTranslatedTopicStringCollectionV1;
import org.jboss.pressgang.ccms.rest.v1.components.ComponentBaseTopicV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTopicV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTranslatedTopicStringV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTranslatedTopicV1;
import org.jboss.pressgang.ccms.rest.v1.jaxrsinterfaces.RESTInterfaceV1;
import org.jboss.pressgang.ccms.utils.common.ExceptionUtilities;
import org.jboss.pressgang.ccms.utils.common.XMLUtilities;
import org.jboss.pressgang.ccms.utils.constants.CommonConstants;
import org.jboss.pressgang.ccms.utils.structures.StringToNodeCollection;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.jboss.pressgang.ccms.zanata.ZanataTranslation;
import org.jboss.resteasy.client.ProxyFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import org.zanata.common.LocaleId;
import org.zanata.rest.dto.resource.Resource;
import org.zanata.rest.dto.resource.TextFlow;
import org.zanata.rest.dto.resource.TextFlowTarget;
import org.zanata.rest.dto.resource.TranslationsResource;

import com.redhat.contentspec.processor.ContentSpecParser;

public class ZanataPullTopicThread implements Runnable
{

	private static final Logger log = Logger.getLogger(ZanataPullTopicThread.class);
	private static final String skynetServer = System.getProperty(CommonConstants.SKYNET_SERVER_SYSTEM_PROPERTY);

	private static final String XML_ENCODING = "UTF-8";

	private final RESTTopicV1 translatedHistoricalTopic;
	private final RESTInterfaceV1 skynetClient;
	private final ZanataInterface zanataInterface = new ZanataInterface();
	private static long syncTimePerTopicPerLocale;
	
	synchronized public static long getSyncTimePerTopicPerLocale()
	{
		return syncTimePerTopicPerLocale;
	}
	
	synchronized public static void setSyncTimePerTopicPerLocale(final long syncTimePerTopicPerLocale)
	{
		ZanataPullTopicThread.syncTimePerTopicPerLocale = syncTimePerTopicPerLocale;
	}

	public ZanataPullTopicThread(final RESTTopicV1 topic, final String skynetServerUrl)
	{
		this.translatedHistoricalTopic = topic;
		this.skynetClient = ProxyFactory.create(RESTInterfaceV1.class, skynetServerUrl);
	}

    @Override
	public void run()
	{
		final List<LocaleId> locales = zanataInterface.getZanataLocales();
		try
		{
			if (translatedHistoricalTopic != null)
			{
				final String zanataId = translatedHistoricalTopic.getId() + "-" + translatedHistoricalTopic.getRevision();

				log.info("Starting to pull translations for " + zanataId);

				final RESTTranslatedTopicCollectionV1 translatedTopics = translatedHistoricalTopic.getTranslatedTopics_OTM();

				final RESTTranslatedTopicCollectionV1 changedTranslatedTopics = new RESTTranslatedTopicCollectionV1();
				final RESTTranslatedTopicCollectionV1 newTranslatedTopics = new RESTTranslatedTopicCollectionV1();

				for (final LocaleId locale : locales)
				{
					final long startTime = System.currentTimeMillis();
										
					if (zanataInterface.getTranslationsExists(zanataId, locale))
					{
						/* find a translation */
						final TranslationsResource translationsResource = zanataInterface.getTranslations(zanataId, locale);
						/* and find the original resource */
						final Resource originalTextResource = zanataInterface.getZanataResource(zanataId);

						if (translationsResource != null && originalTextResource != null)
						{
							log.info("Starting to pull translations for " + zanataId + " locale " + locale);

							/* attempt to find an existing TranslatedTopicData entity */
							RESTTranslatedTopicV1 translatedTopic = null;
							boolean newTranslation = true;

							if (translatedTopics != null && translatedTopics.returnItems() != null)
							{
								for (final RESTTranslatedTopicV1 myTranslatedTopic : translatedTopics.returnItems())
								{
									if (myTranslatedTopic.getTopicRevision().equals(translatedHistoricalTopic.getRevision()) && 
											myTranslatedTopic.getLocale().equals(locale.toString()))
									{
										translatedTopic = myTranslatedTopic;
										newTranslation = false;
										break;
									}
								}
							}

							/*
							 * if an existing TranslatedTopicData entity does not exist, create one
							 */
							if (translatedTopic == null)
							{
								translatedTopic = new RESTTranslatedTopicV1();
								translatedTopic.explicitSetLocale(locale.toString());
								translatedTopic.explicitSetTopicId(translatedHistoricalTopic.getId());
								translatedTopic.explicitSetTopicRevision(translatedHistoricalTopic.getRevision().intValue());
							}

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
							for (final TextFlow textFlow : textFlows)
							{
								for (final TextFlowTarget textFlowTarget : textFlowTargets)
								{
									if (textFlowTarget.getResId().equals(textFlow.getId()))
									{
									    translationDetails.put(textFlow.getContent(), new ZanataTranslation(textFlowTarget));
									    translations.put(textFlow.getContent(), textFlowTarget.getContent());
									    wordCount += textFlow.getContent().split(" ").length;
										break;
									}
								}
								totalWordCount += textFlow.getContent().split(" ").length;
							}

							/* Set the translation completion status */
							translatedTopic.explicitSetTranslationPercentage((int) ( wordCount / totalWordCount * 100.0f));

							final boolean changed;
							if (ComponentBaseTopicV1.hasTag(translatedHistoricalTopic, CommonConstants.CONTENT_SPEC_TAG_ID))
							{
								changed = processContentSpec(translatedTopic, translationDetails, translations);
							}
							else
							{
							    changed = processTopic(translatedTopic, translationDetails, translations);
							}

							/* Only save the data if the content has changed */
							if (changed || !translatedXML.equals(translatedTopic.getXml()) || translatedTopic.getId() == null)
							{

								/*
								 * make a note of the TranslatedTopicData entities that have been changed, so we can render them
								 */
								if (newTranslation)
									newTranslatedTopics.addItem(translatedTopic);
								else
									changedTranslatedTopics.addItem(translatedTopic);
							}

							log.info("Finished pulling translations for " + zanataId + " locale " + locale);
						}
					}
					
					/* work out how long to sleep for */
					final long endTime = System.currentTimeMillis();					
					final long duration = endTime - startTime;
					final long sleep = getSyncTimePerTopicPerLocale() - duration;
					final long fixedSleep = sleep < 0 ? 0 : sleep;
					
					System.out.println("Rate limiting by sleeping for " + fixedSleep / 1000.0 + " seconds. ");
					
					try
					{
						Thread.sleep(fixedSleep);
					}
					catch (final InterruptedException ex)
					{
						// todo: if we interrupt this thread (possibly for an early exit with CTRL-C) then
						// we will have to do something intelligent with this exception.
						// see http://www.ibm.com/developerworks/java/library/j-jtp05236/index.html or http://www.javaspecialists.co.za/archive/Issue056.html
					}
				}

				/* Save the new Translated Topic Datas */
				if (newTranslatedTopics.returnItems() != null && !newTranslatedTopics.returnItems().isEmpty())
				{
					skynetClient.createJSONTranslatedTopics("", newTranslatedTopics);
				}

				/* Save all the changed Translated Topic Datas */
				if (changedTranslatedTopics.returnItems() != null && !changedTranslatedTopics.returnItems().isEmpty())
				{
					skynetClient.updateJSONTranslatedTopics("", changedTranslatedTopics);
				}

				log.info("Finished pulling translations for " + zanataId);
			}
		}
		catch (final Exception ex)
		{
			ExceptionUtilities.handleException(ex);
		}
	}
	
    /**
     * Processes a Translated Topic and updates or removes the translation strings in that topic to match
     * the new values pulled down from Zanata. It also updates the XML using the strings pulled from Zanata.
     * 
     * @param translatedTopic The Translated Topic to update.
     * @param translationDetails The mapping of Original Translation strings to Zanata Translation information.
     * @param translations A direct mapping of Original strings to Translation strings.
     * @return True if anything in the translated topic changed, otherwise false.
     * @throws SAXException Thrown if the XML in the historical topic has invalid XML and can't be parsed.
     */
	@SuppressWarnings("deprecation")
    protected boolean processTopic(final RESTTranslatedTopicV1 translatedTopic, final Map<String, ZanataTranslation> translationDetails,
            final Map<String, String> translations) throws SAXException
	{
	    /* The collection used to modify the TranslatedTopicString entities */
        final RESTTranslatedTopicStringCollectionV1 translatedTopicStrings = new RESTTranslatedTopicStringCollectionV1();
        boolean changed = false;
	    
	    /* get a Document from the stored historical XML */
        final Document xml = XMLUtilities.convertStringToDocument(translatedHistoricalTopic.getXml());
        if (xml != null)
        {
            
            final List<StringToNodeCollection> stringToNodeCollectionsV2 = XMLUtilities.getTranslatableStringsV2(xml, false);
            final List<StringToNodeCollection> stringToNodeCollectionsV1 = XMLUtilities.getTranslatableStringsV1(xml, false);
            
            /* Used to hold the list of StringToNode's that match the translations pulled form Zanata */
            final List<StringToNodeCollection> stringToNodeCollections = new ArrayList<StringToNodeCollection>();
            /* Used to hold a duplicate list of StringToNode's so we can remove the originals from the above List*/
            final List<StringToNodeCollection> tempStringToNodeCollection = new ArrayList<StringToNodeCollection>();
            
            /* Add any StringToNode's that match the original translations */
            for (final StringToNodeCollection stringToNodeCollectionV2 : stringToNodeCollectionsV2)
            {
                for (final String originalString : translations.keySet())
                {
                    if (originalString.equals(stringToNodeCollectionV2.getTranslationString()))
                    {
                        stringToNodeCollections.add(stringToNodeCollectionV2);
                        tempStringToNodeCollection.add(stringToNodeCollectionV2);
                    }
                }
            }
            
            /* Add any StringToNode's that match the original translations and weren't added already by the V2 method*/
            for (final StringToNodeCollection stringToNodeCollectionV1 : stringToNodeCollectionsV1)
            {
                for (final String originalString : translations.keySet())
                {
                    if (originalString.equals(stringToNodeCollectionV1.getTranslationString()) 
                            && !stringToNodeCollections.contains(stringToNodeCollectionV1))
                    {
                        stringToNodeCollections.add(stringToNodeCollectionV1);
                        tempStringToNodeCollection.add(stringToNodeCollectionV1);
                    }
                }
            }
            
            // Remove or update any existing translation strings
            if (translatedTopic.getTranslatedTopicStrings_OTM() != null && translatedTopic.getTranslatedTopicStrings_OTM().returnItems() != null)
            {
                for (final RESTTranslatedTopicStringV1 existingString : translatedTopic.getTranslatedTopicStrings_OTM().returnItems())
                {
                    boolean found = false;
                    
                    for (final StringToNodeCollection original : stringToNodeCollections)
                    {
                        final String originalText = original.getTranslationString();
                        
                        if (existingString.getOriginalString().equals(originalText))
                        {
                            found = true;
                            tempStringToNodeCollection.remove(original);
                            
                            final ZanataTranslation translation = translationDetails.get(originalText);
                            
                            // Check the translations still match
                            if (!translation.getTranslation().equals(existingString.getTranslatedString())
                                    || translation.isFuzzy() != existingString.getFuzzyTranslation())
                            {
                                changed = true;
                                
                                existingString.explicitSetTranslatedString(translation.getTranslation());               
                                existingString.explicitSetFuzzyTranslation(translation.isFuzzy());
                                
                                translatedTopicStrings.addUpdateItem(existingString);
                            }
                        }
                    }
                    
                    // If the original String no longer exists then remove it (this shouldn't happen)
                    if (!found)
                    {
                        changed = true;

                        translatedTopicStrings.addRemoveItem(existingString);
                    }
                }
            }

            /* save the new strings to TranslatedTopicString entities */                            
            for (final StringToNodeCollection original : tempStringToNodeCollection)
            {
                final String originalText = original.getTranslationString();
                final ZanataTranslation translation = translationDetails.get(originalText);

                if (translation != null)
                {
                    changed = true;
                    
                    final RESTTranslatedTopicStringV1 translatedTopicString = new RESTTranslatedTopicStringV1();
                    translatedTopicString.explicitSetOriginalString(originalText);
                    translatedTopicString.explicitSetTranslatedString(translation.getTranslation());
                    translatedTopicString.explicitSetFuzzyTranslation(translation.isFuzzy());

                    translatedTopicStrings.addNewItem(translatedTopicString);
                }
            }
            
            /* Set any strings to be Added or Removed */ 
            if (translatedTopicStrings.getItems() != null && !translatedTopicStrings.getItems().isEmpty())
            {
                translatedTopic.explicitSetTranslatedTopicString_OTM(translatedTopicStrings);
            }

            /*
             * replace the translated strings, and save the result into the TranslatedTopicData entity
             */
            if (xml != null)
            {
                XMLUtilities.replaceTranslatedStrings(xml, translations, stringToNodeCollections);
                translatedTopic.explicitSetXml(XMLUtilities.convertDocumentToString(xml, XML_ENCODING));
            }
        }
        
        return changed;
	}
	
	/**
     * Processes a Translated Topic and updates or removes the translation strings in that topic to match
     * the new values pulled down from Zanata. It also updates the Content Spec using the strings pulled from Zanata.
     * 
     * @param translatedTopic The Translated Topic to update.
     * @param translationDetails The mapping of Original Translation strings to Zanata Translation information.
     * @param translations A direct mapping of Original strings to Translation strings.
     * @return True if anything in the translated topic changed, otherwise false.
     * @throws Exception Thrown if there is an error in the Content Specification syntax.
     */
	protected boolean processContentSpec(final RESTTranslatedTopicV1 translatedTopic, final Map<String, ZanataTranslation> translationDetails,
	        final Map<String, String> translations) throws Exception
	{
	    /* The collection used to modify the TranslatedTopicString entities */
        final RESTTranslatedTopicStringCollectionV1 translatedTopicStrings = new RESTTranslatedTopicStringCollectionV1();
        boolean changed = false;
	    
	    /* Parse the Content Spec stored in the XML Field */
        final ContentSpecParser parser = new ContentSpecParser(skynetServer);
        
        /*
         * replace the translated strings, and save the result into the TranslatedTopicData entity
         */
        if (parser.parse(translatedHistoricalTopic.getXml()))
        {
            final ContentSpec spec = parser.getContentSpec();
            
            if (spec != null)
            {
                final List<StringToCSNodeCollection> stringToNodeCollections = ContentSpecUtilities.getTranslatableStrings(spec, false);
                
                /* create a temporary collection that we can freely remove items from */
                final List<StringToCSNodeCollection> tempStringToNodeCollection = new ArrayList<StringToCSNodeCollection>();
                for (final StringToCSNodeCollection stringToNodeCollection : stringToNodeCollections)
                {
                    tempStringToNodeCollection.add(stringToNodeCollection);
                }
                
                // Remove or update any existing translation strings
                if (translatedTopic.getTranslatedTopicStrings_OTM() != null && translatedTopic.getTranslatedTopicStrings_OTM().returnItems() != null)
                {
                    for (final RESTTranslatedTopicStringV1 existingString : translatedTopic.getTranslatedTopicStrings_OTM().returnItems())
                    {
                        boolean found = false;
                        
                        for (final StringToCSNodeCollection original : tempStringToNodeCollection)
                        {
                            final String originalText = original.getTranslationString();
                            
                            if (existingString.getOriginalString().equals(originalText))
                            {
                                found = true;
                                tempStringToNodeCollection.remove(original);
                                
                                final ZanataTranslation translation = translationDetails.get(originalText);
                                
                                // Check the translations still match
                                if (!translation.getTranslation().equals(existingString.getTranslatedString())
                                        || translation.isFuzzy() != existingString.getFuzzyTranslation())
                                {
                                    changed = true;
                                    
                                    existingString.explicitSetTranslatedString(translation.getTranslation());               
                                    existingString.explicitSetFuzzyTranslation(translation.isFuzzy());

                                    translatedTopicStrings.addUpdateItem(existingString);
                                }
                            }
                        }
                        
                        // If the original String no longer exists then remove it (this shouldn't happen)
                        if (!found)
                        {
                            changed = true;

                            translatedTopicStrings.addRemoveItem(existingString);
                        }
                    }
                }
                
                /* save the strings to TranslatedTopicString entities */                            
                for (final StringToCSNodeCollection original : tempStringToNodeCollection)
                {
                    final String originalText = original.getTranslationString();
                    final ZanataTranslation translation = translationDetails.get(originalText);
    
                    if (translation != null)
                    {
                        final RESTTranslatedTopicStringV1 translatedTopicString = new RESTTranslatedTopicStringV1();
                        translatedTopicString.explicitSetOriginalString(originalText);
                        translatedTopicString.explicitSetTranslatedString(translation.getTranslation());
                        translatedTopicString.explicitSetFuzzyTranslation(translation.isFuzzy());
    
                        translatedTopicStrings.addNewItem(translatedTopicString);
                    }
                }
                
                /* Set any strings to be Added or Removed */ 
                if (translatedTopicStrings.returnItems() != null && !translatedTopicStrings.returnItems().isEmpty())
                {
                    translatedTopic.explicitSetTranslatedTopicString_OTM(translatedTopicStrings);
                }
                
                ContentSpecUtilities.replaceTranslatedStrings(spec, translations);
                translatedTopic.explicitSetXml(spec.toString());
            }
        }
        
        return changed;
	}

}