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

package org.jboss.pressgang.ccms.services.zanatasync;

import static com.google.common.base.Strings.isNullOrEmpty;

import java.util.List;
import java.util.Map;

import org.jboss.pressgang.ccms.contentspec.utils.EntityUtilities;
import org.jboss.pressgang.ccms.contentspec.utils.TranslationUtilities;
import org.jboss.pressgang.ccms.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.provider.TopicProvider;
import org.jboss.pressgang.ccms.provider.TranslatedCSNodeProvider;
import org.jboss.pressgang.ccms.provider.TranslatedTopicProvider;
import org.jboss.pressgang.ccms.utils.common.DocBookUtilities;
import org.jboss.pressgang.ccms.utils.common.TopicUtilities;
import org.jboss.pressgang.ccms.utils.common.XMLUtilities;
import org.jboss.pressgang.ccms.utils.constants.CommonConstants;
import org.jboss.pressgang.ccms.wrapper.CSNodeWrapper;
import org.jboss.pressgang.ccms.wrapper.ServerSettingsWrapper;
import org.jboss.pressgang.ccms.wrapper.TopicWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedCSNodeWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedTopicWrapper;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.jboss.pressgang.ccms.zanata.ZanataTranslation;
import org.w3c.dom.Document;
import org.w3c.dom.Entity;
import org.xml.sax.SAXException;
import org.zanata.common.LocaleId;

public class ContentSpecTopicSync extends TopicSync {

    public ContentSpecTopicSync(final DataProviderFactory providerFactory, final ZanataInterface zanataInterface,
            final ServerSettingsWrapper serverSettings) {
        super(providerFactory, zanataInterface, serverSettings);
    }

    @Override
    protected TranslatedTopicWrapper createTranslatedTopic(final String zanataId, final LocaleId locale) {
        final TranslatedTopicProvider translatedTopicProvider = getProviderFactory().getProvider(TranslatedTopicProvider.class);
        final TopicProvider topicProvider = getProviderFactory().getProvider(TopicProvider.class);
        final TranslatedCSNodeProvider translatedCSNodeProvider = getProviderFactory().getProvider(TranslatedCSNodeProvider.class);

        // Get the id and revision from the zanata id
        final String[] zanataNameSplit = zanataId.split("-");
        final Integer topicId = Integer.parseInt(zanataNameSplit[0]);
        final Integer topicRevision = Integer.parseInt(zanataNameSplit[1]);

        // Get the associated Translated Node
        final TranslatedCSNodeWrapper translatedCSNode = translatedCSNodeProvider.getTranslatedCSNode(Integer.parseInt(zanataNameSplit[2]));

        // We need the historical topic here as well.
        final TopicWrapper historicalTopic = topicProvider.getTopic(topicId, topicRevision);

        // Try to find one that might already exist
        TranslatedTopicWrapper existingPushedTranslatedTopic = EntityUtilities.returnPushedTranslatedTopic(historicalTopic,
                translatedCSNode);

        final TranslatedTopicWrapper translatedTopic = translatedTopicProvider.newTranslatedTopic();
        translatedTopic.setLocale(locale.toString());
        translatedTopic.setTopicId(topicId);
        translatedTopic.setTopicRevision(topicRevision);
        translatedTopic.setTopic(historicalTopic);
        translatedTopic.setTags(historicalTopic.getTags());
        translatedTopic.setTranslatedCSNode(translatedCSNode);

        if (existingPushedTranslatedTopic == null) {
            final CSNodeWrapper csNode = translatedCSNode.getCSNode();
            translatedTopic.setTranslatedXMLCondition(csNode.getInheritedCondition());
            final CSNodeWrapper entityNode = csNode.getContentSpec().getMetaData(CommonConstants.CS_ENTITIES_TITLE);
            if (entityNode != null) {
                translatedTopic.setCustomEntities(entityNode.getAdditionalText());
            }
        } else {
            translatedTopic.setTranslatedXMLCondition(existingPushedTranslatedTopic.getTranslatedXMLCondition());
            translatedTopic.setCustomEntities(existingPushedTranslatedTopic.getCustomEntities());
        }

        return translatedTopic;
    }

    @Override
    protected boolean processTranslatedTopicXML(final TranslatedTopicWrapper translatedTopic,
            final Map<String, ZanataTranslation> translationDetails, final Map<String, String> translations) throws SAXException {
        // Get a Document from the stored historical XML
        final Document xml = TopicUtilities.convertXMLStringToDocument(translatedTopic.getTopic().getXml(),
                translatedTopic.getTopic().getXmlFormat());

        // Clean the XML of all conditions
        if (!isNullOrEmpty(translatedTopic.getTranslatedXMLCondition())) {
            DocBookUtilities.processConditions(translatedTopic.getTranslatedXMLCondition(), xml, "default");
        }

        // Remove any custom entities, since they cause massive translation issues.
        final List<Entity> entities = XMLUtilities.parseEntitiesFromString(translatedTopic.getCustomEntities());
        if (!entities.isEmpty()) {
            TranslationUtilities.resolveCustomTopicEntities(entities, xml);
        }

        return processTranslatedTopicXML(translatedTopic, xml, translationDetails, translations);
    }
}
