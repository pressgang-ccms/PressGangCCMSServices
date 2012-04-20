package com.redhat.topicindex.component.docbookrenderer.structures;

import com.redhat.topicindex.rest.entities.TopicV1;

/**
 * This class is used to map an image referenced inside a topic to the topic
 * itself. This is mostly for error reporting purposes.
 */
public class TopicImageData
{
	private TopicV1 topic;
	private String imageName;

	public TopicV1 getTopic()
	{
		return topic;
	}

	public void setTopic(TopicV1 topic)
	{
		this.topic = topic;
	}

	public String getImageName()
	{
		return imageName;
	}

	public void setImageName(String imageName)
	{
		this.imageName = imageName;
	}

	public TopicImageData(final TopicV1 topic, final String imageName)
	{
		this.topic = topic;
		this.imageName = imageName;
	}

}
