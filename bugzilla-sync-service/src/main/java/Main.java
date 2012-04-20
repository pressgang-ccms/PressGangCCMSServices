import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.resteasy.client.ProxyFactory;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.specimpl.PathSegmentImpl;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

import com.j2bugzilla.base.BugzillaConnector;
import com.j2bugzilla.base.ECSBug;
import com.j2bugzilla.rpc.BugSearch;
import com.j2bugzilla.rpc.GetBug;
import com.j2bugzilla.rpc.LogIn;
import com.redhat.ecs.commonutils.CollectionUtilities;
import com.redhat.ecs.commonutils.ExceptionUtilities;
import com.redhat.ecs.constants.CommonConstants;
import com.redhat.topicindex.rest.collections.BaseRestCollectionV1;
import com.redhat.topicindex.rest.entities.BugzillaBugV1;
import com.redhat.topicindex.rest.entities.TopicV1;
import com.redhat.topicindex.rest.expand.ExpandDataDetails;
import com.redhat.topicindex.rest.expand.ExpandDataTrunk;
import com.redhat.topicindex.rest.sharedinterface.RESTInterfaceV1;

public class Main
{
	public static void main(String args[])
	{
		try
		{
			/* Get the system properties */
			final String skynetServer = System.getProperty(CommonConstants.SKYNET_SERVER_SYSTEM_PROPERTY);
			final String bugzillaServer = System.getProperty(CommonConstants.BUGZILLA_URL_PROPERTY);
			final String bugzillaPassword = System.getProperty(CommonConstants.BUGZILLA_PASSWORD_PROPERTY);
			final String bugzillaUsername = System.getProperty(CommonConstants.BUGZILLA_USERNAME_PROPERTY);

			/* Some sanity checking */
			if (skynetServer == null || skynetServer.trim().isEmpty() || bugzillaServer == null || bugzillaServer.trim().isEmpty() || bugzillaPassword == null || bugzillaPassword.trim().isEmpty() || bugzillaUsername == null || bugzillaUsername.trim().isEmpty())
			{
				System.out.println("The " + CommonConstants.SKYNET_SERVER_SYSTEM_PROPERTY + ", " + CommonConstants.BUGZILLA_URL_PROPERTY + ", " + CommonConstants.BUGZILLA_PASSWORD_PROPERTY + " and " + CommonConstants.BUGZILLA_USERNAME_PROPERTY + " system properties need to be defined.");
				return;
			}

			/*
			 * The regex pattern used to pull information out of the build id
			 * field
			 */
			final Pattern pattern = Pattern.compile(CommonConstants.BUGZILLA_BUILD_ID_NAMED_RE);

			/* The JSON mapper */
			final ObjectMapper mapper = new ObjectMapper();

			/* Setup the REST interface */
			RegisterBuiltin.register(ResteasyProviderFactory.getInstance());
			final RESTInterfaceV1 client = ProxyFactory.create(RESTInterfaceV1.class, skynetServer);

			/* Get the topics from Skynet that have bugs assigned to them */
			final ExpandDataTrunk expand = new ExpandDataTrunk();
			final ExpandDataTrunk topics = new ExpandDataTrunk(new ExpandDataDetails("topics"));
			expand.setBranches(CollectionUtilities.toArrayList(topics));
			final ExpandDataTrunk bugzillaBugz = new ExpandDataTrunk(new ExpandDataDetails(TopicV1.BUGZILLABUGS_NAME));
			topics.setBranches(CollectionUtilities.toArrayList(bugzillaBugz));

			final String expandString = mapper.writeValueAsString(expand);
			final String expandEncodedStrnig = URLEncoder.encode(expandString, "UTF-8");

			final PathSegmentImpl query = new PathSegmentImpl("query;topicHasBugzillaBugs=true", false);

			final BaseRestCollectionV1<TopicV1> topicsWithBugs = client.getJSONTopicsWithQuery(query, expandEncodedStrnig);

			System.out.println("Found " + topicsWithBugs.getSize() + " topics that already have Bugzilla bugs assigned to them.");

			/* Get the bugzilla bugs */
			final LogIn login = new LogIn(bugzillaUsername, bugzillaPassword);
			final BugzillaConnector connector = new BugzillaConnector();
			final BugSearch<ECSBug> search = new BugSearch<ECSBug>(ECSBug.class);

			/*
			 * Create a query that will return all bugs whose Build ID matches
			 * what is prepopulated by Skynet.
			 */
			search.addQueryParam("field0-0-0", "cf_build_id");
			search.addQueryParam("type0-0-0", "regexp");
			search.addQueryParam("value0-0-0", "[0-9]+" + CommonConstants.BUGZILLA_BUILD_ID_RE);

			connector.connectTo("https://" + bugzillaServer + "/xmlrpc.cgi");
			connector.executeMethod(login);
			connector.executeMethod(search);

			final List<ECSBug> bugzillaBugs = search.getSearchResults();

			System.out.println("Found " + bugzillaBugs.size() + " Bugzilla bugs that match the regular expression.");

			/*
			 * A list of the topics that we have updated. This will be used to
			 * compare those topics that say they have bugs assigned to them,
			 * and the topics that the bugzilla bugs actually refer to.
			 */
			final Map<Integer, TopicV1> processedTopics = new HashMap<Integer, TopicV1>();

			/* loop over the bugzilla bugs, and sync up the associated topics */
			float current = 1;
			float total = bugzillaBugs.size();
			for (final ECSBug bug : bugzillaBugs)
			{
				final int percentDone = (int) (current / total * 100);
				++current;
				System.out.print("[" + percentDone + "%] Getting details on bug " + bug.getID() + ". ");

				final GetBug<ECSBug> getBug = new GetBug<ECSBug>(ECSBug.class, bug.getID());
				connector.executeMethod(getBug);
				final ECSBug ecsBug = getBug.getBug();

				final Matcher buildIdMatcher = pattern.matcher(ecsBug.getBuildId());

				boolean foundMatch = false;
				while (buildIdMatcher.find())
				{
					foundMatch = true;

					final String topicId = buildIdMatcher.group("TopicID");
					final Integer topicIdInt = Integer.parseInt(topicId);
					final TopicV1 existingTopic = findTopic(topicIdInt, topicsWithBugs);

					/* This is the topics we will use to update the system */
					TopicV1 updateTopic = null;

					/* keep a database of topcis that we are updating */
					if (!processedTopics.containsKey(topicIdInt))
					{
						updateTopic = new TopicV1();
						updateTopic.setId(topicIdInt);
						updateTopic.setBugzillaBugsExplicit_OTM(new BaseRestCollectionV1<BugzillaBugV1>());
						processedTopics.put(topicIdInt, updateTopic);
					}
					else
					{
						updateTopic = processedTopics.get(topicIdInt);
					}

					if (existingTopic != null)
					{
						final List<BugzillaBugV1> existingBugs = findBug(ecsBug.getID(), existingTopic.getBugzillaBugs_OTM());

						/* if the bug exists, make sure the values are the same */
						if (existingBugs.size() != 0)
						{
							boolean needsUpdating = false;
							if (!ecsBug.getSummary().equals(existingBugs.get(0).getSummary()))
								needsUpdating = true;
							if (!ecsBug.getIsOpen().equals(existingBugs.get(0).getIsOpen()))
								needsUpdating = true;

							if (needsUpdating)
							{
								System.out.println("Updating details on bug " + bug.getID() + " for topic " + topicIdInt + ".");

								final BugzillaBugV1 removeBug = new BugzillaBugV1();
								removeBug.setId(existingBugs.get(0).getId());
								removeBug.setRemoveItem(true);
								updateTopic.getBugzillaBugs_OTM().addItem(removeBug);

								addBug(ecsBug, updateTopic);
							}
							else
							{
								System.out.println("Bug " + bug.getID() + " for topic " + topicIdInt + " does not need updating.");
							}

							/*
							 * if for some reason a topic has the same bug
							 * assigned more than once (maybe by some fluke
							 * someone manually updated the bug list in after we
							 * got the list of topics last time the application
							 * was run), remove the remaining bugs.
							 */
							for (int i = 1; i < existingBugs.size(); ++i)
							{
								final BugzillaBugV1 removeBug = new BugzillaBugV1();
								removeBug.setId(existingBugs.get(i).getId());
								removeBug.setRemoveItem(true);
								updateTopic.getBugzillaBugs_OTM().addItem(removeBug);
							}
						}
						else
						{
							System.out.println("Creating new bug " + bug.getID() + " for topic " + topicIdInt + ".");
							addBug(ecsBug, updateTopic);
						}
					}
					else
					{
						System.out.println("Creating new bug " + bug.getID() + " for topic " + topicIdInt + ".");
						addBug(ecsBug, updateTopic);
					}

				}

				if (!foundMatch)
					System.out.println("Build ID field was not a match for the named regular expression.");
			}

			/*
			 * Loop over the topics we processed, and those that had bugs
			 * assigned to them. Any topic that has a bug assigned to it but
			 * hasn't been processed has bugs that for some reason no longer
			 * exist in Bugzilla.
			 */
			for (final TopicV1 topic : topicsWithBugs.getItems())
			{
				if (!processedTopics.keySet().contains(topic.getId()))
				{
					final TopicV1 updateTopic = new TopicV1();
					updateTopic.setId(topic.getId());
					updateTopic.setBugzillaBugsExplicit_OTM(new BaseRestCollectionV1<BugzillaBugV1>());
					processedTopics.put(topic.getId(), updateTopic);

					for (final BugzillaBugV1 bug : topic.getBugzillaBugs_OTM().getItems())
					{
						final BugzillaBugV1 removeBug = new BugzillaBugV1();
						removeBug.setId(bug.getId());
						removeBug.setRemoveItem(true);
						updateTopic.getBugzillaBugs_OTM().addItem(removeBug);
					}
				}
			}

			final BaseRestCollectionV1<TopicV1> dataObjects = new BaseRestCollectionV1<TopicV1>();
			for (final TopicV1 topic : processedTopics.values())
				dataObjects.addItem(topic);

			/*
			 * make sure the topic ids referenced by the bigzilla build id field
			 * actually exist
			 */
			final List<Integer> invalidIds = new ArrayList<Integer>();
			for (final Integer id : processedTopics.keySet())
			{
				try
				{
					client.getJSONTopic(id, "");
				}
				catch (final Exception ex)
				{
					System.out.println("Topic id " + id + " is invalid.");
					invalidIds.add(id);
				}
			}

			/* find the TopicV1 objects that are invalid */
			final List<TopicV1> removeList = new ArrayList<TopicV1>();
			for (final Integer id : invalidIds)
			{
				for (final TopicV1 topic : dataObjects.getItems())
				{
					if (topic.getId().equals(id))
					{
						removeList.add(topic);
					}
				}
			}

			/* remove them from the collection */
			for (final TopicV1 topic : removeList)
				dataObjects.getItems().remove(topic);

			/* make the changes */
			client.updateJSONTopics("", dataObjects);
		}
		catch (final Exception ex)
		{
			ExceptionUtilities.handleException(ex);
		}
	}

	static private void addBug(final ECSBug ecsBug, final TopicV1 updateTopic)
	{
		final BugzillaBugV1 addBug = new BugzillaBugV1();
		addBug.setBugIdExplicit(ecsBug.getID());
		addBug.setSummaryExplicit(ecsBug.getSummary());
		addBug.setIsOpenExplicit(ecsBug.getIsOpen());
		addBug.setAddItem(true);

		updateTopic.getBugzillaBugs_OTM().addItem(addBug);
	}

	static private TopicV1 findTopic(final Integer topicId, final BaseRestCollectionV1<TopicV1> topicsWithBugs)
	{
		if (topicsWithBugs.getItems() == null)
			return null;

		for (final TopicV1 topic : topicsWithBugs.getItems())
		{
			if (topic.getId().equals(topicId))
				return topic;
		}

		return null;
	}

	static private List<BugzillaBugV1> findBug(final Integer id, final BaseRestCollectionV1<BugzillaBugV1> collection)
	{
		final List<BugzillaBugV1> retValue = new ArrayList<BugzillaBugV1>();

		if (collection.getItems() == null)
			return retValue;

		for (final BugzillaBugV1 element : collection.getItems())
		{
			if (element.getBugId().equals(id))
				retValue.add(element);
		}

		return retValue;
	}
}
