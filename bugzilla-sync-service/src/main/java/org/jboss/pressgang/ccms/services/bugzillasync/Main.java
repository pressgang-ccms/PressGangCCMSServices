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

package org.jboss.pressgang.ccms.services.bugzillasync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.code.regexp.NamedMatcher;
import com.google.code.regexp.NamedPattern;
import com.j2bugzilla.base.BugzillaConnector;
import com.j2bugzilla.base.ECSBug;
import com.j2bugzilla.rpc.BugSearch;
import com.j2bugzilla.rpc.LogIn;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.pressgang.ccms.rest.v1.client.PressGangCCMSProxyFactoryV1;
import org.jboss.pressgang.ccms.rest.v1.collections.RESTBugzillaBugCollectionV1;
import org.jboss.pressgang.ccms.rest.v1.collections.RESTTopicCollectionV1;
import org.jboss.pressgang.ccms.rest.v1.collections.items.RESTTopicCollectionItemV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTBugzillaBugV1;
import org.jboss.pressgang.ccms.rest.v1.entities.RESTTopicV1;
import org.jboss.pressgang.ccms.rest.v1.expansion.ExpandDataDetails;
import org.jboss.pressgang.ccms.rest.v1.expansion.ExpandDataTrunk;
import org.jboss.pressgang.ccms.rest.v1.jaxrsinterfaces.RESTInterfaceV1;
import org.jboss.pressgang.ccms.utils.common.CollectionUtilities;
import org.jboss.pressgang.ccms.utils.common.ExceptionUtilities;
import org.jboss.pressgang.ccms.utils.constants.CommonConstants;
import org.jboss.resteasy.specimpl.PathSegmentImpl;

public class Main {
    /** The Default amount of time that should be waited between Zanata REST API Calls. */
    private static final Double DEFAULT_BUGZILLA_API_CALL_INTERVAL = 0.2;
    private static Double minBugzillaAPICallInterval;
    private static long lastAPICallTime = 0;
    
    public static void main(String args[]) {
        try {
            /* Get the system properties */
            final String skynetServer = System.getProperty(CommonConstants.PRESS_GANG_REST_SERVER_SYSTEM_PROPERTY);
            final String bugzillaServer = System.getProperty(CommonConstants.BUGZILLA_URL_PROPERTY);
            final String bugzillaPassword = System.getProperty(CommonConstants.BUGZILLA_PASSWORD_PROPERTY);
            final String bugzillaUsername = System.getProperty(CommonConstants.BUGZILLA_USERNAME_PROPERTY);
            final String MIN_API_CALL_INTERVAL = System.getProperty(CommonConstants.BUGZILLA_MIN_API_CALL_INTERVAL_PROPERTY);

            System.out.println("REST Server: " + skynetServer);
            System.out.println("Bugzilla Username: " + bugzillaUsername);
            System.out.println("Bugzilla Server: " + bugzillaServer);

            /* Some sanity checking */
            if (skynetServer == null || skynetServer.trim().isEmpty() || bugzillaServer == null
                    || bugzillaServer.trim().isEmpty() || bugzillaPassword == null || bugzillaPassword.trim().isEmpty()
                    || bugzillaUsername == null || bugzillaUsername.trim().isEmpty()) {
                System.out.println("The " + CommonConstants.PRESS_GANG_REST_SERVER_SYSTEM_PROPERTY + ", "
                        + CommonConstants.BUGZILLA_URL_PROPERTY + ", " + CommonConstants.BUGZILLA_PASSWORD_PROPERTY + " and "
                        + CommonConstants.BUGZILLA_USERNAME_PROPERTY + " system properties need to be defined.");
                return;
            }
            
            /* Parse the specified time from the System Variables. If no time is set or is invalid then use the default value */
            try {
                minBugzillaAPICallInterval = Double.parseDouble(MIN_API_CALL_INTERVAL);
            } catch (NumberFormatException ex) {
                minBugzillaAPICallInterval = DEFAULT_BUGZILLA_API_CALL_INTERVAL;
            } catch (NullPointerException ex) {
                minBugzillaAPICallInterval = DEFAULT_BUGZILLA_API_CALL_INTERVAL;
            }

            /*
             * The regex pattern used to pull information out of the build id field
             */
            final NamedPattern pattern = NamedPattern.compile(CommonConstants.BUGZILLA_BUILD_ID_NAMED_RE);

            /* The JSON mapper */
            final ObjectMapper mapper = new ObjectMapper();

            /* Create a REST Client interface */
            final RESTInterfaceV1 client = PressGangCCMSProxyFactoryV1.create(skynetServer).getRESTClient();

            /* Get the topics from Skynet that have bugs assigned to them */
            final ExpandDataTrunk expand = new ExpandDataTrunk();
            final ExpandDataDetails topicsExpand = new ExpandDataDetails("topics");
            final ExpandDataTrunk topics = new ExpandDataTrunk(topicsExpand);
            final ExpandDataTrunk bugzillaBugz = new ExpandDataTrunk(new ExpandDataDetails(RESTTopicV1.BUGZILLABUGS_NAME));
            expand.setBranches(CollectionUtilities.toArrayList(topics));
            topics.setBranches(CollectionUtilities.toArrayList(bugzillaBugz));

            final String expandString = mapper.writeValueAsString(expand);

            final PathSegmentImpl query = new PathSegmentImpl("query;topicHasBugzillaBugs=true", false);

            System.out.println("Fetching topics with existing Bugzilla bugs.");

            final RESTTopicCollectionV1 topicsWithBugs = client.getJSONTopicsWithQuery(query, expandString);

            System.out.println("Found " + topicsWithBugs.getSize()
                    + " topics that already have Bugzilla bugs assigned to them.");

            System.out.println("Searching Bugzilla for matching bug reports.");

            /* Get the bugzilla bugs */
            final LogIn login = new LogIn(bugzillaUsername, bugzillaPassword);
            final BugzillaConnector connector = new BugzillaConnector();
            final BugSearch<ECSBug> search = new BugSearch<ECSBug>(ECSBug.class);

            /*
             * Create a query that will return all bugs whose Build ID matches what is prepopulated by Skynet.
             */
            search.addQueryParam("f1", "cf_build_id");
            search.addQueryParam("o1", "regexp");
            search.addQueryParam("v1", "[0-9]+" + CommonConstants.BUGZILLA_BUILD_ID_RE);
            search.addQueryParam("query_format", "advanced");

            connector.connectTo("https://" + bugzillaServer + "/xmlrpc.cgi");
            connector.executeMethod(login);
            connector.executeMethod(search);
            waitForMinimumCallInterval();

            final List<ECSBug> bugzillaBugs = search.getSearchResults();

            System.out.println("Found " + bugzillaBugs.size() + " Bugzilla bugs that match the regular expression.");

            /*
             * A list of the topics that we have updated. This will be used to compare those topics that say they have bugs
             * assigned to them, and the topics that the bugzilla bugs actually refer to.
             */
            final Map<Integer, RESTTopicV1> processedTopics = new HashMap<Integer, RESTTopicV1>();

            /* loop over the bugzilla bugs, and sync up the associated topics */
            float current = 1;
            float total = bugzillaBugs.size();
            for (final ECSBug bug : bugzillaBugs) {
                final int percentDone = (int) (current / total * 100);
                ++current;
                System.out.print("[" + percentDone + "%] Getting details on bug " + bug.getID() + ". ");

                /*final GetBug<ECSBug> getBug = new GetBug<ECSBug>(ECSBug.class, bug.getID());
                connector.executeMethod(getBug);
                waitForMinimumCallInterval();*/
                
                //final ECSBug ecsBug = getBug.getBug();
                final ECSBug ecsBug = bug;

                final NamedMatcher buildIdMatcher = pattern.matcher(ecsBug.getBuildId());

                boolean foundMatch = false;
                while (buildIdMatcher.find()) {
                    foundMatch = true;

                    final String topicId = buildIdMatcher.group("TopicID");
                    final Integer topicIdInt = Integer.parseInt(topicId);
                    final RESTTopicV1 existingTopic = findTopic(topicIdInt, topicsWithBugs);

                    /* This is the topics we will use to update the system */
                    RESTTopicV1 updateTopic = null;

                    /* keep a database of topcis that we are updating */
                    if (!processedTopics.containsKey(topicIdInt)) {
                        updateTopic = new RESTTopicV1();
                        updateTopic.setId(topicIdInt);
                        updateTopic.explicitSetBugzillaBugs_OTM(new RESTBugzillaBugCollectionV1());
                        processedTopics.put(topicIdInt, updateTopic);
                    } else {
                        updateTopic = processedTopics.get(topicIdInt);
                    }

                    if (existingTopic != null) {
                        final List<RESTBugzillaBugV1> existingBugs = findBug(ecsBug.getID(),
                                existingTopic.getBugzillaBugs_OTM());

                        /* if the bug exists, make sure the values are the same */
                        if (existingBugs.size() != 0) {
                            boolean needsUpdating = false;
                            if (!ecsBug.getSummary().equals(existingBugs.get(0).getSummary()))
                                needsUpdating = true;
                            if (!ecsBug.getIsOpen().equals(existingBugs.get(0).getIsOpen()))
                                needsUpdating = true;

                            if (needsUpdating) {
                                System.out.println("Updating details on bug " + bug.getID() + " for topic " + topicIdInt + ".");

                                updateBug(ecsBug, existingBugs.get(0), updateTopic);
                            } else {
                                System.out.println("Bug " + bug.getID() + " for topic " + topicIdInt
                                        + " does not need updating.");
                            }

                            /*
                             * if for some reason a topic has the same bug assigned more than once (maybe by some fluke someone
                             * manually updated the bug list in after we got the list of topics last time the application was
                             * run), remove the remaining bugs.
                             */
                            for (int i = 1; i < existingBugs.size(); ++i) {
                                final RESTBugzillaBugV1 removeBug = new RESTBugzillaBugV1();
                                removeBug.setId(existingBugs.get(i).getId());
                                updateTopic.getBugzillaBugs_OTM().addRemoveItem(removeBug);
                            }
                        } else {
                            System.out.println("Creating new bug " + bug.getID() + " for topic " + topicIdInt + ".");
                            addBug(ecsBug, updateTopic);
                        }
                    } else {
                        System.out.println("Creating new bug " + bug.getID() + " for topic " + topicIdInt + ".");
                        addBug(ecsBug, updateTopic);
                    }

                }

                if (!foundMatch)
                    System.out.println("Build ID field was not a match for the named regular expression.");
            }

            /*
             * Loop over the topics we processed, and those that had bugs assigned to them. Any topic that has a bug assigned to
             * it but hasn't been processed has bugs that for some reason no longer exist in Bugzilla.
             */
            for (final RESTTopicV1 topic : topicsWithBugs.returnItems()) {
                if (!processedTopics.containsKey(topic.getId())) {
                    final RESTTopicV1 updateTopic = new RESTTopicV1();
                    updateTopic.setId(topic.getId());
                    updateTopic.explicitSetBugzillaBugs_OTM(new RESTBugzillaBugCollectionV1());
                    processedTopics.put(topic.getId(), updateTopic);

                    for (final RESTBugzillaBugV1 bug : topic.getBugzillaBugs_OTM().returnItems()) {
                        final RESTBugzillaBugV1 removeBug = new RESTBugzillaBugV1();
                        removeBug.setId(bug.getId());
                        updateTopic.getBugzillaBugs_OTM().addRemoveItem(removeBug);
                    }
                }
            }

            final RESTTopicCollectionV1 dataObjects = new RESTTopicCollectionV1();
            for (final RESTTopicV1 topic : processedTopics.values())
                dataObjects.addItem(topic);

            /*
             * make sure the topic ids referenced by the bugzilla build id field actually exist
             */
            final List<Integer> invalidIds = new ArrayList<Integer>();
            for (final Integer id : processedTopics.keySet()) {
                try {
                    client.getJSONTopic(id, "");
                } catch (final Exception ex) {
                    System.out.println("Topic id " + id + " is invalid.");
                    invalidIds.add(id);
                }
            }

            /* find the RESTTopicV1 objects that are invalid */
            final List<RESTTopicCollectionItemV1> removeList = new ArrayList<RESTTopicCollectionItemV1>();
            for (final Integer id : invalidIds) {
                for (final RESTTopicCollectionItemV1 topicItem : dataObjects.getItems()) {
                    final RESTTopicV1 topic = topicItem.getItem();
                    if (topic.getId().equals(id)) {
                        System.out.println("Topic id " + topic.getId() + " was found for removal.");
                        removeList.add(topicItem);
                    }
                }
            }

            /* remove them from the collection */
            for (final RESTTopicCollectionItemV1 topicItem : removeList) {
                final RESTTopicV1 topic = topicItem.getItem();
                if (!dataObjects.getItems().remove(topicItem))
                    System.out.println("Topic id " + topic.getId() + " could not be removed.");
                else
                    System.out.println("Topic id " + topic.getId() + " was removed.");
            }

            /* make the changes */
            client.updateJSONTopics(null, dataObjects);
        } catch (final Exception ex) {
            ExceptionUtilities.handleException(ex);
        }
    }

    static private void addBug(final ECSBug ecsBug, final RESTTopicV1 updateTopic) {
        final RESTBugzillaBugV1 addBug = new RESTBugzillaBugV1();
        addBug.setBugIdExplicit(ecsBug.getID());
        addBug.setSummaryExplicit(ecsBug.getSummary());
        addBug.setIsOpenExplicit(ecsBug.getIsOpen());

        updateTopic.getBugzillaBugs_OTM().addNewItem(addBug);
    }

    static private void updateBug(final ECSBug ecsBug, final RESTBugzillaBugV1 bug, final RESTTopicV1 updateTopic) {
        if (!bug.getSummary().equals(ecsBug.getSummary()))
            bug.setSummaryExplicit(ecsBug.getSummary());
        if (!bug.getIsOpen().equals(ecsBug.getIsOpen()))
            bug.setIsOpenExplicit(ecsBug.getIsOpen());

        updateTopic.getBugzillaBugs_OTM().addUpdateItem(bug);
    }

    static private RESTTopicV1 findTopic(final Integer topicId, final RESTTopicCollectionV1 topicsWithBugs) {
        if (topicsWithBugs.getItems() == null || topicsWithBugs.getItems().isEmpty())
            return null;

        for (final RESTTopicV1 topic : topicsWithBugs.returnItems()) {
            if (topic.getId().equals(topicId))
                return topic;
        }

        return null;
    }

    static private List<RESTBugzillaBugV1> findBug(final Integer id, final RESTBugzillaBugCollectionV1 collection) {
        final List<RESTBugzillaBugV1> retValue = new ArrayList<RESTBugzillaBugV1>();

        if (collection.getItems() == null || collection.getItems().isEmpty())
            return retValue;

        for (final RESTBugzillaBugV1 element : collection.returnItems()) {
            if (element.getBugId().equals(id))
                retValue.add(element);
        }

        return retValue;
    }
    
    private static void waitForMinimumCallInterval() {
        /* No need to wait when the call interval is nothing */
        if (minBugzillaAPICallInterval <= 0) return;
        
        long currentTime = System.currentTimeMillis();
        /* Check if the current time is less than the last call plus the minimum wait time */
        if (currentTime < (lastAPICallTime + minBugzillaAPICallInterval)) {
            try {
                Thread.sleep((long) (minBugzillaAPICallInterval * 1000));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        /* Set the current time to the last call time. */
        lastAPICallTime = System.currentTimeMillis();
    }
}
