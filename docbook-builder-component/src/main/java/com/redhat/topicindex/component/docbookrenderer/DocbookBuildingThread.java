package com.redhat.topicindex.component.docbookrenderer;

import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.ws.rs.core.PathSegment;

import net.ser1.stomp.Client;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.resteasy.client.ProxyFactory;
import org.jboss.resteasy.specimpl.PathSegmentImpl;
import com.redhat.ecs.commonutils.CollectionUtilities;
import com.redhat.ecs.commonutils.DocBookUtilities;
import com.redhat.ecs.commonutils.ExceptionUtilities;
import com.redhat.ecs.commonutils.NotificationUtilities;
import com.redhat.ecs.commonutils.ZipUtilities;
import com.redhat.ecs.constants.CommonConstants;
import com.redhat.ecs.servicepojo.ServiceStarter;
import com.redhat.ecs.services.commonstomp.BaseStompRunnable;
import com.redhat.ecs.services.docbookcompiling.BuildDocbookMessage;
import com.redhat.ecs.services.docbookcompiling.DocbookBuilderConstants;
import com.redhat.ecs.services.docbookcompiling.DocbookBuildingOptions;
import com.redhat.topicindex.messaging.TopicRendererType;
import com.redhat.topicindex.rest.collections.BaseRestCollectionV1;
import com.redhat.topicindex.rest.entities.BlobConstantV1;
import com.redhat.topicindex.rest.entities.interfaces.RESTBaseTopicV1;
import com.redhat.topicindex.rest.entities.interfaces.RESTTopicV1;
import com.redhat.topicindex.rest.entities.interfaces.RESTTranslatedTopicV1;
import com.redhat.topicindex.rest.exceptions.InternalProcessingException;
import com.redhat.topicindex.rest.exceptions.InvalidParameterException;
import com.redhat.topicindex.rest.expand.ExpandDataDetails;
import com.redhat.topicindex.rest.expand.ExpandDataTrunk;
import com.redhat.topicindex.rest.sharedinterface.RESTInterfaceV1;

import com.redhat.contentspec.ContentSpec;
import com.redhat.contentspec.builder.DocbookBuilder;
import com.redhat.contentspec.rest.RESTManager;
import com.redhat.contentspec.structures.CSDocbookBuildingOptions;
import com.redhat.contentspec.utils.ContentSpecGenerator;
import com.redhat.contentspec.utils.logging.ErrorLoggerManager;

public class DocbookBuildingThread extends BaseStompRunnable
{	
	/** The REST client */
	private final RESTInterfaceV1 restClient;
	
	/** Jackson object mapper */
	private final ObjectMapper mapper = new ObjectMapper();
	
	private BlobConstantV1 rocbookdtd;
	
	public DocbookBuildingThread(final ServiceStarter serviceStarter, final Client client, final String message, final Map<String, String> headers, final boolean shutdownRequested)
	{
		super(client, serviceStarter, message, headers, shutdownRequested);
		this.restClient = ProxyFactory.create(RESTInterfaceV1.class, serviceStarter.getSkynetServer());
		try {
			rocbookdtd = restClient.getJSONBlobConstant(DocbookBuilderConstants.ROCBOOK_DTD_BLOB_ID, "");
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	@Override
	public void run()
	{
		try
		{

			/* early exit if shutdown has been requested */
			if (this.isShutdownRequested())
			{
				this.resendMessage();
				return;
			}

			NotificationUtilities.dumpMessageToStdOut("Processing Docbook Build Request");

			final BuildDocbookMessage buildDocbookMessage = mapper.readValue(this.getMessage(), BuildDocbookMessage.class);

			if (buildDocbookMessage.getEntityType() == TopicRendererType.TOPIC)
			{
				buildTopicBook(buildDocbookMessage);
			}
			else if (buildDocbookMessage.getEntityType() == TopicRendererType.TRANSLATEDTOPIC)
			{
				buildTranslatedTopicBook(buildDocbookMessage);
			}
		}
		catch (final Exception ex)
		{
			ex.printStackTrace();
		}
		finally
		{
			/* ACK the message */
			/*
			 * if (this.headers.containsKey("message-id")) { final Map<String,
			 * String> header = new HashMap<String, String>();
			 * header.put("message-id", this.headers.get("message-id"));
			 * 
			 * try { client.transmit(Command.ACK, header, null); } catch (final
			 * Exception ex) {
			 * 
			 * } }
			 */
		}
	}
	
	private void buildTopicBook(final BuildDocbookMessage buildDocbookMessage)
			 throws InvalidParameterException, InternalProcessingException, JsonGenerationException, JsonMappingException, IOException
	{		
		/*
		 * Make sure we have a valid set of options before compiling the
		 * docbook
		 */
		if (buildDocbookMessage.getDocbookOptions() != null && buildDocbookMessage.getDocbookOptions().isValid())
		{	
			
			/*
			 * The message is a query that we will send the rest server,
			 * making sure to get all the tags associated with the topics,
			 * and the categories associated with the tags.
			 */

			NotificationUtilities.dumpMessageToStdOut("\tGetting Topic Collection with query " + buildDocbookMessage.getQuery());

			final PathSegment pathSegment = new PathSegmentImpl(buildDocbookMessage.getQuery(), false);
			
			final ExpandDataTrunk expand = new ExpandDataTrunk();

			final ExpandDataTrunk topicsExpand = new ExpandDataTrunk(new ExpandDataDetails("topics"));
			final ExpandDataTrunk tags = new ExpandDataTrunk(new ExpandDataDetails("tags"));
			final ExpandDataTrunk properties = new ExpandDataTrunk(new ExpandDataDetails(RESTTopicV1.PROPERTIES_NAME));
			final ExpandDataTrunk categories = new ExpandDataTrunk(new ExpandDataDetails("categories"));
			final ExpandDataTrunk parentTags = new ExpandDataTrunk(new ExpandDataDetails("parenttags"));
			final ExpandDataTrunk outgoingRelationships = new ExpandDataTrunk(new ExpandDataDetails("outgoingRelationships"));
			
			/* We need to expand the categories collection on the topic tags */
			tags.setBranches(CollectionUtilities.toArrayList(categories, parentTags, properties));
			outgoingRelationships.setBranches(CollectionUtilities.toArrayList(tags, properties));
			topicsExpand.setBranches(CollectionUtilities.toArrayList(tags, outgoingRelationships, properties));

			expand.setBranches(CollectionUtilities.toArrayList(topicsExpand));

			final String expandString = mapper.writeValueAsString(expand);
			final String expandEncodedStrnig = URLEncoder.encode(expandString, "UTF-8");

			final BaseRestCollectionV1<RESTTopicV1> topics = restClient.getJSONTopicsWithQuery(pathSegment, expandEncodedStrnig);

			/*
			 * Construct the URL that will show us the topics used in
			 * this Docbook build
			 */
			final String searchTagsUrl = CommonConstants.FULL_SERVER_URL + "/CustomSearchTopicList.seam?" + buildDocbookMessage.getQuery().replaceAll(";", "&amp;");
			
			buildAndEmailFromTopics(RESTTopicV1.class, topics, buildDocbookMessage.getDocbookOptions(), searchTagsUrl);
		}
		else
		{
			NotificationUtilities.dumpMessageToStdOut("Invalid Docbook Compilation Options");
		}
	}
	
	private void buildTranslatedTopicBook(final BuildDocbookMessage buildDocbookMessage)
			 throws InvalidParameterException, InternalProcessingException, JsonGenerationException, JsonMappingException, IOException
	{	
		/*
		 * Make sure we have a valid set of options before compiling the
		 * docbook
		 */
		if (buildDocbookMessage.getDocbookOptions() != null && buildDocbookMessage.getDocbookOptions().isValid())
		{
			
			/*
			 * The message is a query that we will send the rest server,
			 * making sure to get all the tags associated with the topics,
			 * and the categories associated with the tags.
			 */

			NotificationUtilities.dumpMessageToStdOut("\tGetting Translated Topic Collection with query " + buildDocbookMessage.getQuery());

			final PathSegment pathSegment = new PathSegmentImpl(buildDocbookMessage.getQuery(), false);

			final ExpandDataTrunk expand = new ExpandDataTrunk();

			final ExpandDataTrunk translatedTopicsExpand = new ExpandDataTrunk(new ExpandDataDetails("translatedtopics"));
			final ExpandDataTrunk topicExpandTranslatedTopics = new ExpandDataTrunk(new ExpandDataDetails(RESTTopicV1.TRANSLATEDTOPICS_NAME));
			final ExpandDataTrunk tags = new ExpandDataTrunk(new ExpandDataDetails("tags"));
			final ExpandDataTrunk properties = new ExpandDataTrunk(new ExpandDataDetails(RESTTopicV1.PROPERTIES_NAME));
			final ExpandDataTrunk categories = new ExpandDataTrunk(new ExpandDataDetails("categories"));
			final ExpandDataTrunk parentTags = new ExpandDataTrunk(new ExpandDataDetails("parenttags"));
			final ExpandDataTrunk outgoingRelationships = new ExpandDataTrunk(new ExpandDataDetails(RESTTranslatedTopicV1.ALL_LATEST_OUTGOING_NAME));
			final ExpandDataTrunk topicsExpand = new ExpandDataTrunk(new ExpandDataDetails(RESTTranslatedTopicV1.TOPIC_NAME));
			
			/* We need to expand the categories collection on the topic tags */
			tags.setBranches(CollectionUtilities.toArrayList(categories, parentTags, properties));
			outgoingRelationships.setBranches(CollectionUtilities.toArrayList(tags, properties, topicsExpand));
			
			topicsExpand.setBranches(CollectionUtilities.toArrayList(topicExpandTranslatedTopics));
			
			translatedTopicsExpand.setBranches(CollectionUtilities.toArrayList(tags, outgoingRelationships, properties, topicsExpand));

			expand.setBranches(CollectionUtilities.toArrayList(translatedTopicsExpand));

			final String expandString = mapper.writeValueAsString(expand);
			final String expandEncodedStrnig = URLEncoder.encode(expandString, "UTF-8");

			final BaseRestCollectionV1<RESTTranslatedTopicV1> topics = restClient.getJSONTranslatedTopicsWithQuery(pathSegment, expandEncodedStrnig);
			
			/*
			 * Construct the URL that will show us the topics used in
			 * this Docbook build
			 */
			final String searchTagsUrl = CommonConstants.FULL_SERVER_URL + "/GroupedTranslatedTopicDataLocaleList.seam?" + buildDocbookMessage.getQuery().replaceAll(";", "&amp;");
			
			/* If the "Include all related topics" is selected then we need to include dummy topics */
			if (buildDocbookMessage.getDocbookOptions().getIncludeUntranslatedTopics() != null && buildDocbookMessage.getDocbookOptions().getIncludeUntranslatedTopics())
				addDummyRelatedTranslatedTopics(topics, buildDocbookMessage.getQuery());
			
			if (this.isShutdownRequested())
			{
				return;
			}
			
			buildAndEmailFromTopics(RESTTranslatedTopicV1.class, topics, buildDocbookMessage.getDocbookOptions(), searchTagsUrl);
		}
		else
		{
			NotificationUtilities.dumpMessageToStdOut("Invalid Docbook Compilation Options");
		}
	}
	
	private void addDummyRelatedTranslatedTopics(final BaseRestCollectionV1<RESTTranslatedTopicV1> translatedTopics, final String query) throws JsonGenerationException, JsonMappingException, IOException, InvalidParameterException, InternalProcessingException
	{
		NotificationUtilities.dumpMessageToStdOut("Doing dummy translated topic pass");
		
		/* remove any locale query strings */
		String topicQuery = query.replaceAll("locale\\d*=[a-zA-Z\\-]*\\d+(;|$)", "");
		
		NotificationUtilities.dumpMessageToStdOut("\tGetting Topic Collection with query " + topicQuery);
		
		final PathSegment pathSegment = new PathSegmentImpl(topicQuery, false);

		final ExpandDataTrunk expand = new ExpandDataTrunk();

		final ExpandDataTrunk topicsExpand = new ExpandDataTrunk(new ExpandDataDetails("topics"));
		final ExpandDataTrunk outgoingRelationships = new ExpandDataTrunk(new ExpandDataDetails("outgoingRelationships"));
		final ExpandDataTrunk tags = new ExpandDataTrunk(new ExpandDataDetails("tags"));
		final ExpandDataTrunk properties = new ExpandDataTrunk(new ExpandDataDetails(RESTTopicV1.PROPERTIES_NAME));
		final ExpandDataTrunk categories = new ExpandDataTrunk(new ExpandDataDetails("categories"));
		final ExpandDataTrunk parentTags = new ExpandDataTrunk(new ExpandDataDetails("parenttags"));
		final ExpandDataTrunk expandTranslatedTopics = new ExpandDataTrunk(new ExpandDataDetails(RESTTopicV1.TRANSLATEDTOPICS_NAME));

		/* We need to expand the categories collection on the topic tags */
		tags.setBranches(CollectionUtilities.toArrayList(categories, parentTags, properties));
		outgoingRelationships.setBranches(CollectionUtilities.toArrayList(tags, properties, expandTranslatedTopics));
		topicsExpand.setBranches(CollectionUtilities.toArrayList(tags, outgoingRelationships, properties, expandTranslatedTopics));

		expand.setBranches(CollectionUtilities.toArrayList(topicsExpand));

		final String expandString = mapper.writeValueAsString(expand);
		final String expandEncodedStrnig = URLEncoder.encode(expandString, "UTF-8");

		final BaseRestCollectionV1<RESTTopicV1> topics = restClient.getJSONTopicsWithQuery(pathSegment, expandEncodedStrnig);

		
		/* Split the topics up into their different locales */
		final Map<String, Map<Integer, RESTTranslatedTopicV1>> groupedLocaleTopics = new HashMap<String, Map<Integer, RESTTranslatedTopicV1>>();
		for (final RESTTranslatedTopicV1 topic: translatedTopics.getItems())
		{
			if (!groupedLocaleTopics.containsKey(topic.getLocale()))
				groupedLocaleTopics.put(topic.getLocale(), new HashMap<Integer, RESTTranslatedTopicV1>());
			groupedLocaleTopics.get(topic.getLocale()).put(topic.getTopicId(), topic);
		}
		
		/* create and add the dummy topics per locale */
		for (final String locale : groupedLocaleTopics.keySet())
		{
			final Map<Integer, RESTTranslatedTopicV1> translatedTopicsMap = groupedLocaleTopics.get(locale);
			for (final RESTTopicV1 topic: topics.getItems())
			{
				if (this.isShutdownRequested())
				{
					return;
				}
				
				if (!translatedTopicsMap.containsKey(topic.getId()))
				{
					final RESTTranslatedTopicV1 dummyTopic = createDummyTranslatedTopic(translatedTopicsMap, topic, true, locale);
					
					translatedTopics.addItem(dummyTopic);
				}
			}
		}
	}
	
	/**
	 * Creates a dummy translated topic so that a book
	 * can be built using the same relationships as a 
	 * normal build.
	 * 
	 * @param translatedTopicsMap A map of topic ids to translated topics
	 * @param topic The topic to create the dummy topic from
	 * @param expandRelationships Whether the relationships should be expanded for the dummy topic
	 * @return The dummy translated topic
	 */
	private RESTTranslatedTopicV1 createDummyTranslatedTopic(final Map<Integer, RESTTranslatedTopicV1> translatedTopicsMap, final RESTTopicV1 topic, final boolean expandRelationships, final String locale)
	{
		if (this.isShutdownRequested())
		{
			return null;
		}
		
		final RESTTranslatedTopicV1 translatedTopic = new RESTTranslatedTopicV1();
		
		translatedTopic.setId(topic.getId() * -1);
		translatedTopic.setTopicId(topic.getId());
		translatedTopic.setTopicRevision(topic.getRevision().intValue());
		translatedTopic.setTopic(topic);
		translatedTopic.setTranslationPercentage(100);
		translatedTopic.setRevision(topic.getRevision());
		translatedTopic.setXml(topic.getXml());
		translatedTopic.setTags(topic.getTags());
		translatedTopic.setSourceUrls_OTM(topic.getSourceUrls_OTM());
		translatedTopic.setProperties(topic.getProperties());
		translatedTopic.setLocale(locale);
		
		/* prefix the locale to show that it is missing the related translated topic */
		translatedTopic.setTitle("[" + topic.getLocale() + "] " + topic.getTitle());
		
		/* Add the dummy outgoing relationships */
		if (topic.getOutgoingRelationships() != null && topic.getOutgoingRelationships().getItems() != null)
		{
			final BaseRestCollectionV1<RESTTranslatedTopicV1> outgoingRelationships = new BaseRestCollectionV1<RESTTranslatedTopicV1>();
			for (final RESTTopicV1 relatedTopic : topic.getOutgoingRelationships().getItems())
			{
				if (this.isShutdownRequested())
				{
					return null;
				}
				
				/* check to see if the translated topic already exists */
				if (translatedTopicsMap.containsKey(relatedTopic.getId()))
				{
					outgoingRelationships.addItem(translatedTopicsMap.get(relatedTopic.getId()));
				}
				else
				{
					outgoingRelationships.addItem(createDummyTranslatedTopic(translatedTopicsMap, relatedTopic, false, locale));
				}
			}
			translatedTopic.setOutgoingRelationships(outgoingRelationships);
		}
		
		/* Add the dummy incoming relationships */
		if (topic.getIncomingRelationships() != null && topic.getIncomingRelationships().getItems() != null)
		{
			final BaseRestCollectionV1<RESTTranslatedTopicV1> incomingRelationships = new BaseRestCollectionV1<RESTTranslatedTopicV1>();
			for (final RESTTopicV1 relatedTopic : topic.getIncomingRelationships().getItems())
			{
				if (this.isShutdownRequested())
				{
					return null;
				}
				
				/* check to see if the translated topic already exists */
				if (translatedTopicsMap.containsKey(relatedTopic.getId()))
				{
					incomingRelationships.addItem(translatedTopicsMap.get(relatedTopic.getId()));
				}
				else
				{
					incomingRelationships.addItem(createDummyTranslatedTopic(translatedTopicsMap, relatedTopic, false, locale));
				}
			}
			translatedTopic.setIncomingRelationships(incomingRelationships);
		}
		
		return translatedTopic;
	}
	
	private <T extends RESTBaseTopicV1<T>> void buildAndEmailFromTopics(final Class<T> clazz, final BaseRestCollectionV1<T> topics, final DocbookBuildingOptions docbookBuildingOptions, final String searchTagsUrl)
	{
		if (this.isShutdownRequested())
		{
			return;
		}
		
		if (topics != null && topics.getItems() != null)
		{
			/* Split the topics up into their different locales */
			final Map<String, BaseRestCollectionV1<T>> groupedLocaleTopics = new HashMap<String, BaseRestCollectionV1<T>>();
			for (final T topic: topics.getItems())
			{
				if (!groupedLocaleTopics.containsKey(topic.getLocale()))
					groupedLocaleTopics.put(topic.getLocale(), new BaseRestCollectionV1<T>());
				groupedLocaleTopics.get(topic.getLocale()).addItem(topic);
			}
			
			final HashMap<String, byte[]> files = new HashMap<String, byte[]>();
			boolean success = true;
			for (final String locale: groupedLocaleTopics.keySet())
			{
				if (this.isShutdownRequested())
				{
					return;
				}
				
				final ErrorLoggerManager elm = new ErrorLoggerManager();
				final RESTManager restManager = new RESTManager(elm, getServiceStarter().getSkynetServer());
				final ContentSpecGenerator csGenerator = new ContentSpecGenerator(restClient);
				
				/* Add the topics to the cache to improve loading time */
				restManager.getRESTEntityCache().add(topics);
				
				final ContentSpec contentSpec = csGenerator.generateContentSpecFromTopics(clazz, topics, locale, docbookBuildingOptions);
				
				if (this.isShutdownRequested())
				{
					return;
				}
				
				try {
					final DocbookBuilder<RESTTopicV1> builder = new DocbookBuilder<RESTTopicV1>(restManager, rocbookdtd, CommonConstants.DEFAULT_LOCALE);
					final HashMap<String, byte[]> buildFiles = builder.buildBook(contentSpec, null, new CSDocbookBuildingOptions(docbookBuildingOptions), searchTagsUrl);
					
					/* change the publican.cfg since we have multiple langs */
					if (groupedLocaleTopics.keySet().size() > 1)
					{
						final String publicanFileName = DocBookUtilities.escapeTitle(contentSpec.getTitle()) + "/publican.cfg";
						final String newPublicanFileName = DocBookUtilities.escapeTitle(contentSpec.getTitle()) + "/publican-" + locale + ".cfg";
						byte[] publicanCfg = buildFiles.get(publicanFileName);
						
						/* remove the old file */
						buildFiles.remove(publicanFileName);
						
						/* add in the new file */
						buildFiles.put(newPublicanFileName, publicanCfg);
					}
						
					/*
					 * Resend the message if the files was null. The only reason they will be
					 * null is by a reset message.
					 */
					if (buildFiles == null)
					{
						this.resendMessage();
						return;
					}
					else
					{
						files.putAll(buildFiles);
					}
						
				} catch (Exception ex) {
					ExceptionUtilities.handleException(ex);
					success = false;
				}
			}
			
			if (this.isShutdownRequested())
			{
				return;
			}
			
			if (success && files != null && !files.isEmpty())
			{
				// now create the zip file
				NotificationUtilities.dumpMessageToStdOut("\tBuilding the Publican ZIP file");
				byte[] zipFile = null;
				try
				{
					zipFile = ZipUtilities.createZip(files);
				}
				catch (final Exception ex)
				{
					zipFile = null;
					ExceptionUtilities.handleException(ex);
				}
				
				emailZIP(zipFile, docbookBuildingOptions);
			}
		}
		else
		{
			NotificationUtilities.dumpMessageToStdOut("No Topics to Process");
		}
	}
	
	/**
	 * This function emails the ZIP file to the user
	 * 
	 * @param topics
	 *            The collection of topics to process
	 */
	private void emailZIP(final byte[] zip, final DocbookBuildingOptions docbookBuildingOptions)
	{
		NotificationUtilities.dumpMessageToStdOut("Doing Distribution Pass - Emailing To " + docbookBuildingOptions.getEmailTo());

		// create a date formatter
		final SimpleDateFormat formatter = new SimpleDateFormat(CommonConstants.FILTER_DISPLAY_DATE_FORMAT);

		// Get system properties
		final Properties properties = System.getProperties();

		// Recipient's email ID needs to be mentioned.
		final String to = docbookBuildingOptions.getEmailTo();

		// Sender's email ID needs to be mentioned
		final String from = "donotreply@redhat.com";

		// Get the default Session object.
		final Session session = Session.getDefaultInstance(properties);

		try
		{
			// Create a default MimeMessage object.
			final MimeMessage message = new MimeMessage(session);

			// Set From: header field of the header.
			message.setFrom(new InternetAddress(from));

			// Set To: header field of the header.
			message.addRecipient(Message.RecipientType.TO, new InternetAddress(to));

			// Set Subject: header field
			message.setSubject("Skynet Docbook Build");

			// Create the message part
			final BodyPart messageBodyPart = new MimeBodyPart();

			// Fill the message
			messageBodyPart.setText("The attached file was generated at " + formatter.format(new Date()));

			// Create a multipart message
			final Multipart multipart = new MimeMultipart();

			// Set text message part
			multipart.addBodyPart(messageBodyPart);

			// Create the attachment
			final BodyPart attachmentBodyPart = new MimeBodyPart();
			final String filename = "Book.zip";
			attachmentBodyPart.setContent(zip, CommonConstants.ZIP_MIME_TYPE);
			attachmentBodyPart.setFileName(filename);

			// Set text attachment part
			multipart.addBodyPart(attachmentBodyPart);

			// Send the complete message parts
			message.setContent(multipart);

			// Send message
			Transport.send(message);
		}
		catch (final MessagingException mex)
		{
			mex.printStackTrace();
		}
	}
	
}
