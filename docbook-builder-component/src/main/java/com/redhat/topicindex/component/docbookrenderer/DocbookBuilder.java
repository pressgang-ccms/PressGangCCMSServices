package com.redhat.topicindex.component.docbookrenderer;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.resteasy.specimpl.PathSegmentImpl;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
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
import com.redhat.ecs.services.docbookcompiling.BuildDocbookMessage;
import com.redhat.ecs.services.docbookcompiling.DocbookBuilderConstants;
import com.redhat.ecs.services.docbookcompiling.DocbookBuildingOptions;
import com.redhat.ecs.services.docbookcompiling.DocbookUtils;
import com.redhat.ecs.services.docbookcompiling.xmlprocessing.XMLPreProcessor;
import com.redhat.topicindex.component.docbookrenderer.constants.DocbookBuilderXMLConstants;
import com.redhat.topicindex.component.docbookrenderer.structures.TopicErrorData;
import com.redhat.topicindex.component.docbookrenderer.structures.TopicErrorDatabase;
import com.redhat.topicindex.component.docbookrenderer.structures.TopicImageData;
import com.redhat.topicindex.component.docbookrenderer.structures.tocformat.TagRequirements;
import com.redhat.topicindex.component.docbookrenderer.structures.tocformat.TocFormatBranch;
import com.redhat.topicindex.rest.collections.BaseRestCollectionV1;
import com.redhat.topicindex.rest.entities.BaseTopicV1;
import com.redhat.topicindex.rest.entities.BlobConstantV1;
import com.redhat.topicindex.rest.entities.CategoryV1;
import com.redhat.topicindex.rest.entities.ImageV1;
import com.redhat.topicindex.rest.entities.PropertyTagV1;
import com.redhat.topicindex.rest.entities.TagV1;
import com.redhat.topicindex.rest.entities.TopicV1;
import com.redhat.topicindex.rest.entities.TranslatedTopicV1;
import com.redhat.topicindex.rest.exceptions.InternalProcessingException;
import com.redhat.topicindex.rest.exceptions.InvalidParameterException;
import com.redhat.topicindex.rest.expand.ExpandDataDetails;
import com.redhat.topicindex.rest.expand.ExpandDataTrunk;
import com.redhat.topicindex.rest.sharedinterface.RESTInterfaceV1;

public class DocbookBuilder<T extends BaseTopicV1<T>> {

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

	/** The name of the builder */
	private static final String BUILD_NAME = Main.NAME + " " + Main.BUILD;
	
	private Date buildDate;
	
	/**
	 * Contains all the details of all topics that will be included in this
	 * build
	 */
	// private final TocTopicDatabase topicDatabase = new TocTopicDatabase();

	private Map<T, Document> topicXMLDocuments = new HashMap<T, Document>();

	/**
	 * Holds the compiler errors that form the Errors.xml file in the compiled
	 * docbook
	 */
	private final TopicErrorDatabase<T> errorDatabase = new TopicErrorDatabase<T>();

	/**
	 * Holds information on file url locations, which will be downloaded and
	 * included in the docbook zip file
	 */
	private final ArrayList<TopicImageData<T>> imageLocations = new ArrayList<TopicImageData<T>>();

	/** The REST client */
	private final RESTInterfaceV1 restClient;
	
	/** The Builder thread that called this build */
	final DocbookBuildingThread builderThread;

	/** Jackson object mapper */
	private final ObjectMapper mapper = new ObjectMapper();

	private ArrayList<String> verbatimElements;
	private ArrayList<String> inlineElements;
	private ArrayList<String> contentsInlineElements;
	private boolean xmlFormattingPropertiesSet = false;

	private BlobConstantV1 rocbookDTD;
	
	private final Class<T> clazz;
	
	public DocbookBuilder(final Class<T> clazz, final DocbookBuildingThread builderThread, final RESTInterfaceV1 restClient) throws InvalidParameterException, InternalProcessingException {
		this.clazz = clazz;
		this.builderThread = builderThread;
		this.restClient = restClient;
		
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
		
		rocbookDTD = restClient.getJSONBlobConstant(DocbookBuilderConstants.ROCBOOK_DTD_BLOB_ID, "");
	}
	
	public boolean buildBook(final BaseRestCollectionV1<T> topics, final BuildDocbookMessage buildDocbookMessage, final String searchTagsUrl) throws InvalidParameterException, InternalProcessingException
	{
		this.buildDate = new Date();
		
		if (topics.getItems() != null)
		{
			NotificationUtilities.dumpMessageToStdOut("Processing " + topics.getItems().size() + " Topics");

			/* Initialise the topic database */
			// topicDatabase.setTopics(topics.getItems());

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
			final boolean fixedUrlsSuccess;
			if (clazz == TopicV1.class) {
				fixedUrlsSuccess = setFixedURLsPass();
			} else {
				fixedUrlsSuccess = true;
			}

			/* early exit if shutdown has been requested */
			if (builderThread.isShutdownRequested())
			{
				return false;
			}
			
			/* Split the topics up into their different locales */
			final Map<String, BaseRestCollectionV1<T>> groupedLocaleTopics = new HashMap<String, BaseRestCollectionV1<T>>();
			for (final T topic: topics.getItems())
			{
				if (!groupedLocaleTopics.containsKey(topic.getLocale()))
					groupedLocaleTopics.put(topic.getLocale(), new BaseRestCollectionV1<T>());
				groupedLocaleTopics.get(topic.getLocale()).addItem(topic);
			}

			final Map<String, TocFormatBranch<T>> localeGroupedTocs = new HashMap<String, TocFormatBranch<T>>();
			for (final String locale: groupedLocaleTopics.keySet())
			{
				final BaseRestCollectionV1<T> localeTopics = groupedLocaleTopics.get(locale);
				
				topicXMLDocuments = new HashMap<T, Document>();
				
				final Map<T, ArrayList<String>> usedIdAttributes = doFirstValidationPass(localeTopics, fixedUrlsSuccess, locale);
	
				/* early exit if shutdown has been requested */
				if (builderThread.isShutdownRequested())
				{
					return false;
				}
	
				doSecondValidationPass(localeTopics, usedIdAttributes, fixedUrlsSuccess, locale);
	
				/* early exit if shutdown has been requested */
				if (builderThread.isShutdownRequested())
				{
					return false;
				}
	
				/* build up the toc format */
				final TocFormatBranch<T> toc = doFormattedTocPass();
	
				/* early exit if shutdown has been requested */
				if (builderThread.isShutdownRequested())
				{
					return false;
				}
	
				/* add the topic to the toc */
				doPopulateTocPass(toc);
	
				/* early exit if shutdown has been requested */
				if (builderThread.isShutdownRequested())
				{
					return false;
				}
	
				/* make sure duplicate topics have unique ids in their xml */
				toc.setUniqueIds();
	
				/* early exit if shutdown has been requested */
				if (builderThread.isShutdownRequested())
				{
					return false;
				}
	
				doProcessingPass(toc, buildDocbookMessage.getDocbookOptions(), searchTagsUrl, DocbookBuilder.BUILD_NAME, fixedUrlsSuccess, locale);
	
				/* early exit if shutdown has been requested */
				if (builderThread.isShutdownRequested())
				{
					return false;
				}
				
				localeGroupedTocs.put(locale, toc);
			}

			final byte[] zip = doBuildZipPass(localeGroupedTocs, buildDocbookMessage.getDocbookOptions(), fixedUrlsSuccess);

			/* early exit if shutdown has been requested */
			if (builderThread.isShutdownRequested())
			{
				return false;
			}

			emailZIP(zip, buildDocbookMessage.getDocbookOptions());

			NotificationUtilities.dumpMessageToStdOut("Processing Complete");
		}
		else
		{
			NotificationUtilities.dumpMessageToStdOut("Error Getting Topic Collection");
		}
		
		return true;
	}

	/**
	 * Sets the XML of the topic returned by the original REST query.
	 * 
	 * @param topicProcessingData
	 *            The TopicProcessingData that is associated with the Topic that
	 *            has an error
	 */
	private void setTopicXMLForError(final T topic, final String template, final boolean fixedUrlsSuccess)
	{
		/* replace any markers with the topic sepecific text */
		final String fixedTemplate = template.replaceAll(DocbookBuilderConstants.TOPIC_ERROR_LINK_MARKER, topic.getErrorXRefID());

		final Document doc = XMLUtilities.convertStringToDocument(fixedTemplate);
		topicXMLDocuments.put(topic, doc);
		DocbookUtils.setSectionTitle(topic.getTitle(), doc);
		processTopicID(topic, fixedUrlsSuccess);
	}

	/**
	 * Sets the XML of the topic in the toc
	 * 
	 * @param topicProcessingData
	 *            The TopicProcessingData that is associated with the Topic that
	 *            has an error
	 */
	private void setTopicXMLForError(final TocFormatBranch<T> toc, final T topic, final String template, final boolean fixedUrlsSuccess)
	{
		/* replace any markers with the topic sepecific text */
		final String fixedTemplate = template.replaceAll(DocbookBuilderConstants.TOPIC_ERROR_LINK_MARKER, topic.getErrorXRefID());

		final Document doc = XMLUtilities.convertStringToDocument(fixedTemplate);
		toc.setXMLDocument(topic, doc);
		DocbookUtils.setSectionTitle(topic.getTitle(), doc);
		processTopicID(toc, topic, fixedUrlsSuccess);
	}

	/**
	 * Sets the topic xref id to the topic database id
	 */
	private void processTopicID(final T topic, final boolean fixedUrlsSuccess)
	{
		if (fixedUrlsSuccess)
		{
			topicXMLDocuments.get(topic).getDocumentElement().setAttribute("id", topic.getXrefPropertyOrId(CommonConstants.FIXED_URL_PROP_TAG_ID));
		}
		else
		{
			topicXMLDocuments.get(topic).getDocumentElement().setAttribute("id", topic.getXRefID());
		}
	}

	/**
	 * Sets the topic xref id to the topic database id, including the toc branch
	 * id that uniquely identifies the topic in the document
	 */
	private void processTopicID(final TocFormatBranch<T> toc, final T topic, final boolean fixedUrlsSuccess)
	{
		final TocFormatBranch<T> branch = toc.getBranchThatContainsTopic(topic);

		if (fixedUrlsSuccess)
		{
			toc.getXMLDocument(topic).getDocumentElement().setAttribute("id", topic.getXrefPropertyOrId(CommonConstants.FIXED_URL_PROP_TAG_ID) + branch.getTOCBranchID());
		}
		else
		{
			toc.getXMLDocument(topic).getDocumentElement().setAttribute("id", topic.getXRefID() + branch.getTOCBranchID());
		}
	}

	private void procesImageLocations(final T topic)
	{
		/*
		 * Images have to be in the image folder in Publican. Here we loop
		 * through all the imagedata elements and fix up any reference to an
		 * image that is not in the images folder.
		 */
		final List<Node> images = this.getImages(topicXMLDocuments.get(topic));

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

				imageLocations.add(new TopicImageData<T>(topic, fileRefAttribute.getNodeValue()));
			}
		}
	}

	/**
	 * We loop over the topics that were returned by the filter and perform some
	 * validations tasks.
	 */
	private Map<T, ArrayList<String>> doFirstValidationPass(final BaseRestCollectionV1<T> topics, final boolean fixedUrlsSuccess, final String locale)
	{
		NotificationUtilities.dumpMessageToStdOut("Doing " + locale + " First Validation Pass");

		final Map<T, ArrayList<String>> retValue = new HashMap<T, ArrayList<String>>();

		for (final T topic : topics.getItems())
		{
			if (builderThread.isShutdownRequested())
			{
				NotificationUtilities.dumpMessageToStdOut("Shutdown detected in DocbookBuildingThread.doFirstValidationPass(). Exiting Loop.");
				return retValue;
			}

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
					DocbookUtils.setSectionTitle(topic.getTitle(), document);
					topicXMLDocuments.put(topic, document);
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
			 * Extract the id attributes used in this topic. We'll use this data
			 * in the second pass to make sure that individual topics don't
			 * repeat id attributes.
			 */
			collectIdAttributes(topic, topicXMLDocuments.get(topic), retValue);

			if (xmlValid)
			{
				/* set the section id */
				processTopicID(topic, fixedUrlsSuccess);

				procesImageLocations(topic);
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
	private void collectIdAttributes(final T topic, final Node node, final Map<T, ArrayList<String>> usedIdAttributes)
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
	private void doSecondValidationPass(final BaseRestCollectionV1<T> topics, final Map<T, ArrayList<String>> usedIdAttributes, final boolean fixedUrlsSuccess, final String locale)
	{
		NotificationUtilities.dumpMessageToStdOut("Doing " + locale + " Second Validation Pass");

		for (final T topic1 : usedIdAttributes.keySet())
		{
			if (builderThread.isShutdownRequested())
			{
				NotificationUtilities.dumpMessageToStdOut("Shutdown detected in DocbookBuildingThread.doSecondValidationPass(). Exiting Loop.");
				return;
			}

			validateIdAttributes(topic1, usedIdAttributes, fixedUrlsSuccess);
		}

	}

	private boolean validateIdAttributes(final T topic, final Map<T, ArrayList<String>> usedIdAttributes, final boolean fixedUrlsSuccess)
	{
		boolean retValue = true;

		if (usedIdAttributes.containsKey(topic))
		{
			final ArrayList<String> ids1 = usedIdAttributes.get(topic);

			for (final T topic2 : usedIdAttributes.keySet())
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
	 * This function add some common boilerplate to the topics, and replaces the
	 * injection points with live data and links
	 * 
	 * @param toc
	 *            The table of contents, with their associated topics, to
	 *            process
	 * @throws InternalProcessingException
	 * @throws InvalidParameterException
	 */
	private void doProcessingPass(final TocFormatBranch<T> toc, final DocbookBuildingOptions docbookBuildingOptions, final String searchTagsUrl, final String buildName, 
			final boolean fixedUrlsSuccess, final String locale) throws InvalidParameterException, InternalProcessingException
	{
		NotificationUtilities.dumpMessageToStdOut("Doing " + locale + " Processing Pass");

		final int showPercent = 5;
		final float total = toc.getTopicCount();
		float current = 0;
		int lastPercent = 0;

		for (final T topic : toc.getAllTopics())
		{
			if (builderThread.isShutdownRequested())
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

			final Document doc = toc.getXMLDocument(topic);

			if (doc != null)
			{
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
				final XMLPreProcessor<T> xmlPreProcessor = new XMLPreProcessor<T>();
				
				final List<Integer> customInjectionErrors = xmlPreProcessor.processInjections(toc, topic, customInjectionIds, doc, docbookBuildingOptions, fixedUrlsSuccess);
				final List<Integer> genericInjectionErrors = xmlPreProcessor.processGenericInjections(toc, topic, doc, customInjectionIds, topicTypeTagDetails, docbookBuildingOptions, fixedUrlsSuccess);
				final List<Integer> topicContentFragmentsErrors = xmlPreProcessor.processTopicContentFragments(topic, doc, docbookBuildingOptions);
				final List<Integer> topicTitleFragmentsErrors = xmlPreProcessor.processTopicTitleFragments(topic, doc, docbookBuildingOptions);

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
					final String message = "Topic has related Topic(s) " + CollectionUtilities.toSeperatedString(CollectionUtilities.toAbsIntegerList(genericInjectionErrors)) + " that were not included in the filter used to build this book.";
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
					final String message = "Topic has injected content from Topic(s) " + CollectionUtilities.toSeperatedString(topicContentFragmentsErrors) + " that were not related.";
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
					final String message = "Topic has injected a title from Topic(s) " + CollectionUtilities.toSeperatedString(topicTitleFragmentsErrors) + " that were not related.";
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
				
				/* check for dummy topics */
				if (topic instanceof TranslatedTopicV1)
				{
					/* Add the warning for the topics relationships that haven't been translated */
					if (topic.getOutgoingRelationships() != null && topic.getOutgoingRelationships().getItems() != null)
					{
						for (T relatedTopic: topic.getOutgoingRelationships().getItems())
						{
							final TranslatedTopicV1 relatedTranslatedTopic = (TranslatedTopicV1) relatedTopic;
							
							/* Only show errors for topics that weren't included in the injections */
							if (!customInjectionErrors.contains(relatedTranslatedTopic.getTopicId()) && !genericInjectionErrors.contains(relatedTopic.getId()))
							{
								if ((!toc.isInToc(relatedTranslatedTopic.getTopicId()) && (docbookBuildingOptions != null && !docbookBuildingOptions.getIgnoreMissingCustomInjections())) || toc.isInToc(relatedTranslatedTopic.getTopicId()))
								{
									if (relatedTopic.isDummyTopic() && relatedTranslatedTopic.hasBeenPushedForTranslation())
										errorDatabase.addWarning(topic, "Topic ID " + relatedTranslatedTopic.getTopicId() + ", Revision " + relatedTranslatedTopic.getTopicRevision() + ", Title \"" + relatedTopic.getTitle() + "\" is an untranslated topic.");
									else if (relatedTopic.isDummyTopic())
										errorDatabase.addWarning(topic, "Topic ID " + relatedTranslatedTopic.getTopicId() + ", Revision " + relatedTranslatedTopic.getTopicRevision() + ", Title \"" + relatedTopic.getTitle() + "\" hasn't been pushed for translation.");
								}
							}
						}
					}
					
					/* Check the topic itself isn't a dummy topic */
					if (topic.isDummyTopic() && ((TranslatedTopicV1) topic).hasBeenPushedForTranslation())
						errorDatabase.addWarning(topic, "This topic is an untranslated topic.");
					else if (topic.isDummyTopic())
						errorDatabase.addWarning(topic, "This topic hasn't been pushed for translation.");
				}

				if (!valid)
				{
					setTopicXMLForError(topic, ERROR_XML_TEMPLATE, fixedUrlsSuccess);
				}
				else
				{
					/* add the standard boilerplate xml */
					xmlPreProcessor.processTopicAdditionalInfo(topic, doc, docbookBuildingOptions, searchTagsUrl, buildName, buildDate);
					
					/*
					 * make sure the XML is valid docbook after the standard
					 * processing has been done
					 */
					final XMLValidator xmlValidator = new XMLValidator();
					if (xmlValidator.validateTopicXML(doc, rocbookDTD.getName(), rocbookDTD.getValue()) == null)
					{
						System.out.println(XMLUtilities.convertNodeToString(doc, verbatimElements, inlineElements, contentsInlineElements, true));
						NotificationUtilities.dumpMessageToStdOut("\tTopic " + topic.getId() + " has invalid Docbook XML");

						String xmlString = null;
						if (xmlFormattingPropertiesSet)
							xmlString = XMLUtilities.convertNodeToString(doc, verbatimElements, inlineElements, contentsInlineElements, true);
						else
							xmlString = XMLUtilities.convertNodeToString(doc, true);

						final String xmlStringInCDATA = XMLUtilities.wrapStringInCDATA(xmlString);
						errorDatabase.addError(topic, "Topic has invalid Docbook XML. The error is <emphasis>" + xmlValidator.getErrorText() + "</emphasis>. The processed XML is <programlisting>" + xmlStringInCDATA + "</programlisting>");
						setTopicXMLForError(toc, topic, ERROR_XML_TEMPLATE, fixedUrlsSuccess);
					}
				}
			}
		}
	}

	private void doPopulateTocPass(final TocFormatBranch<T> toc)
	{
		/*
		 * If this branch has no parent, then it is the top level and we don't
		 * add topics to it
		 */
		if (toc.getParent() != null && toc.getDisplayTags().hasRequirements())
		{
			final TagRequirements requirements = new TagRequirements();
			/* get the tags required to be a child of the parent toc levels */
			toc.getTagsWithParent(requirements);
			/* and add the tags required to be displayed at this level */
			requirements.merge(toc.getDisplayTags());

			for (final T topic : topicXMLDocuments.keySet())
			{
				boolean doesMatch = true;
				for (final TagV1 andTag : requirements.getMatchAllOf())
				{
					if (!topic.isTaggedWith(andTag.getId()))
					{
						doesMatch = false;
						break;
					}
				}

				if (doesMatch && requirements.getMatchOneOf().size() != 0)
				{
					for (final ArrayList<TagV1> orBlock : requirements.getMatchOneOf())
					{
						if (orBlock.size() != 0)
						{
							boolean matchesOrBlock = false;
							for (final TagV1 orTag : orBlock)
							{
								if (topic.isTaggedWith(orTag.getId()))
								{
									matchesOrBlock = true;
									break;
								}
							}

							if (!matchesOrBlock)
							{
								doesMatch = false;
								break;
							}
						}
					}
				}

				if (doesMatch)
				{
					toc.getTopics().put(topic.clone(false), (Document) topicXMLDocuments.get(topic).cloneNode(true));
				}
			}
		}

		for (final TocFormatBranch<T> child : toc.getChildren())
			doPopulateTocPass(child);
	}

	/**
	 * This method will return a collection of TocFormatBranch objects that
	 * represent the top level of the TOC. Each TocFormatBranch then has a
	 * hierarchy of child TOC elements.
	 * 
	 * Not that the TocFormatBranch elements returned by this method don't list
	 * the topics that fall under each level of the TOC, just the tags that a
	 * topic needs to have in order to appear in each level of the TOC.
	 * 
	 * @return a collection of TocFormatBranch objects that represent the top
	 *         level of the TOC.
	 */
	private TocFormatBranch<T> doFormattedTocPass()
	{
		try
		{
			/* The return value is a collection of the top level TOC branches */
			final TocFormatBranch<T> retValue = new TocFormatBranch<T>();

			/* Create an expand block for the tag parent tags */
			final ExpandDataTrunk parentTags = new ExpandDataTrunk(new ExpandDataDetails("parenttags"));
			parentTags.setBranches(CollectionUtilities.toArrayList(new ExpandDataTrunk(new ExpandDataDetails("categories"))));

			final ExpandDataTrunk childTags = new ExpandDataTrunk(new ExpandDataDetails("childtags"));

			final ExpandDataTrunk expandTags = new ExpandDataTrunk(new ExpandDataDetails("tags"));
			expandTags.setBranches(CollectionUtilities.toArrayList(parentTags, childTags));

			final ExpandDataTrunk expand = new ExpandDataTrunk();
			expand.setBranches(CollectionUtilities.toArrayList(expandTags));

			final String expandString = mapper.writeValueAsString(expand);
			final String expandEncodedString = URLEncoder.encode(expandString, "UTF-8");

			/* Get the technology and common names categories */
			final CategoryV1 technologyCategroy = restClient.getJSONCategory(DocbookBuilderConstants.TECHNOLOGY_CATEGORY_ID, expandEncodedString);
			final CategoryV1 commonNamesCategory = restClient.getJSONCategory(DocbookBuilderConstants.COMMON_NAME_CATEGORY_ID, expandEncodedString);

			/*
			 * The top level TOC elements are made up of the technology and
			 * common name tags that are not encompassed by another tag. So here
			 * we get the tags out of the tech and common names categories, and
			 * pull outthose that are not encompassed.
			 */
			final List<TagV1> topLevelTags = new ArrayList<TagV1>();
			for (final CategoryV1 category : new CategoryV1[]
			{ technologyCategroy, commonNamesCategory })
			{
				for (final TagV1 tag : category.getTags().getItems())
				{
					boolean isEmcompassed = false;
					for (final TagV1 parentTag : tag.getParentTags().getItems())
					{
						for (final CategoryV1 parentTagCategory : parentTag.getCategories().getItems())
						{
							if (parentTagCategory.getId() == DocbookBuilderConstants.TECHNOLOGY_CATEGORY_ID || parentTagCategory.getId() == DocbookBuilderConstants.COMMON_NAME_CATEGORY_ID)
							{
								isEmcompassed = true;
								break;
							}
						}

						if (isEmcompassed)
							break;
					}

					/*
					 * This tag is not encompassed by any other tech or common
					 * name tags, so it is a candidate to appear on the top
					 * level of the TOC
					 */
					if (!isEmcompassed)
					{
						topLevelTags.add(tag);
					}
				}
			}

			/* Create an expand block for the tag parent tags */
			final ExpandDataTrunk concernCategoryExpand = new ExpandDataTrunk();
			final ExpandDataTrunk concernCategoryExpandTags = new ExpandDataTrunk(new ExpandDataDetails("tags"));
			concernCategoryExpand.setBranches(CollectionUtilities.toArrayList(concernCategoryExpandTags));

			final String concernCategoryExpandString = mapper.writeValueAsString(concernCategoryExpand);
			final String concernCategoryExpandStringEncoded = URLEncoder.encode(concernCategoryExpandString, "UTF-8");

			/* Get the technology and common names categories */
			final CategoryV1 concernCategory = restClient.getJSONCategory(DocbookBuilderConstants.CONCERN_CATEGORY_ID, concernCategoryExpandStringEncoded);

			/* Get the task, reference, concern and */
			final TagV1 taskTag = restClient.getJSONTag(DocbookBuilderConstants.TASK_TAG_ID, "");
			final TagV1 referenceTag = restClient.getJSONTag(DocbookBuilderConstants.REFERENCE_TAG_ID, "");
			final TagV1 conceptTag = restClient.getJSONTag(DocbookBuilderConstants.CONCEPT_TAG_ID, "");
			final TagV1 conceptualOverviewTag = restClient.getJSONTag(DocbookBuilderConstants.CONCEPTUALOVERVIEW_TAG_ID, "");

			/* add TocFormatBranch objects for each top level tag */
			for (final TagV1 tag : topLevelTags)
			{
				/*
				 * Create the top level tag. This level is represented by the
				 * tags that are not encompased, and includes any topic that has
				 * that tag or any tag that is encompassed by this tag.
				 */
				final TagRequirements topLevelBranchTags = new TagRequirements((TagV1) null, new ArrayList<TagV1>()
				{
					private static final long serialVersionUID = 7499166852563779981L;

					{
						add(tag);
						addAll(tag.getChildTags().getItems());
					}
				});

				final TocFormatBranch<T> topLevelTag = new TocFormatBranch<T>(tag, retValue, topLevelBranchTags, null);

				for (final TagV1 concernTag : concernCategory.getTags().getItems())
				{
					/*
					 * the second level of the toc are the concerns, which will
					 * display the tasks and conceptual overviews beneath them
					 */
					final TocFormatBranch<T> concernLevelTocBranch = new TocFormatBranch<T>(concernTag, topLevelTag, new TagRequirements(concernTag, (TagV1) null), new TagRequirements((TagV1) null, CollectionUtilities.toArrayList(conceptualOverviewTag, taskTag)));

					/*
					 * the third levels of the TOC are the concept and reference
					 * topics
					 */
					new TocFormatBranch<T>(conceptTag, concernLevelTocBranch, null, new TagRequirements(conceptTag, (TagV1) null));
					new TocFormatBranch<T>(referenceTag, concernLevelTocBranch, null, new TagRequirements(referenceTag, (TagV1) null));
				}
			}

			return retValue;
		}
		catch (final Exception ex)
		{
			ExceptionUtilities.handleException(ex);
			return null;
		}
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

				for (final T topic : topicXMLDocuments.keySet())
				{
					if (builderThread.isShutdownRequested())
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
						for (final T topic : topicXMLDocuments.keySet())
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
							for (final T relatedTopic : topic.getOutgoingRelationships().getItems())
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

	private byte[] doBuildZipPass(final Map<String, TocFormatBranch<T>> groupedLocaleTocs, final DocbookBuildingOptions docbookBuildingOptions, final boolean fixedUrlsSuccess) throws InvalidParameterException, InternalProcessingException
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

		// final String startPage =
		// restClient.getJSONStringConstant(DocbookBuilderConstants.START_PAGE_ID,
		// "").getValue();
		final String jbossSvg = restClient.getJSONStringConstant(DocbookBuilderConstants.JBOSS_SVG_ID, "").getValue();

		final String yahooDomEventJs = restClient.getJSONStringConstant(DocbookBuilderConstants.YAHOO_DOM_EVENT_JS_ID, "").getValue();
		final String treeviewMinJs = restClient.getJSONStringConstant(DocbookBuilderConstants.TREEVIEW_MIN_JS_ID, "").getValue();
		final String treeviewCss = restClient.getJSONStringConstant(DocbookBuilderConstants.TREEVIEW_CSS_ID, "").getValue();
		final String jqueryMinJs = restClient.getJSONStringConstant(DocbookBuilderConstants.JQUERY_MIN_JS_ID, "").getValue();

		final byte[] treeviewSpriteGif = restClient.getJSONBlobConstant(DocbookBuilderConstants.TREEVIEW_SPRITE_GIF_ID, "").getValue();
		final byte[] treeviewLoadingGif = restClient.getJSONBlobConstant(DocbookBuilderConstants.TREEVIEW_LOADING_GIF_ID, "").getValue();
		final byte[] check1Gif = restClient.getJSONBlobConstant(DocbookBuilderConstants.CHECK1_GIF_ID, "").getValue();
		final byte[] check2Gif = restClient.getJSONBlobConstant(DocbookBuilderConstants.CHECK2_GIF_ID, "").getValue();

		/* build up the files that will make up the zip file */
		final HashMap<String, byte[]> files = new HashMap<String, byte[]>();

		/* add the images to the zip file */
		addImagesToBook(files);
		
		/*
		 * the narrative style will not include a TOC or Eclipse plugin, and
		 * includes a different publican.cfg file
		 */
		String publicanCfgFixed = publicanCfg;

		/* add the files that make up the Eclipse help package */
		NotificationUtilities.dumpMessageToStdOut("\tAdding standard files to Publican ZIP file");

		/* fix the Publican CFG file */
		if (publicanCfgFixed != null)
		{
			
			if (docbookBuildingOptions.getPublicanShowRemarks())
			{
				publicanCfgFixed += "\nshow_remarks: 1";
			}

			publicanCfgFixed += "\ncvs_pkg: " + docbookBuildingOptions.getCvsPkgOption();
		}
		
		for (final String locale: groupedLocaleTocs.keySet())
		{
			String publicanCfgLocaleFixed = publicanCfgFixed.replaceFirst("xml_lang\\: .*(\\r\\n|\\n)", "xml_lang: " + locale + "\n");
			
			// add the publican.cfg file
			if (groupedLocaleTocs.size() > 1)
				files.put("Book/publican-" + locale + ".cfg", StringUtilities.getStringBytes(StringUtilities.convertToLinuxLineEndings(publicanCfgLocaleFixed)));
			else
				files.put("Book/publican.cfg", StringUtilities.getStringBytes(StringUtilities.convertToLinuxLineEndings(publicanCfgLocaleFixed)));
			
			final TocFormatBranch<T> toc = groupedLocaleTocs.get(locale);
	
			// replace the date marker in the Book.XML file
			files.put("Book/" + locale + "/Book_Info.xml", bookInfoXml.replace(DocbookBuilderConstants.DATE_YYMMDD_TEXT_MARKER, new StringBuilder(new SimpleDateFormat("yyMMdd").format(new Date()))).getBytes());
	
			files.put("Book/" + locale + "/Author_Group.xml", StringUtilities.getStringBytes(authorGroupXml));
			files.put("Book/" + locale + "/Book.ent", StringUtilities.getStringBytes(bookEnt));
	
			// these files are used by the YUI treeview
			files.put("Book/" + locale + "/files/yahoo-dom-event.js", StringUtilities.getStringBytes(yahooDomEventJs));
			files.put("Book/" + locale + "/files/treeview-min.js", StringUtilities.getStringBytes(treeviewMinJs));
			files.put("Book/" + locale + "/files/treeview.css", StringUtilities.getStringBytes(treeviewCss));
			files.put("Book/" + locale + "/files/jquery.min.js", StringUtilities.getStringBytes(jqueryMinJs));
	
			// these are the images that are referenced in the treeview.css file
			files.put("Book/" + locale + "/files/treeview-sprite.gif", treeviewSpriteGif);
			files.put("Book/" + locale + "/files/treeview-loading.gif", treeviewLoadingGif);
			files.put("Book/" + locale + "/files/check1.gif", check1Gif);
			files.put("Book/" + locale + "/files/check2.gif", check2Gif);
	
			files.put("Book/" + locale + "/images/icon.svg", StringUtilities.getStringBytes(iconSvg));
			files.put("Book/" + locale + "/images/jboss.svg", StringUtilities.getStringBytes(jbossSvg));
	
			/*
			 * build the middle of the Book.xml file, where we include references to
			 * the topic type pages that were built above
			 */
			String bookXmlXiIncludes = "";
	
			/* add an xml file for each topic */
			NotificationUtilities.dumpMessageToStdOut("\tAdding " + locale + " Topics files to Publican ZIP file");
			toc.addTopicsToZIPFile(files, fixedUrlsSuccess);
	
			/* build the topics file, which defines the toc */
			NotificationUtilities.dumpMessageToStdOut("\tBuilding " + locale + " Topics.xml");
			bookXmlXiIncludes += toc.buildDocbook(fixedUrlsSuccess);
	
			/* build the chapter containing the compiler errors */
			if (!docbookBuildingOptions.getSuppressErrorsPage())
			{
				NotificationUtilities.dumpMessageToStdOut("\tBuilding " + locale + " Error Chapter");
				final String compilerOutput = buildErrorChapter(locale);
	
				files.put("Book/" + locale + "/Errors.xml", StringUtilities.getStringBytes(StringUtilities.cleanTextForXML(compilerOutput == null ? "" : compilerOutput)));
				bookXmlXiIncludes += "	<xi:include href=\"Errors.xml\" xmlns:xi=\"http://www.w3.org/2001/XInclude\" />\n";
			}
	
			// replace the marker in the book.xml template
			final String thisBookXml = bookXml.replace(DocbookBuilderConstants.BOOK_XML_XI_INCLUDES_MARKER, bookXmlXiIncludes);
	
			files.put("Book/" + locale + "/Book.xml", StringUtilities.getStringBytes(thisBookXml));
			files.put("Book/" + locale + "/Revision_History.xml", StringUtilities.getStringBytes(revisionHistoryXml));
		}

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
	
	private String buildErrorChapter(final String locale)
	{
		String errorItemizedLists = "";

		if (errorDatabase.hasItems(locale))
		{
			for (final TopicErrorData<T> topicErrorData : errorDatabase.getErrors(locale))
			{
				final T topic = topicErrorData.getTopic();

				final List<String> topicErrorItems = new ArrayList<String>();

				final String tags = topic.getCommaSeparatedTagList();
				final String url = topic.getSkynetURL();

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
					final String title;
					if (topic instanceof TranslatedTopicV1)
					{
						final TranslatedTopicV1 translatedTopic = (TranslatedTopicV1) topic;
						title = "Topic ID " + translatedTopic.getTopicId() + ", Revision " + translatedTopic.getTopicRevision();
					}
					else
					{
						title = "Topic ID " + topic.getId();
					}
					final String id = topic.getErrorXRefID();

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

		for (final TopicImageData<T> imageLocation : this.imageLocations)
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
