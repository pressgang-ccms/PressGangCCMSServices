/*
  Copyright 2011-2014 Red Hat, Inc

  This file is part of PressGang CCMS.

  PressGang CCMS is free software: you can redistribute it and/or modify
  it under the terms of the GNU Lesser General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  PressGang CCMS is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public License
  along with PressGang CCMS.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.jboss.pressgang.ccms.services.docbookrenderer.utils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.xml.transform.TransformerException;

import net.ser1.stomp.Client;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.pressgang.ccms.contentspec.SpecTopic;
import org.jboss.pressgang.ccms.docbook.compiling.DocbookBuildingOptions;
import org.jboss.pressgang.ccms.docbook.constants.DocbookBuilderConstants;
import org.jboss.pressgang.ccms.docbook.messaging.DocbookBuildType;
import org.jboss.pressgang.ccms.docbook.messaging.DocbookRendererMessage;
import org.jboss.pressgang.ccms.docbook.processing.DocbookXMLPreProcessor;
import org.jboss.pressgang.ccms.rest.v1.client.PressGangCCMSProxyFactoryV1;
import org.jboss.pressgang.ccms.rest.v1.collections.base.RESTBaseCollectionItemV1;
import org.jboss.pressgang.ccms.rest.v1.collections.base.RESTBaseCollectionV1;
import org.jboss.pressgang.ccms.rest.v1.components.ComponentBaseTopicV1;
import org.jboss.pressgang.ccms.rest.v1.components.ComponentTranslatedTopicV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTBlobConstantV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTImageV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTLanguageImageV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTagV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTopicV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTranslatedTopicV1;
import org.jboss.pressgang.ccms.rest.v1.entities.base.RESTBaseTopicV1;
import org.jboss.pressgang.ccms.rest.v1.exceptions.InternalProcessingException;
import org.jboss.pressgang.ccms.rest.v1.exceptions.InvalidParameterException;
import org.jboss.pressgang.ccms.rest.v1.expansion.ExpandDataDetails;
import org.jboss.pressgang.ccms.rest.v1.expansion.ExpandDataTrunk;
import org.jboss.pressgang.ccms.rest.v1.jaxrsinterfaces.RESTInterfaceV1;
import org.jboss.pressgang.ccms.services.docbookrenderer.Main;
import org.jboss.pressgang.ccms.utils.common.CollectionUtilities;
import org.jboss.pressgang.ccms.utils.common.ExceptionUtilities;
import org.jboss.pressgang.ccms.utils.common.NotificationUtilities;
import org.jboss.pressgang.ccms.utils.common.XMLUtilities;
import org.jboss.pressgang.ccms.utils.common.XMLValidator;
import org.jboss.pressgang.ccms.utils.services.ServiceStarter;
import org.jboss.pressgang.ccms.utils.services.stomp.BaseStompRunnable;
import org.jboss.pressgang.ccms.utils.structures.Pair;
import org.jboss.pressgang.ccms.zanata.ZanataDetails;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

public class RenderingThread<T extends RESTBaseTopicV1<T, U, V>, U extends RESTBaseCollectionV1<T, U, V>, V extends RESTBaseCollectionItemV1<T, U, V>> extends BaseStompRunnable
{
	/** Jackson object mapper */
	private final ObjectMapper mapper = new ObjectMapper();
	/** The Rocbook DTD, to be loaded once and then shared */
	static private RESTBlobConstantV1 constants = null;
	/** RestEASY Proxy client */
	private final RESTInterfaceV1 client;
	
	private final DocbookBuildingOptions docbookBuildingOptions = new DocbookBuildingOptions();

	public RenderingThread(final Client client, final String message, final Map<String, String> headers, final ServiceStarter serviceStarter, final boolean shutdownRequested)
	{
		super(client, serviceStarter, message, headers, shutdownRequested);
		docbookBuildingOptions.setInsertSurveyLink(false);
		this.client = PressGangCCMSProxyFactoryV1.create(this.getServiceStarter().getSkynetServer()).getRESTClient();
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

			/* load the dtd once */
			synchronized (RenderingThread.class)
			{
				if (constants == null)
				{
					constants = client.getJSONBlobConstant(DocbookBuilderConstants.ROCBOOK_DTD_BLOB_ID, "");
				}
			}

			/* Get the id of the topic we are updating */
			final DocbookRendererMessage message = mapper.readValue(this.getMessage(), DocbookRendererMessage.class);

			if (message.entityType == DocbookBuildType.TOPIC)
			{
				renderTopic(client, message.entityId);
			}
			else if (message.entityType == DocbookBuildType.TRANSLATEDTOPIC)
			{
				renderTranslatedTopic(client, message.entityId);
			}
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

		final ExpandDataTrunk expandTopic = new ExpandDataTrunk(new ExpandDataDetails(RESTTranslatedTopicV1.TOPIC_NAME));
		final ExpandDataTrunk expandTopicTranslations = new ExpandDataTrunk(new ExpandDataDetails(RESTTopicV1.TRANSLATEDTOPICS_NAME));
		final ExpandDataTrunk outgoingRelationships = new ExpandDataTrunk(new ExpandDataDetails(RESTTranslatedTopicV1.ALL_LATEST_OUTGOING_NAME));
		final ExpandDataTrunk outgoingRelationshipsTags = new ExpandDataTrunk(new ExpandDataDetails(RESTBaseTopicV1.TAGS_NAME));
		final ExpandDataTrunk outgoingRelationshipsTagsCategories = new ExpandDataTrunk(new ExpandDataDetails(RESTTagV1.CATEGORIES_NAME));
		final ExpandDataTrunk propertyTags = new ExpandDataTrunk(new ExpandDataDetails(RESTBaseTopicV1.PROPERTIES_NAME));

		outgoingRelationships.setBranches(CollectionUtilities.toArrayList(outgoingRelationshipsTags, expandTopic));
		outgoingRelationshipsTags.setBranches(CollectionUtilities.toArrayList(outgoingRelationshipsTagsCategories));
		
		expandTopic.setBranches(CollectionUtilities.toArrayList(expandTopicTranslations));

		final ExpandDataTrunk tags = new ExpandDataTrunk(new ExpandDataDetails(RESTBaseTopicV1.TAGS_NAME));
		final ExpandDataTrunk tagsCategories = new ExpandDataTrunk(new ExpandDataDetails(RESTTagV1.CATEGORIES_NAME));

		tags.setBranches(CollectionUtilities.toArrayList(tagsCategories, propertyTags));

		expand.setBranches(CollectionUtilities.toArrayList(outgoingRelationships, tags, expandTopic));

		/* convert the ExpandDataTrunk to an encoded JSON String */
		final String expandString = mapper.writeValueAsString(expand);
		//final String expandEncodedString = URLEncoder.encode(expandString, "UTF-8");
		
		final RESTTranslatedTopicV1 translatedTopic = client.getJSONTranslatedTopic(translatedTopicDataId, expandString);

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
			try
			{
				translatedTopicIdField = translatedTopic.getClass().getDeclaredField("translatedTopicId");
				translatedTopicIdField.setAccessible(true);
				translatedTopicId = (Integer)translatedTopicIdField.get(translatedTopic);
			}
			catch (Exception ex)
			{
				ExceptionUtilities.handleException(ex);
			}
			
			NotificationUtilities.dumpMessageToStdOut("Processing TranslatedTopic " + translatedTopicId + "-" + translatedTopic.getLocale() + ": " + translatedTopic.getTitle());
				
			/* the object we will send back to do the update */
			final RESTTranslatedTopicV1 updatedTranslatedTopicV1 = new RESTTranslatedTopicV1();
			updatedTranslatedTopicV1.setId(translatedTopic.getId());
			
			final XMLValidator validator = new XMLValidator();

			try
			{
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
					
					final DocbookXMLPreProcessor xmlPreProcessor = new DocbookXMLPreProcessor();
					final SpecTopic specTopic = new SpecTopic(translatedTopic.getTopicId(), translatedTopic.getTitle());
					specTopic.setTopic(translatedTopic);
					
					final ArrayList<Integer> customInjectionIds = new ArrayList<Integer>();
					xmlPreProcessor.processInjections(null, specTopic, customInjectionIds, doc, null, null, false);
					xmlPreProcessor.processGenericInjections(null, specTopic, doc, customInjectionIds, topicTypeTagDetails, null, false);
					processInternalImageFiles(doc, specTopic);
					
					/*
					 * Validate the topic after injections as Injections such as
					 * "InjectListItems" won't validate until after injections
					 */
					if (validator.validateTopicXML(doc, constants.getName(), constants.getValue()) == null)
					{
						updatedTranslatedTopicV1.explicitSetHtml(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
						updatedTranslatedTopicV1.explicitSetXmlErrors(validator.getErrorText());
					}
					else
					{
						/* add the standard boilerplate xml */
						/* currently disabled until how the additions should be displayed are figured out */
						xmlPreProcessor.processTopicAdditionalInfo(specTopic, doc, null, docbookBuildingOptions, Main.NAME + " " + Main.BUILD, new Date(), new ZanataDetails());
	
						/* Generate the note for the translated topics relationships that haven't been translated */
						if (translatedTopic.getOutgoingRelationships() != null && translatedTopic.getOutgoingRelationships().returnItems() != null)
						{
							final List<RESTTranslatedTopicV1> nonTranslatedTopics = new ArrayList<RESTTranslatedTopicV1>();
							for (RESTTranslatedTopicV1 relatedTranslatedTopic: translatedTopic.getOutgoingRelationships().returnItems())
							{
								if (ComponentBaseTopicV1.returnIsDummyTopic(relatedTranslatedTopic))
									nonTranslatedTopics.add(relatedTranslatedTopic);
							}
							
							processTranslatedTitleErrors(doc, nonTranslatedTopics);
						}
						
						/* render the topic html */
						final String processedXML = XMLUtilities.convertDocumentToString(doc, DocbookBuilderConstants.XML_ENCODING);
						final String processedXMLWithDocType = DocbookXMLPreProcessor.processDocumentType(processedXML);
	
						try
						{
							final String transformedXml = XMLRenderer.transformDocbook(processedXMLWithDocType, this.getServiceStarter().getSkynetServer());
	
							updatedTranslatedTopicV1.explicitSetHtml(transformedXml);
						}
						catch (final TransformerException ex)
						{
							updatedTranslatedTopicV1.explicitSetHtml(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
						}
					}
				}
				else
				{
					updatedTranslatedTopicV1.explicitSetHtml(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
				}
			}
			catch (SAXException ex)
			{
				updatedTranslatedTopicV1.explicitSetHtml(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
			}
			
			/* Set the last changed date to the current date/time */
			updatedTranslatedTopicV1.explicitSetHtmlUpdated(new Date());
			
			client.updateJSONTranslatedTopic(expandString, updatedTranslatedTopicV1);

			NotificationUtilities.dumpMessageToStdOut("TranslatedTopic " + translatedTopicId + "-" + translatedTopic.getLocale() + " has been updated");
		}
	}
	
	/**
	 * Add the list of Translated Topics who referenced 
	 * another topic that hasn't been translated.
	 */
	private void processTranslatedTitleErrors(final Document xmlDoc, final List<RESTTranslatedTopicV1> errorTopics)
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
		for (RESTTranslatedTopicV1 topic: errorTopics)
		{
			Element errorTitleListItem = xmlDoc.createElement("listitem");
			Element errorTitlePara = xmlDoc.createElement("para");
			errorTitlePara.setTextContent(topic.getTitle());
			errorTitleListItem.appendChild(errorTitlePara);
			
			if (ComponentTranslatedTopicV1.hasBeenPushedForTranslation(topic))
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
		//final String expandEncodedString = URLEncoder.encode(expandString, "UTF-8");

		/* get the topic list */
		final RESTTopicV1 topic = client.getJSONTopic(topicId, expandString);

		/* early exit if shutdown has been requested */
		if (this.isShutdownRequested())
		{
			this.resendMessage();
			return;
		}

		NotificationUtilities.dumpMessageToStdOut("Processing Topic " + topic.getId() + ": " + topic.getTitle());

		final RESTTopicV1 updatedTopicV1 = new RESTTopicV1();
		updatedTopicV1.setId(topicId);

		final XMLValidator validator = new XMLValidator();

		try
		{
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
				
				final DocbookXMLPreProcessor xmlPreProcessor = new DocbookXMLPreProcessor();
				final SpecTopic specTopic = new SpecTopic(topic.getId(), topic.getTitle());
				specTopic.setTopic(topic);
				
				xmlPreProcessor.processInjections(null, specTopic, customInjectionIds, doc, null, null, false);
				xmlPreProcessor.processGenericInjections(null, specTopic, doc, customInjectionIds, topicTypeTagDetails, null, false);
				processInternalImageFiles(doc, specTopic);
	
				/*
				 * Validate the topic after injections as Injections such as
				 * "InjectListItems" won't validate until after injections
				 */
				if (validator.validateTopicXML(doc, constants.getName(), constants.getValue()) == null)
				{
					updatedTopicV1.explicitSetHtml(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
					updatedTopicV1.explicitSetXmlErrors(validator.getErrorText());
				}
				else
				{
					/* add the standard boilerplate xml */
					xmlPreProcessor.processTopicAdditionalInfo(specTopic, doc, null, docbookBuildingOptions, Main.NAME + " " + Main.BUILD, new Date(), new ZanataDetails());
	
					/* render the topic html */
					final String processedXML = XMLUtilities.convertDocumentToString(doc, DocbookBuilderConstants.XML_ENCODING);
					final String processedXMLWithDocType = DocbookXMLPreProcessor.processDocumentType(processedXML);
	
					try
					{
						final String transformedXml = XMLRenderer.transformDocbook(processedXMLWithDocType, this.getServiceStarter().getSkynetServer());
	
						updatedTopicV1.explicitSetHtml(transformedXml);
					}
					catch (final TransformerException ex)
					{
						updatedTopicV1.explicitSetHtml(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
					}
				}
			}
			else
			{
				updatedTopicV1.explicitSetHtml(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
			}
		}
		catch (SAXException ex)
		{
			updatedTopicV1.explicitSetHtml(DocbookBuilderConstants.XSL_ERROR_TEMPLATE);
		}

		client.updateJSONTopic("", updatedTopicV1);

		NotificationUtilities.dumpMessageToStdOut("Topic " + topic.getId() + " has been updated");
	}
	
	public void processInternalImageFiles(final Document xmlDoc, final SpecTopic topic)
	{
		if (xmlDoc == null)
			return;

		final List<Node> imageDataNodes = XMLUtilities.getNodes(xmlDoc.getDocumentElement(), "imagedata", "inlinegraphic");
		for (final Node imageDataNode : imageDataNodes)
		{
			final NamedNodeMap attributes = imageDataNode.getAttributes();
			final Node filerefAttribute = attributes.getNamedItem("fileref");
			if (filerefAttribute != null)
			{
				String imageId = filerefAttribute.getTextContent();
				imageId = imageId.replace("images/", "");
				final int periodIndex = imageId.lastIndexOf(".");
				if (periodIndex != -1)
				{
					imageId = imageId.substring(0, periodIndex);
				}

				/*
				 * at this point imageId should be an integer that is the id of the image uploaded in skynet. We will leave the validation of imageId to the
				 * ImageFileDisplay class.
				 */
				
				try
				{	
					/* Expand the Language Images */
					final ExpandDataTrunk expand = new ExpandDataTrunk();
					expand.setBranches(CollectionUtilities.toArrayList(new ExpandDataTrunk(new ExpandDataDetails(RESTImageV1.LANGUAGEIMAGES_NAME))));
					final String expandString = mapper.writeValueAsString(expand);
					//final String expandEncodedString = URLEncoder.encode(expandString, "UTF-8");
					
					final RESTImageV1 imageFile = client.getJSONImage(Integer.parseInt(imageId), expandString);
					
					final String locale = topic.getTopic().getLocale();
					boolean localeImageExists = false;
					if (imageFile != null && imageFile.getLanguageImages_OTM() != null && imageFile.getLanguageImages_OTM().returnItems() != null)
					{
						for (final RESTLanguageImageV1 languageImage : imageFile.getLanguageImages_OTM().returnItems())
						{
							if (locale.equals(languageImage.getLocale()))
							{
								localeImageExists = true;
							}
						}
					}
					
					if (localeImageExists)
					{
						filerefAttribute.setTextContent("ImageFileDisplay.seam?imageFileId=" + imageId + "&language=" + locale);
					}
					else
					{
						filerefAttribute.setTextContent("ImageFileDisplay.seam?imageFileId=" + imageId);
					}
				}
				catch (Exception ex)
				{
					ExceptionUtilities.handleException(ex);
				}
			}
		}
	}
}
