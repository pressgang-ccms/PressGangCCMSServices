package com.redhat.topicindex.component.docbookrenderer.structures.toc;

import java.util.ArrayList;
import java.util.List;

import com.redhat.ecs.services.docbookcompiling.DocbookBuildingOptions;
import com.redhat.ecs.services.docbookcompiling.DocbookUtils;
import com.redhat.topicindex.component.docbookrenderer.utils.AttributeBuilder;

/**
 * This class represents the top level toc container
 */
public class TocTopLevel extends TocFolderElement
{
	public TocTopLevel(final DocbookBuildingOptions docbookBuildingOptions, final String label, final String id, final List<TocElement> children)
	{
		super(docbookBuildingOptions, label, id, children);
	}

	public TocTopLevel(final DocbookBuildingOptions docbookBuildingOptions, final String label, final String id)
	{
		super(docbookBuildingOptions, label, id);
	}

	public TocTopLevel(final DocbookBuildingOptions docbookBuildingOptions)
	{
		super(docbookBuildingOptions);
	}

	public TocTopLevel()
	{
		super();
	}

	@Override
	public String getDocbook()
	{
		/* generate the docbook that represents this folder */
		final List<String> childrenDocbook = new ArrayList<String>();

		for (final TocElement child : children)
			childrenDocbook.add(child.getDocbook());

		this.docbook = DocbookUtils.wrapListItems(childrenDocbook, label);

		/* wrap the whole thing up in a para/div */
		final String idAttribute = docbookBuildingOptions != null && docbookBuildingOptions.getEnableDynamicTreeToc() ? AttributeBuilder.NAV_TOC_PARENT_ID : AttributeBuilder.NAV_DISABLED_TOC_PARENT_ID;

		this.docbook = DocbookUtils.wrapInPara(this.docbook, AttributeBuilder.NAV_TOC_PARENT_ROLE, idAttribute);

		/*
		 * wrap it up again in another div. this allows us to add elements above
		 * and below the tree (like the search box)
		 */
		this.docbook = DocbookUtils.wrapInSimpleSect(this.docbook, null, AttributeBuilder.NAV_TOC_CONTAINER_ID);

		/* and place that para into a section */
		this.docbook = DocbookUtils.buildChapter(this.docbook, "", AttributeBuilder.NAV_TOC_CHAPTER_ID);

		this.docbook = DocbookUtils.addXMLBoilerplate(this.docbook);
		
		return this.docbook;
	}

	@Override
	public String getEclipseXml()
	{
		/* generate the eclipse xml that represents this folder */
		this.eclipseXml = "<toc label=\"" + this.label + "\">";
		for (final TocElement child : children)
			this.eclipseXml += child.eclipseXml;
		this.eclipseXml += "</toc>";
		
		return this.eclipseXml;
	}

}
