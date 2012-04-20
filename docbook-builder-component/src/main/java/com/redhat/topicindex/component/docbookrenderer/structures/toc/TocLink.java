package com.redhat.topicindex.component.docbookrenderer.structures.toc;

import com.redhat.ecs.services.docbookcompiling.DocbookBuildingOptions;
import com.redhat.ecs.services.docbookcompiling.DocbookUtils;
import com.redhat.topicindex.component.docbookrenderer.constants.DocbookBuilderXMLConstants;

/**
	This class represents a link to a topic in the toc
*/
public class TocLink extends TocElement 
{
	private String pageName;

	public String getPageName() 
	{
		return pageName;
	}

	public void setPageName(final String pageName) 
	{
		this.pageName = pageName;
	}
	
	protected void setTopicId(final Integer topicId)
	{
		this.setPageName(DocbookBuilderXMLConstants.TOPIC_XREF_PREFIX + topicId);
	}
	
	public TocLink(final DocbookBuildingOptions docbookBuildingOptions, final String label, final String id, final Integer sort, final String pageName)
	{
		super(docbookBuildingOptions, label, id, "", sort);
		this.pageName = pageName;
	}
	
	public TocLink(final DocbookBuildingOptions docbookBuildingOptions, final String label, final String id, final String docbook, final String pageName)
	{
		super(docbookBuildingOptions, label, id, docbook);
		this.pageName = pageName;
		this.docbook = docbook;	
	}
	
	public TocLink(final DocbookBuildingOptions docbookBuildingOptions, final String label, final String id, final String pageName)
	{
		super(docbookBuildingOptions, label, id);
		this.pageName = pageName;
	}
	
	public TocLink()
	{
		super();
		this.pageName = "";
	}
	
	@Override
	public String getDocbook() 
	{
		this.docbook = DocbookUtils.buildULinkListItem(pageName + ".html", label);
		return docbook;
	}

	@Override
	public String getEclipseXml() 
	{
		this.eclipseXml = "<topic label=\"" + label + "\" href=\"" + pageName + ".html\"/>";
		return eclipseXml;
	}
}
