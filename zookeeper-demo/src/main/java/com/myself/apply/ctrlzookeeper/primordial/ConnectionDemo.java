package com.myself.apply.ctrlzookeeper.primordial;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class ConnectionDemo {
	public static void main(String[] args) {
		try {
			CountDownLatch countDownLatch = new CountDownLatch(1);
			ZooKeeper zooKeeper = new ZooKeeper("127.0.0.1:2181", 4000,
					watchedEvent -> {
						if (Watcher.Event.KeeperState.SyncConnected == watchedEvent.getState()) {
							// 收到服务端的响应事件，连接成功
							countDownLatch.countDown();
						}
					});
			countDownLatch.await();
			System.out.println(zooKeeper.getState());

			// 添加节点
			zooKeeper.create("/zk-persis-youngs", "0".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Thread.sleep(1000);

			// 得到当前节点的值
			Stat stat = new Stat();
			byte[] zooKeeperData = zooKeeper.getData("/zk-persis-youngs", null, stat);
			System.out.println(new String(zooKeeperData));

			// 修改节点值
			zooKeeper.setData("/zk-persis-youngs", "1".getBytes(), stat.getVersion());

			// 得到当前节点的值
			byte[] zooKeeperData2 = zooKeeper.getData("/zk-persis-youngs", null, stat);
			System.out.println(new String(zooKeeperData2));

			// 删除节点值
			zooKeeper.delete("/zk-persis-youngs", stat.getVersion());

			zooKeeper.close();
			System.in.read();
		} catch (IOException | InterruptedException | KeeperException e) {
			e.printStackTrace();
		}
	}
}
