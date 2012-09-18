package org.jboss.pressgang.ccms.services.spellchecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.core.PathSegment;
import net.htmlparser.jericho.Source;

import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.pressgang.ccms.docbook.processing.XMLPreProcessor;
import org.jboss.pressgang.ccms.rest.v1.client.PressGangCCMSProxyFactoryV1;
import org.jboss.pressgang.ccms.rest.v1.collections.RESTTagCollectionV1;
import org.jboss.pressgang.ccms.rest.v1.collections.RESTTopicCollectionV1;
import org.jboss.pressgang.ccms.rest.v1.collections.join.RESTAssignedPropertyTagCollectionV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTStringConstantV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTagV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTopicV1;
import org.jboss.pressgang.ccms.rest.v1.entities.join.RESTAssignedPropertyTagV1;
import org.jboss.pressgang.ccms.rest.v1.expansion.ExpandDataDetails;
import org.jboss.pressgang.ccms.rest.v1.expansion.ExpandDataTrunk;
import org.jboss.pressgang.ccms.rest.v1.jaxrsinterfaces.RESTInterfaceV1;
import org.jboss.pressgang.ccms.services.spellchecker.data.SpellingErrorData;
import org.jboss.pressgang.ccms.utils.common.CollectionUtilities;
import org.jboss.pressgang.ccms.utils.common.ExceptionUtilities;
import org.jboss.pressgang.ccms.utils.common.XMLUtilities;
import org.jboss.pressgang.ccms.utils.services.ServiceStarter;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.specimpl.PathSegmentImpl;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;


import dk.dren.hunspell.Hunspell;
import dk.dren.hunspell.Hunspell.Dictionary;

public class Main
{
	/**
	 * The system property that defines the query against which to run the
	 * content checks
	 */
	private static final String SPELL_CHECK_QUERY_SYSTEM_PROPERTY = "topicIndex.spellCheckQuery";
	/** The string constant that defines the Docbook elements to ignore */
	private static final Integer DOCBOOK_IGNORE_ELEMENTS_STRING_CONSTANT_ID = 30;
	/** The property tag that holds the gammar errors */
	private static final Integer GRAMMAR_ERRORS_PROPERTY_TAG_ID = 27;
	/** The property tag that holds the spelling errors */
	private static final Integer SPELLING_ERRORS_PROPERTY_TAG_ID = 26;
	/** The tag that indicates that a topic has a spelling error */
	private static final Integer SPELLING_ERRORS_TAG_ID = 456;
	/** The tag that indicates that a topic has a grammar error */
	private static final Integer GRAMMAR_ERRORS_TAG_ID = 457;
	/** http://en.wikipedia.org/wiki/Regular_expression#POSIX_character_classes **/
	private static final String PUNCTUATION_CHARACTERS_RE = "[\\]\\[!\"#$%&()*+,./:;<=>?@\\^`{|}~\\s]";
	/** A Regular expression to identify hyphenated words **/
	private static final Pattern HYPHENATED_WORD_RE = Pattern.compile("(?<First>[^-]+)-(?<Second>[^-]+)");
	/** A regex that matches an xref */
	private static final String XREF_RE = "<xref*.?/\\s*>";
	/** A regex that matches the opening tag of an entry */
	private static final String ENTRY_RE = "<entry>";
	/** A regext that matches a closing tag of an entry */
	private static final String ENTRY_CLOSE_RE = "</entry>";
	/**
	 * A string that is used to replace ignored elements, to indicate that there
	 * was a break between words before the element was removed
	 */
	private static final String ELEMENT_PUNCTUATION_MARKER = "#";
	
	/** A regular expression that matches an Inject Content Fragment */
    private static final String INJECT_CONTENT_FRAGMENT_RE =
    /* start xml comment and 'Inject:' surrounded by optional white space */
    "\\s*InjectText:\\s*" +
    /* one digit block */
    "(?<TopicIDs>(\\d+))" +
    /* xml comment end */
    "\\s*";

    /** A regular expression that matches an Inject Content Fragment */
    private static final String INJECT_TITLE_FRAGMENT_RE =
    /* start xml comment and 'Inject:' surrounded by optional white space */
    "\\s*InjectTitle:\\s*" +
    /* one digit block */
    "(?<TopicIDS>(\\d+))" +
    /* xml comment end */
    "\\s*";
	
	/** The Jackson mapper that converts POJOs to JSON */
	private final ObjectMapper mapper = new ObjectMapper();

	/** Entry point */
	public static void main(final String[] args)
	{
		System.out.println("-> Main.main()");

		final ServiceStarter starter = new ServiceStarter();
		if (starter.isValid())
		{
			RegisterBuiltin.register(ResteasyProviderFactory.getInstance());
			new Main(starter);
		}

		System.out.println("<- Main.main()");
	}

	public Main(final ServiceStarter serviceStarter)
	{
		System.out.println("-> Main.Main()");

		final String query = System.getProperty(SPELL_CHECK_QUERY_SYSTEM_PROPERTY);

		try
		{
			System.out.println("Main.Main() - Getting topics from query " + query);

			/* Get the topics */
			final RESTInterfaceV1 restClient = PressGangCCMSProxyFactoryV1.create(serviceStarter.getSkynetServer()).getRESTClient();

			final PathSegment pathSegment = new PathSegmentImpl(query, false);

			final ExpandDataTrunk expand = new ExpandDataTrunk();

			final ExpandDataTrunk topicsExpand = new ExpandDataTrunk(new ExpandDataDetails("topics"));
			expand.setBranches(CollectionUtilities.toArrayList(topicsExpand));

			final ExpandDataTrunk tagsExpand = new ExpandDataTrunk(new ExpandDataDetails("tags"));
			final ExpandDataTrunk propertiesExpand = new ExpandDataTrunk(new ExpandDataDetails("properties"));
			topicsExpand.setBranches(CollectionUtilities.toArrayList(tagsExpand, propertiesExpand));

			final String expandString = mapper.writeValueAsString(expand);
			//final String expandEncodedString = URLEncoder.encode(expandString, "UTF-8");

			final RESTTopicCollectionV1 topics = restClient.getJSONTopicsWithQuery(pathSegment, expandString);

			/* Get the tags to ignore */
			final RESTStringConstantV1 ignoreTags = restClient.getJSONStringConstant(DOCBOOK_IGNORE_ELEMENTS_STRING_CONSTANT_ID, "");
			final List<String> ignoreTagsList = CollectionUtilities.toArrayList(ignoreTags.getValue().split("\r\n"));

			/* Create the dictionaries */
			final Dictionary standardDict = Hunspell.getInstance().getDictionary("target/classes/dict/en_US/en_US");
			final Dictionary customDict = Hunspell.getInstance().getDictionary("target/classes/customdict/en_US/en_US");

			/* Process the topics */
			for (final RESTTopicV1 topic : topics.returnItems())
			{
				processDocument(restClient, topic, ignoreTagsList, standardDict, customDict);
			}
		}
		catch (final Exception ex)
		{
			ExceptionUtilities.handleException(ex);
		}

		System.out.println("<- Main.Main()");
	}

	/**
	 * Process the topic for spelling and grammar issues
	 * 
	 * @param topic
	 *            The topic to process
	 * @param ignoreElements
	 *            The XML elements to ignore
	 * @param standardDict
	 *            The standard dictionary
	 * @param customDict
	 *            The custom dictionary
	 */
	private void processDocument(final RESTInterfaceV1 restClient, final RESTTopicV1 topic, final List<String> ignoreElements, final Dictionary standardDict, final Dictionary customDict)
	{
		/* Run the content checks */
		final List<SpellingErrorData> spellingErrors = checkSpelling(topic, ignoreElements, standardDict, customDict);
		final List<String> doubleWords = checkGrammar(topic, ignoreElements);

		/*
		 * The topic will be updated to remove the tags and property tags
		 * regardless of the results of the content checks. If errors are found,
		 * the property tags will be added back with the new details.
		 */
		boolean topicIsUpdated = false;

		final RESTTopicV1 updateTopic = new RESTTopicV1();
		updateTopic.setId(topic.getId());
		updateTopic.explicitSetProperties(new RESTAssignedPropertyTagCollectionV1());
		updateTopic.explicitSetTags(new RESTTagCollectionV1());

		/* Add or remove the spelling tags as needed */
		boolean foundSpellingTag = false;
		for (final RESTTagV1 tag : topic.getTags().returnItems())
		{
			if (tag.getId().equals(SPELLING_ERRORS_TAG_ID))
			{
				foundSpellingTag = true;
				break;
			}
		}

		if (spellingErrors.size() == 0 && foundSpellingTag)
		{
			final RESTTagV1 removeSpellingErrorTag = new RESTTagV1();
			removeSpellingErrorTag.setId(SPELLING_ERRORS_TAG_ID);
			updateTopic.getTags().addRemoveItem(removeSpellingErrorTag);
			topicIsUpdated = true;
		}
		else if (spellingErrors.size() != 0 && !foundSpellingTag)
		{
			final RESTTagV1 removeSpellingErrorTag = new RESTTagV1();
			removeSpellingErrorTag.setId(SPELLING_ERRORS_TAG_ID);
			updateTopic.getTags().addNewItem(removeSpellingErrorTag);
			topicIsUpdated = true;
		}

		/* Add or remove the grammar tags as needed */
		boolean foundGrammarTag = false;
		for (final RESTTagV1 tag : topic.getTags().returnItems())
		{
			if (tag.getId().equals(GRAMMAR_ERRORS_TAG_ID))
			{
				foundGrammarTag = true;
				break;
			}
		}

		if (doubleWords.size() == 0 && foundGrammarTag)
		{
			final RESTTagV1 removeGrammarErrorTag = new RESTTagV1();
			removeGrammarErrorTag.setId(GRAMMAR_ERRORS_TAG_ID);
			updateTopic.getTags().addRemoveItem(removeGrammarErrorTag);
			topicIsUpdated = true;
		}
		else if (doubleWords.size() != 0 && !foundGrammarTag)
		{
			final RESTTagV1 grammarErrorTag = new RESTTagV1();
			grammarErrorTag.setId(GRAMMAR_ERRORS_TAG_ID);
			updateTopic.getTags().addNewItem(grammarErrorTag);
			topicIsUpdated = true;
		}
		
		/* remove any old grammar error details if none exist */
		if (doubleWords.size() == 0)
		{
			for (final RESTAssignedPropertyTagV1 tag : topic.getProperties().returnItems())
			{
				if (tag.getId().equals(GRAMMAR_ERRORS_PROPERTY_TAG_ID))
				{
					final RESTAssignedPropertyTagV1 removeGrammarErrorPropertyTag = new RESTAssignedPropertyTagV1();
					removeGrammarErrorPropertyTag.setId(GRAMMAR_ERRORS_PROPERTY_TAG_ID);
					removeGrammarErrorPropertyTag.setValue(tag.getValue());
					updateTopic.getProperties().addRemoveItem(removeGrammarErrorPropertyTag);
					topicIsUpdated = true;
				}
			}
		}

		/* remove any old spelling error details if none exist */
		if (spellingErrors.size() == 0)
		{
			for (final RESTAssignedPropertyTagV1 tag : topic.getProperties().returnItems())
			{
				if (tag.getId().equals(SPELLING_ERRORS_PROPERTY_TAG_ID))
				{
					final RESTAssignedPropertyTagV1 removeSpellingErrorPropertyTag = new RESTAssignedPropertyTagV1();
					removeSpellingErrorPropertyTag.setId(SPELLING_ERRORS_PROPERTY_TAG_ID);
					removeSpellingErrorPropertyTag.setValue(tag.getValue());
					updateTopic.getProperties().addRemoveItem(removeSpellingErrorPropertyTag);
					topicIsUpdated = true;
				}
			}
		}

		/* build up the property tags if errors exist */
		if (spellingErrors.size() != 0 || doubleWords.size() != 0)
		{
			System.out.println("Topic ID: " + topic.getId());
			System.out.println("Topic Title: " + topic.getTitle());

			/* Build up the grammar error property tag */
			if (doubleWords.size() != 0)
			{
				final StringBuilder doubleWordErrors = new StringBuilder();

				if (doubleWords.size() != 0)
				{
					doubleWordErrors.append("Repeated Words: " + CollectionUtilities.toSeperatedString(doubleWords, ", "));
					System.out.println(doubleWordErrors.toString());
				}
				
				/* Find if a grammar property tag already exists */
				RESTAssignedPropertyTagV1 foundGrammarPropertyTag = null;
				for (final RESTAssignedPropertyTagV1 tag : topic.getProperties().returnItems())
				{
					if (tag.getId().equals(GRAMMAR_ERRORS_PROPERTY_TAG_ID))
					{
						foundGrammarPropertyTag = tag;
						break;
					}
				}

				/* Update the database */
				final RESTAssignedPropertyTagV1 addGrammarErrorTag;
				if (foundGrammarPropertyTag == null)
				{
					addGrammarErrorTag = new RESTAssignedPropertyTagV1();
					addGrammarErrorTag.setId(GRAMMAR_ERRORS_PROPERTY_TAG_ID);
				}
				else
				{
					addGrammarErrorTag = foundGrammarPropertyTag;
				}
				
				/* Only update the property tag if the value has changed */
				if (addGrammarErrorTag.getValue() == null && !addGrammarErrorTag.getValue().equals(doubleWordErrors.toString()))
				{
					topicIsUpdated = true;

					addGrammarErrorTag.explicitSetValue(doubleWordErrors.toString());

					updateTopic.getProperties().addNewItem(addGrammarErrorTag);
				}
			}

			/* Build up the spelling error property tag */
			if (spellingErrors.size() != 0)
			{
				final StringBuilder spellingErrorsMessage = new StringBuilder();

				int longestWord = 0;
				for (final SpellingErrorData error : spellingErrors)
				{
					final int wordLength = error.getMisspelledWord().length() + (error.getMispellCount() != 1 ? 5 : 0);
					longestWord = wordLength > longestWord ? wordLength : longestWord;
				}

				/* Build up the spelling errors string */
				for (final SpellingErrorData error : spellingErrors)
				{
					final StringBuilder spaces = new StringBuilder();
					for (int i = error.getMisspelledWord().length() + (error.getMispellCount() != 1 ? 5 : 0); i < longestWord; ++i)
					{
						spaces.append(" ");
					}

					spellingErrorsMessage.append(error.getMisspelledWord());
					if (error.getMispellCount() != 1)
					{
						spellingErrorsMessage.append(" [x" + error.getMispellCount() + "]");
					}
					spellingErrorsMessage.append(":" + spaces.toString() + " ");
					spellingErrorsMessage.append(CollectionUtilities.toSeperatedString(error.getSuggestions(), ", "));
					spellingErrorsMessage.append("\n");
				}

				System.out.println(spellingErrorsMessage.toString());
				
				/* Find if a spelling property tag already exists */
				RESTAssignedPropertyTagV1 foundSpellingPropertyTag = null;
				for (final RESTAssignedPropertyTagV1 tag : topic.getProperties().returnItems())
				{
					if (tag.getId().equals(SPELLING_ERRORS_PROPERTY_TAG_ID))
					{
						foundSpellingPropertyTag = tag;
						break;
					}
				}
				
				/* Update the database */
				final RESTAssignedPropertyTagV1 addSpellingErrorTag;
				if (foundSpellingPropertyTag == null)
				{
					addSpellingErrorTag = new RESTAssignedPropertyTagV1();
					addSpellingErrorTag.setId(SPELLING_ERRORS_PROPERTY_TAG_ID);
				}
				else
				{
					addSpellingErrorTag = foundSpellingPropertyTag;
				}
				
				/* Only update the property tag if the value has changed */
				if (addSpellingErrorTag.getValue() == null || !addSpellingErrorTag.getValue().equals(spellingErrorsMessage.toString()))
				{
					topicIsUpdated = true;

					addSpellingErrorTag.explicitSetValue(spellingErrorsMessage.toString());

					updateTopic.getProperties().addNewItem(addSpellingErrorTag);
				}
			}
			else
			{
				System.out.println();
			}
		}

		/*
		 * Update the topic in the database if there are changes that need to be
		 * persisted
		 */
		if (topicIsUpdated)
		{
			try
			{
				/*
				 * final ExpandDataTrunk expand = new ExpandDataTrunk();
				 * 
				 * final ExpandDataTrunk tagsExpand = new ExpandDataTrunk(new
				 * ExpandDataDetails("tags")); final ExpandDataTrunk
				 * propertyTagsExpand = new ExpandDataTrunk(new
				 * ExpandDataDetails("properties"));
				 * 
				 * expand.setBranches(CollectionUtilities.toArrayList(tagsExpand,
				 * propertyTagsExpand));
				 * 
				 * final String expandString =
				 * mapper.writeValueAsString(expand); final String
				 * expandEncodedStrnig = URLEncoder.encode(expandString,
				 * "UTF-8");
				 * 
				 * final RESTTopicV1 updatedTopic =
				 * restClient.updateJSONTopic(expandEncodedStrnig, updateTopic);
				 * System.out.println(updatedTopic.getId());
				 */

				restClient.updateJSONTopic("", updateTopic);
			}
			catch (final Exception ex)
			{
				ExceptionUtilities.handleException(ex);
			}
		}
	}

	/**
	 * Checks the topic for spelling errors
	 * 
	 * @param topic
	 *            The topic to process
	 * @param ignoreElements
	 *            The XML elements to ignore
	 * @param standardDict
	 *            The standard dictionary
	 * @param customDict
	 *            The custom dictionary
	 * @return A collection of spelling errors, their frequency, and suggested
	 *         replacements
	 * @throws SAXException
	 */
	private List<SpellingErrorData> checkSpelling(final RESTTopicV1 topic, final List<String> ignoreElements, final Dictionary standarddict, final Dictionary customDict)
	{
		/* Some collections to hold the spelling error details */
		final Map<String, SpellingErrorData> misspelledWords = new HashMap<String, SpellingErrorData>();

		/*
		 * prepare the topic xml for a spell check
		 */
		Document doc = null;
		try
		{
			doc = XMLUtilities.convertStringToDocument(topic.getXml());
		}
		catch (final Exception ex)
		{

		}

		if (doc != null)
		{
			stripOutIgnoredElements(doc, ignoreElements);
			final String cleanedXML = XMLUtilities.convertDocumentToString(doc, "UTF-8").replaceAll("\n", " ");

			final Source source = new Source(cleanedXML);
			final String xmlText = source.getRenderer().toString();

			/* Get the word list */
			final List<String> xmlTextWords = CollectionUtilities.toArrayList(xmlText.split(PUNCTUATION_CHARACTERS_RE + "+"));

			/* Check for spelling */
			for (int i = 0; i < xmlTextWords.size(); ++i)
			{
				final String word = xmlTextWords.get(i);
				final String trimmedWord = word.trim();

				/*
				 * make sure we are not looking at a blank string, or a
				 * combination of underscores and dashes
				 */
				if (!trimmedWord.isEmpty() && !trimmedWord.matches("[_\\-]+"))
				{
					/* Check spelling */
					final boolean standardDictMispelled = standarddict.misspelled(word);
					final boolean customDictMispelled = customDict.misspelled(word);

					if (standardDictMispelled && customDictMispelled)
					{
						/*
						 * This may have been a hyphenated word, which is more a
						 * question of grammar than spelling. Check to see if it
						 * is hyphenated, and if so split it up and see if each
						 * side was a valid word.
						 */

						final Matcher matcher = HYPHENATED_WORD_RE.matcher(word);
						if (matcher.matches())
						{
							final String firstWord = matcher.group("First");
							final String secondWord = matcher.group("Second");

							final boolean standardDictMispelledFirst = standarddict.misspelled(firstWord);
							final boolean customDictMispelledFirst = customDict.misspelled(firstWord);

							final boolean standardDictMispelledSecond = standarddict.misspelled(secondWord);
							final boolean customDictMispelledSecond = customDict.misspelled(secondWord);

							/*
							 * check to see if either component of the
							 * hyphenated word is misspelled on its own
							 */
							if (!(standardDictMispelledFirst && customDictMispelledFirst) && !(standardDictMispelledSecond && customDictMispelledSecond))
							{
								/*
								 * both words are valid on their own, so ignore
								 * this word
								 */
								continue;
							}
						}

						if (misspelledWords.containsKey(word))
						{
							misspelledWords.get(word).incMispellCount();
						}
						else
						{
							final List<String> suggestions = standarddict.suggest(word);
							CollectionUtilities.addAllThatDontExist(customDict.suggest(word), suggestions);
							Collections.sort(suggestions);

							misspelledWords.put(word, new SpellingErrorData(word, suggestions));
						}
					}
				}
			}
		}

		return CollectionUtilities.toArrayList(misspelledWords.values());
	}

	/**
	 * Checks the Docbook XML for common grammar errors
	 * 
	 * @param topic
	 *            The topic to process
	 * @param ignoreElements
	 *            The list of XML elements to ignore
	 * @return A list of grammar errors that were found
	 */
	private List<String> checkGrammar(final RESTTopicV1 topic, final List<String> ignoreElements)
	{
		final List<String> doubleWords = new ArrayList<String>();

		/*
		 * prepare the topic xml for a grammar check
		 */
		Document grammarDoc = null;
		try
		{
			grammarDoc = XMLUtilities.convertStringToDocument(topic.getXml());
		}
		catch (final Exception ex)
		{

		}

		if (grammarDoc != null)
		{
			replaceIgnoredElements(grammarDoc, ignoreElements);
			final String grammarCleanedXML = XMLUtilities.convertDocumentToString(grammarDoc, "UTF-8").replaceAll("\n", " ");

			if (grammarCleanedXML != null)
			{
				final Source grammarSource = new Source(replaceElementsWithMarkers(grammarCleanedXML));
				final String grammarXmlText = grammarSource.getRenderer().toString();

				/* Get the grammar word list */
				final List<String> xmlTextWordsForDoubleChecking = CollectionUtilities.toArrayList(grammarXmlText.split("\\s+"));

				/* Check for double words */
				for (int i = 0; i < xmlTextWordsForDoubleChecking.size(); ++i)
				{
					final String word = xmlTextWordsForDoubleChecking.get(i);

					if (!word.trim().isEmpty())
					{
						/* Check for doubled words */
						if (i != 0)
						{
							/* don't detected numbers */
							try
							{
								Double.parseDouble(word);
								continue;
							}
							catch (final Exception ex)
							{

							}

							/* make sure the "word" is not just punctuation */
							if (word.matches(PUNCTUATION_CHARACTERS_RE + "+"))
								continue;

							if (word.toLowerCase().equals(xmlTextWordsForDoubleChecking.get(i - 1)))
							{
								if (!doubleWords.contains(word + " " + word))
									doubleWords.add(word + " " + word);
							}
						}
					}
				}
			}
		}

		return doubleWords;
	}

	/**
	 * When converting XML to plain text, the loss of some elements causes
	 * unintended side effects for the grammar checks. A sentence such as
	 * "Refer to <xref linkend="something"/> to find out more information" will
	 * appear to have repeated the word "to" when the xref is removed.
	 * 
	 * This method will replace these elements with a punctuation marker, which
	 * is then used to break up the sequence of words to prevent these false
	 * positivies.
	 * 
	 * @param input
	 *            The XML to be processed
	 * @return The XML with certain tags replaced with a punctuation marker
	 */
	private String replaceElementsWithMarkers(final String input)
	{
		return input.replaceAll(XREF_RE, ELEMENT_PUNCTUATION_MARKER).replaceAll(ENTRY_RE, ELEMENT_PUNCTUATION_MARKER).replaceAll(ENTRY_CLOSE_RE, ELEMENT_PUNCTUATION_MARKER).replaceAll("<!--" + XMLPreProcessor.CUSTOM_INJECTION_SEQUENCE_RE + "-->", ELEMENT_PUNCTUATION_MARKER)
				.replaceAll("<!--" + XMLPreProcessor.CUSTOM_INJECTION_LIST_RE + "-->", ELEMENT_PUNCTUATION_MARKER).replaceAll("<!--" + XMLPreProcessor.CUSTOM_INJECTION_LISTITEMS_RE + "-->", ELEMENT_PUNCTUATION_MARKER)
				.replaceAll("<!--" + XMLPreProcessor.CUSTOM_ALPHA_SORT_INJECTION_LIST_RE + "-->", ELEMENT_PUNCTUATION_MARKER).replaceAll("<!--" + XMLPreProcessor.CUSTOM_INJECTION_SINGLE_RE + "-->", ELEMENT_PUNCTUATION_MARKER)
				.replaceAll("<!--" + INJECT_CONTENT_FRAGMENT_RE + "-->", ELEMENT_PUNCTUATION_MARKER).replaceAll("<!--" + INJECT_TITLE_FRAGMENT_RE + "-->", ELEMENT_PUNCTUATION_MARKER);
	}

	/**
	 * Here we remove any nodes that we don't want to include in the spell check
	 * 
	 * @param node
	 *            The node to process
	 * @param ignoreElements
	 *            The list of elements that are to be ignored
	 */
	private void stripOutIgnoredElements(final Node node, final List<String> ignoreElements)
	{
		final List<Node> removeNodes = new ArrayList<Node>();

		for (int i = 0; i < node.getChildNodes().getLength(); ++i)
		{
			final Node childNode = node.getChildNodes().item(i);

			for (final String ignoreElement : ignoreElements)
			{
				if (childNode.getNodeName().toLowerCase().equals(ignoreElement.toLowerCase()))
				{
					removeNodes.add(childNode);
				}
			}
		}

		for (final Node removeNode : removeNodes)
		{
			node.removeChild(removeNode);
		}

		for (int i = 0; i < node.getChildNodes().getLength(); ++i)
		{
			final Node childNode = node.getChildNodes().item(i);
			stripOutIgnoredElements(childNode, ignoreElements);
		}
	}

	/**
	 * Here we replace any nodes that we don't want to include in the grammar
	 * checks with punctuation marks
	 * 
	 * @param node
	 *            The node to process
	 * @param ignoreElements
	 *            The list of elements that are to be ignored
	 */
	private void replaceIgnoredElements(final Node node, final List<String> ignoreElements)
	{
		final List<Node> removeNodes = new ArrayList<Node>();

		for (int i = 0; i < node.getChildNodes().getLength(); ++i)
		{
			final Node childNode = node.getChildNodes().item(i);

			for (final String ignoreElement : ignoreElements)
			{
				if (childNode.getNodeName().toLowerCase().equals(ignoreElement.toLowerCase()))
				{
					removeNodes.add(childNode);
				}
			}
		}

		/*
		 * Loop through the nodes we found for removal, and insert an
		 * "innocuous" punctuation mark that is used to prevent unintended
		 * run-ons when the ignored node is removed.
		 */
		for (final Node removeNode : removeNodes)
		{
			final Text textnode = node.getOwnerDocument().createTextNode(" " + ELEMENT_PUNCTUATION_MARKER + " ");
			node.insertBefore(textnode, removeNode);
			node.removeChild(removeNode);
		}

		for (int i = 0; i < node.getChildNodes().getLength(); ++i)
		{
			final Node childNode = node.getChildNodes().item(i);
			replaceIgnoredElements(childNode, ignoreElements);
		}
	}
}
