package com.eucalyptus.reporting.queue.mem;

import java.util.HashMap;
import java.util.Map;

import com.eucalyptus.reporting.queue.*;
import com.eucalyptus.reporting.queue.QueueFactory.QueueIdentifier;

public class MemQueueFactory
	implements InternalQueueFactory
{
	private Map<QueueIdentifier, MemQueue> queueMap;
	
	
	@Override
	public void startup()
	{
		queueMap = new HashMap<QueueIdentifier, MemQueue>();
		for (QueueIdentifier id : QueueIdentifier.values()) {
			queueMap.put(id, new MemQueue());
		}
	}

	@Override
	public void shutdown()
	{
	}

	@Override
	public QueueReceiver getReceiver(QueueIdentifier identifier)
	{
		return queueMap.get(identifier);
	}

	@Override
	public QueueSender getSender(QueueIdentifier idenfitifer)
	{
		return queueMap.get(idenfitifer);
	}

}
