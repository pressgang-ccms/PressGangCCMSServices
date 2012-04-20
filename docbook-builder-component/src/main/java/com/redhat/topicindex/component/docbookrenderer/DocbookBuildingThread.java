package com.redhat.topicindex.component.docbookrenderer;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import net.ser1.stomp.Command;

import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.resteasy.client.ProxyFactory;
import org.jboss.resteasy.specimpl.PathSegmentImpl;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.text.RuleBasedNumberFormat;
import com.redhat.ecs.commonstructures.Pair;
import com.redhat.ecs.commonutils.CollectionUtilities;
import com.redhat.ecs.commonutils.ExceptionUtilities;
import com.redhat.ecs.commonutils.NotificationUtilities;
import com.redhat.ecs.commonutils.StringUtilities;
import com.redhat.ecs.commonutils.XMLUtilities;
import com.redhat.ecs.commonutils.XMLValidator;
import com.redhat.ecs.commonutils.ZipUtilities;
import com.redhat.ecs.constants.CommonConstants;
import com.redhat.ecs.servicepojo.ServiceStarter;
import com.redhat.ecs.services.commonstomp.BaseStompRunnable;
import com.redhat.ecs.services.docbookcompiling.BuildDocbookMessage;
import com.redhat.ecs.services.docbookcompiling.DocbookBuilderConstants;
import com.redhat.ecs.services.docbookcompiling.DocbookBuildingOptions;
import com.redhat.ecs.services.docbookcompiling.DocbookUtils;
import com.redhat.ecs.services.docbookcompiling.xmlprocessing.XMLPreProcessor;
import com.redhat.ecs.services.docbookcompiling.xmlprocessing.structures.TocTopicDatabase;
import com.redhat.ecs.services.docbookcompiling.xmlprocessing.structures.TopicProcessingData;
import com.redhat.topicindex.component.docbookrenderer.constants.DocbookBuilderXMLConstants;
import com.redhat.topicindex.component.docbookrenderer.sort.TocElementSortLabelComparator;
import com.redhat.topicindex.component.docbookrenderer.structures.TopicErrorData;
import com.redhat.topicindex.component.docbookrenderer.structures.TopicErrorDatabase;
import com.redhat.topicindex.component.docbookrenderer.structures.TopicImageData;
import com.redhat.topicindex.component.docbookrenderer.structures.toc.TocElement;
import com.redhat.topicindex.component.docbookrenderer.structures.toc.TocFolderElement;
import com.redhat.topicindex.component.docbookrenderer.structures.toc.TocLink;
import com.redhat.topicindex.component.docbookrenderer.structures.toc.TocTopLevel;
import com.redhat.topicindex.rest.collections.BaseRestCollectionV1;
import com.redhat.topicindex.rest.entities.BlobConstantV1;
import com.redhat.topicindex.rest.entities.ImageV1;
import com.redhat.topicindex.rest.entities.PropertyTagV1;
import com.redhat.topicindex.rest.entities.TagV1;
import com.redhat.topicindex.rest.entities.TopicV1;
import com.redhat.topicindex.rest.exceptions.InternalProcessingException;
import com.redhat.topicindex.rest.exceptions.InvalidParameterException;
import com.redhat.topicindex.rest.expand.ExpandDataDetails;
import com.redhat.topicindex.rest.expand.ExpandDataTrunk;
import com.redhat.topicindex.rest.sharedinterface.RESTInterfaceV1;
import com.redhat.topicindex.rest.sort.TopicV1TitleComparator;

public class DocbookBuildingThread extends BaseStompRunnable
{
	private static final String STARTS_WITH_NUMBER_RE = "^(?<Numbers>\\d+)(?<EverythingElse>.*)$";

	/**
	 * A generic error template to be used for any topic that does not pass
	 * validation
	 */
	private static String ERROR_XML_TEMPLATE = "<section><title>Error</title><warning><para>This topic is invalid. Please see <xref linkend=\"" + DocbookBuilderConstants.TOPIC_ERROR_LINK_MARKER + "\"/> for more details.</para></warning></section>";

	/**
	 * A generic error template to be used for any topic that does not pass
	 * validation
	 */
	private static String NOTE_XML_TEMPLATE = "<section><title>Note</title><note><para>This topic has no XML content, and is included here as a placeholder.</para></note></section>";

	/**
	 * Contains all the details of all topics that will be included in this
	 * build
	 */
	private TocTopicDatabase topicDatabase = new TocTopicDatabase();

	/**
	 * Holds the compiler errors that form the Errors.xml file in the compiled
	 * docbook
	 */
	private TopicErrorDatabase errorDatabase = new TopicErrorDatabase();

	/**
	 * Holds information on file url locations, which will be downloaded and
	 * included in the docbook zip file
	 */
	private ArrayList<TopicImageData> imageLocations = new ArrayList<TopicImageData>();

	/** The REST client */
	private final RESTInterfaceV1 restClient;

	/** Jackson object mapper */
	private final ObjectMapper mapper = new ObjectMapper();

	private ArrayList<String> verbatimElements;
	private ArrayList<String> inlineElements;
	private ArrayList<String> contentsInlineElements;
	private boolean xmlFormattingPropertiesSet = false;

	private BlobConstantV1 rocbookDTD;

	public DocbookBuildingThread(final ServiceStarter serviceStarter, final Client client, final String message, final Map<String, String> headers, final boolean shutdownRequested)
	{
		super(client, serviceStarter, message, headers, shutdownRequested);
		this.restClient = ProxyFactory.create(RESTInterfaceV1.class, serviceStarter.getSkynetServer());
	}

	public void run()
	{
		try
		{
			final String buildName = Main.NAME + " " + Main.BUILD;

			/* early exit if shutdown has been requested */
			if (this.isShutdownRequested())
			{
				this.resendMessage();
				return;
			}

			NotificationUtilities.dumpMessageToStdOut("Processing Docbook Build Request");

			/*
			 * Get the XML formatting details. These are used to pretty-print
			 * the XML when it is converted into a String.
			 */
			final String verbatimElementsString = System.getProperty(CommonConstants.VERBATIM_XML_ELEMENTS_SYSTEM_PROPERTY);
			final String inlineElementsString = System.getProperty(CommonConstants.INLINE_XML_ELEMENTS_SYSTEM_PROPERTY);
			final String contentsInlineElementsString = System.getProperty(CommonConstants.CONTENTS_INLINE_XML_ELEMENTS_SYSTEM_PROPERTY);

			xmlFormattingPropertiesSet = verbatimElementsString != null && !verbatimElementsString.trim().isEmpty() && inlineElementsString != null && !inlineElementsString.trim().isEmpty() && contentsInlineElementsString != null && !contentsInlineElementsString.trim().isEmpty();

			if (xmlFormattingPropertiesSet)
			{
				verbatimElements = CollectionUtilities.toArrayList(verbatimElementsString.split(","));
				inlineElements = CollectionUtilities.toArrayList(inlineElementsString.split(","));
				contentsInlineElements = CollectionUtilities.toArrayList(contentsInlineElementsString.split(","));
			}

			final BuildDocbookMessage buildDocbookMessage = mapper.readValue(this.getMessage(), BuildDocbookMessage.class);

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
				final ExpandDataTrunk properties = new ExpandDataTrunk(new ExpandDataDetails(TopicV1.PROPERTIES_NAME));
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

				final BaseRestCollectionV1<TopicV1> topics = restClient.getJSONTopicsWithQuery(pathSegment, expandEncodedStrnig);

				rocbookDTD = restClient.getJSONBlobConstant(DocbookBuilderConstants.ROCBOOK_DTD_BLOB_ID, "");

				if (topics.getItems() != null)
				{
					NotificationUtilities.dumpMessageToStdOut("Processing " + topics.getItems().size() + " Topics");

					/*
					 * Construct the URL that will show us the topics used in
					 * this Docbook build
					 */
					final String searchTagsUrl = CommonConstants.FULL_SERVER_URL + "/CustomSearchTopicList.seam?" + buildDocbookMessage.getQuery().replaceAll(";", "&amp;");

					/* Initialise the topic database */
					topicDatabase.setTopics(topics.getItems());

					/*
					 * Each topic is processed in a number of stages. See the
					 * method comments for more detail on what each of these
					 * steps does
					 */

					/*
					 * assign fixed urls property tags to the topics. If
					 * fixedUrlsSuccess is true, the id of the topic sections,
					 * xfref injection points and file names in the zip file
					 * will be taken from the fixed url property tag, defaulting
					 * back to the TopicID## format if for some reason that
					 * property tag does not exist.
					 */
					final boolean fixedUrlsSuccess = setFixedURLsPass();

					/* early exit if shutdown has been requested */
					if (this.isShutdownRequested())
					{
						this.resendMessage();
						return;
					}

					final Map<TopicV1, ArrayList<String>> usedIdAttributes = doFirstValidationPass(topics, fixedUrlsSuccess);

					/* early exit if shutdown has been requested */
					if (this.isShutdownRequested())
					{
						this.resendMessage();
						return;
					}

					doSecondValidationPass(topics, usedIdAttributes, fixedUrlsSuccess);

					/* early exit if shutdown has been requested */
					if (this.isShutdownRequested())
					{
						this.resendMessage();
						return;
					}

					final TocTopLevel toc = doTOCPass(topics, usedIdAttributes, buildDocbookMessage.getDocbookOptions(), searchTagsUrl, buildName, fixedUrlsSuccess);

					/* early exit if shutdown has been requested */
					if (this.isShutdownRequested())
					{
						this.resendMessage();
						return;
					}

					/*
					 * process the fixed urls for the landing pages, and fix the
					 * topic ids
					 */
					setFixedURLsLandingPagesPass(toc, fixedUrlsSuccess);

					/* early exit if shutdown has been requested */
					if (this.isShutdownRequested())
					{
						this.resendMessage();
						return;
					}

					doProcessingPass(topics, buildDocbookMessage.getDocbookOptions(), searchTagsUrl, buildName, fixedUrlsSuccess);

					/* early exit if shutdown has been requested */
					if (this.isShutdownRequested())
					{
						this.resendMessage();
						return;
					}

					final byte[] zip = doBuildZipPass(toc, buildDocbookMessage.getDocbookOptions(), fixedUrlsSuccess);

					/* early exit if shutdown has been requested */
					if (this.isShutdownRequested())
					{
						this.resendMessage();
						return;
					}

					emailZIP(topics, zip, buildDocbookMessage.getDocbookOptions());

					NotificationUtilities.dumpMessageToStdOut("Processing Complete");
				}
				else
				{
					NotificationUtilities.dumpMessageToStdOut("Error Getting Topic Collection");
				}
			}
			else
			{
				NotificationUtilities.dumpMessageToStdOut("Invalid Docbook Compilation Options");
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

	/**
	 * Sets the topic XML to a generic error message.
	 * 
	 * @param topicProcessingData
	 *            The TopicProcessingData that is associated with the Topic that
	 *            has an error
	 */
	private void setTopicXMLForError(final TopicV1 topic, final String template, final boolean fixedUrlsSuccess)
	{
		final TopicProcessingData topicProcessingData = topicDatabase.getTopicProcessingData(topic);

		/* replace any markers with the topic sepecific text */
		final String fixedTemplate = template.replaceAll(DocbookBuilderConstants.TOPIC_ERROR_LINK_MARKER, DocbookBuilderConstants.ERROR_XREF_ID_PREFIX + topic.getId());

		final Document doc = XMLUtilities.convertStringToDocument(fixedTemplate);
		topicProcessingData.setXmlDocument(doc);
		DocbookUtils.setSectionTitle(topic.getTitle(), doc);
		processTopicID(topic, topicProcessingData, fixedUrlsSuccess);
	}

	/**
	 * Sets the topic xref id to the topic database id
	 */
	private void processTopicID(final TopicV1 topic, final TopicProcessingData topicProcessingData, final boolean fixedUrlsSuccess)
	{
		if (fixedUrlsSuccess)
		{
			final PropertyTagV1 propTag = topic.getProperty(CommonConstants.FIXED_URL_PROP_TAG_ID);
			if (propTag != null)
			{
				topicProcessingData.getXmlDocument().getDocumentElement().setAttribute("id", propTag.getValue());
			}
			else
			{
				topicProcessingData.getXmlDocument().getDocumentElement().setAttribute("id", topic.getXRefID());
			}
		}
		else
		{
			topicProcessingData.getXmlDocument().getDocumentElement().setAttribute("id", topic.getXRefID());
		}
	}

	private void procesImageLocations(final TopicV1 topic, final TopicProcessingData topicProcessingData)
	{
		/*
		 * Images have to be in the image folder in Publican. Here we loop
		 * through all the imagedata elements and fix up any reference to an
		 * image that is not in the images folder.
		 */
		final List<Node> images = this.getImages(topicProcessingData.getXmlDocument());

		for (final Node imageNode : images)
		{
			final NamedNodeMap attributes = imageNode.getAttributes();
			if (attributes != null)
			{
				final Node fileRefAttribute = attributes.getNamedItem("fileref");

				if (fileRefAttribute != null && !fileRefAttribute.getNodeValue().startsWith("images/"))
				{
					fileRefAttribute.setNodeValue("images/" + fileRefAttribute.getNodeValue());
				}

				imageLocations.add(new TopicImageData(topic, fileRefAttribute.getNodeValue()));
			}
		}
	}

	/**
	 * We loop over the topics that were returned by the filter and perform some
	 * validations tasks.
	 */
	private Map<TopicV1, ArrayList<String>> doFirstValidationPass(final BaseRestCollectionV1<TopicV1> topics, final boolean fixedUrlsSuccess)
	{
		NotificationUtilities.dumpMessageToStdOut("Doing First Validation Pass");

		final Map<TopicV1, ArrayList<String>> retValue = new HashMap<TopicV1, ArrayList<String>>();

		for (final TopicV1 topic : topics.getItems())
		{
			if (this.isShutdownRequested())
			{
				NotificationUtilities.dumpMessageToStdOut("Shutdown detected in DocbookBuildingThread.doFirstValidationPass(). Exiting Loop.");
				return retValue;
			}

			final TopicProcessingData topicProcessingData = topicDatabase.getTopicProcessingData(topic);

			if (topicProcessingData != null)
			{
				boolean xmlValid = true;

				/* make sure we have some xml */
				if (topic.getXml() == null || topic.getXml().trim().length() == 0)
				{
					NotificationUtilities.dumpMessageToStdOut("\tTopic " + topic.getId() + " has no XML");
					errorDatabase.addError(topic, "Topic has no XML.");
					setTopicXMLForError(topic, NOTE_XML_TEMPLATE, fixedUrlsSuccess);
					xmlValid = false;
				}

				/* make sure we have valid XML */
				if (xmlValid)
				{
					final Document document = XMLUtilities.convertStringToDocument(topic.getXml());
					if (document == null)
					{
						NotificationUtilities.dumpMessageToStdOut("\tTopic " + topic.getId() + " has invalid XML");
						errorDatabase.addError(topic, "Topic has invalid XML.");
						setTopicXMLForError(topic, ERROR_XML_TEMPLATE, fixedUrlsSuccess);
						xmlValid = false;
					}
					else
					{
						topicProcessingData.setXmlDocument(document);
					}
				}

				/* make sure the topic has the correct tags */
				if (topic.getTagsInCategory(DocbookBuilderConstants.TYPE_CATEGORY_ID) != 1)
				{
					errorDatabase.addError(topic, "Topic has to have a Topic Type Tag");
					setTopicXMLForError(topic, ERROR_XML_TEMPLATE, fixedUrlsSuccess);
				}

				if (topic.getTagsInCategory(DocbookBuilderConstants.TECHNOLOGY_CATEGORY_ID) == 0 && topic.getTagsInCategory(DocbookBuilderConstants.COMMON_NAME_CATEGORY_ID) == 0)
				{
					errorDatabase.addError(topic, "Topic has to have at least one Technology or Common Name Tag");
					setTopicXMLForError(topic, ERROR_XML_TEMPLATE, fixedUrlsSuccess);
				}

				if (topic.getTagsInCategory(DocbookBuilderConstants.CONCERN_CATEGORY_ID) == 0)
				{
					errorDatabase.addError(topic, "Topic has to have a Concern Tag");
					setTopicXMLForError(topic, ERROR_XML_TEMPLATE, fixedUrlsSuccess);
				}

				/*
				 * Extract the id attributes used in this topic. We'll use this
				 * data in the second pass to make sure that individual topics
				 * don't repeat id attributes.
				 */
				collectIdAttributes(topic, topicProcessingData.getXmlDocument(), retValue);

				if (xmlValid)
				{
					/* set the section id */
					processTopicID(topic, topicProcessingData, fixedUrlsSuccess);

					procesImageLocations(topic, topicProcessingData);
				}
			}
		}

		return retValue;
	}

	/**
	 * @param node
	 *            The node to search for imagedata elements in
	 * @return Search any imagedata elements found in the supplied node
	 */
	private List<Node> getImages(final Node node)
	{
		final List<Node> images = new ArrayList<Node>();
		final NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); ++i)
		{
			final Node child = children.item(i);

			if (child.getNodeName().equals("imagedata"))
			{
				images.add(child);
			}
			else
			{
				images.addAll(getImages(child));
			}
		}
		return images;
	}

	/**
	 * This function scans the supplied XML node and it's children for id
	 * attributes, collecting them in the usedIdAttributes parameter
	 * 
	 * @param topic
	 *            The topic being processed
	 * @param node
	 *            The current node being processed (will be the document root to
	 *            start with, and then all the children as this function is
	 *            recursively called)
	 * @param usedIdAttributes
	 *            The collection of topics mapped to the id attributes found in
	 *            the topic XML
	 */
	private void collectIdAttributes(final TopicV1 topic, final Node node, Map<TopicV1, ArrayList<String>> usedIdAttributes)
	{
		final NamedNodeMap attributes = node.getAttributes();
		if (attributes != null)
		{
			final Node idAttribute = attributes.getNamedItem("id");
			if (idAttribute != null)
			{
				final String idAttibuteValue = idAttribute.getNodeValue();
				if (!usedIdAttributes.containsKey(topic))
					usedIdAttributes.put(topic, new ArrayList<String>());
				usedIdAttributes.get(topic).add(idAttibuteValue);
			}
		}

		final NodeList elements = node.getChildNodes();
		for (int i = 0; i < elements.getLength(); ++i)
			collectIdAttributes(topic, elements.item(i), usedIdAttributes);
	}

	/**
	 * This validation pass takes a look at the id attributes that have been
	 * used in all the topics and finds any duplicates.
	 * 
	 * @param topics
	 *            The collection of topics to process
	 * @param usedIdAttributes
	 *            The collection of id attributes found in the
	 *            doFirstValidationPass() function
	 */
	private void doSecondValidationPass(final BaseRestCollectionV1<TopicV1> topics, final Map<TopicV1, ArrayList<String>> usedIdAttributes, final boolean fixedUrlsSuccess)
	{
		NotificationUtilities.dumpMessageToStdOut("Doing Second Validation Pass");

		for (final TopicV1 topic1 : usedIdAttributes.keySet())
		{
			if (this.isShutdownRequested())
			{
				NotificationUtilities.dumpMessageToStdOut("Shutdown detected in DocbookBuildingThread.doSecondValidationPass(). Exiting Loop.");
				return;
			}

			final TopicProcessingData topicProcessingData = topicDatabase.getTopicProcessingData(topic1);
			if (topicProcessingData != null)
			{
				validateIdAttributes(topic1, topicProcessingData, usedIdAttributes, fixedUrlsSuccess);
			}
		}

	}

	private boolean validateIdAttributes(final TopicV1 topic, final TopicProcessingData topicProcessingData, final Map<TopicV1, ArrayList<String>> usedIdAttributes, final boolean fixedUrlsSuccess)
	{
		boolean retValue = true;

		if (usedIdAttributes.containsKey(topic))
		{
			final ArrayList<String> ids1 = usedIdAttributes.get(topic);

			for (final TopicV1 topic2 : usedIdAttributes.keySet())
			{
				if (topic2 == topic)
					continue;

				if (usedIdAttributes.containsKey(topic2))
				{
					final ArrayList<String> ids2 = usedIdAttributes.get(topic2);

					for (final String id1 : ids1)
					{
						if (ids2.contains(id1))
						{
							errorDatabase.addError(topic, "Topic has an id attribute called " + id1 + " which is also present in Topic " + topic2.getId());
							setTopicXMLForError(topic, ERROR_XML_TEMPLATE, fixedUrlsSuccess);
							retValue = false;
						}
					}
				}
			}
		}

		return retValue;
	}

	/**
	 * This function add some common bolierplate to the topics, and replaces the
	 * injection points with live data and links
	 * 
	 * @param topics
	 *            The collection of topics to process
	 * @throws InternalProcessingException
	 * @throws InvalidParameterException
	 */
	private void doProcessingPass(final BaseRestCollectionV1<TopicV1> topics, final DocbookBuildingOptions docbookBuildingOptions, final String searchTagsUrl, final String buildName, final boolean fixedUrlsSuccess) throws InvalidParameterException, InternalProcessingException
	{
		NotificationUtilities.dumpMessageToStdOut("Doing Processing Pass");

		final int showPercent = 5;
		final float total = topics.getItems().size();
		float current = 0;
		int lastPercent = 0;

		for (final TopicV1 topic : topics.getItems())
		{
			if (this.isShutdownRequested())
			{
				NotificationUtilities.dumpMessageToStdOut("Shutdown detected in DocbookBuildingThread.doProcessingPass(). Exiting Loop.");
				return;
			}

			++current;
			final int percent = Math.round(current / total * 100);
			if (percent - lastPercent >= showPercent)
			{
				lastPercent = percent;
				NotificationUtilities.dumpMessageToStdOut("\tProcessing Pass " + percent + "% Done");
			}

			final TopicProcessingData topicProcessingData = topicDatabase.getTopicProcessingData(topic);

			if (topicProcessingData != null)
			{
				final Document doc = topicProcessingData.getXmlDocument();

				/* set the section id */
				// doc.getDocumentElement().setAttribute("id",
				// topic.getXRefID());

				/* process the injection points */

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
				final List<Integer> customInjectionErrors = XMLPreProcessor.processInjections(false, topic, customInjectionIds, doc, topicDatabase, docbookBuildingOptions, fixedUrlsSuccess);
				final List<Integer> genericInjectionErrors = XMLPreProcessor.processGenericInjections(false, topic, doc, customInjectionIds, topicTypeTagDetails, topicDatabase, docbookBuildingOptions, fixedUrlsSuccess);
				final List<Integer> topicContentFragmentsErrors = XMLPreProcessor.processTopicContentFragments(topic, doc, docbookBuildingOptions);
				final List<Integer> topicTitleFragmentsErrors = XMLPreProcessor.processTopicTitleFragments(topic, doc, docbookBuildingOptions);

				boolean valid = true;
				final boolean ignoringInjectionErrors = docbookBuildingOptions == null || docbookBuildingOptions.getIgnoreMissingCustomInjections();

				if (!customInjectionErrors.isEmpty())
				{
					final String message = "Topic has referenced Topic(s) " + CollectionUtilities.toSeperatedString(customInjectionErrors) + " in a custom injection point that was either not related, or not included in the filter used to build this book.";
					if (ignoringInjectionErrors)
					{
						errorDatabase.addWarning(topic, message);
					}
					else
					{
						errorDatabase.addError(topic, message);
						valid = false;
					}
				}

				if (!genericInjectionErrors.isEmpty())
				{
					final String message = "Topic has related Topic(s) " + CollectionUtilities.toSeperatedString(customInjectionErrors) + " that were not included in the filter used to build this book.";
					if (ignoringInjectionErrors)
					{
						errorDatabase.addWarning(topic, message);
					}
					else
					{
						errorDatabase.addError(topic, message);
						valid = false;
					}
				}

				if (!topicContentFragmentsErrors.isEmpty())
				{
					final String message = "Topic has injected content from Topic(s) " + CollectionUtilities.toSeperatedString(customInjectionErrors) + " that were not related.";
					if (ignoringInjectionErrors)
					{
						errorDatabase.addWarning(topic, message);
					}
					else
					{
						errorDatabase.addError(topic, message);
						valid = false;
					}
				}

				if (!topicTitleFragmentsErrors.isEmpty())
				{
					final String message = "Topic has injected a title from Topic(s) " + CollectionUtilities.toSeperatedString(customInjectionErrors) + " that were not related.";
					if (ignoringInjectionErrors)
					{
						errorDatabase.addWarning(topic, message);
					}
					else
					{
						errorDatabase.addError(topic, message);
						valid = false;
					}
				}

				if (!valid)
				{
					setTopicXMLForError(topic, ERROR_XML_TEMPLATE, fixedUrlsSuccess);
				}
				else
				{
					/* add the standard boilerplate xml */
					XMLPreProcessor.processTopicAdditionalInfo(topic, topicProcessingData.getXmlDocument(), docbookBuildingOptions, searchTagsUrl, buildName);

					/* add the breadcrumbs */
					processTopicBreadCrumbs(topic, topicProcessingData, fixedUrlsSuccess);

					/*
					 * make sure the XML is valid docbook after the standard
					 * processing has been done
					 */
					final XMLValidator xmlValidator = new XMLValidator();
					if (xmlValidator.validateTopicXML(doc, rocbookDTD.getName(), rocbookDTD.getValue()) == null)
					{
						NotificationUtilities.dumpMessageToStdOut("\tTopic " + topic.getId() + " has invalid Docbook XML");

						String xmlString = null;
						if (xmlFormattingPropertiesSet)
							xmlString = XMLUtilities.convertNodeToString(doc, verbatimElements, inlineElements, contentsInlineElements, true);
						else
							xmlString = XMLUtilities.convertNodeToString(doc, true);

						final String xmlStringInCDATA = XMLUtilities.wrapStringInCDATA(xmlString);
						errorDatabase.addError(topic, "Topic has invalid Docbook XML. The error is <emphasis>" + xmlValidator.getErrorText() + "</emphasis>. The processed XML is <programlisting>" + xmlStringInCDATA + "</programlisting>");
						setTopicXMLForError(topic, ERROR_XML_TEMPLATE, fixedUrlsSuccess);
					}
				}
			}
		}
	}

	private void processTopicBreadCrumbs(final TopicV1 topic, final TopicProcessingData topicProcessingData, final boolean fixedUrlsSuccess)
	{
		assert topic != null : "The topic parameter can not be null";
		assert topicProcessingData != null : "topic.getTempTopicXMLDoc() can not be null";

		final Document xmlDocument = topicProcessingData.getXmlDocument();
		final Node docElement = xmlDocument.getDocumentElement();

		assert docElement != null : "xmlDocument.getDocumentElement() should not be null";

		/*
		 * The breadcrumbs will be inserted after the first subtitle or title
		 * (in that order)
		 */
		Node title = null;
		final List<Node> subTitleNodes = XMLUtilities.getNodes(docElement, "subtitle");
		if (subTitleNodes.size() != 0)
		{
			title = subTitleNodes.get(0);
		}
		else
		{
			final List<Node> titleNodes = XMLUtilities.getNodes(docElement, "title");
			if (titleNodes.size() != 0)
			{
				title = titleNodes.get(0);
			}
		}

		Node insertBefore = null;

		if (title != null)
		{
			/*
			 * We expect the first child of the document element to be a title,
			 * and the bread crumb will be inserted after the title.
			 */
			if (title.getParentNode() == docElement)
			{
				insertBefore = title.getNextSibling();
			}
			/*
			 * If the first child is not a section title, we will insert the
			 * bread crumb before it
			 */
			else
			{
				insertBefore = docElement.getFirstChild();
			}
		}

		final Element itemizedList = xmlDocument.createElement("itemizedlist");

		/*
		 * final Element homeLink = xmlDocument.createElement("xref");
		 * homeLink.setAttribute("linkend", Constants.TOPIC_XREF_PREFIX +
		 * Constants.HOME_LANDING_PAGE_TOPIC_ID);
		 * breadCrumbPara.appendChild(homeLink);
		 */

		final Element titleElement = xmlDocument.createElement("title");
		titleElement.setTextContent("Categories");
		itemizedList.appendChild(titleElement);

		/*
		 * find the landing pages that match the combination of tags present in
		 * this topic
		 */

		/*
		 * This is a map of technology and common name tags to a list of landing
		 * page topics that include that tag along with the concern tag. This is
		 * used to group the breadcrumbs that are listed under the same top
		 * level TOC item.
		 */
		final Map<TagV1, List<Pair<TagV1, TopicV1>>> techCommonNameToTopicMap = new HashMap<TagV1, List<Pair<TagV1, TopicV1>>>();

		if (topic.getTags() != null && topic.getTags().getItems() != null)
		{
			final List<TagV1> topicTags = topic.getTags().getItems();
			for (int i = 0; i < topicTags.size(); ++i)
			{
				final TagV1 tag1 = topicTags.get(i);
				final Integer tag1ID = tag1.getId();

				for (int j = i + 1; j < topicTags.size(); ++j)
				{
					final TagV1 tag2 = topicTags.get(j);
					final Integer tag2ID = tag2.getId();

					final List<Integer> matchingTags = CollectionUtilities.toArrayList(DocbookBuilderConstants.TAG_DESCRIPTION_TAG_ID, tag1ID, tag2ID);
					final List<Integer> excludeTags = new ArrayList<Integer>();
					final List<TopicV1> landingPage = topicDatabase.getMatchingTopicsFromInteger(matchingTags, excludeTags, true, true);

					if (landingPage.size() == 1)
					{
						for (final TagV1 techCommonNameTag : new TagV1[] { tag1, tag2 })
						{
							if (techCommonNameTag.isInCategory(DocbookBuilderConstants.COMMON_NAME_CATEGORY_ID) || techCommonNameTag.isInCategory(DocbookBuilderConstants.TECHNOLOGY_CATEGORY_ID))
							{
								if (!techCommonNameToTopicMap.containsKey(techCommonNameTag))
									techCommonNameToTopicMap.put(techCommonNameTag, new ArrayList<Pair<TagV1, TopicV1>>());
								techCommonNameToTopicMap.get(techCommonNameTag).add(new Pair<TagV1, TopicV1>(techCommonNameTag == tag1 ? tag2 : tag1, landingPage.get(0)));
							}
						}
					}
				}
			}
		}

		for (final TagV1 techCommonNameTag : techCommonNameToTopicMap.keySet())
		{
			final Element listItem = xmlDocument.createElement("listitem");
			itemizedList.appendChild(listItem);

			final Element para = xmlDocument.createElement("para");
			listItem.appendChild(para);

			final Text techCommonName = xmlDocument.createTextNode(techCommonNameTag.getName() + ": ");
			para.appendChild(techCommonName);

			boolean firstConcern = true;
			for (final Pair<TagV1, TopicV1> concernAndLandingPage : techCommonNameToTopicMap.get(techCommonNameTag))
			{
				if (!firstConcern)
				{
					final Text concernDelimiter = xmlDocument.createTextNode(", ");
					para.appendChild(concernDelimiter);
				}

				final Element landingPageXRef = xmlDocument.createElement("link");
				landingPageXRef.setTextContent(concernAndLandingPage.getFirst().getName());

				if (fixedUrlsSuccess)
				{
					final PropertyTagV1 propTag = concernAndLandingPage.getSecond().getProperty(CommonConstants.FIXED_URL_PROP_TAG_ID);
					if (propTag != null)
					{
						landingPageXRef.setAttribute("linkend", propTag.getValue());
					}
					else
					{
						landingPageXRef.setAttribute("linkend", concernAndLandingPage.getSecond().getXRefID());
					}
				}
				else
				{
					landingPageXRef.setAttribute("linkend", concernAndLandingPage.getSecond().getXRefID());
				}
				para.appendChild(landingPageXRef);
				firstConcern = false;
			}
		}

		if (techCommonNameToTopicMap.size() != 0)
		{
			/*
			 * If insertBefore == null, it means the document is empty, or the
			 * title has no sibling, so we just append to the document.
			 */
			if (insertBefore == null)
				docElement.appendChild(itemizedList);
			else
				docElement.insertBefore(itemizedList, insertBefore);
		}
	}

	/**
	 * This function generates the table of contents pages for the topics
	 * 
	 * @param topics
	 *            The collection of topics to process
	 * @return
	 */
	private TocTopLevel doTOCPass(final BaseRestCollectionV1<TopicV1> topics, final Map<TopicV1, ArrayList<String>> usedIdAttributes, final DocbookBuildingOptions docbookBuildingOptions, final String searchTagsUrl, final String buildName, final boolean fixedUrlsSuccess)
	{
		NotificationUtilities.dumpMessageToStdOut("Doing TOC Pass");

		final TocTopLevel tocTopLevel = new TocTopLevel(docbookBuildingOptions);

		try
		{
			/* Get a reference to the tag description tag */
			final TagV1 tagDescription = restClient.getJSONTag(DocbookBuilderConstants.TAG_DESCRIPTION_TAG_ID, "");
			/* Get a reference to the home tag */
			final TagV1 home = restClient.getJSONTag(DocbookBuilderConstants.HOME_TAG_ID, "");

			/*
			 * Get a reference to the other topic type tags that should be
			 * displayed in the landing pages. Here we are defining that the
			 * task and overview topics will be displayed.
			 */
			final List<TagV1> topicTypes = new ArrayList<TagV1>();
			topicTypes.add(restClient.getJSONTag(DocbookBuilderConstants.TASK_TAG_ID, ""));
			topicTypes.add(restClient.getJSONTag(DocbookBuilderConstants.CONCEPTUALOVERVIEW_TAG_ID, ""));
			if (docbookBuildingOptions != null && !docbookBuildingOptions.getTaskAndOverviewOnly())
			{
				topicTypes.add(restClient.getJSONTag(DocbookBuilderConstants.CONCEPT_TAG_ID, ""));
				topicTypes.add(restClient.getJSONTag(DocbookBuilderConstants.REFERENCE_TAG_ID, ""));
			}

			/* The categories that make up the top level of the toc */
			final List<Integer> techCommonNameCategories = CollectionUtilities.toArrayList(DocbookBuilderConstants.COMMON_NAME_CATEGORY_ID, DocbookBuilderConstants.TECHNOLOGY_CATEGORY_ID);

			/* The categories that make up the second level of the toc */
			final List<Integer> concernCatgeory = CollectionUtilities.toArrayList(DocbookBuilderConstants.CONCERN_CATEGORY_ID);

			/*
			 * Get the tags that have been applied to topics in this build from
			 * the tech and common name categories
			 */
			final List<TagV1> techCommonNameTags = topicDatabase.getTagsFromCategories(techCommonNameCategories);

			/*
			 * Get the tags that have been applied to topics in this build from
			 * the tech and common name categories
			 */
			final List<TagV1> concernTags = topicDatabase.getTagsFromCategories(concernCatgeory);

			/*
			 * Landing pages are just topics, but with negative ids to
			 * distinguish them from opics pulled out of the database
			 */
			int nextLandingPageId = -1;

			/*
			 * Build the home landing page. This page will be renamed to
			 * index.html by the docbook compilation script.
			 * 
			 * The home landing page will always have an ID of -1.
			 */

			final String homeLinkLabel = "HOME";
			final TopicV1 homePage = buildLandingPageTopic(CollectionUtilities.toArrayList(home), nextLandingPageId, homeLinkLabel, usedIdAttributes, true, searchTagsUrl, docbookBuildingOptions, buildName, fixedUrlsSuccess);
			XMLPreProcessor.processTopicBugzillaLink(homePage, topicDatabase.getTopicProcessingData(homePage).getXmlDocument(), docbookBuildingOptions, buildName, searchTagsUrl);
			tocTopLevel.addChild(new TocLink(docbookBuildingOptions, homeLinkLabel, nextLandingPageId + "", DocbookUtils.buildULinkListItem("index.html", homeLinkLabel), DocbookBuilderXMLConstants.TOPIC_XREF_PREFIX + nextLandingPageId));

			/*
			 * decrement the nextLandingPageId counter, so the landing page
			 * topics start with an id of -2
			 */
			--nextLandingPageId;

			final int showPercent = 5;
			final float total = techCommonNameTags.size() * concernTags.size();
			float current = 0;
			int lastPercent = 0;

			/* Loop over all the tech and common name tags */
			for (final TagV1 techCommonNameTag : techCommonNameTags)
			{
				if (this.isShutdownRequested())
				{
					NotificationUtilities.dumpMessageToStdOut("Shutdown detected in DocbookBuildingThread.doTOCPass(). Exiting Loop.");
					return tocTopLevel;
				}

				/* This collection will hold the toc links to the landing pages */
				final List<TocLink> landingPageLinks = new ArrayList<TocLink>();

				/* Loop over all the concern tags */
				for (final TagV1 concernTag : concernTags)
				{
					++current;

					final int percent = Math.round(current / total * 100);
					if (percent - lastPercent >= showPercent)
					{
						lastPercent = percent;
						NotificationUtilities.dumpMessageToStdOut("\tProcessing Landing Pages " + percent + "% Done");
					}

					/*
					 * tags that are encompassed by another tag don't show up in
					 * the toc
					 */
					final boolean techCommonNameTagHasParent = techCommonNameTag.getParentTags() != null && techCommonNameTag.getParentTags().getItems() != null && techCommonNameTag.getParentTags().getItems().size() != 0;
					final boolean concernTagHasParent = concernTag.getParentTags() != null && concernTag.getParentTags().getItems() != null && concernTag.getParentTags().getItems().size() != 0;

					if (techCommonNameTagHasParent || concernTagHasParent)
						continue;

					/*
					 * See if we have topics that match this intersection of
					 * tech / common name and concern. We also have to deal with
					 * those tags that these technology and concern tags
					 * encompass. To do this we build up a list of tags that
					 * includes the technology parent and all it's children, and
					 * the concern parent and all it's children. We will then
					 * check each tech tag against each concern tag.
					 */

					/* build up the list of tech tags */
					final List<TagV1> techCommonNameTagAndChildTags = new ArrayList<TagV1>();
					techCommonNameTagAndChildTags.add(techCommonNameTag);

					if (techCommonNameTag.getChildTags() != null && techCommonNameTag.getChildTags().getItems() != null)
					{
						for (final TagV1 childTechCommonNameTag : techCommonNameTag.getChildTags().getItems())
						{
							techCommonNameTagAndChildTags.add(childTechCommonNameTag);
						}
					}

					/* build up a list of concern tags */
					final List<TagV1> concernTagAndChildTags = new ArrayList<TagV1>();
					concernTagAndChildTags.add(concernTag);
					if (concernTag.getChildTags() != null && concernTag.getChildTags().getItems() != null)
					{
						for (final TagV1 childConcernTag : concernTag.getChildTags().getItems())
						{
							concernTagAndChildTags.add(childConcernTag);
						}
					}

					/*
					 * find those topics that are tagged with any of the tech
					 * tags and any of the concern tags, and a topic type tag
					 */
					final List<TopicV1> matchingTopics = new ArrayList<TopicV1>();

					for (final TagV1 topicType : topicTypes)
					{
						for (final TagV1 techTagOrChild : techCommonNameTagAndChildTags)
						{
							for (final TagV1 concernTagOrChild : concernTagAndChildTags)
							{
								final List<TopicV1> thisMatchingTopics = topicDatabase.getMatchingTopicsFromTag(CollectionUtilities.toArrayList(techTagOrChild, concernTagOrChild, topicType));
								CollectionUtilities.addAllThatDontExist(thisMatchingTopics, matchingTopics);
							}
						}
					}

					/* build a landing page for these topics (if any were found */
					if (matchingTopics.size() != 0)
					{
						/* Sort the topics by title */
						Collections.sort(matchingTopics, new TopicV1TitleComparator());

						/* define a title for the landing page */
						final String landingPageTitle = techCommonNameTag.getName() + " > " + concernTag.getName();

						/*
						 * we have found topics that fall into this intersection
						 * of technology / common name and concern tags. create
						 * a link in the treeview
						 */
						landingPageLinks.add(new TocLink(docbookBuildingOptions, concernTag.getName(), nextLandingPageId + "", concernTag.getSortForCategory(DocbookBuilderConstants.CONCERN_CATEGORY_ID), DocbookBuilderXMLConstants.TOPIC_XREF_PREFIX + nextLandingPageId));

						/*
						 * We have topics that match this intersection, so we
						 * need to build a landing page
						 */
						final TopicV1 landingPage = buildLandingPageTopic(CollectionUtilities.toArrayList(techCommonNameTag, concernTag, tagDescription), nextLandingPageId, landingPageTitle, usedIdAttributes, false, searchTagsUrl, docbookBuildingOptions, buildName, fixedUrlsSuccess);

						/*
						 * Insert some links to those topics that have both the
						 * technology / common name tag and the concern tag
						 */
						final List<Node> listitems = new ArrayList<Node>();
						for (final TopicV1 matchingTopic : matchingTopics)
						{
							if (fixedUrlsSuccess)
							{
								final PropertyTagV1 propTag = matchingTopic.getProperty(CommonConstants.FIXED_URL_PROP_TAG_ID);
								if (propTag != null)
								{
									listitems.add(DocbookUtils.createRelatedTopicXRef(topicDatabase.getTopicProcessingData(landingPage).getXmlDocument(), propTag.getValue()));
								}
								else
								{
									listitems.add(DocbookUtils.createRelatedTopicXRef(topicDatabase.getTopicProcessingData(landingPage).getXmlDocument(), matchingTopic.getXRefID()));
								}
							}
							else
							{
								listitems.add(DocbookUtils.createRelatedTopicXRef(topicDatabase.getTopicProcessingData(landingPage).getXmlDocument(), matchingTopic.getXRefID()));
							}
						}
						final Node itemizedlist = DocbookUtils.wrapListItems(topicDatabase.getTopicProcessingData(landingPage).getXmlDocument(), listitems);
						topicDatabase.getTopicProcessingData(landingPage).getXmlDocument().getDocumentElement().appendChild(itemizedlist);

						XMLPreProcessor.processTopicBugzillaLink(landingPage, topicDatabase.getTopicProcessingData(landingPage).getXmlDocument(), docbookBuildingOptions, buildName, searchTagsUrl);

						/* Decrement the landing page topic id counter */
						--nextLandingPageId;
					}
				}

				/* test to see if there were any topics to add under this tech */
				if (landingPageLinks.size() != 0)
				{
					/*
					 * if so, create a folder, and add all of the concern links
					 * to it
					 */
					final TocFolderElement tocFolder = new TocFolderElement(docbookBuildingOptions, techCommonNameTag.getName());
					tocFolder.getChildren().addAll(landingPageLinks);
					tocFolder.sortChildren(new TocElementSortLabelComparator(true));
					/* add the tech folder to the top level folder */
					tocTopLevel.getChildren().add(tocFolder);
				}

			}

		}
		catch (final Exception ex)
		{
			ExceptionUtilities.handleException(ex);
		}

		tocTopLevel.sortChildren(new TocElementSortLabelComparator(true));
		return tocTopLevel;
	}

	private TopicV1 buildLandingPageTopic(final List<TagV1> templateTags, final Integer topicId, final String title, final Map<TopicV1, ArrayList<String>> usedIdAttributes, final boolean processedOnly, final String searchTagsUrl, final DocbookBuildingOptions docbookBuildingOptions, final String buildName,
			final boolean fixedUrlsSuccess) throws InvalidParameterException, InternalProcessingException
	{
		TopicV1 template = null;

		/* First, search the topicDatabase */
		final List<TopicV1> matchingExistingTopics = topicDatabase.getMatchingTopicsFromTag(templateTags);
		template = matchingExistingTopics.size() != 0 ? matchingExistingTopics.get(0) : null;

		/* if that fails, search the database */
		if (template == null && !processedOnly)
		{
			try
			{
				/*
				 * build up the query string that gets a topic with all the
				 * template tags
				 */
				StringBuffer query = new StringBuffer("query");
				for (final TagV1 templateTag : templateTags)
				{
					query.append(";");
					query.append("tag" + templateTag.getId() + "=1");
				}

				/*
				 * create an ExpandDataTrunk to expand the topics, the tags
				 * within the topics, and the categories and parenttags within
				 * the tags
				 */
				final ExpandDataTrunk expand = new ExpandDataTrunk();

				final ExpandDataTrunk expandTopics = new ExpandDataTrunk(new ExpandDataDetails("topics"));

				final ExpandDataTrunk tags = new ExpandDataTrunk(new ExpandDataDetails("tags"));
				tags.setBranches(CollectionUtilities.toArrayList(new ExpandDataTrunk(new ExpandDataDetails("categories")), new ExpandDataTrunk(new ExpandDataDetails("parenttags"))));

				expandTopics.setBranches(CollectionUtilities.toArrayList(tags));
				expand.setBranches(CollectionUtilities.toArrayList(expandTopics));

				final String expandString = mapper.writeValueAsString(expand);
				final String expandEncodedStrnig = URLEncoder.encode(expandString, "UTF-8");

				final BaseRestCollectionV1<TopicV1> templates = restClient.getJSONTopicsWithQuery(new PathSegmentImpl(query.toString(), false), expandEncodedStrnig);
				template = templates.getItems() != null && templates.getItems().size() != 0 ? templates.getItems().get(0) : null;
			}
			catch (final Exception ex)
			{
				ExceptionUtilities.handleException(ex);
			}
		}

		/*
		 * We have topics that match this intersection, so we need to build a
		 * landing page
		 */
		final TopicV1 landingPage = new TopicV1();
		landingPage.setId(topicId);
		landingPage.setTitle(title);
		landingPage.setProperties(new BaseRestCollectionV1<PropertyTagV1>());
		landingPage.setTags(new BaseRestCollectionV1<TagV1>());
		landingPage.setOutgoingRelationships(new BaseRestCollectionV1<TopicV1>());

		/* Add the landing page to the topic pool */
		topicDatabase.addTopic(landingPage);

		for (final TagV1 tag : templateTags)
			landingPage.addTag(tag);

		/*
		 * Apply the xml from the template topic, or a generic template if not
		 * template topic exists
		 */

		Document doc = null;

		if (template != null)
		{
			/* copy the xml */
			landingPage.setXml(template.getXml());

			/* convert the xml to a Document object */
			doc = XMLUtilities.convertStringToDocument(landingPage.getXml());

			/* fix the title */
			if (doc != null)
				DocbookUtils.setSectionTitle(landingPage.getTitle(), doc);

			/* copy the property tags */
			if (template.getProperties() != null && template.getProperties().getItems() != null)
			{
				for (final PropertyTagV1 propTag : template.getProperties().getItems())
				{
					landingPage.getProperties().addItem(propTag);
				}
			}
			
			/* copy the tags */
			if (template.getTags() != null && template.getTags().getItems() != null)
			{
				for (final TagV1 tag : template.getTags().getItems())
				{
					landingPage.getTags().addItem(tag);
				}
			}
			
			/* copy the tags */
			/*if (template.getOutgoingRelationships() != null && template.getOutgoingRelationships().getItems() != null)
			{
				for (final TopicV1 topic : template.getOutgoingRelationships().getItems())
				{
					landingPage.getOutgoingRelationships().addItem(topic);
				}
			}*/

			/* set the last modified date */
			landingPage.setLastModified(template.getLastModified());

		}

		if (doc != null)
			doc = new XMLValidator().validateTopicXML(doc, rocbookDTD.getName(), rocbookDTD.getValue());

		/*
		 * if validation fails at this point the template in the database is not
		 * valid, so revert to a base template
		 */
		if (template == null || doc == null || !validateIdAttributes(landingPage, topicDatabase.getTopicProcessingData(landingPage), usedIdAttributes, fixedUrlsSuccess))
		{
			// landingPage.getParentTopicToTopics().clear();
			landingPage.setXml("<section><title></title><para></para></section>");
			landingPage.setLastModified(new Date());
			/* convert the xml to a Document object */
			doc = XMLUtilities.convertStringToDocument(landingPage.getXml());
			/* fix the title */
			if (doc != null)
				DocbookUtils.setSectionTitle(landingPage.getTitle(), doc);
		}

		/* set the Document for the topic processing data */
		topicDatabase.getTopicProcessingData(landingPage).setXmlDocument(doc);

		/*
		 * Apply some of the standard fixes to the landing page topics
		 */
		processTopicID(landingPage, topicDatabase.getTopicProcessingData(landingPage), fixedUrlsSuccess);
		procesImageLocations(landingPage, topicDatabase.getTopicProcessingData(landingPage));

		return landingPage;

	}

	/**
	 * This method does a pass over all the topics returned by the query and
	 * attempts to create unique Fixed URL if one does not already exist.
	 * 
	 * @return true if fixed url property tags were able to be created for all
	 *         topics, and false otherwise
	 */
	private boolean setFixedURLsPass()
	{
		NotificationUtilities.dumpMessageToStdOut("Doing Fixed Url Generation Pass");

		int tries = 0;
		boolean success = false;

		while (tries < DocbookBuilderXMLConstants.MAXIMUM_SET_PROP_TAGS_RETRY && !success)
		{
			++tries;

			try
			{
				final BaseRestCollectionV1<TopicV1> updateTopics = new BaseRestCollectionV1<TopicV1>();

				for (final TopicV1 topic : topicDatabase.getTopics())
				{
					if (this.isShutdownRequested())
					{
						NotificationUtilities.dumpMessageToStdOut("Shutdown detected in DocbookBuildingThread.setFixedURLsPass(). Exiting Loop.");
						return false;
					}

					final PropertyTagV1 existingUniqueURL = topic.getProperty(CommonConstants.FIXED_URL_PROP_TAG_ID);

					if (existingUniqueURL == null || !existingUniqueURL.isValid())
					{
						/*
						 * generate the base url
						 */
						String baseUrlName = topic.getTitle();

						/*
						 * start by removing any prefixed numbers (you can't
						 * start an xref id with numbers)
						 */
						final Pattern pattern = Pattern.compile(STARTS_WITH_NUMBER_RE);
						final Matcher matcher = pattern.matcher(baseUrlName);

						if (matcher.find())
						{
							try
							{
								final String numbers = matcher.group("Numbers");
								final String everythingElse = matcher.group("EverythingElse");

								if (numbers != null && everythingElse != null)
								{
									final NumberFormat formatter = new RuleBasedNumberFormat(RuleBasedNumberFormat.SPELLOUT);
									final String numbersSpeltOut = formatter.format(Integer.parseInt(numbers));
									baseUrlName = numbersSpeltOut + everythingElse;

									/* capatilise the first character */
									if (baseUrlName.length() > 0)
										baseUrlName = baseUrlName.substring(0, 1).toUpperCase() + baseUrlName.substring(1, baseUrlName.length());
								}
							}
							catch (final Exception ex)
							{
								ExceptionUtilities.handleException(ex);
							}
						}

						/*
						 * replace spaces with underscores and remove non
						 * alphanumeric characters
						 */
						baseUrlName = baseUrlName.replaceAll(" ", "_").replaceAll("[^0-9A-Za-z_]", "");
						while (baseUrlName.indexOf("__") != -1)
							baseUrlName = baseUrlName.replaceAll("__", "_");

						/* generate a unique fixed url */
						String postFix = "";

						for (int uniqueCount = 1; uniqueCount <= DocbookBuilderXMLConstants.MAXIMUM_SET_PROP_TAGS_RETRY; ++uniqueCount)
						{
							final String query = "query;propertyTag1=" + CommonConstants.FIXED_URL_PROP_TAG_ID + URLEncoder.encode(" " + baseUrlName + postFix, "UTF-8");
							final BaseRestCollectionV1<TopicV1> topics = restClient.getJSONTopicsWithQuery(new PathSegmentImpl(query, false), "");

							if (topics.getSize() != 0)
							{
								postFix = uniqueCount + "";
							}
							else
							{
								break;
							}
						}

						NotificationUtilities.dumpMessageToStdOut("\tFound unique URL for topic " + topic.getId() + " of: " + baseUrlName + postFix);

						/*
						 * persist the new fixed url, as long as we are not
						 * looking at a landing page topic
						 */
						if (topic.getId() >= 0)
						{
							final PropertyTagV1 propertyTag = new PropertyTagV1();
							propertyTag.setId(CommonConstants.FIXED_URL_PROP_TAG_ID);
							propertyTag.setValue(baseUrlName + postFix);
							propertyTag.setAddItem(true);

							final BaseRestCollectionV1<PropertyTagV1> updatePropertyTags = new BaseRestCollectionV1<PropertyTagV1>();
							updatePropertyTags.addItem(propertyTag);

							/* remove any old fixed url property tags */
							if (topic.getProperties() != null && topic.getProperties().getItems() != null)
							{
								for (final PropertyTagV1 existing : topic.getProperties().getItems())
								{
									if (existing.getId().equals(CommonConstants.FIXED_URL_PROP_TAG_ID))
									{
										final PropertyTagV1 removePropertyTag = new PropertyTagV1();
										removePropertyTag.setId(CommonConstants.FIXED_URL_PROP_TAG_ID);
										removePropertyTag.setValue(existing.getValue());
										removePropertyTag.setRemoveItem(true);
										updatePropertyTags.addItem(removePropertyTag);
									}
								}
							}

							final TopicV1 updateTopic = new TopicV1();
							updateTopic.setId(topic.getId());
							updateTopic.setPropertiesExplicit(updatePropertyTags);

							updateTopics.addItem(updateTopic);
						}
					}
				}

				if (updateTopics.getItems() != null && updateTopics.getItems().size() != 0)
				{
					NotificationUtilities.dumpMessageToStdOut("\tUpdating fixed url property tags for " + updateTopics.getItems().size() + " topics.");
					restClient.updateJSONTopics("", updateTopics);
				}

				/* If we got here, then the REST update went ok */
				success = true;

				/* copy the topics fixed url properties to our local collection */
				if (updateTopics.getItems() != null && updateTopics.getItems().size() != 0)
				{
					for (final TopicV1 topicWithFixedUrl : updateTopics.getItems())
					{
						for (final TopicV1 topic : topicDatabase.getTopics())
						{
							if (topicWithFixedUrl.getId().equals(topic.getId()))
							{
								/* clear any existing property tags */
								topic.setProperties(new BaseRestCollectionV1<PropertyTagV1>());

								final PropertyTagV1 fixedUrlProp = topicWithFixedUrl.getProperty(CommonConstants.FIXED_URL_PROP_TAG_ID);

								if (fixedUrlProp != null)
									topic.getProperties().addItem(fixedUrlProp);
							}

							/*
							 * we also have to copy the fixed urls into the
							 * related topics
							 */
							for (final TopicV1 relatedTopic : topic.getOutgoingRelationships().getItems())
							{
								if (topicWithFixedUrl.getId().equals(relatedTopic.getId()))
								{
									/* clear any existing property tags */
									relatedTopic.setProperties(new BaseRestCollectionV1<PropertyTagV1>());

									final PropertyTagV1 fixedUrlProp = topicWithFixedUrl.getProperty(CommonConstants.FIXED_URL_PROP_TAG_ID);

									if (fixedUrlProp != null)
										relatedTopic.getProperties().addItem(fixedUrlProp);
								}
							}
						}
					}
				}
			}
			catch (final Exception ex)
			{
				/*
				 * Dump the exception to the command prompt, and restart the
				 * loop
				 */
				ExceptionUtilities.handleException(ex);
			}
		}

		/* did we blow the try count? */
		return success;
	}

	/**
	 * This method loops back over the topic database and sets fixed urls on the
	 * landing pages (identified by a negative topic id). It also sets the
	 * section ids, and fixes up the TocLink elements so they point to the new
	 * xrefs ids.
	 */
	private void setFixedURLsLandingPagesPass(final TocTopLevel toc, final boolean fixedUrlsSuccess)
	{
		NotificationUtilities.dumpMessageToStdOut("Doing Fixed Url Generation Pass on Landing Pages");

		int tries = 0;
		boolean success = false;

		while (tries < DocbookBuilderXMLConstants.MAXIMUM_SET_PROP_TAGS_RETRY && !success)
		{
			++tries;

			try
			{
				for (final TopicV1 topic : topicDatabase.getTopics())
				{
					if (this.isShutdownRequested())
					{
						NotificationUtilities.dumpMessageToStdOut("Shutdown detected in DocbookBuildingThread.setFixedURLsLandingPagesPass(). Exiting Loop.");
						return;
					}

					if (topic.getId() < 0)
					{
						/*
						 * generate the base url by replacing spaces with
						 * underscores and removing non alphanumeric characters
						 */
						String baseUrlName = topic.getTitle().replaceAll(" ", "_").replaceAll("[^0-9A-Za-z_]", "");
						while (baseUrlName.indexOf("__") != -1)
							baseUrlName = baseUrlName.replaceAll("__", "_");

						/* generate a unique fixed url */
						String postFix = "";
						boolean uniqueNameSuccess = false;
						for (int uniqueCount = 1; uniqueCount <= DocbookBuilderXMLConstants.MAXIMUM_SET_PROP_TAGS_RETRY; ++uniqueCount)
						{
							final String query = "query;propertyTag1=" + CommonConstants.FIXED_URL_PROP_TAG_ID + URLEncoder.encode(" " + baseUrlName + postFix, "UTF-8");
							final BaseRestCollectionV1<TopicV1> topics = restClient.getJSONTopicsWithQuery(new PathSegmentImpl(query, false), "");

							if (topics.getSize() != 0)
							{
								postFix = uniqueCount + "";
							}
							else
							{
								uniqueNameSuccess = true;
								break;
							}
						}

						if (!uniqueNameSuccess)
							throw new Exception("Could not find unique name");

						NotificationUtilities.dumpMessageToStdOut("\tFound unique URL for topic " + topic.getId() + " of: " + baseUrlName + postFix);

						/*
						 * remove any existing fixed url property tags. these
						 * are sometimes copied in from the templates used to
						 * build the landing pages
						 */
						if (topic.getProperties() != null && topic.getProperties().getItems() != null)
						{
							final List<PropertyTagV1> removeTags = new ArrayList<PropertyTagV1>();
							for (final PropertyTagV1 propTag : topic.getProperties().getItems())
							{
								if (propTag.getId().equals(CommonConstants.FIXED_URL_PROP_TAG_ID))
									removeTags.add(propTag);
							}

							for (final PropertyTagV1 propTag : removeTags)
							{
								topic.getProperties().getItems().remove(propTag);
							}
						}

						/* create a new fixed url property tag */
						final PropertyTagV1 propertyTag = new PropertyTagV1();
						propertyTag.setId(CommonConstants.FIXED_URL_PROP_TAG_ID);
						propertyTag.setValue(baseUrlName + postFix);

						if (topic.getProperties() == null)
							topic.setProperties(new BaseRestCollectionV1<PropertyTagV1>());

						topic.getProperties().addItem(propertyTag);

						processTopicID(topic, topicDatabase.getTopicProcessingData(topic), fixedUrlsSuccess);

						/* Update the TOC page link */
						if (fixedUrlsSuccess)
						{
							final TocElement matchingChild = toc.getFirstChildById(topic.getId().toString());
							if (matchingChild != null && matchingChild instanceof TocLink)
							{
								((TocLink) matchingChild).setPageName(baseUrlName + postFix);
							}
						}
					}
				}
			}
			catch (final Exception ex)
			{
				/*
				 * Dump the exception to the command prompt, and restart the
				 * loop
				 */
				ExceptionUtilities.handleException(ex);
			}
		}
	}

	private byte[] doBuildZipPass(final TocTopLevel tocTopLevel, final DocbookBuildingOptions docbookBuildingOptions, final boolean fixedUrlsSuccess) throws InvalidParameterException, InternalProcessingException
	{
		NotificationUtilities.dumpMessageToStdOut("Doing Build ZIP Pass");

		/* Load some constants from the database */
		final String revisionHistoryXml = restClient.getJSONStringConstant(DocbookBuilderConstants.REVISION_HISTORY_XML_ID, "").getValue();
		final String bookXml = restClient.getJSONStringConstant(DocbookBuilderConstants.BOOK_XML_ID, "").getValue();
		final String publicanCfg = restClient.getJSONStringConstant(DocbookBuilderConstants.PUBLICAN_CFG_ID, "").getValue();
		final String authorGroupXml = restClient.getJSONStringConstant(DocbookBuilderConstants.AUTHOR_GROUP_XML_ID, "").getValue();
		final String bookInfoXml = restClient.getJSONStringConstant(DocbookBuilderConstants.BOOK_INFO_XML_ID, "").getValue();
		final String bookEnt = restClient.getJSONStringConstant(DocbookBuilderConstants.BOOK_ENT_ID, "").getValue();
		final String iconSvg = restClient.getJSONStringConstant(DocbookBuilderConstants.ICON_SVG_ID, "").getValue();

		final String makefile = restClient.getJSONStringConstant(DocbookBuilderConstants.MAKEFILE_ID, "").getValue();
		final String spec_in = restClient.getJSONStringConstant(DocbookBuilderConstants.SPEC_IN_ID, "").getValue();
		final String package_sh = restClient.getJSONStringConstant(DocbookBuilderConstants.PACKAGE_SH_ID, "").getValue();

		final String startPage = restClient.getJSONStringConstant(DocbookBuilderConstants.START_PAGE_ID, "").getValue();
		final String jbossSvg = restClient.getJSONStringConstant(DocbookBuilderConstants.JBOSS_SVG_ID, "").getValue();

		final String yahooDomEventJs = restClient.getJSONStringConstant(DocbookBuilderConstants.YAHOO_DOM_EVENT_JS_ID, "").getValue();
		final String treeviewMinJs = restClient.getJSONStringConstant(DocbookBuilderConstants.TREEVIEW_MIN_JS_ID, "").getValue();
		final String treeviewCss = restClient.getJSONStringConstant(DocbookBuilderConstants.TREEVIEW_CSS_ID, "").getValue();
		final String jqueryMinJs = restClient.getJSONStringConstant(DocbookBuilderConstants.JQUERY_MIN_JS_ID, "").getValue();

		final byte[] treeviewSpriteGif = restClient.getJSONBlobConstant(DocbookBuilderConstants.TREEVIEW_SPRITE_GIF_ID, "").getValue();
		final byte[] treeviewLoadingGif = restClient.getJSONBlobConstant(DocbookBuilderConstants.TREEVIEW_LOADING_GIF_ID, "").getValue();
		final byte[] check1Gif = restClient.getJSONBlobConstant(DocbookBuilderConstants.CHECK1_GIF_ID, "").getValue();
		final byte[] check2Gif = restClient.getJSONBlobConstant(DocbookBuilderConstants.CHECK2_GIF_ID, "").getValue();

		final String pluginXml = restClient.getJSONStringConstant(DocbookBuilderConstants.PLUGIN_XML_ID, "").getValue();
		final String eclisePackageSh = restClient.getJSONStringConstant(DocbookBuilderConstants.ECLIPSE_PACKAGE_SH_ID, "").getValue();
		final String publicanEclipseCfg = restClient.getJSONStringConstant(DocbookBuilderConstants.PUBLICAN_ECLIPSE_CFG_ID, "").getValue();

		/* build up the files that will make up the zip file */
		final HashMap<String, byte[]> files = new HashMap<String, byte[]>();

		/* add the images to the zip file */
		addImagesToBook(files);

		/*
		 * the narrative style will not include a TOC or Eclipse plugin, and
		 * includes a different publican.cfg file
		 */
		String publicnCfgFixed = publicanCfg;

		/* get the HTML TOC */
		NotificationUtilities.dumpMessageToStdOut("\tGenerating the HTML TOC XML");
		final String toc = tocTopLevel.getDocbook();

		/* get the Eclipse TOC */
		NotificationUtilities.dumpMessageToStdOut("\tGenerating the Eclipse TOC XML");
		final String eclipseToc = tocTopLevel.getEclipseXml();

		/* add the files that make up the Eclipse help package */
		NotificationUtilities.dumpMessageToStdOut("\tAdding standard files to Publican ZIP file");
		files.put("Book/eclipse/com.redhat.eap6.doc_1.0.0/toc.xml", StringUtilities.getStringBytes(eclipseToc));
		files.put("Book/eclipse/com.redhat.eap6.doc_1.0.0/plugin.xml", StringUtilities.getStringBytes(pluginXml));
		files.put("Book/eclipse_package.sh", StringUtilities.getStringBytes(StringUtilities.convertToLinuxLineEndings(eclisePackageSh)));
		files.put("Book/publican_eclipse.cfg", StringUtilities.getStringBytes(publicanEclipseCfg));
		files.put("Book/en-US/Toc.xml", StringUtilities.getStringBytes(toc));
		files.put("Book/en-US/StartPage.xml", StringUtilities.getStringBytes(startPage == null ? "" : startPage));

		/* fix the Publican CFG file */
		if (publicnCfgFixed != null)
		{
			if (docbookBuildingOptions.getPublicanShowRemarks())
			{
				publicnCfgFixed += "\nshow_remarks: 1";
			}

			publicnCfgFixed += "\ncvs_pkg: " + docbookBuildingOptions.getCvsPkgOption();
		}

		// add the publican.cfg file
		files.put("Book/publican.cfg", StringUtilities.getStringBytes(StringUtilities.convertToLinuxLineEndings(publicnCfgFixed)));

		// add the files that are used to package up the RPM file
		files.put("Book/package.sh", StringUtilities.getStringBytes(StringUtilities.convertToLinuxLineEndings(package_sh)));

		// the make file is built up from options supplied from the user
		String makefileFixed = makefile;
		makefileFixed = "RELEASE = " + docbookBuildingOptions.getMakefileReleaseOption() + "\n" + makefileFixed;
		makefileFixed = "VERSION = " + docbookBuildingOptions.getMakefileVersionOption() + "\n" + makefileFixed;
		makefileFixed = "BOOKS = " + docbookBuildingOptions.getMakefileBooksOption() + "\n" + makefileFixed;
		makefileFixed = "LANG = " + docbookBuildingOptions.getMakefileLangOption() + "\n" + makefileFixed;
		makefileFixed = "PROD_VERSION = " + docbookBuildingOptions.getMakefileProdVersionOption() + "\n" + makefileFixed;
		makefileFixed = "PRODUCT = " + docbookBuildingOptions.getMakefileProductOption() + "\n" + makefileFixed;
		files.put("Book/packager/en-US/Makefile", StringUtilities.getStringBytes(StringUtilities.convertToLinuxLineEndings(makefileFixed)));

		files.put("Book/packager/en-US/spec.in", StringUtilities.getStringBytes(StringUtilities.convertToLinuxLineEndings(spec_in)));

		// replace the date marker in the Book.XML file
		files.put("Book/en-US/Book_Info.xml", bookInfoXml.replace(DocbookBuilderConstants.DATE_YYMMDD_TEXT_MARKER, new StringBuilder(new SimpleDateFormat("yyMMdd").format(new Date()))).getBytes());

		files.put("Book/en-US/Author_Group.xml", StringUtilities.getStringBytes(authorGroupXml));
		files.put("Book/en-US/Book.ent", StringUtilities.getStringBytes(bookEnt));

		// these files are used by the YUI treeview
		files.put("Book/en-US/files/yahoo-dom-event.js", StringUtilities.getStringBytes(yahooDomEventJs));
		files.put("Book/en-US/files/treeview-min.js", StringUtilities.getStringBytes(treeviewMinJs));
		files.put("Book/en-US/files/treeview.css", StringUtilities.getStringBytes(treeviewCss));
		files.put("Book/en-US/files/jquery.min.js", StringUtilities.getStringBytes(jqueryMinJs));

		// these are the images that are referenced in the treeview.css file
		files.put("Book/en-US/files/treeview-sprite.gif", treeviewSpriteGif);
		files.put("Book/en-US/files/treeview-loading.gif", treeviewLoadingGif);
		files.put("Book/en-US/files/check1.gif", check1Gif);
		files.put("Book/en-US/files/check2.gif", check2Gif);

		files.put("Book/en-US/images/icon.svg", StringUtilities.getStringBytes(iconSvg));
		files.put("Book/en-US/images/jboss.svg", StringUtilities.getStringBytes(jbossSvg));

		/*
		 * build the middle of the Book.xml file, where we include references to
		 * the topic type pages that were built above
		 */
		String bookXmlXiIncludes = "";

		/* add an xml file for each topic */
		NotificationUtilities.dumpMessageToStdOut("\tAdding Topics files to Publican ZIP file");
		String topics = "";
		for (final TopicV1 topic : topicDatabase.getTopics())
		{
			if (this.isShutdownRequested())
			{
				NotificationUtilities.dumpMessageToStdOut("Shutdown detected in DocbookBuildingThread.doBuildZipPass(). Exiting Loop.");
				return null;
			}

			/*
			 * The file names will either be the fixed url property tag, or the
			 * topic id if we could not generate unique file names
			 */
			String fileName = "";
			if (fixedUrlsSuccess)
			{
				final PropertyTagV1 propTag = topic.getProperty(CommonConstants.FIXED_URL_PROP_TAG_ID);
				if (propTag != null)
				{
					fileName = propTag.getValue() + ".xml";
				}
				else
				{
					errorDatabase.addError(topic, "Topic does not have the fixed url property tag.");
					fileName = "Topic" + topic.getId() + ".xml";
				}
			}
			else
			{
				fileName = "Topic" + topic.getId() + ".xml";
			}

			final Document doc = topicDatabase.getTopicProcessingData(topic).getXmlDocument();

			/* get a formatted copy of the XML Document */
			String docString = null;
			if (xmlFormattingPropertiesSet)
				docString = XMLUtilities.convertNodeToString(doc, verbatimElements, inlineElements, contentsInlineElements, true);
			else
				docString = XMLUtilities.convertNodeToString(doc, true);

			final String topicString = DocbookUtils.addXMLBoilerplate(docString);

			/*
			 * Remove the non-breaking space character, which seems to pop up
			 * every now and again. This character breaks Publican.
			 */
			final String topicStringSanatized = StringUtilities.cleanTextForXML(topicString);

			files.put("Book/en-US/" + fileName, StringUtilities.getStringBytes(topicStringSanatized));
			topics += "	<xi:include href=\"" + fileName + "\" xmlns:xi=\"http://www.w3.org/2001/XInclude\" />\n";
		}

		topics = DocbookUtils.addXMLBoilerplate(DocbookUtils.buildChapter(topics, ""));
		files.put("Book/en-US/Topics.xml", topics.getBytes());

		bookXmlXiIncludes += "	<xi:include href=\"Topics.xml\" xmlns:xi=\"http://www.w3.org/2001/XInclude\" />\n";

		/* build the chapter containing the compiler errors */
		if (!docbookBuildingOptions.getSuppressErrorsPage())
		{
			NotificationUtilities.dumpMessageToStdOut("\tBuilding Error Chapter");
			final String compilerOutput = buildErrorChapter();

			files.put("Book/en-US/Errors.xml", StringUtilities.getStringBytes(StringUtilities.cleanTextForXML(compilerOutput == null ? "" : compilerOutput)));
			bookXmlXiIncludes += "	<xi:include href=\"Errors.xml\" xmlns:xi=\"http://www.w3.org/2001/XInclude\" />\n";
		}

		/*
		 * only reference the Toc.xml file if we are not building a narrative
		 * book
		 */
		if (!docbookBuildingOptions.getBuildNarrative())
		{
			bookXmlXiIncludes += "	<xi:include href=\"Toc.xml\" xmlns:xi=\"http://www.w3.org/2001/XInclude\" />";
			bookXmlXiIncludes += "	<xi:include href=\"StartPage.xml\" xmlns:xi=\"http://www.w3.org/2001/XInclude\" />";
		}

		// replace the marker in the book.xml template
		final String thisBookXml = bookXml.replace(DocbookBuilderConstants.BOOK_XML_XI_INCLUDES_MARKER, bookXmlXiIncludes);

		files.put("Book/en-US/Book.xml", StringUtilities.getStringBytes(thisBookXml));
		files.put("Book/en-US/Revision_History.xml", StringUtilities.getStringBytes(revisionHistoryXml));

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

		return zipFile;
	}

	/**
	 * This function emails the ZIP file to the user
	 * 
	 * @param topics
	 *            The collection of topics to process
	 */
	private void emailZIP(final BaseRestCollectionV1<TopicV1> topics, final byte[] zip, final DocbookBuildingOptions docbookBuildingOptions)
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

	private String getTopicSkynetURL(final TopicV1 topic)
	{
		return CommonConstants.SERVER_URL + "/TopicIndex/CustomSearchTopicList.seam?topicIds=" + topic.getId();
	}

	private String buildErrorChapter()
	{
		String errorItemizedLists = "";

		if (errorDatabase.hasItems())
		{
			for (final TopicErrorData topicErrorData : errorDatabase.getErrors())
			{
				final TopicV1 topic = topicErrorData.getTopic();

				final List<String> topicErrorItems = new ArrayList<String>();

				final String tags = topic.getCommaSeparatedTagList();
				final String url = getTopicSkynetURL(topic);

				topicErrorItems.add(DocbookUtils.buildListItem("INFO: " + tags));
				topicErrorItems.add(DocbookUtils.buildListItem("INFO: <ulink url=\"" + url + "\">Topic URL</ulink>"));

				for (final String error : topicErrorData.getItemsOfType(TopicErrorDatabase.ERROR))
					topicErrorItems.add(DocbookUtils.buildListItem("ERROR: " + error));

				for (final String warning : topicErrorData.getItemsOfType(TopicErrorDatabase.WARNING))
					topicErrorItems.add(DocbookUtils.buildListItem("WARNING: " + warning));

				/*
				 * this should never be false, because a topic will only be
				 * listed in the errors collection once a error or warning has
				 * been added. The count of 2 comes from the standard list items
				 * we added above for the tags and url.
				 */
				if (topicErrorItems.size() > 2)
				{
					final String title = "Topic ID " + topic.getId();
					final String id = DocbookBuilderConstants.ERROR_XREF_ID_PREFIX + topic.getId();

					errorItemizedLists += DocbookUtils.wrapListItems(topicErrorItems, title, id);
				}
			}
		}
		else
		{
			errorItemizedLists = "<para>No Errors Found</para>";
		}

		return DocbookUtils.buildChapter(errorItemizedLists, "Compiler Output");
	}

	private void addImagesToBook(final HashMap<String, byte[]> files) throws InvalidParameterException, InternalProcessingException
	{
		/* Load the database constants */
		final byte[] failpenguinPng = restClient.getJSONBlobConstant(DocbookBuilderConstants.FAILPENGUIN_PNG_ID, "").getValue();

		/* download the image files that were identified in the processing stage */
		int imageProgress = 0;
		final int imageTotal = this.imageLocations.size();

		for (final TopicImageData imageLocation : this.imageLocations)
		{
			boolean success = false;

			final int extensionIndex = imageLocation.getImageName().lastIndexOf(".");
			final int pathIndex = imageLocation.getImageName().lastIndexOf("/");

			if (/* characters were found */
			extensionIndex != -1 && pathIndex != -1 &&
			/* the path character was found before the extension */
			extensionIndex > pathIndex)
			{
				try
				{
					/*
					 * The file name minus the extension should be an integer
					 * that references an ImageFile record ID.
					 */
					final String topicID = imageLocation.getImageName().substring(pathIndex + 1, extensionIndex);
					final ImageV1 imageFile = restClient.getJSONImage(Integer.parseInt(topicID), "");

					if (imageFile != null)
					{
						success = true;
						files.put("Book/en-US/" + imageLocation.getImageName(), imageFile.getImageData());
					}
					else
					{
						errorDatabase.addError(imageLocation.getTopic(), "ImageFile ID " + topicID + " from image location " + imageLocation + " was not found!");
						NotificationUtilities.dumpMessageToStdOut("ImageFile ID " + topicID + " from image location " + imageLocation + " was not found!");
					}
				}
				catch (final Exception ex)
				{
					success = false;
					errorDatabase.addError(imageLocation.getTopic(), imageLocation + " is not a valid image. Must be in the format [ImageFileID].extension e.g. 123.png, or images/321.jpg");
					ExceptionUtilities.handleException(ex);
				}
			}

			/* put in a place holder */
			if (!success)
			{
				files.put("Book/en-US/" + imageLocation.getImageName(), failpenguinPng);
			}

			final float progress = (float) imageProgress / (float) imageTotal * 100;
			NotificationUtilities.dumpMessageToStdOut("\tDownloading Images " + Math.round(progress) + "% done");

			++imageProgress;
		}
	}
}
