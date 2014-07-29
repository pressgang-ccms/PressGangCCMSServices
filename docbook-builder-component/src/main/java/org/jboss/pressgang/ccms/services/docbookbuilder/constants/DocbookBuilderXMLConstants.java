/*
  Copyright 2011-2014 Red Hat

  This file is part of PressGang CCMS.

  PressGang CCMS is free software: you can redistribute it and/or modify
  it under the terms of the GNU Lesser General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  PressGang CCMS is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public License
  along with PressGang CCMS.  If not, see <http://www.gnu.org/licenses/>.
*/

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
