package com.github.ghthou.distributed_lock;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class CuratorLock {
	
	private final static String lockPath = "/distribute-lock";
	
	private static CuratorFramework client;
	static{
		String zkAddr = PropertyUtil.getProperty("dubbo.address.zookeeper");
		RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
		client = CuratorFrameworkFactory.builder()
                .connectString(zkAddr)
                .sessionTimeoutMs(2000)
                .retryPolicy(retryPolicy)
                .build();
		client.start();
	}
	
	public static InterProcessMutex lock(){
		return new InterProcessMutex(client, lockPath);
	}
	
	public static void release(InterProcessMutex lock) throws Exception{
		lock.release();
	}
	
}
