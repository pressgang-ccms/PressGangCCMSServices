package com.redhat.topicindex.component.docbookrenderer.structures.toc;

import com.redhat.ecs.services.docbookcompiling.DocbookBuildingOptions;


/**
	This class is the base for all elements that appear in the toc
 */
public abstract class TocElement 
{
	/** The name of this element */
	protected String label;
	/** The DocBook XML that is used to render this element */
	protected String docbook;
	/** The XML that is used to render this element in an Eclipse tree */
	protected String eclipseXml;
	/** The options specified by the user when building the DocBook */
	protected DocbookBuildingOptions docbookBuildingOptions;
	/** The id of this element */
	protected String id;
	/** The sort value for this element */
	private Integer sort;

	public String getLabel() 
	{
		return label;
	}

	public void setLabel(final String label) 
	{
		this.label = label;
	}
	
	public TocElement(final DocbookBuildingOptions docbookBuildingOptions, final String label, final String id, final String docbook, final Integer sort)
	{
		this.label = label;
		this.docbookBuildingOptions = docbookBuildingOptions;
		this.docbook = docbook;
		this.eclipseXml = "";
		this.id = id;
		this.sort = sort;
	}
	
	public TocElement(final DocbookBuildingOptions docbookBuildingOptions, final String label, final String id, final String docbook)
	{
		this.label = label;
		this.docbookBuildingOptions = docbookBuildingOptions;
		this.docbook = docbook;
		this.eclipseXml = "";
		this.id = id;
	}
	
	public TocElement(final DocbookBuildingOptions docbookBuildingOptions, final String label, final String id)
	{
		this.label = label;
		this.docbookBuildingOptions = docbookBuildingOptions;
		this.docbook = "";
		this.eclipseXml = "";
		this.id = id;
	}
	
	public TocElement(final DocbookBuildingOptions docbookBuildingOptions, final String label)
	{
		this.label = label;
		this.docbookBuildingOptions = docbookBuildingOptions;
		this.docbook = "";
		this.eclipseXml = "";
		this.id = "";
	}
	
	public TocElement(final DocbookBuildingOptions docbookBuildingOptions)
	{
		this.label = "";
		this.docbookBuildingOptions = docbookBuildingOptions;
		this.docbook = "";
		this.eclipseXml = "";
	}
	
	public TocElement()
	{
		this.label = "";
		this.docbookBuildingOptions = null;
		this.docbook = "";
		this.eclipseXml = "";
	}

	public String getDocbook() 
	{
		return docbook;
	}

	public String getEclipseXml() 
	{
		return eclipseXml;
	}

	public String getId()
	{
		return id;
	}

	public void setId(final String id)
	{
		this.id = id;
	}

	public Integer getSort()
	{
		return sort;
	}

	public void setSort(final Integer sort)
	{
		this.sort = sort;
	}
}
