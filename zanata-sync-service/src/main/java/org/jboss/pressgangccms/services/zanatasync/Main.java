package org.jboss.pressgangccms.services.zanatasync;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.pressgangccms.rest.v1.collections.RESTTranslatedTopicCollectionV1;
import org.jboss.pressgangccms.rest.v1.components.ComponentTranslatedTopicV1;
import org.jboss.pressgangccms.rest.v1.entities.RESTTopicV1;
import org.jboss.pressgangccms.rest.v1.entities.RESTTranslatedTopicV1;
import org.jboss.pressgangccms.rest.v1.expansion.ExpandDataDetails;
import org.jboss.pressgangccms.rest.v1.expansion.ExpandDataTrunk;
import org.jboss.pressgangccms.rest.v1.jaxrsinterfaces.RESTInterfaceV1;
import org.jboss.pressgangccms.utils.common.CollectionUtilities;
import org.jboss.pressgangccms.utils.common.ExceptionUtilities;
import org.jboss.pressgangccms.utils.constants.CommonConstants;
import org.jboss.pressgangccms.zanata.ZanataConstants;
import org.jboss.pressgangccms.zanata.ZanataInterface;
import org.jboss.resteasy.client.ProxyFactory;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.zanata.rest.dto.resource.ResourceMeta;

public class Main
{
	private static final String NUMBER_OF_ZANATA_LANGUAGES_PROPERTY = "topicIndex.numberOfZanataLocales";
	private static final String TOTAL_ZANATA_SYNC_TIME_PROPERTY = "topicIndex.zanataSyncTime";
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
	
	/** Static initialisation block to read system properties */
	static {
		try
		{
			Main.numLocales = Integer.parseInt(System.getProperty(NUMBER_OF_ZANATA_LANGUAGES_PROPERTY));
		}
		catch (final NumberFormatException ex)
		{
			
		}
		finally
		{
			/* Stop divide by zero */
			if (Main.numLocales == 0)
				Main.numLocales = 1;
		}
		
		try
		{
			Main.syncTime = Integer.parseInt(System.getProperty(TOTAL_ZANATA_SYNC_TIME_PROPERTY));
		}
		catch (final NumberFormatException ex)
		{
			
		}
	}

	/**
	 * @param args
	 */
	public static void main(String[] args)
	{
		final long startTime = System.currentTimeMillis();
		
		RegisterBuiltin.register(ResteasyProviderFactory.getInstance());

		try
		{
			log.info("Skynet REST: " + skynetServer);
			log.info("Zanata Server: " + zanataServer);
			log.info("Zanata Username: " + zanataUsername);
			log.info("Zanata Token: " + zanataToken);
			log.info("Zanata Project: " + zanataProject);
			log.info("Zanata Project Version: " + zanataVersion);
			log.info("Total Sync Time: " + syncTime / 1000.0 + " seconds or " + syncTime / 1000.0 / 60.0 + " minutes");
			log.info("Estimated Number Of Locales: " + numLocales);

			/* Some sanity checking */
			if (skynetServer == null || skynetServer.trim().isEmpty() || zanataServer == null || zanataServer.trim().isEmpty() || zanataToken == null || zanataToken.trim().isEmpty() || zanataUsername == null || zanataUsername.trim().isEmpty() || zanataProject == null || zanataProject.trim().isEmpty()
					|| zanataVersion == null || zanataVersion.trim().isEmpty())
			{
				log.error("The " + CommonConstants.SKYNET_SERVER_SYSTEM_PROPERTY + ", " + ZanataConstants.ZANATA_SERVER_PROPERTY + ", " + ZanataConstants.ZANATA_TOKEN_PROPERTY + ", " + ZanataConstants.ZANATA_USERNAME_PROPERTY + ", " + ZanataConstants.ZANATA_SERVER_PROPERTY + " and "
						+ ZanataConstants.ZANATA_PROJECT_VERSION_PROPERTY + " system properties need to be defined.");
				return;
			}

			/* Create a custom ObjectMapper to handle the mapping between the interfaces and the concrete classes */
			final RESTInterfaceV1 client = ProxyFactory.create(RESTInterfaceV1.class, skynetServer);
			
			/* Get the Zanata resources */
			final ZanataInterface zanataInterface = new ZanataInterface();
			final List<ResourceMeta> zanataResources = zanataInterface.getZanataResources();
			final List<String> existingZanataResources = new ArrayList<String>();	
			
			final int numberZanataTopics = zanataResources.size();
			
			System.out.println("Found " + numberZanataTopics + " topics in Zanata.");
			
			/* Let the worker threads know how long they should spend on each locale and topic */
			if (ZanataPullWorkQueue.getInstance().getNumThreads() != 0 && numberZanataTopics != 0 && numLocales != 0)
			{
				final long timePerTopicPerLocale = syncTime / numberZanataTopics / numLocales * ZanataPullWorkQueue.getInstance().getNumThreads();
				ZanataPullTopicThread.setSyncTimePerTopicPerLocale(timePerTopicPerLocale);
				
				System.out.println("Each sync request will take at least " + (timePerTopicPerLocale / 1000.0) + " seconds");
			}

			// Get the Skynet Resources			
			final ExpandDataTrunk expand = new ExpandDataTrunk();
			final ExpandDataTrunk expandTranslatedTopic = new ExpandDataTrunk(new ExpandDataDetails("translatedtopics"));
			final ExpandDataTrunk expandTopic = new ExpandDataTrunk(new ExpandDataDetails(RESTTranslatedTopicV1.TOPIC_NAME));
			final ExpandDataTrunk expandTopicTags = new ExpandDataTrunk(new ExpandDataDetails(RESTTopicV1.TAGS_NAME));
			final ExpandDataTrunk expandTopicTranslations = new ExpandDataTrunk(new ExpandDataDetails(RESTTopicV1.TRANSLATEDTOPICS_NAME));
			final ExpandDataTrunk expandTopicTranslationStrings = new ExpandDataTrunk(new ExpandDataDetails(RESTTranslatedTopicV1.TRANSLATEDTOPICSTRING_NAME));

			expandTopicTranslations.setBranches(CollectionUtilities.toArrayList(expandTopicTranslationStrings));
			expandTopic.setBranches(CollectionUtilities.toArrayList(expandTopicTranslations, expandTopicTags));
			expandTranslatedTopic.setBranches(CollectionUtilities.toArrayList(expandTopic));
			expand.setBranches(CollectionUtilities.toArrayList(expandTranslatedTopic));
			
			/* A map to hold Zanata document ids to Topics */
			final Map<String, RESTTopicV1> translatedTopicsMap = new HashMap<String, RESTTopicV1>();
			
			// Start comment block here to test single file
			// convert the ExpandDataTrunk to an encoded JSON String
			
			final String expandString = mapper.writeValueAsString(expand);

			final RESTTranslatedTopicCollectionV1 translatedTopics = client.getJSONTranslatedTopics(expandString);
			System.out.println("Found " + translatedTopics.getItems().size() + " topics in Skynet.");

			// Loop through and find the zanata ID and relevant original topics
			
			if (translatedTopics != null && translatedTopics.getItems() != null)
			{
				for (final RESTTranslatedTopicV1 translatedTopic : translatedTopics.getItems())
				{				
					final String zanataId = ComponentTranslatedTopicV1.returnZanataId(translatedTopic);

					if (!translatedTopicsMap.containsKey(zanataId))
					{
						translatedTopicsMap.put(zanataId, translatedTopic.getTopic());
					}
				}
			}
			else
			{
				System.out.println("Did not recieve expected response from REST service.");
				System.exit(1);
			}
			
			// End comment block here to test single file
			
			/* load a single topic to test with */
			/*final ExpandDataTrunk expandSingle = new ExpandDataTrunk();
			expandSingle.setBranches(CollectionUtilities.toArrayList(expandTopic));
			final String expandSingleString = mapper.writeValueAsString(expandSingle);
			
			final RESTTranslatedTopicV1 translatedTopic = client.getJSONTranslatedTopic(1492, expandSingleString);
			final String myZanataId = ComponentTranslatedTopicV1.returnZanataId(translatedTopic);

			if (!translatedTopicsMap.containsKey(myZanataId))
			{
				translatedTopicsMap.put(myZanataId, translatedTopic.getTopic());
			}*/

			for (final String zanataId : translatedTopicsMap.keySet())
			{
				/*
				 * Pull each topic in a separate thread to decrease total
				 * processing time
				 */
				ZanataPullWorkQueue.getInstance().execute(new ZanataPullTopicThread(translatedTopicsMap.get(zanataId), skynetServer));

				/* add the zanata id to the list of existing resources */
				existingZanataResources.add(zanataId);
			}

			/* create the missing translated topics */
			if (zanataResources != null)
			{
				for (ResourceMeta resource : zanataResources)
				{
					final String resourceName = resource.getName();

					if (!existingZanataResources.contains(resourceName) && resourceName.indexOf("-") != -1)
					{
						/* Comment out this line to stop the service creating new records in Skynet when they don't exist */
						createTranslatedTopicFromZanataResource(resource, client);
					}
				}
			}

			/* Sleep for a little to give the threads time to start executing */
			Thread.sleep(100);

			/* wait for the threads to finish and then exit */
			while (!(ZanataPullWorkQueue.getInstance().getQueuedItemCount() == 0 && ZanataPullWorkQueue.getInstance().getRunningThreadsCount() == 0))
			{
				Thread.sleep(100);
			}

			log.info("All Translations synced. Exiting...");
			System.exit(0);
		}
		catch (Exception ex)
		{
			ExceptionUtilities.handleException(ex);
		}
		finally
		{
			final long endTime = System.currentTimeMillis();
			final long duration = endTime - startTime;
			System.out.println("Total sync time was " + duration / 1000.0 + " seconds or " + duration / 1000.0 / 60.0 + " minutes or " + duration / 1000.0 / 60.0 / 60.0 + " hours");
		}
	}

	private static void createTranslatedTopicFromZanataResource(final ResourceMeta resource, final RESTInterfaceV1 client) throws Exception
	{
		try
		{
			final String resourceName = resource.getName();

			/* Get the Topic ID and Revision */
			Integer id = null, revision = null;
			try
			{
				id = Integer.parseInt(resourceName.substring(0, resourceName.indexOf("-")));
				revision = Integer.parseInt(resourceName.substring(resourceName.indexOf("-") + 1));
			}
			catch (NumberFormatException ex)
			{/* Do Nothing */
			}

			if (id != null && revision != null)
			{
				/* check that the historical topic actually exists */
				RESTTopicV1 historicalTopic = null;
				try
				{
					historicalTopic = client.getJSONTopicRevision(id, revision, "");
				}
				catch (Exception e)
				{/* Do Nothing */
				}

				if (historicalTopic != null && historicalTopic.getLocale().equals(resource.getLang().toString()))
				{
					/* create the new translated topic */
					RESTTranslatedTopicV1 newTranslatedTopic = new RESTTranslatedTopicV1();
					newTranslatedTopic.explicitSetTopicId(id);
					newTranslatedTopic.explicitSetTopicRevision(revision);

					/* create the base language data */
					newTranslatedTopic.setAddItem(true);
					newTranslatedTopic.explicitSetXml(historicalTopic.getXml());
					newTranslatedTopic.explicitSetLocale(resource.getLang().toString());
					newTranslatedTopic.explicitSetTranslationPercentage(100);

					newTranslatedTopic = client.createJSONTranslatedTopic("", newTranslatedTopic);

					final RESTTranslatedTopicCollectionV1 translatedTopics = new RESTTranslatedTopicCollectionV1();
					translatedTopics.addItem(newTranslatedTopic);

					historicalTopic.setTranslatedTopics_OTM(translatedTopics);

					if (newTranslatedTopic != null)
					{
						/*
						 * Pull the data from zanata for the new translated
						 * topic
						 */
						ZanataPullWorkQueue.getInstance().execute(new ZanataPullTopicThread(historicalTopic, skynetServer));
					}
				}
			}
		}
		catch (final Exception ex)
		{
			ExceptionUtilities.handleException(ex);
		}
	}
}
