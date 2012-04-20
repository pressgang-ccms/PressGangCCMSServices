package com.redhat.topicindex.component.docbookrenderer.sort;

import java.util.Comparator;

import com.redhat.topicindex.component.docbookrenderer.structures.toc.TocElement;
import com.redhat.topicindex.component.docbookrenderer.structures.toc.TocFolderElement;
import com.redhat.topicindex.component.docbookrenderer.structures.toc.TocLink;

public class TocElementSortLabelComparator implements Comparator<TocElement>
{
	private boolean linksAppearAboveFolders;

	public TocElementSortLabelComparator(final boolean linksAppearAboveFolders)
	{
		this.linksAppearAboveFolders = linksAppearAboveFolders;
	}

	public int compare(final TocElement o1, final TocElement o2)
	{
		if (o1 == null && o2 == null)
			return 0;
		if (o1 == null)
			return -1;
		if (o2 == null)
			return 1;

		if (o1 instanceof TocLink && o2 instanceof TocFolderElement)
			return linksAppearAboveFolders ? -1 : 1;
		if (o1 instanceof TocFolderElement && o2 instanceof TocLink)
			return linksAppearAboveFolders ? 1 : -1;

		if (o1.getSort() == null && o2.getSort() == null)
		{
			/* If the sort values are both null, fall back to a label comparison */
			if (o1.getLabel() == null && o2.getLabel() == null)
				return 0;
			if (o1.getLabel() == null)
				return -1;
			if (o2.getLabel() == null)
				return 1;
			
			return o1.getLabel().compareTo(o2.getLabel());
		}
		if (o1.getSort() == null)
			return -1;
		if (o2.getSort() == null)
			return 1;

		return o1.getSort().compareTo(o2.getSort());
	}

}
