package com.redhat.topicindex.component.topicrenderer.utils;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.xml.transform.TransformerException;

import net.ser1.stomp.Client;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.resteasy.client.ProxyFactory;
import org.w3c.dom.Document;

import com.redhat.ecs.commonstructures.Pair;
import com.redhat.ecs.commonutils.CollectionUtilities;
import com.redhat.ecs.commonutils.NotificationUtilities;
import com.redhat.ecs.commonutils.XMLUtilities;
import com.redhat.ecs.commonutils.XMLValidator;
import com.redhat.ecs.servicepojo.ServiceStarter;
import com.redhat.ecs.services.commonstomp.BaseStompRunnable;
import com.redhat.ecs.services.docbookcompiling.DocbookBuilderConstants;
import com.redhat.ecs.services.docbookcompiling.xmlprocessing.TranslatedXMLPreProcessor;
import com.redhat.ecs.services.docbookcompiling.xmlprocessing.XMLPreProcessor;
import com.redhat.topicindex.component.topicrenderer.Main;
import com.redhat.topicindex.messaging.DocbookRendererMessage;
import com.redhat.topicindex.messaging.TopicRendererType;
import com.redhat.topicindex.rest.entities.BlobConstantV1;
import com.redhat.topicindex.rest.entities.TopicV1;
import com.redhat.topicindex.rest.entities.TranslatedTopicDataV1;
import com.redhat.topicindex.rest.entities.TranslatedTopicV1;
import com.redhat.topicindex.rest.exceptions.InternalProcessingException;
import com.redhat.topicindex.rest.exceptions.InvalidParameterException;
import com.redhat.topicindex.rest.expand.ExpandDataDetails;
import com.redhat.topicindex.rest.expand.ExpandDataTrunk;
import com.redhat.topicindex.rest.sharedinterface.RESTInterfaceV1;

public class RenderingThread extends BaseStompRunnable
{
	/** Jackson object mapper */
	private final ObjectMapper mapper = new ObjectMapper();
	/** The Rocbook DTD, to be loaded once and then shared */
	static private BlobConstantV1 constants = null;

	public RenderingThread(final Client client, final String message, final Map<String, String> headers, final ServiceStarter serviceStarter, final boolean shutdownRequested)
	{
		super(client, serviceStarter, message, headers, shutdownRequested);
	}

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

			final RESTInterfaceV1 client = ProxyFactory.create(RESTInterfaceV1.class, this.getServiceStarter().getSkynetServer());

			/* load the dtd once */
			synchronized (RenderingThread.class)
			{
				if (constants == null)
					constants = client.getJSONBlobConstant(DocbookBuilderConstants.ROCBOOK_DTD_BLOB_ID, "");
			}

			/* Get the id of the topic we are updating */
			final DocbookRendererMessage message = mapper.readValue(this.getMessage(), DocbookRendererMessage.class);

			if (message.entityType == TopicRendererType.TOPIC)
				renderTopic(client, message.entityId);
			else if (message.entityType == TopicRendererType.TRANSLATEDTOPIC)
				renderTranslatedTopic(client, message.entityId);
		}
		catch (final Exception ex)
		{
			ex.printStackTrace();
		}
		finally
		{
			/*
			 * ACKing currently doesn't work -
			 * https://issues.jboss.org/browse/HORNETQ-727
			 */

			/*
			 * if (headers.containsKey("message-id")) { final Map<String,
			 * String> ackHeader = new HashMap<String, String>();
			 * ackHeader.put("message-id", headers.get("message-id"));
			 * 
			 * client.transmit(Command.ACK, ackHeader, null); }
			 */
		}
	}
	
	private void renderTranslatedTopic(final RESTInterfaceV1 client, final int translatedTopicDataId) throws JsonGenerationException, JsonMappingException, IOException, InvalidParameterException, InternalProcessingException
	{
		/* get the translated data */
		final ExpandDataTrunk expand = new ExpandDataTrunk();
		final ExpandDataTrunk expandTranslatedTopic = new ExpandDataTrunk(new ExpandDataDetails(TranslatedTopicDataV1.TRANSLATEDTOPIC_NAME));
		final ExpandDataTrunk expandOutgoingRelationship = new ExpandDataTrunk(new ExpandDataDetails(TranslatedTopicDataV1.OUTGOING_TRANSLATIONS_NAME));
		final ExpandDataTrunk expandIncomingRelationship = new ExpandDataTrunk(new ExpandDataDetails(TranslatedTopicDataV1.INCOMING_TRANSLATIONS_NAME));
		
		expand.setBranches(CollectionUtilities.toArrayList(expandTranslatedTopic, expandOutgoingRelationship, expandIncomingRelationship));
		
		/* convert the ExpandDataTrunk to an encoded JSON String */
		final String expandString = mapper.writeValueAsString(expand);
		final String expandEncodedString = URLEncoder.encode(expandString, "UTF-8");
		
		final TranslatedTopicDataV1 translatedTopicData = client.getJSONTranslatedTopicData(translatedTopicDataId, expandEncodedString);

		if (translatedTopicData != null && translatedTopicData.getTranslatedTopic() != null)
		{
			final TranslatedTopicV1 translatedTopic = translatedTopicData.getTranslatedTopic();
			
			/*
			 * create an ExpandDataTrunk to expand the topics, the tags within the
			 * topics, and the categories and parenttags within the tags
			 */
			ExpandDataTrunk expandTopic = new ExpandDataTrunk();

			final ExpandDataTrunk outgoingRelationships = new ExpandDataTrunk(new ExpandDataDetails("outgoingRelationships"));
			final ExpandDataTrunk outgoingRelationshipsTags = new ExpandDataTrunk(new ExpandDataDetails("tags"));
			final ExpandDataTrunk outgoingRelationshipsTagsCategories = new ExpandDataTrunk(new ExpandDataDetails("categories"));
			final ExpandDataTrunk propertyTags = new ExpandDataTrunk(new ExpandDataDetails("properties"));

			outgoingRelationships.setBranches(CollectionUtilities.toArrayList(outgoingRelationshipsTags));
			outgoingRelationshipsTags.setBranches(CollectionUtilities.toArrayList(outgoingRelationshipsTagsCategories));

			final ExpandDataTrunk tags = new ExpandDataTrunk(new ExpandDataDetails("tags"));
			final ExpandDataTrunk tagsCategories = new ExpandDataTrunk(new ExpandDataDetails("categories"));

			tags.setBranches(CollectionUtilities.toArrayList(tagsCategories, propertyTags));

			expandTopic.setBranches(CollectionUtilities.toArrayList(outgoingRelationships, tags));

			/* convert the ExpandDataTrunk to an encoded JSON String */
			final String expandTopicString = mapper.writeValueAsString(expandTopic);
			final String expandTopicEncodedString = URLEncoder.encode(expandTopicString, "UTF-8");

			/* get the topic list */
			final TopicV1 topic = client.getJSONTopicRevision(translatedTopic.getTopicId(), translatedTopic.getTopicRevision(), expandTopicEncodedString);
			
			/* early exit if shutdown has been requested */
			if (this.isShutdownRequested())
			{
				this.resendMessage();
				return;
			}

			NotificationUtilities.dumpMessageToStdOut("Processing TranslatedTopic " + translatedTopic.getId() + "-" + translatedTopicData.getTranslationLocale() + ": " + topic.getTitle());
			
			if (topic != null) {
				
				/* the object we will send back to do the update */
				final TranslatedTopicDataV1 updatedTranslatedTopicV1 = new TranslatedTopicDataV1();
				updatedTranslatedTopicV1.setId(translatedTopicData.getId());
				updatedTranslatedTopicV1.setTranslatedTopic(translatedTopic);
				
				final XMLValidator validator = new XMLValidator();
	
				final Document doc = XMLUtilities.convertStringToDocument(translatedTopicData.getTranslatedXml());
				if (doc != null)
				{
					
					/*
					 * create a collection of the tags that make up the topics types
					 * that will be included in generic injection points
					 */
					final List<Pair<Integer, String>> topicTypeTagDetails = new ArrayList<Pair<Integer, String>>();
					topicTypeTagDetails.add(Pair.newPair(DocbookBuilderConstants.TASK_TAG_ID, DocbookBuilderConstants.TASK_TAG_NAME));
					topicTypeTagDetails.add(Pair.newPair(DocbookBuilderConstants.REFERENCE_TAG_ID, DocbookBuilderConstants.REFERENCE_TAG_NAME));
					topicTypeTagDetails.add(Pair.newPair(DocbookBuilderConstants.CONCEPT_TAG_ID, DocbookBuilderConstants.CONCEPT_TAG_NAME));
					topicTypeTagDetails.add(Pair.newPair(DocbookBuilderConstants.CONCEPTUALOVERVIEW_TAG_ID, DocbookBuilderConstants.CONCEPTUALOVERVIEW_TAG_NAME));

					final TranslatedXMLPreProcessor translatedXMLPreProcessor = new TranslatedXMLPreProcessor();
					final ArrayList<Integer> customInjectionIds = new ArrayList<Integer>();
					translatedXMLPreProcessor.processInjections(true, translatedTopicData, topic, customInjectionIds, doc, null, null, false);
					translatedXMLPreProcessor.processGenericInjections(true,translatedTopicData, topic, doc, customInjectionIds, topicTypeTagDetails, null, null, false);
					XMLPreProcessor.processInternalImageFiles(doc);

					translatedXMLPreProcessor.processTopicContentFragments(translatedTopicData, topic, doc, null);
					translatedXMLPreProcessor.processTopicTitleFragments(translatedTopicData, topic, doc, null);
					
					translatedXMLPreProcessor.processTitleErrors(doc);
					
					/*
					 * Validate the topic after injections as Injections such as
					 * "InjectListItems" won't validate until after injections
					 */
					if (validator.validateTopicXML(doc, constants.getName(), constants.getValue()) == null)
					{
						updatedTranslatedTopicV1.setTranslatedXmlRenderedExplicit(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
					}
					else
					{
						/* add the standard boilerplate xml */
						/* currently disabled until how the additions should be displayed are figured out */
						XMLPreProcessor.processTopicAdditionalInfo(topic, doc, null, Main.NAME + " " + Main.BUILD, null);
	
						/* render the topic html */
						final String processedXML = XMLUtilities.convertDocumentToString(doc, DocbookBuilderConstants.XML_ENCODING);
						final String processedXMLWithDocType = XMLPreProcessor.processDocumentType(processedXML);
	
						try
						{
							final String transformedXml = XMLRenderer.transformDocbook(processedXMLWithDocType, this.getServiceStarter().getSkynetServer());
	
							updatedTranslatedTopicV1.setTranslatedXmlRenderedExplicit(transformedXml);
						}
						catch (final TransformerException ex)
						{
							updatedTranslatedTopicV1.setTranslatedXmlRenderedExplicit(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
						}
					}
				}
				else
				{
					updatedTranslatedTopicV1.setTranslatedXmlRenderedExplicit(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
				}
				
				/* Set the last changed date to the current date/time */
				updatedTranslatedTopicV1.setUpdatedExplicit(new Date());
				
				client.updateJSONTranslatedTopicData(expandEncodedString, updatedTranslatedTopicV1);

				NotificationUtilities.dumpMessageToStdOut("TranslatedTopic " + translatedTopic.getId() + "-" + translatedTopicData.getTranslationLocale() + " has been updated");
			}
		}
	}

	private void renderTopic(final RESTInterfaceV1 client, final int topicId) throws JsonGenerationException, JsonMappingException, IOException, InvalidParameterException, InternalProcessingException
	{
		/*
		 * create an ExpandDataTrunk to expand the topics, the tags within the
		 * topics, and the categories and parenttags within the tags
		 */
		final ExpandDataTrunk expand = new ExpandDataTrunk();

		final ExpandDataTrunk outgoingRelationships = new ExpandDataTrunk(new ExpandDataDetails("outgoingRelationships"));
		final ExpandDataTrunk outgoingRelationshipsTags = new ExpandDataTrunk(new ExpandDataDetails("tags"));
		final ExpandDataTrunk outgoingRelationshipsTagsCategories = new ExpandDataTrunk(new ExpandDataDetails("categories"));
		final ExpandDataTrunk propertyTags = new ExpandDataTrunk(new ExpandDataDetails("properties"));

		outgoingRelationships.setBranches(CollectionUtilities.toArrayList(outgoingRelationshipsTags));
		outgoingRelationshipsTags.setBranches(CollectionUtilities.toArrayList(outgoingRelationshipsTagsCategories));

		final ExpandDataTrunk tags = new ExpandDataTrunk(new ExpandDataDetails("tags"));
		final ExpandDataTrunk tagsCategories = new ExpandDataTrunk(new ExpandDataDetails("categories"));

		tags.setBranches(CollectionUtilities.toArrayList(tagsCategories, propertyTags));

		expand.setBranches(CollectionUtilities.toArrayList(outgoingRelationships, tags));

		/* convert the ExpandDataTrunk to an encoded JSON String */
		final String expandString = mapper.writeValueAsString(expand);
		final String expandEncodedStrnig = URLEncoder.encode(expandString, "UTF-8");

		/* get the topic list */
		final TopicV1 topic = client.getJSONTopic(topicId, expandEncodedStrnig);

		/* early exit if shutdown has been requested */
		if (this.isShutdownRequested())
		{
			this.resendMessage();
			return;
		}

		NotificationUtilities.dumpMessageToStdOut("Processing Topic " + topic.getId() + ": " + topic.getTitle());

		final TopicV1 updatedTopicV1 = new TopicV1();
		updatedTopicV1.setId(topicId);

		final XMLValidator validator = new XMLValidator();

		final Document doc = XMLUtilities.convertStringToDocument(topic.getXml());
		if (doc != null)
		{
			/*
			 * create a collection of the tags that make up the topics types
			 * that will be included in generic injection points
			 */
			final List<Pair<Integer, String>> topicTypeTagDetails = new ArrayList<Pair<Integer, String>>();
			topicTypeTagDetails.add(Pair.newPair(DocbookBuilderConstants.TASK_TAG_ID, DocbookBuilderConstants.TASK_TAG_NAME));
			topicTypeTagDetails.add(Pair.newPair(DocbookBuilderConstants.REFERENCE_TAG_ID, DocbookBuilderConstants.REFERENCE_TAG_NAME));
			topicTypeTagDetails.add(Pair.newPair(DocbookBuilderConstants.CONCEPT_TAG_ID, DocbookBuilderConstants.CONCEPT_TAG_NAME));
			topicTypeTagDetails.add(Pair.newPair(DocbookBuilderConstants.CONCEPTUALOVERVIEW_TAG_ID, DocbookBuilderConstants.CONCEPTUALOVERVIEW_TAG_NAME));

			final ArrayList<Integer> customInjectionIds = new ArrayList<Integer>();
			XMLPreProcessor.processInjections(true, topic, customInjectionIds, doc, null, null, false);
			XMLPreProcessor.processGenericInjections(true, topic, doc, customInjectionIds, topicTypeTagDetails, null, null, false);
			XMLPreProcessor.processInternalImageFiles(doc);

			XMLPreProcessor.processTopicContentFragments(topic, doc, null);
			XMLPreProcessor.processTopicTitleFragments(topic, doc, null);

			/*
			 * Validate the topic after injections as Injections such as
			 * "InjectListItems" won't validate until after injections
			 */
			if (validator.validateTopicXML(doc, constants.getName(), constants.getValue()) == null)
			{
				updatedTopicV1.setHtmlExplicit(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
				updatedTopicV1.setXmlErrorsExplicit(validator.getErrorText());
			}
			else
			{
				/* add the standard boilerplate xml */
				XMLPreProcessor.processTopicAdditionalInfo(topic, doc, null, Main.NAME + " " + Main.BUILD, null);

				/* render the topic html */
				final String processedXML = XMLUtilities.convertDocumentToString(doc, DocbookBuilderConstants.XML_ENCODING);
				final String processedXMLWithDocType = XMLPreProcessor.processDocumentType(processedXML);

				try
				{
					final String transformedXml = XMLRenderer.transformDocbook(processedXMLWithDocType, this.getServiceStarter().getSkynetServer());

					updatedTopicV1.setHtmlExplicit(transformedXml);
					updatedTopicV1.setXmlErrorsExplicit("");
				}
				catch (final TransformerException ex)
				{
					updatedTopicV1.setHtmlExplicit(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
					updatedTopicV1.setXmlErrorsExplicit(ex.toString());
				}
			}
		}
		else
		{
			updatedTopicV1.setHtmlExplicit(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
			updatedTopicV1.setXmlErrorsExplicit(validator.getErrorText());
		}

		client.updateJSONTopic("", updatedTopicV1);

		NotificationUtilities.dumpMessageToStdOut("Topic " + topic.getId() + " has been updated");
	}
}
