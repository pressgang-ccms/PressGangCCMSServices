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

import com.redhat.contentspec.ContentSpec;
import com.redhat.contentspec.processor.ContentSpecParser;
import com.redhat.contentspec.utils.ContentSpecUtilities;
import com.redhat.ecs.commonutils.ExceptionUtilities;
import com.redhat.ecs.commonutils.XMLUtilities;
import com.redhat.ecs.constants.CommonConstants;
import com.redhat.topicindex.rest.collections.RESTTranslatedTopicCollectionV1;
import com.redhat.topicindex.rest.collections.RESTTranslatedTopicStringCollectionV1;
import com.redhat.topicindex.rest.entities.ComponentBaseTopicV1;
import com.redhat.topicindex.rest.entities.interfaces.RESTTopicV1;
import com.redhat.topicindex.rest.entities.interfaces.RESTTranslatedTopicStringV1;
import com.redhat.topicindex.rest.entities.interfaces.RESTTranslatedTopicV1;
import com.redhat.topicindex.rest.sharedinterface.RESTInterfaceV1;
import com.redhat.topicindex.zanata.ZanataInterface;

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

							if (translatedTopics != null && translatedTopics.getItems() != null)
							{
								for (final RESTTranslatedTopicV1 myTranslatedTopic : translatedTopics.getItems())
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
							translatedTopic.explicitSetTranslationPercentage((int) (i / ((double) textFlows.size()) * 100.0f));

							/* The collection used to modify the TranslatedTopicString entities */
							RESTTranslatedTopicStringCollectionV1 translatedTopicStrings = new RESTTranslatedTopicStringCollectionV1();
							
							/*
							 * Remove any current TranslatedTopicString entities. This remove/add cycle makes up for the fact that the REST interface doesn't
							 * allow for children to be updated
							 */
							
							if (translatedTopic.getTranslatedTopicStrings_OTM() != null && translatedTopic.getTranslatedTopicStrings_OTM().getItems() != null)
							{
								for (final RESTTranslatedTopicStringV1 existingString : translatedTopic.getTranslatedTopicStrings_OTM().getItems())
								{
									final RESTTranslatedTopicStringV1 translatedTopicString = new RESTTranslatedTopicStringV1();
									translatedTopicString.setId(existingString.getId());
									translatedTopicString.setRemoveItem(true);
									
									translatedTopicStrings.addItem(translatedTopicString);
								}
							}

							/* save the strings to TranslatedTopicString entities */							
							for (final String originalText : translations.keySet())
							{
								final String translation = translations.get(originalText);

								final RESTTranslatedTopicStringV1 translatedTopicString = new RESTTranslatedTopicStringV1();
								translatedTopicString.explicitSetOriginalString(originalText);
								translatedTopicString.explicitSetTranslatedString(translation);
								translatedTopicString.setAddItem(true);

								translatedTopicStrings.addItem(translatedTopicString);
							}
							translatedTopic.explicitSetTranslatedTopicString_OTM(translatedTopicStrings);

							if (ComponentBaseTopicV1.hasTag(translatedHistoricalTopic, CommonConstants.CONTENT_SPEC_TAG_ID))
							{
								/* Parse the Content Spec stored in the XML Field */
								final ContentSpecParser parser = new ContentSpecParser(skynetServer);
								
								/*
								 * replace the translated strings, and save the result into the TranslatedTopicData entity
								 */
								if (parser.parse(translatedHistoricalTopic.getXml()))
								{
									final ContentSpec spec = parser.getContentSpec();
									ContentSpecUtilities.replaceTranslatedStrings(spec, translations);
									translatedTopic.explicitSetXml(spec.toString());
								}
							}
							else
							{
								/* get a Document from the stored historical XML */
								final Document xml = XMLUtilities.convertStringToDocument(translatedHistoricalTopic.getXml());
	
								/*
								 * replace the translated strings, and save the result into the TranslatedTopicData entity
								 */
								if (xml != null)
								{
									XMLUtilities.replaceTranslatedStrings(xml, translations);
									translatedTopic.explicitSetXml(XMLUtilities.convertDocumentToString(xml, XML_ENCODING));
								}
							}

							/* Only save the data if the content has changed */
							if (!translatedXML.equals(translatedTopic.getXml()) || translatedTopic.getId() == null)
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
