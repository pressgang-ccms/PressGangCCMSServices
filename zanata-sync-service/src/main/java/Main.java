import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.resteasy.client.ProxyFactory;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.zanata.rest.dto.resource.ResourceMeta;

import com.redhat.ecs.commonutils.CollectionUtilities;
import com.redhat.ecs.commonutils.ExceptionUtilities;
import com.redhat.ecs.constants.CommonConstants;
import com.redhat.topicindex.rest.collections.BaseRestCollectionV1;
import com.redhat.topicindex.rest.entities.TopicV1;
import com.redhat.topicindex.rest.entities.TranslatedTopicV1;
import com.redhat.topicindex.rest.expand.ExpandDataDetails;
import com.redhat.topicindex.rest.expand.ExpandDataTrunk;
import com.redhat.topicindex.rest.sharedinterface.RESTInterfaceV1;
import com.redhat.topicindex.zanata.ZanataInterface;

public class Main {
	
	private static final Logger log = Logger.getLogger("SkynetZanataSyncService");
	
	/** Jackson object mapper */
	private final static ObjectMapper mapper = new ObjectMapper();
	
	/* Get the system properties */
	private static final String skynetServer = System.getProperty(CommonConstants.SKYNET_SERVER_SYSTEM_PROPERTY);
	private static final String zanataServer = System.getProperty(CommonConstants.ZANATA_SERVER_PROPERTY);
	private static final String zanataToken = System.getProperty(CommonConstants.ZANATA_TOKEN_PROPERTY);
	private static final String zanataUsername = System.getProperty(CommonConstants.ZANATA_USERNAME_PROPERTY);
	private static final String zanataProject = System.getProperty(CommonConstants.ZANATA_PROJECT_PROPERTY);
	private static final String zanataVersion = System.getProperty(CommonConstants.ZANATA_PROJECT_VERSION_PROPERTY);

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		RegisterBuiltin.register(ResteasyProviderFactory.getInstance());
		
		try
		{			
			log.info("Skynet REST: " + skynetServer);
			log.info("Zanata Server: " + zanataServer);
			log.info("Zanata Username: " + zanataUsername);
			log.info("Zanata Token: " + zanataToken);
			log.info("Zanata Project: " + zanataProject);
			log.info("Zanata Project Version: " + zanataVersion);
			
			/* Some sanity checking */
			if (skynetServer == null || skynetServer.trim().isEmpty() || zanataServer == null || zanataServer.trim().isEmpty() || zanataToken == null || zanataToken.trim().isEmpty() || zanataUsername == null || zanataUsername.trim().isEmpty()
					|| zanataProject == null || zanataProject.trim().isEmpty() || zanataVersion == null || zanataVersion.trim().isEmpty())
			{
				log.error("The " + CommonConstants.SKYNET_SERVER_SYSTEM_PROPERTY + ", " + CommonConstants.ZANATA_SERVER_PROPERTY + ", " + CommonConstants.ZANATA_TOKEN_PROPERTY + ", " + CommonConstants.ZANATA_USERNAME_PROPERTY + 
						", " + CommonConstants.ZANATA_SERVER_PROPERTY + " and " + CommonConstants.ZANATA_PROJECT_VERSION_PROPERTY + " system properties need to be defined.");
				return;
			}

			/* Setup the REST interface */ 
			final RESTInterfaceV1 client = ProxyFactory.create(RESTInterfaceV1.class, skynetServer);
			
			/* get the translated data */
			final ExpandDataTrunk expand = new ExpandDataTrunk();
			final ExpandDataTrunk expandTranslatedTopic = new ExpandDataTrunk(new ExpandDataDetails("translatedtopics"));
			final ExpandDataTrunk expandTopic = new ExpandDataTrunk(new ExpandDataDetails(TranslatedTopicV1.TOPIC_NAME));
			final ExpandDataTrunk expandTopicTranslations = new ExpandDataTrunk(new ExpandDataDetails(TopicV1.TRANSLATEDTOPICS_NAME));
			
			expandTopic.setBranches(CollectionUtilities.toArrayList(expandTopicTranslations));
			expandTranslatedTopic.setBranches(CollectionUtilities.toArrayList(expandTopic));
			expand.setBranches(CollectionUtilities.toArrayList(expandTranslatedTopic));
			
			/* convert the ExpandDataTrunk to an encoded JSON String */
			final String expandString = mapper.writeValueAsString(expand);
			final String expandEncodedString = URLEncoder.encode(expandString, "UTF-8");
			
			BaseRestCollectionV1<TranslatedTopicV1> translatedTopics = client.getJSONTranslatedTopics(expandEncodedString);
			List<ResourceMeta> zanataResources = ZanataInterface.getInstance().getZanataResources();
			List<String> existingZanataResources = new ArrayList<String>();
			
			/* Loop through and find the zanata ID and relevant original topics */
			final Map<String, TopicV1> translatedTopicsMap = new HashMap<String, TopicV1>(); 
			if (translatedTopics != null && translatedTopics.getItems() != null)
			{
				for (final TranslatedTopicV1 translatedTopic: translatedTopics.getItems())
				{
					final String zanataId = translatedTopic.getZanataId();
					
					if (!translatedTopicsMap.containsKey(zanataId))
					{
						translatedTopicsMap.put(zanataId, translatedTopic.getTopic());
					}
				}
			}
			
			for (String zanataId : translatedTopicsMap.keySet())
			{	
				/* Pull each topic in a separate thread to decrease total processing time */
				ZanataPullWorkQueue.getInstance().execute(new ZanataPullTopicThread(translatedTopicsMap.get(zanataId), skynetServer));
				
				/* add the zanata id to the list of existing resources*/
				existingZanataResources.add(zanataId);
			}
			
			/* create the missing translated topics */
			if (zanataResources != null) 
			{
				for (ResourceMeta resource: zanataResources) 
				{
					final String resourceName = resource.getName();
					
					if (!existingZanataResources.contains(resourceName) && resourceName.indexOf("-") != -1)
					{
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
	}

	private static void createTranslatedTopicFromZanataResource(final ResourceMeta resource, final RESTInterfaceV1 client) throws Exception
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
		{/* Do Nothing */}
		
		if (id != null && revision != null)
		{
			/* check that the historical topic actually exists */
			TopicV1 historicalTopic = null;
			try {
				historicalTopic = client.getJSONTopicRevision(id, revision, "");
			}
			catch (Exception e)
			{/* Do Nothing */}
			
			if (historicalTopic != null && historicalTopic.getLocale().equals(resource.getLang().toString()))
			{
				/* create the new translated topic */
				TranslatedTopicV1 newTranslatedTopic = new TranslatedTopicV1();
				newTranslatedTopic.setTopicIdExplicit(id);
				newTranslatedTopic.setTopicRevisionExplicit(revision);
				
				/* create the base language data */
				newTranslatedTopic.setAddItem(true);
				newTranslatedTopic.setXmlExplicit(historicalTopic.getXml());
				newTranslatedTopic.setLocaleExplicit(resource.getLang().toString());
				newTranslatedTopic.setTranslationPercentageExplicit(100);
				
				newTranslatedTopic = client.createJSONTranslatedTopic("", newTranslatedTopic);
				
				BaseRestCollectionV1<TranslatedTopicV1> translatedTopics = new BaseRestCollectionV1<TranslatedTopicV1>();
				translatedTopics.addItem(newTranslatedTopic);
				
				historicalTopic.setTranslatedTopics_OTM(translatedTopics);
				
				if (newTranslatedTopic != null)
				{
					/* Pull the data from zanata for the new translated topic */
					ZanataPullWorkQueue.getInstance().execute(new ZanataPullTopicThread(historicalTopic, skynetServer));
				}
			}
		}
	}
}
