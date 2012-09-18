package org.jboss.pressgang.ccms.services.docbookbuilder.constants;

/**
 * This class contains the constants used when modifying or building docbook XML 
 */
public class DocbookBuilderXMLConstants
{
	/** The prefix applied to the a Topic's ID when generating an XRef ID */
	public static final String TOPIC_XREF_PREFIX = "TopicID";
	
	/** Number of times to try setting the property tags on the topics */
	public static final Integer MAXIMUM_SET_PROP_TAGS_RETRY = 5;

}
