/*
  Copyright 2011-2014 Red Hat, Inc

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.pressgang.ccms.contentspec.ContentSpec;
import org.jboss.pressgang.ccms.contentspec.KeyValueNode;
import org.jboss.pressgang.ccms.contentspec.utils.CSTransformer;
import org.jboss.pressgang.ccms.contentspec.utils.ContentSpecUtilities;
import org.jboss.pressgang.ccms.contentspec.utils.EntityUtilities;
import org.jboss.pressgang.ccms.contentspec.utils.TranslationUtilities;
import org.jboss.pressgang.ccms.provider.ContentSpecProvider;
import org.jboss.pressgang.ccms.provider.DataProviderFactory;
import org.jboss.pressgang.ccms.provider.LocaleProvider;
import org.jboss.pressgang.ccms.provider.TranslatedCSNodeStringProvider;
import org.jboss.pressgang.ccms.provider.TranslatedContentSpecProvider;
import org.jboss.pressgang.ccms.rest.v1.constants.CommonFilterConstants;
import org.jboss.pressgang.ccms.utils.constants.CommonConstants;
import org.jboss.pressgang.ccms.wrapper.CSNodeWrapper;
import org.jboss.pressgang.ccms.wrapper.ContentSpecWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedCSNodeStringWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedCSNodeWrapper;
import org.jboss.pressgang.ccms.wrapper.TranslatedContentSpecWrapper;
import org.jboss.pressgang.ccms.wrapper.collection.CollectionWrapper;
import org.jboss.pressgang.ccms.wrapper.collection.UpdateableCollectionWrapper;
import org.jboss.pressgang.ccms.zanata.NotModifiedException;
import org.jboss.pressgang.ccms.zanata.ZanataInterface;
import org.jboss.pressgang.ccms.zanata.ZanataTranslation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Entity;
import org.zanata.common.LocaleId;
import org.zanata.rest.dto.resource.Resource;
import org.zanata.rest.dto.resource.TextFlow;
import org.zanata.rest.dto.resource.TextFlowTarget;
import org.zanata.rest.dto.resource.TranslationsResource;

public class ContentSpecSync extends BaseZanataSync {
    private static final Logger log = LoggerFactory.getLogger(ContentSpecSync.class);

    public ContentSpecSync(final DataProviderFactory providerFactory, final ZanataInterface zanataInterface) {
        super(providerFactory, zanataInterface);
    }

    @Override
    public void processZanataResources(final Set<String> zanataIds, final List<LocaleId> locales) {
        if (zanataIds == null || zanataIds.isEmpty() || locales == null || locales.isEmpty()) {
            return;
        }

        final TranslatedContentSpecProvider translatedContentSpecProvider = getProviderFactory().getProvider(TranslatedContentSpecProvider
                .class);
        final ContentSpecProvider contentSpecProvider = getProviderFactory().getProvider(ContentSpecProvider.class);
        final double resourceSize = zanataIds.size();
        double resourceCount = 0;

        for (final String zanataId : zanataIds) {
            try {
                // Work out progress
                long progress = Math.round(resourceCount / resourceSize * 100.0);
                setProgress(progress);
                resourceCount++;

                if (!zanataId.matches("^CS\\d+-\\d+$")) continue;

                log.info(progress + "% Synchronising " + zanataId);

                // The original Zanata Document Text Resources. This will be populated later.
                Resource originalTextResource = null;

                // Get the Translated Content Spec
                final TranslatedContentSpecWrapper translatedContentSpec = getTranslatedContentSpec(translatedContentSpecProvider,
                        contentSpecProvider, zanataId);
                boolean newTranslation = translatedContentSpec.getId() == null;

                boolean changed = false;
                for (final LocaleId locale : locales) {
                    // Check that the locale is still valid. ie It hasn't been removed in the locale manager
                    if (!getZanataInterface().getZanataLocales().contains(locale)) {
                        continue;
                    }

                    try {
                        // Find a translation
                        final TranslationsResource translationsResource;
                        try {
                            translationsResource = getZanataInterface().getTranslations(zanataId, locale);
                        } catch (NotModifiedException e) {
                            // The translation hasn't been modified so move to the next locale
                            continue;
                        }

                        // Check that a translation exists
                        if (translationsResource != null) {
                            if (originalTextResource == null) {
                                // find the original resource
                                originalTextResource = getZanataInterface().getZanataResource(zanataId);
                            }

                            // Sync the translations for the locale.
                            if (syncTranslatedContentSpecNodesForLocale(translatedContentSpec.getTranslatedNodes(), originalTextResource, locale,
                                    translationsResource)) {
                                changed = true;
                            }
                        } else {
                            log.info(progress + "% No translations found for " + zanataId + " locale " + locale);
                        }
                    } catch (final Exception ex) {
                        // Error with the locale
                        log.error("Failed to retrieve Locale " + locale.toString() + " for Zanata ID " + zanataId, ex);
                    }
                }

                // Only save the data if the content has changed
                if (newTranslation || changed) {
                    // Save all the changes
                    if (newTranslation) {
                        translatedContentSpecProvider.createTranslatedContentSpec(translatedContentSpec);
                    } else {
                        translatedContentSpec.setTranslatedNodes(translatedContentSpec.getTranslatedNodes());
                        translatedContentSpecProvider.updateTranslatedContentSpec(translatedContentSpec);
                    }

                    log.info(progress + "% Finished synchronising translations for " + zanataId);
                } else {
                    log.info(progress + "% No changes were found for " + zanataId);
                }
            } catch (final Exception ex) {
                // Error with the resource
                log.error("Failed to sync Zanata ID " + zanataId, ex);
            }
        }

        log.info("100% Finished synchronising all Content Spec translations");
    }

    /**
     * Gets the Translated Content Spec for a Zanata Id, or creates one if one doesn't exist.
     *
     * @param translatedContentSpecProvider
     * @param contentSpecProvider
     * @param zanataId
     * @return
     */
    protected TranslatedContentSpecWrapper getTranslatedContentSpec(final TranslatedContentSpecProvider translatedContentSpecProvider,
            final ContentSpecProvider contentSpecProvider, final String zanataId) {
        // Get the translated topic in the CCMS
        final CollectionWrapper<TranslatedContentSpecWrapper> translatedContentSpecs = translatedContentSpecProvider
                .getTranslatedContentSpecsWithQuery(
                "query;" + CommonFilterConstants.ZANATA_IDS_FILTER_VAR + "=" + zanataId);

        final TranslatedContentSpecWrapper translatedContentSpec;
        if (translatedContentSpecs.getItems().size() != 0) {
            translatedContentSpec = translatedContentSpecs.getItems().get(0);
        } else {
            final String[] zanataNameSplit = zanataId.split("-");
            final Integer contentSpecId = Integer.parseInt(zanataNameSplit[0].replace("CS", ""));
            final Integer contentSpecRevision = Integer.parseInt(zanataNameSplit[1]);

            // We need the historical content spec to create the translated content spec
            final ContentSpecWrapper historicalContentSpec = contentSpecProvider.getContentSpec(contentSpecId, contentSpecRevision);

            // Resolve any custom entities
            final List<Entity> entities = getEntities(historicalContentSpec);
            TranslationUtilities.resolveCustomContentSpecEntities(entities, historicalContentSpec);

            translatedContentSpec = TranslationUtilities.createTranslatedContentSpec(getProviderFactory(), historicalContentSpec);
        }

        return translatedContentSpec;
    }

    /**
     * Gets and parses the entities defined in the parsed content spec.
     *
     * @param contentSpec The content spec to get the entities from.
     * @return A list of DOM Entity elements, or an empty list if no entities exist.
     */
    protected List<Entity> getEntities(final ContentSpecWrapper contentSpec) {
        final ContentSpec tempSpec = new ContentSpec();

        if (contentSpec.getChildren() != null) {
            for (final CSNodeWrapper csNode : contentSpec.getChildren().getItems()) {
                if (csNode.getNodeType() == CommonConstants.CS_NODE_META_DATA) {
                    KeyValueNode<?> keyValueNode = CSTransformer.transformMetaData(csNode);
                    tempSpec.appendKeyValueNode(keyValueNode);
                }
            }
        }

        return ContentSpecUtilities.getContentSpecEntities(tempSpec);
    }

    /**
     * Syncs a Content Specs translatable nodes with the Translations from Zanata for a specific locale.
     *
     * @param translatedNodes      The content spec nodes to be synced.
     * @param originalTextResource The original Text Resource used to push to Zanata.
     * @param locale               The locale of the Translations being synced.
     * @param translationsResource The Translation Resource that holds the translated strings from Zanata.
     * @return
     */
    protected boolean syncTranslatedContentSpecNodesForLocale(final UpdateableCollectionWrapper<TranslatedCSNodeWrapper> translatedNodes,
            final Resource originalTextResource, final LocaleId locale, final TranslationsResource translationsResource) {
        boolean changed = false;

        // a mapping of the original strings to their translations
        final Map<String, ZanataTranslation> translations = new HashMap<String, ZanataTranslation>();

        final List<TextFlowTarget> textFlowTargets = translationsResource.getTextFlowTargets();
        final List<TextFlow> textFlows = originalTextResource.getTextFlows();


        // map the translation to the original resource
        for (final TextFlow textFlow : textFlows) {
            for (final TextFlowTarget textFlowTarget : textFlowTargets) {
                if (textFlowTarget.getResId().equals(textFlow.getId()) && !textFlowTarget.getContent().isEmpty()) {
                    translations.put(textFlow.getContent(), new ZanataTranslation(textFlowTarget));
                    break;
                }
            }
        }

        // Iterate over the translatable nodes and set the translation
        final List<TranslatedCSNodeWrapper> translatedCSNodeList = translatedNodes.getItems();
        for (final TranslatedCSNodeWrapper translatedCSNode : translatedCSNodeList) {
            // Ignore nodes without a original string, as it means it wasn't pushed to zanata
            if (translatedCSNode.getOriginalString() == null) {
                continue;
            } else if (translations.containsKey(translatedCSNode.getOriginalString())) {
                // If the string matches then sync the translation
                final TranslatedCSNodeStringWrapper translatedCSNodeString = getTranslatedCSNodeString(translatedCSNode, locale);
                boolean newTranslation = translatedCSNodeString.getId() == null;

                if (syncTranslatedContentSpecNodeForLocale(translatedCSNode, translatedCSNodeString, translations)) {
                    changed = true;

                    // If the translation string has changed or is new then set it as new/updated in the collection
                    if (newTranslation) {
                        translatedCSNode.getTranslatedStrings().addNewItem(translatedCSNodeString);
                    } else {
                        translatedCSNode.getTranslatedStrings().remove(translatedCSNodeString);
                        translatedCSNode.getTranslatedStrings().addUpdateItem(translatedCSNodeString);
                    }
                    translatedCSNode.setTranslatedStrings(translatedCSNode.getTranslatedStrings());

                    // Set the translated node as updated/created
                    translatedNodes.remove(translatedCSNode);
                    if (translatedCSNode.getId() == null) {
                        translatedNodes.addNewItem(translatedCSNode);
                    } else {
                        translatedNodes.addUpdateItem(translatedCSNode);
                    }
                }
            }
        }

        return changed;
    }

    /**
     * @param translatedCSNode
     * @param locale
     * @return
     */
    protected TranslatedCSNodeStringWrapper getTranslatedCSNodeString(final TranslatedCSNodeWrapper translatedCSNode,
            final LocaleId locale) {
        final TranslatedCSNodeStringProvider translatedCSNodeStringProvider = getProviderFactory().getProvider(
                TranslatedCSNodeStringProvider.class);
        final LocaleProvider localeProvider = getProviderFactory().getProvider(LocaleProvider.class);
        TranslatedCSNodeStringWrapper translatedCSNodeString = null;

        // Check if a Translated String already exists
        for (final TranslatedCSNodeStringWrapper existingTranslatedCSNodeString : translatedCSNode.getTranslatedStrings().getItems()) {
            if (existingTranslatedCSNodeString.getLocale().getTranslationValue().equals(locale.toString())) {
                translatedCSNodeString = existingTranslatedCSNodeString;
                break;
            }
        }

        // If the translatedCSNodeString is still null then create a new one
        if (translatedCSNodeString == null) {
            translatedCSNodeString = translatedCSNodeStringProvider.newTranslatedCSNodeString(translatedCSNode);
            translatedCSNodeString.setFuzzy(false);
            translatedCSNodeString.setLocale(EntityUtilities.findTranslationLocaleFromString(localeProvider, locale.toString()));
        }

        return translatedCSNodeString;
    }

    protected boolean syncTranslatedContentSpecNodeForLocale(final TranslatedCSNodeWrapper translatedCSNode,
            final TranslatedCSNodeStringWrapper translatedCSNodeString, final Map<String, ZanataTranslation> translations) {
        boolean changed = false;

        // Check if a translation exists for the original string
        if (translations.containsKey(translatedCSNode.getOriginalString())) {
            final ZanataTranslation translation = translations.get(translatedCSNode.getOriginalString());

            // Check that the translation string still matches
            if (!translation.getTranslation().equals(translatedCSNodeString.getTranslatedString())) {
                translatedCSNodeString.setTranslatedString(translation.getTranslation());
                changed = true;
            }

            // Check that the fuzzy flag still matches
            if (translation.isFuzzy() != translatedCSNodeString.isFuzzy()) {
                translatedCSNodeString.setFuzzy(translation.isFuzzy());
                changed = true;
            }
        }

        return changed;
    }
}
