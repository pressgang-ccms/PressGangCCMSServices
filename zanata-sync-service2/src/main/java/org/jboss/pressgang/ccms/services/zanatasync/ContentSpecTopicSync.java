package org.jboss.pressgang.ccms.services.zanatasync;

import java.util.Map;

import org.jboss.pressgang.ccms.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.provider.TopicProvider;
import org.jboss.pressgang.ccms.provider.TranslatedCSNodeProvider;
import org.jboss.pressgang.ccms.provider.TranslatedTopicProvider;
import org.jboss.pressgang.ccms.rest.v1.constants.CommonFilterConstants;
import org.jboss.pressgang.ccms.utils.common.DocBookUtilities;
import org.jboss.pressgang.ccms.utils.common.XMLUtilities;
import org.jboss.pressgang.ccms.wrapper.CSNodeWrapper;
import org.jboss.pressgang.ccms.wrapper.TopicWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedCSNodeWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedTopicWrapper;
import org.jboss.pressgang.ccms.wrapper.collection.CollectionWrapper;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.jboss.pressgang.ccms.zanata.ZanataTranslation;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.zanata.common.LocaleId;

public class ContentSpecTopicSync extends TopicSync {

    public ContentSpecTopicSync(final DataProviderFactory providerFactory, final ZanataInterface zanataInterface) {
        super(providerFactory, zanataInterface);
    }

    @Override
    protected TranslatedTopicWrapper getTranslatedTopic(final DataProviderFactory providerFactory, final String zanataId,
            final LocaleId locale) {
        final TranslatedTopicProvider translatedTopicProvider = providerFactory.getProvider(TranslatedTopicProvider.class);
        final TopicProvider topicProvider = providerFactory.getProvider(TopicProvider.class);
        final TranslatedCSNodeProvider translatedCSNodeProvider = providerFactory.getProvider(TranslatedCSNodeProvider.class);

        // Get the translated topic in the CCMS
        final CollectionWrapper<TranslatedTopicWrapper> translatedTopics = translatedTopicProvider.getTranslatedTopicsWithQuery(
                "query;" + CommonFilterConstants.ZANATA_IDS_FILTER_VAR + "=" + zanataId);

        TranslatedTopicWrapper translatedTopic = null;
        if (translatedTopics.getItems().size() != 0) {
            // Find the original translation (the query results will return all locales)
            for (final TranslatedTopicWrapper transTopic : translatedTopics.getItems()) {
                if (transTopic.getLocale().equals(locale.toString())) {
                    translatedTopic = transTopic;
                    break;
                }
            }
        }

        // If the TranslatedTopic doesn't exist in PressGang then we need to create it
        if (translatedTopic == null) {
            final String[] zanataNameSplit = zanataId.split("-");
            final Integer topicId = Integer.parseInt(zanataNameSplit[0]);
            final Integer topicRevision = Integer.parseInt(zanataNameSplit[1]);

            // Get the associated Translated Node
            final TranslatedCSNodeWrapper translatedCSNode = translatedCSNodeProvider.getTranslatedCSNode(
                    Integer.parseInt(zanataNameSplit[2]));

            // We need the historical topic here as well.
            final TopicWrapper historicalTopic = topicProvider.getTopic(topicId, topicRevision);

            translatedTopic = translatedTopicProvider.newTranslatedTopic();
            translatedTopic.setLocale(locale.toString());
            translatedTopic.setTopicId(topicId);
            translatedTopic.setTopicRevision(topicRevision);
            translatedTopic.setTopic(historicalTopic);
            translatedTopic.setTags(historicalTopic.getTags());
            translatedTopic.setTranslatedCSNode(translatedCSNode);

            // We need to get the Condition, however it could be inherited so look up the parent nodes as required
            final CSNodeWrapper csNode = translatedCSNode.getCSNode();
            final String condition = getCSNodeCondition(csNode);
            translatedTopic.setTranslatedXMLCondition(condition);
        }

        return translatedTopic;
    }

    /**
     * Get the condition for a Content Spec Node. This involves looking up the nodes parents incase the condition is inherited.
     *
     * @param csNode
     * @return
     */
    protected String getCSNodeCondition(final CSNodeWrapper csNode) {
        String condition = csNode.getCondition();
        // If the condition is null then we need to look at the parent nodes
        if (condition == null) {
            // If the node has no parent, then it is a top level node so get the content spec condition.
            // Otherwise try and get the condition from the nodes parent.
            if (csNode.getParent() == null) {
                return csNode.getContentSpec().getCondition();
            } else {
                return getCSNodeCondition(csNode.getParent());
            }
        } else {
            return condition;
        }
    }

    @Override
    protected boolean processTranslatedTopicXML(final TranslatedTopicWrapper translatedTopic,
            final Map<String, ZanataTranslation> translationDetails, final Map<String, String> translations) throws SAXException {
        // Get a Document from the stored historical XML
        final Document xml = XMLUtilities.convertStringToDocument(translatedTopic.getTopic().getXml());

        // Clean the XML of all conditions
        DocBookUtilities.processConditions(translatedTopic.getTranslatedXMLCondition(), xml, "default");

        return super.processTranslatedTopicXML(translatedTopic, xml, translationDetails, translations);
    }
}
