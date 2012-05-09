package com.redhat.topicindex.component.docbookrenderer;

import java.util.Map;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import com.redhat.ecs.commonutils.NotificationUtilities;
import com.redhat.ecs.servicepojo.ServiceStarter;
import com.redhat.ecs.services.commonstomp.BaseStompServiceThread;
import com.redhat.ecs.services.commonstomp.StompWorkQueue;

/**
 * 
 */
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
		StompWorkQueue.getInstance().execute(new DocbookBuildingThread(this.serviceStarter, this.client, message, headers, !this.isRunning()));
	}
}

/**
 * This command line application is used to watch for topic queries being added
 * to a STOMP queue. The corresponding topics are then downloaded, processed,
 * and bundled into a Publican ZIP file
 */
public class Main
{
	/** The build version */
	public static final String NAME  = "Skynet Docbook Building Service";
	
	/** The build version */
	public static final String BUILD = "20120508-1101";

	public static void main(final String[] args)
	{
		NotificationUtilities.dumpMessageToStdOut("Skynet Docbook Compiler Service Build " + BUILD);
		
		/* validate the template against the DTD */
		RegisterBuiltin.register(ResteasyProviderFactory.getInstance());
		
		final ServiceStarter starter = new ServiceStarter();
		if (starter.isValid())
		{
			/* create the service thread */
			final ServiceThread serviceThread = new ServiceThread(starter);
			starter.start(serviceThread);
		}
	}
}
