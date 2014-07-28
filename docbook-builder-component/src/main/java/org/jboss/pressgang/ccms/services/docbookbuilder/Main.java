/*
  Copyright 2011-2014 Red Hat

  This file is part of PresGang CCMS.

  PresGang CCMS is free software: you can redistribute it and/or modify
  it under the terms of the GNU Lesser General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  PresGang CCMS is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public License
  along with PresGang CCMS.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.jboss.pressgang.ccms.services.docbookbuilder;

import java.util.Map;

import org.apache.log4j.Appender;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.jboss.pressgang.ccms.utils.common.NotificationUtilities;
import org.jboss.pressgang.ccms.utils.services.ServiceStarter;
import org.jboss.pressgang.ccms.utils.services.stomp.BaseStompServiceThread;
import org.jboss.pressgang.ccms.utils.services.stomp.StompWorkQueue;
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
