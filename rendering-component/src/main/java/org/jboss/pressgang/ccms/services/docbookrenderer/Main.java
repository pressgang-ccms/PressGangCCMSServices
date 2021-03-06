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

package org.jboss.pressgang.ccms.services.docbookrenderer;

import java.util.Map;

import org.jboss.pressgang.ccms.services.docbookrenderer.utils.RenderingThread;
import org.jboss.pressgang.ccms.utils.common.NotificationUtilities;
import org.jboss.pressgang.ccms.utils.services.ServiceStarter;
import org.jboss.pressgang.ccms.utils.services.stomp.BaseStompServiceThread;
import org.jboss.pressgang.ccms.utils.services.stomp.StompWorkQueue;
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
