package com.myself.apply.ctrlzookeeper.curator;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class CuratorWatcherDemo {
	public static void main(String[] args) {
		CuratorFramework curator = CuratorFrameworkFactory.builder().connectString("127.0.0.1:2181")
				.sessionTimeoutMs(4000)
				.retryPolicy(new ExponentialBackoffRetry(1000, 3))
				.namespace("curator").build();

		curator.start();

	}
}
