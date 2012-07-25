package org.jboss.pressgangccms.services.zanatasync;
import org.jboss.pressgangccms.utils.concurrency.BaseWorkQueue;

public class ZanataPullWorkQueue extends BaseWorkQueue<ZanataPullTopicThread> {

	private static ZanataPullWorkQueue instance = null;
	
	synchronized public static ZanataPullWorkQueue getInstance()
	{
		if (instance == null)
		{
			instance = new ZanataPullWorkQueue();
		}
		return instance;
	}
	
	private ZanataPullWorkQueue()
	{
		super();
	}
}
