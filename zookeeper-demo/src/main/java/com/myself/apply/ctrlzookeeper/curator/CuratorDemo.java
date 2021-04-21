package com.myself.apply.ctrlzookeeper.curator;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;

public class CuratorDemo {
	public static void main(String[] args) throws Exception {
		CuratorFramework curator = CuratorFrameworkFactory.builder().connectString("127.0.0.1:2181")
				.sessionTimeoutMs(4000)
				.retryPolicy(new ExponentialBackoffRetry(1000, 3))
				.namespace("curator").build();

		curator.start();

		// 增
		String path = curator.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
				.forPath("/youngs/mode", "1".getBytes());

		// 查
		byte[] bytes = curator.getData().forPath("/youngs/mode");
		System.out.println(new String(bytes));

		// 改
		Stat stat = curator.setData().forPath("/youngs/mode", "2".getBytes());
		System.out.println(stat);

		// 删
		curator.delete().deletingChildrenIfNeeded().forPath("/youngs/mode");
	}
}
