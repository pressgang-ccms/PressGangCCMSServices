package com.redhat.topicindex.component.docbookrenderer.structures.toc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.redhat.ecs.services.docbookcompiling.DocbookBuildingOptions;
import com.redhat.ecs.services.docbookcompiling.DocbookUtils;

/**
	This class represents a folder in the TOC
 */
public class TocFolderElement extends TocElement
{
	protected List<TocElement> children;

	public TocElement getFirstChildById(final String id)
	{
		if (children != null)
		{
			for (final TocElement element : children)
			{
				if (id == null)
				{
					if (element.getId() == null)
						return element;
				}
				else if (id.equals(element.getId()))
				{
					return element;
				}
			}
			
			for (final TocElement element : children)
			{
				if (element instanceof TocFolderElement)
				{
					final TocFolderElement folder = (TocFolderElement)element;
					final TocElement matchingChild = folder.getFirstChildById(id);
					if (matchingChild != null)
						return matchingChild;
				}
			}
		}
				
		return null;
	}
	
	public List<TocElement> getChildren() 
	{
		return children;
	}
	
	public void sortChildren(final Comparator<TocElement> comparator)
	{
		Collections.sort(children, comparator);
	}

	public void setChildren(final List<TocElement> children) 
	{
		this.children = children;
	}
	
	public void addChild(final TocElement child)
	{
		this.children.add(child);
	}
	
	public TocFolderElement(final DocbookBuildingOptions docbookBuildingOptions, final String label, final String id, final List<TocElement> children)
	{
		super(docbookBuildingOptions, label, id);
		this.children = children;
	}
	
	public TocFolderElement(final DocbookBuildingOptions docbookBuildingOptions, final String label, final String id)
	{
		super(docbookBuildingOptions, label, id);
		this.children = new ArrayList<TocElement>();
	}
	
	public TocFolderElement(final DocbookBuildingOptions docbookBuildingOptions, final String label)
	{
		super(docbookBuildingOptions, label);
		this.children = new ArrayList<TocElement>();
	}
	
	public TocFolderElement(final DocbookBuildingOptions docbookBuildingOptions)
	{
		super(docbookBuildingOptions);
		this.children = new ArrayList<TocElement>();
	}
	
	public TocFolderElement()
	{
		super();
		this.children = new ArrayList<TocElement>();
	}
	
	@Override
	public String getDocbook()
	{
		// generate the docbook that represents this folder
		final List<String> childrenDocbook = new ArrayList<String>();
				
		for (final TocElement child : children)
			childrenDocbook.add(child.getDocbook());
		
		this.docbook = DocbookUtils.wrapInListItem(
			DocbookUtils.wrapListItems(childrenDocbook, label)
		);
		
		return this.docbook;
	}
	
	@Override
	public String getEclipseXml()
	{
		// generate the eclipse xml that represents this folder
		this.eclipseXml = "<topic label=\"" + this.label + "\">";
		for (final TocElement child : children)
			this.eclipseXml += child.eclipseXml;
		this.eclipseXml += "</topic>";
		
		return this.eclipseXml;
	}
}
