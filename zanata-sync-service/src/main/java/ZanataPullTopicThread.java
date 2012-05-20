import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.jboss.resteasy.client.ProxyFactory;
import org.w3c.dom.Document;

import org.zanata.common.LocaleId;
import org.zanata.rest.dto.resource.Resource;
import org.zanata.rest.dto.resource.TextFlow;
import org.zanata.rest.dto.resource.TextFlowTarget;
import org.zanata.rest.dto.resource.TranslationsResource;

import com.redhat.ecs.commonutils.ExceptionUtilities;
import com.redhat.ecs.commonutils.XMLUtilities;
import com.redhat.ecs.constants.CommonConstants;
import com.redhat.topicindex.rest.collections.BaseRestCollectionV1;
import com.redhat.topicindex.rest.entities.TopicV1;
import com.redhat.topicindex.rest.entities.TranslatedTopicStringV1;
import com.redhat.topicindex.rest.entities.TranslatedTopicV1;
import com.redhat.topicindex.rest.sharedinterface.RESTInterfaceV1;
import com.redhat.topicindex.zanata.ZanataInterface;

public class ZanataPullTopicThread implements Runnable {
	
	private static final Logger log = Logger.getLogger(ZanataPullTopicThread.class);

	private static final String XML_ENCODING = "UTF-8";
	
	private final TopicV1 translatedHistoricalTopic;
	private final RESTInterfaceV1 skynetClient;
	private final ZanataInterface zanataInterface = new ZanataInterface();

	public ZanataPullTopicThread(final TopicV1 topic, final String skynetServerUrl)
	{
		this.translatedHistoricalTopic = topic;
		this.skynetClient = ProxyFactory.create(RESTInterfaceV1.class, skynetServerUrl);
	}
	
	public void run() {
		try
		{
			if (translatedHistoricalTopic != null)
			{
				final String zanataId = translatedHistoricalTopic.getId() + "-" + translatedHistoricalTopic.getRevision();
				
				log.info("Starting to pull translations for " + zanataId);
				
				final BaseRestCollectionV1<TranslatedTopicV1> translatedTopics = translatedHistoricalTopic.getTranslatedTopics_OTM();		
				
				final BaseRestCollectionV1<TranslatedTopicV1> changedTranslatedTopics = new BaseRestCollectionV1<TranslatedTopicV1>();
				final BaseRestCollectionV1<TranslatedTopicV1> newTranslatedTopics = new BaseRestCollectionV1<TranslatedTopicV1>();
				
				for (String locale: CommonConstants.LOCALES)
				{
					if (zanataInterface.getTranslationsExists(zanataId, LocaleId.fromJavaName(locale)))
					{
						/* find a translation */
						final TranslationsResource translationsResource = zanataInterface.getTranslations(zanataId, LocaleId.fromJavaName(locale));
						/* and find the original resource */
						final Resource originalTextResource = zanataInterface.getZanataResource(zanataId);
		
						if (translationsResource != null && originalTextResource != null)
						{
							log.info("Starting to pull translations for " + zanataId + " locale " + locale);
							
							/* attempt to find an existing TranslatedTopicData entity */
							TranslatedTopicV1 translatedTopic = null;
							boolean newTranslation = true;
		
							if (translatedTopics != null && translatedTopics.getItems() != null)
							{
								for (final TranslatedTopicV1 myTranslatedTopic : translatedTopics.getItems())
								{
									if (myTranslatedTopic.getLocale().equals(locale))
									{
										translatedTopic = myTranslatedTopic;
										newTranslation = false;
										break;
									}
								}
							}
		
							/*
							 * if an existing TranslatedTopicData entity does not
							 * exist, create one
							 */
							if (translatedTopic == null)
							{
								translatedTopic = new TranslatedTopicV1();
								translatedTopic.setLocaleExplicit(locale);
								translatedTopic.setTopicIdExplicit(translatedHistoricalTopic.getId());
								translatedTopic.setTopicRevisionExplicit(translatedHistoricalTopic.getRevision().intValue());
							}
							translatedTopic.setAddItem(true);
							
							/* Set the current xml of the translated topic data so we can see if it has changed */
							final String translatedXML = translatedTopic.getXml() == null ? "" : translatedTopic.getXml();
		
							/*
							 * a mapping of the original strings to their translations
							 */
							final Map<String, String> translations = new HashMap<String, String>();
		
							final List<TextFlowTarget> textFlowTargets = translationsResource.getTextFlowTargets();
							final List<TextFlow> textFlows = originalTextResource.getTextFlows();
							int i = 0;
		
							/* map the translation to the original resource */
							for (final TextFlow textFlow : textFlows)
							{
								for (final TextFlowTarget textFlowTarget : textFlowTargets)
								{
									if (textFlowTarget.getResId().equals(textFlow.getId()))
									{
										translations.put(textFlow.getContent(), textFlowTarget.getContent());
										i++;
										break;
									}
								}
							}
							
							/* Set the translation completion status */
							translatedTopic.setTranslationPercentageExplicit((int) ( i / ((double) textFlows.size()) * 100.0f));
							
							/* save the strings to TranslatedTopicString entities */
							BaseRestCollectionV1<TranslatedTopicStringV1> translatedTopicStrings = new BaseRestCollectionV1<TranslatedTopicStringV1>();
							for (final String originalText : translations.keySet())
							{
								final String translation = translations.get(originalText);
								
								final TranslatedTopicStringV1 translatedTopicString = new TranslatedTopicStringV1();
								translatedTopicString.setOriginalStringExplicit(originalText);
								translatedTopicString.setTranslatedStringExplicit(translation);
								translatedTopicString.setAddItem(true);
								
								translatedTopicStrings.addItem(translatedTopicString);
							}
							translatedTopic.setTranslatedTopicStringExplicit_OTM(translatedTopicStrings);
		
							/* get a Document from the stored historical XML */
							final Document xml = XMLUtilities.convertStringToDocument(translatedHistoricalTopic.getXml());
		
							/*
							 * replace the translated strings, and save the result into
							 * the TranslatedTopicData entity
							 */
							if (xml != null)
							{
								XMLUtilities.replaceTranslatedStrings(xml, translations);
								translatedTopic.setXmlExplicit(XMLUtilities.convertDocumentToString(xml, XML_ENCODING));
							}
							
							/* Only save the data if the content has changed */
							if (!translatedXML.equals(translatedTopic.getXml()) || translatedTopic.getId() == null) {
			
								/*
								 * make a note of the TranslatedTopicData entities that
								 * have been changed, so we can render them
								 */
								if (newTranslation)
									newTranslatedTopics.addItem(translatedTopic);
								else
									changedTranslatedTopics.addItem(translatedTopic);
							}
							
							log.info("Finished pulling translations for " + zanataId + " locale " + locale);
						}
					}
				}
				
				/* Save the new Translated Topic Datas */
				if (newTranslatedTopics.getItems() != null && !newTranslatedTopics.getItems().isEmpty())
				{
					skynetClient.createJSONTranslatedTopics("", newTranslatedTopics);
				}
				
				/* Save all the changed Translated Topic Datas */
				if (changedTranslatedTopics.getItems() != null && !changedTranslatedTopics.getItems().isEmpty())
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

}
