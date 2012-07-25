package org.jboss.pressgangccms.services.docbookbuilder;

import java.util.Map;

import org.apache.log4j.Appender;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.jboss.pressgangccms.utils.common.NotificationUtilities;
import org.jboss.pressgangccms.utils.services.ServiceStarter;
import org.jboss.pressgangccms.utils.services.stomp.BaseStompServiceThread;
import org.jboss.pressgangccms.utils.services.stomp.StompWorkQueue;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

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
	public static final String BUILD = "20120725-1748";

	public static void main(final String[] args)
	{
		NotificationUtilities.dumpMessageToStdOut("Skynet Docbook Compiler Service Build " + BUILD);
		
		/* Create a custom ObjectMapper to handle the mapping between the interfaces and the concrete classes */
		RegisterBuiltin.register(ResteasyProviderFactory.getInstance());
		
		/* Setup the logger used by the CSP Builder */
		PatternLayout layout = new PatternLayout();
		layout.setConversionPattern("[%d{dd MMM yyyy HH:mm}] %m%n");
		
		Appender appender = new ConsoleAppender(layout, "System.out");
		appender.setName("stdout");
		
		Logger.getRootLogger().setLevel(Level.INFO);
		Logger.getRootLogger().addAppender(appender);
		
		final ServiceStarter starter = new ServiceStarter();
		if (starter.isValid())
		{
			/* create the service thread */
			final ServiceThread serviceThread = new ServiceThread(starter);
			starter.start(serviceThread);
		}
	}
}