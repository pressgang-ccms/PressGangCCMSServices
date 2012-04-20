package com.redhat.topicindex.component.docbookrenderer.utils;

import java.util.List;

/**
 * This class contains static functions to be used when generating attributes in the docbook output.
 * Most functions are quite simple, but including them here serves as a way to self-document
 * the attributes that will appear in the output
 * @author matthew
 *
 */
public class AttributeBuilder 
{
	public static final String TOP_NAV_LINKS_ROLE = "TopNavLinks";
	public static final String TOP_TASK_LIST_XREF_PREFIX = "toptag";
	public static final String TASK_LIST_XREF_PREFIX = "tag";
	public static final String CATEGORY_LIST_XREF_PREFIX = "cat";
	/** The prefix placed in front of the topic id when generating topic section ids */
	public static final String NAV_PAGE_XREF_ID_PREFIX = "NavPageXRef-";
	/** This role applies to all elements that display nav links. This allows a single CSS style to be applied. */
	public static final String GENERIC_NAV_LINK_ROLE = "NavLinks";
	public static final String NAV_PAGE_TITLE_ROLE = "NavPageTitle";
	public static final String NAV_PAGE_FLOAT_CLEAR_ROLE = "NavPageFloatClear";
	public static final String NAV_PAGE_SUBTITLE_ROLE = "NavPageSubTitle";
	public static final String NAV_PAGE_CURRENT_LINK_ROLE = "NavPageCurrentLink";
	public static final String NAV_PAGE_OVERVIEWS_ROLE = "NavPageOverviews";
	public static final String NAV_PAGE_TASKS_ROLE = "NavPageTasks";
	public static final String NAV_PAGE_NAVLINKS_ROLE = "NavPageNavLinks";
	public static final String NAV_TOC_PARENT_ROLE = "yui-skin-sam";
	public static final String NAV_TOC_PARENT_ID = "tocTree";
	public static final String NAV_DISABLED_TOC_PARENT_ID = "disabledTocTree";
	public static final String NAV_TOC_CHAPTER_ID = "Toc";
	public static final String NAV_TOC_CONTAINER_ID = "treeContainer";
	
	public static String buildXRefForTaskList(final List<Integer> tagHierarchy, final Integer categoryId, final boolean topList)
    {
    	String xRef = "";
    	
    	// build up the xref using the top level tags
    	for (int i = 0; i < tagHierarchy.size() - 1; ++i)
    		xRef += TASK_LIST_XREF_PREFIX + tagHierarchy.get(i) + "-";
    	
    	// the final tag may or may not be a summary
    	xRef += (topList?TOP_TASK_LIST_XREF_PREFIX:TASK_LIST_XREF_PREFIX) + tagHierarchy.get(tagHierarchy.size() - 1);
    	
    	// add the category id to the end
    	if (categoryId != null)
    	{
    		if (xRef.length() != 0)
    			xRef += "-";
    		
    		xRef += CATEGORY_LIST_XREF_PREFIX + categoryId;
    	}
    	
    	return NAV_PAGE_XREF_ID_PREFIX + xRef;
    }
	
	/**
	 * Builds the role name for the columns of links associated with Technology and Concern 
	 * @param primaryTagName
	 * @param categoryName
	 * @return
	 */
	public static String buildFontPageListLinkRole(final String primaryTagName, final String categoryName)
	 {
		 final String fixedcategoryName = categoryName.replaceAll(" ", "");
		 
		 //return fixedPrimaryTagName + "-" + fixedcategoryName;
		 
		 return fixedcategoryName + "NavLinks " + GENERIC_NAV_LINK_ROLE;
	 }
	 
	 public static String buildRoleForFrontPageAudiencePara(final String audienceTagName)
	 {
		 return audienceTagName.replaceAll(" ", "");
	 }
	 
	 public static String buildXRefForFrontPageAudiencePara(final String audienceTagName)
	 {
		 return audienceTagName.replaceAll(" ", "");
	 }
	 
	 public static String buildDocbookXMLFileNameForFrontPage(final String audienceTagName)
	 {
		 return audienceTagName.replaceAll(" ", "") + ".xml";
	 }
	 
	 public static String buildRoleForNavPageColumns(final Integer columnCount)
	 {
		 return "NavColumn" + (columnCount==null?"Null":columnCount.toString()) + " " + GENERIC_NAV_LINK_ROLE;
	 }
}
