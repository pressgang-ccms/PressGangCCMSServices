package com.redhat.topicindex.component.topicrenderer.utils;

import java.io.IOException;
import java.lang.reflect.Field;
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
import org.w3c.dom.Element;

import com.redhat.contentspec.SpecTopic;
import com.redhat.ecs.commonstructures.Pair;
import com.redhat.ecs.commonutils.CollectionUtilities;
import com.redhat.ecs.commonutils.ExceptionUtilities;
import com.redhat.ecs.commonutils.NotificationUtilities;
import com.redhat.ecs.commonutils.XMLUtilities;
import com.redhat.ecs.commonutils.XMLValidator;
import com.redhat.ecs.servicepojo.ServiceStarter;
import com.redhat.ecs.services.commonstomp.BaseStompRunnable;
import com.redhat.ecs.services.docbookcompiling.DocbookBuilderConstants;
import com.redhat.ecs.services.docbookcompiling.DocbookBuildingOptions;
import com.redhat.ecs.services.docbookcompiling.xmlprocessing.XMLPreProcessor;
import com.redhat.topicindex.component.topicrenderer.Main;
import com.redhat.topicindex.messaging.DocbookRendererMessage;
import com.redhat.topicindex.messaging.TopicRendererType;
import com.redhat.topicindex.rest.entities.BlobConstantV1;
import com.redhat.topicindex.rest.entities.TagV1;
import com.redhat.topicindex.rest.entities.TopicV1;
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
	
	private final DocbookBuildingOptions docbookBuildingOptions = new DocbookBuildingOptions();

	public RenderingThread(final Client client, final String message, final Map<String, String> headers, final ServiceStarter serviceStarter, final boolean shutdownRequested)
	{
		super(client, serviceStarter, message, headers, shutdownRequested);
		docbookBuildingOptions.setInsertSurveyLink(false);
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
		/*
		 * create an ExpandDataTrunk to expand the topics, the tags within the
		 * topics, and the categories and parenttags within the tags
		 */
		ExpandDataTrunk expand = new ExpandDataTrunk();

		final ExpandDataTrunk expandTopic = new ExpandDataTrunk(new ExpandDataDetails(TranslatedTopicV1.TOPIC_NAME));
		final ExpandDataTrunk expandTopicTranslations = new ExpandDataTrunk(new ExpandDataDetails(TopicV1.TRANSLATEDTOPICS_NAME));
		final ExpandDataTrunk outgoingRelationships = new ExpandDataTrunk(new ExpandDataDetails(TranslatedTopicV1.ALL_LATEST_OUTGOING_NAME));
		final ExpandDataTrunk outgoingRelationshipsTags = new ExpandDataTrunk(new ExpandDataDetails(TranslatedTopicV1.TAGS_NAME));
		final ExpandDataTrunk outgoingRelationshipsTagsCategories = new ExpandDataTrunk(new ExpandDataDetails(TagV1.CATEGORIES_NAME));
		final ExpandDataTrunk propertyTags = new ExpandDataTrunk(new ExpandDataDetails(TranslatedTopicV1.PROPERTIES_NAME));

		outgoingRelationships.setBranches(CollectionUtilities.toArrayList(outgoingRelationshipsTags, expandTopic));
		outgoingRelationshipsTags.setBranches(CollectionUtilities.toArrayList(outgoingRelationshipsTagsCategories));
		
		expandTopic.setBranches(CollectionUtilities.toArrayList(expandTopicTranslations));

		final ExpandDataTrunk tags = new ExpandDataTrunk(new ExpandDataDetails(TranslatedTopicV1.TAGS_NAME));
		final ExpandDataTrunk tagsCategories = new ExpandDataTrunk(new ExpandDataDetails(TagV1.CATEGORIES_NAME));

		tags.setBranches(CollectionUtilities.toArrayList(tagsCategories, propertyTags));

		expand.setBranches(CollectionUtilities.toArrayList(outgoingRelationships, tags, expandTopic));

		/* convert the ExpandDataTrunk to an encoded JSON String */
		final String expandString = mapper.writeValueAsString(expand);
		final String expandEncodedString = URLEncoder.encode(expandString, "UTF-8");
		
		final TranslatedTopicV1 translatedTopic = client.getJSONTranslatedTopic(translatedTopicDataId, expandEncodedString);

		if (translatedTopic != null)
		{
			
			/* early exit if shutdown has been requested */
			if (this.isShutdownRequested())
			{
				this.resendMessage();
				return;
			}

			/* Get the translated topic id for easier debugging */
			Integer translatedTopicId = null;
			final Field translatedTopicIdField;
			try {
				translatedTopicIdField = translatedTopic.getClass().getDeclaredField("translatedTopicId");
				translatedTopicIdField.setAccessible(true);
				translatedTopicId = (Integer)translatedTopicIdField.get(translatedTopic);
			} catch (Exception ex) {
				ExceptionUtilities.handleException(ex);
			}
			
			NotificationUtilities.dumpMessageToStdOut("Processing TranslatedTopic " + translatedTopicId + "-" + translatedTopic.getLocale() + ": " + translatedTopic.getTitle());
				
			/* the object we will send back to do the update */
			final TranslatedTopicV1 updatedTranslatedTopicV1 = new TranslatedTopicV1();
			updatedTranslatedTopicV1.setId(translatedTopic.getId());
			
			final XMLValidator validator = new XMLValidator();

			final Document doc = XMLUtilities.convertStringToDocument(translatedTopic.getXml());
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
				
				final XMLPreProcessor<TranslatedTopicV1> xmlPreProcessor = new XMLPreProcessor<TranslatedTopicV1>();
				final SpecTopic specTopic = new SpecTopic(translatedTopic.getTopicId(), translatedTopic.getTitle());
				specTopic.setTopic(translatedTopic);
				
				final ArrayList<Integer> customInjectionIds = new ArrayList<Integer>();
				xmlPreProcessor.processInjections(null, specTopic, customInjectionIds, doc, null, false);
				xmlPreProcessor.processGenericInjections(null, specTopic, doc, customInjectionIds, topicTypeTagDetails, null, false);
				XMLPreProcessor.processInternalImageFiles(doc);

				xmlPreProcessor.processTopicContentFragments(specTopic, doc, null);
				xmlPreProcessor.processTopicTitleFragments(specTopic, doc, null);
				
				/*
				 * Validate the topic after injections as Injections such as
				 * "InjectListItems" won't validate until after injections
				 */
				if (validator.validateTopicXML(doc, constants.getName(), constants.getValue()) == null)
				{
					updatedTranslatedTopicV1.setHtmlExplicit(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
					updatedTranslatedTopicV1.setXmlErrorsExplicit(validator.getErrorText());
				}
				else
				{
					/* add the standard boilerplate xml */
					/* currently disabled until how the additions should be displayed are figured out */
					xmlPreProcessor.processTopicAdditionalInfo(specTopic, doc, docbookBuildingOptions, Main.NAME + " " + Main.BUILD, null, new Date());

					/* Generate the note for the translated topics relationships that haven't been translated */
					if (translatedTopic.getOutgoingRelationships() != null && translatedTopic.getOutgoingRelationships().getItems() != null)
					{
						final List<TranslatedTopicV1> nonTranslatedTopics = new ArrayList<TranslatedTopicV1>();
						for (TranslatedTopicV1 relatedTranslatedTopic: translatedTopic.getOutgoingRelationships().getItems())
						{
							if (relatedTranslatedTopic.isDummyTopic())
								nonTranslatedTopics.add(relatedTranslatedTopic);
						}
						
						processTranslatedTitleErrors(doc, nonTranslatedTopics);
					}
					
					/* render the topic html */
					final String processedXML = XMLUtilities.convertDocumentToString(doc, DocbookBuilderConstants.XML_ENCODING);
					final String processedXMLWithDocType = XMLPreProcessor.processDocumentType(processedXML);

					try
					{
						final String transformedXml = XMLRenderer.transformDocbook(processedXMLWithDocType, this.getServiceStarter().getSkynetServer());

						updatedTranslatedTopicV1.setHtmlExplicit(transformedXml);
					}
					catch (final TransformerException ex)
					{
						updatedTranslatedTopicV1.setHtmlExplicit(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
						updatedTranslatedTopicV1.setXmlErrorsExplicit(ex.toString());
					}
				}
			}
			else
			{
				updatedTranslatedTopicV1.setHtmlExplicit(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
				updatedTranslatedTopicV1.setXmlErrorsExplicit(validator.getErrorText());
			}
			
			/* Set the last changed date to the current date/time */
			updatedTranslatedTopicV1.setHtmlUpdatedExplicit(new Date());
			
			client.updateJSONTranslatedTopic(expandEncodedString, updatedTranslatedTopicV1);

			NotificationUtilities.dumpMessageToStdOut("TranslatedTopic " + translatedTopicId + "-" + translatedTopic.getLocale() + " has been updated");
		}
	}
	
	/**
	 * Add the list of Translated Topics who referenced 
	 * another topic that hasn't been translated.
	 */
	private void processTranslatedTitleErrors(final Document xmlDoc, final List<TranslatedTopicV1> errorTopics)
	{
		/* Check that there are errors */
		if (errorTopics.isEmpty()) return;

		/* Create the itemized list to hold the translations */
		final Element errorUntranslatedSection = xmlDoc.createElement("itemizedlist");
		boolean untranslatedErrors = false;

		/* Create the title for the list */
		final Element errorUntranslatedSectionTitle = xmlDoc.createElement("title");
		errorUntranslatedSectionTitle.setTextContent("The following links in this topic reference untranslated resources:");
		errorUntranslatedSection.appendChild(errorUntranslatedSectionTitle);
		
		/* Create the itemized list to hold the translations */
		final Element errorNonPushedSection = xmlDoc.createElement("itemizedlist");
		boolean nonPushedErrors = false;
		
		/* Create the title for the list */
		final Element errorNonPushedSectionTitle = xmlDoc.createElement("title");
		errorNonPushedSectionTitle.setTextContent("The following links in this topic references a topic that hasn't been pushed for translation:");
		errorNonPushedSection.appendChild(errorNonPushedSectionTitle);

		/* Add all of the topic titles that had errors to the list */
		for (TranslatedTopicV1 topic: errorTopics)
		{
			Element errorTitleListItem = xmlDoc.createElement("listitem");
			Element errorTitlePara = xmlDoc.createElement("para");
			errorTitlePara.setTextContent(topic.getTitle());
			errorTitleListItem.appendChild(errorTitlePara);
			
			if (topic.hasBeenPushedForTranslation())
			{
				errorUntranslatedSection.appendChild(errorTitleListItem);
				untranslatedErrors = true;
			}
			else
			{
				errorNonPushedSection.appendChild(errorTitleListItem);
				nonPushedErrors = true;
			}
		}
		
		/* Add the sections that have errors added to them */
		if (untranslatedErrors)
			xmlDoc.getDocumentElement().appendChild(errorUntranslatedSection);
		
		if (nonPushedErrors)
			xmlDoc.getDocumentElement().appendChild(errorNonPushedSection);
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
			
			final XMLPreProcessor<TopicV1> xmlPreProcessor = new XMLPreProcessor<TopicV1>();
			final SpecTopic specTopic = new SpecTopic(topic.getId(), topic.getTitle());
			specTopic.setTopic(topic);
			
			xmlPreProcessor.processInjections(null, specTopic, customInjectionIds, doc, null, false);
			xmlPreProcessor.processGenericInjections(null, specTopic, doc, customInjectionIds, topicTypeTagDetails, null, false);
			XMLPreProcessor.processInternalImageFiles(doc);

			xmlPreProcessor.processTopicContentFragments(specTopic, doc, null);
			xmlPreProcessor.processTopicTitleFragments(specTopic, doc, null);

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
				xmlPreProcessor.processTopicAdditionalInfo(specTopic, doc, docbookBuildingOptions, Main.NAME + " " + Main.BUILD, null, new Date());

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
