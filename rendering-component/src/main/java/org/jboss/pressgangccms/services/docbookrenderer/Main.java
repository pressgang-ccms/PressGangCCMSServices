package org.jboss.pressgangccms.services.docbookrenderer;

import java.util.Map;

import org.jboss.pressgangccms.services.docbookrenderer.utils.RenderingThread;
import org.jboss.pressgangccms.utils.common.NotificationUtilities;
import org.jboss.pressgangccms.utils.services.ServiceStarter;
import org.jboss.pressgangccms.utils.services.stomp.BaseStompServiceThread;
import org.jboss.pressgangccms.utils.services.stomp.StompWorkQueue;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

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
 * 
 * The queue name is SkynetTopicRenderQueue
 */
public class Main
{
	/** The build version */
	public static final String NAME  = "Skynet Topic Renderer Service";
	
	/** The build version */
	public static final String BUILD = "20120509-0936";

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
