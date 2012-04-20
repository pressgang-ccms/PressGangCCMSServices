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
import com.redhat.topicindex.rest.entities.TranslatedTopicDataV1;
import com.redhat.topicindex.rest.entities.TranslatedTopicStringV1;
import com.redhat.topicindex.rest.entities.TranslatedTopicV1;
import com.redhat.topicindex.rest.sharedinterface.RESTInterfaceV1;
import com.redhat.topicindex.zanata.ZanataInterface;

public class ZanataPullTopicThread implements Runnable {
	
	private static final Logger log = Logger.getLogger(ZanataPullTopicThread.class);

	private static final String XML_ENCODING = "UTF-8";
	
	private final TranslatedTopicV1 translatedTopic;
	private final RESTInterfaceV1 skynetClient;

	public ZanataPullTopicThread(final TranslatedTopicV1 translatedTopic, final String skynetServerUrl)
	{
		this.translatedTopic = translatedTopic;
		this.skynetClient = ProxyFactory.create(RESTInterfaceV1.class, skynetServerUrl);
	}
	
	public void run() {
		try
		{
			if (translatedTopic != null)
			{
				log.info("Starting to pull translations for translated topic " + translatedTopic.getId());
				
				final BaseRestCollectionV1<TranslatedTopicDataV1> processedTranslatedTopicDatas = new BaseRestCollectionV1<TranslatedTopicDataV1>();
				final BaseRestCollectionV1<TranslatedTopicDataV1> newTranslatedTopicDataEntities = new BaseRestCollectionV1<TranslatedTopicDataV1>();
				
				/* ... find the matching historical envers topic */
				final TopicV1 historicalTopic = skynetClient.getJSONTopicRevision(translatedTopic.getTopicId(), translatedTopic.getTopicRevision(), "");
				
				final BaseRestCollectionV1<TranslatedTopicDataV1> translatedTopicDataEntities = translatedTopic.getTranslatedTopicData_OTM();
				
				for (String locale: CommonConstants.LOCALES)
				{
					if (ZanataInterface.getTranslationsExists(translatedTopic.getZanataId(), LocaleId.fromJavaName(locale)))
					{
					
						/* find a translation */
						final TranslationsResource translationsResource = ZanataInterface.getTranslations(translatedTopic.getZanataId(), LocaleId.fromJavaName(locale));
						/* and find the original resource */
						final Resource originalTextResource = ZanataInterface.getZanataResource(translatedTopic.getZanataId());
		
						if (translationsResource != null && originalTextResource != null)
						{
		
							log.info("Starting to pull translations for locale " + locale);
							
							/* attempt to find an existing TranslatedTopicData entity */
							TranslatedTopicDataV1 translatedTopicData = null;
		
							if (translatedTopicDataEntities != null && translatedTopicDataEntities.getItems() != null)
							{
								for (final TranslatedTopicDataV1 myTranslatedTopicData : translatedTopicDataEntities.getItems())
								{
									if (myTranslatedTopicData.getTranslationLocale().equals(locale))
									{
										translatedTopicData = myTranslatedTopicData;
										break;
									}
								}
							}
		
							/*
							 * if an existing TranslatedTopicData entity does not
							 * exist, create one
							 */
							if (translatedTopicData == null)
							{
								translatedTopicData = new TranslatedTopicDataV1();
								translatedTopicData.setTranslationLocaleExplicit(locale);
								translatedTopicData.setAddItem(true);
								
								newTranslatedTopicDataEntities.addItem(translatedTopicData);
							}
							
							/* Set the current xml of the translated topic data so we can see if it has changed */
							final String translatedXML = translatedTopicData.getTranslatedXml() == null ? "" : translatedTopicData.getTranslatedXml();
		
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
							translatedTopicData.setTranslationPercentageExplicit(i/textFlows.size());
							
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
							translatedTopicData.setTranslatedStringsExplicit_OTM(translatedTopicStrings);
		
							/* get a Document from the stored historical XML */
							final Document xml = XMLUtilities.convertStringToDocument(historicalTopic.getXml());
		
							/*
							 * replace the translated strings, and save the result into
							 * the TranslatedTopicData entity
							 */
							if (xml != null)
							{
								XMLUtilities.replaceTranslatedStrings(xml, translations);
								translatedTopicData.setTranslatedXmlExplicit(XMLUtilities.convertDocumentToString(xml, XML_ENCODING));
							}
							
							/* Only save the data if the content has changed */
							if (!translatedXML.equals(translatedTopicData.getTranslatedXml())) {
			
								/*
								 * make a note of the TranslatedTopicData entities that
								 * have been changed, so we can render them
								 */
								processedTranslatedTopicDatas.addItem(translatedTopicData);
							}
							
							log.info("Finished pulling translations for locale " + locale);
						}
					}
				}
				
				/* Save the new Translated Topic Datas */
				if (newTranslatedTopicDataEntities.getItems() != null && newTranslatedTopicDataEntities.getItems().isEmpty())
				{
					translatedTopic.setTranslatedTopicDataExplicit_OTM(newTranslatedTopicDataEntities);
					skynetClient.updateJSONTranslatedTopic("", translatedTopic);
				}
				
				/* Save all the changed Translated Topic Datas */
				if (processedTranslatedTopicDatas.getItems() != null && processedTranslatedTopicDatas.getItems().isEmpty())
				{
					skynetClient.updateJSONTranslatedTopicDatas("", processedTranslatedTopicDatas);
				}
			}
			
			log.info("Finished pulling translations for translated topic " + translatedTopic.getId());
		}
		catch (final Exception ex)
		{
			ExceptionUtilities.handleException(ex);
		}
	}

}
