package com.redhat.topicindex.component.docbookrenderer.structures.toc;

import java.util.List;

import com.redhat.ecs.services.docbookcompiling.xmlprocessing.structures.TocTopicDatabase;
import com.redhat.topicindex.rest.entities.TagV1;

public class TocLandingPage
{
	private TocTopicDatabase tocTopicDatabase;
	private List<TagV1> matchingTags;
	private String docbook;

	public String getDocbook()
	{
		return docbook;
	}

	public void setDocbook(String docbook)
	{
		this.docbook = docbook;
	}
	
	public TocLandingPage(final TocTopicDatabase tocTopicDatabase, final List<TagV1> matchingTags)
	{
		this.tocTopicDatabase = tocTopicDatabase;
		this.matchingTags = matchingTags;
		
		generateDocbook();
	}
	
	private void generateDocbook()
	{
		
	}
}
