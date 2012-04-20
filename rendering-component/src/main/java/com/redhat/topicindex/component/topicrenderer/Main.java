package com.redhat.topicindex.component.topicrenderer;

import java.util.Map;

import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

import com.redhat.ecs.commonutils.NotificationUtilities;
import com.redhat.ecs.servicepojo.ServiceStarter;
import com.redhat.ecs.services.commonstomp.BaseStompServiceThread;
import com.redhat.ecs.services.commonstomp.StompWorkQueue;
import com.redhat.topicindex.component.topicrenderer.utils.RenderingThread;

class ServiceThread extends BaseStompServiceThread
{
	public ServiceThread(final ServiceStarter serviceStarter)
	{
		super(serviceStarter);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public void message(final Map headers, final String message)
	{
		NotificationUtilities.dumpMessageToStdOut("Message Recieved: " + message);
		StompWorkQueue.getInstance().execute(new RenderingThread(this.client, message, headers, this.serviceStarter, !this.isRunning()));
	}
}

/**
 * This command line application is used to watch for topic ids being added to a
 * STOMP queue. The corresponding topics are then downloaded, transformed into
 * HTML, and the results saved back to the database.
 */
public class Main
{
	/** The build version */
	public static final String NAME  = "Skynet Topic Renderer Service";
	
	/** The build version */
	public static final String BUILD = "20120306-1451";

	public static void main(final String[] args)
	{
		NotificationUtilities.dumpMessageToStdOut("Skynet Docbook Renderer Service Build " + BUILD);
		
		RegisterBuiltin.register(ResteasyProviderFactory.getInstance());

		/*
		 * Messages need to be ACKed in order. We make use of the WorkQueue in order
		 * to queue work, but to ensure that the messages are ACKed in order, we
		 * need to make sure that there is only going to be one worker thread
		 * running at a time.
		 */
		/*final String threadCount = System.getProperty("NumberOfWorkerThreads");
		boolean threadCountOk = true;
		try
		{
			if (Integer.parseInt(threadCount) != 1)
				threadCountOk = false;
		}
		catch (final Exception ex)
		{
			threadCountOk = false;
		}

		if (!threadCountOk)
		{
			NotificationUtilities.dumpMessageToStdOut("NumberOfWorkerThreads system property MUST be 1!");
			return;
		}*/

		final ServiceStarter starter = new ServiceStarter();
		if (starter.isValid())
		{
			/* create the service thread */
			final ServiceThread serviceThread = new ServiceThread(starter);
			starter.start(serviceThread);
		}
	}
}
