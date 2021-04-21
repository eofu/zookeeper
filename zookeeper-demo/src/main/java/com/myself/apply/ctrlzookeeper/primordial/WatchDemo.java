package com.myself.apply.ctrlzookeeper.primordial;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class WatchDemo {
	public static void main(String[] args) {
		try {
			CountDownLatch countDownLatch = new CountDownLatch(1);
			ZooKeeper zooKeeper = new ZooKeeper("127.0.0.1:2181", 4000,
					watchedEvent -> {
						System.out.println("默认事件：" + watchedEvent.getType());
						if (Watcher.Event.KeeperState.SyncConnected == watchedEvent.getState()) {
							// 收到服务端的响应事件，连接成功
							countDownLatch.countDown();
						}
					});
			countDownLatch.await();

			zooKeeper.create("/zk-persis-youngs", "1".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

			// exists getdata getchildren
			// 通过exists绑定事件
			Stat stat = zooKeeper.exists("/zk-persis-youngs",
					watchedEvent -> {
						System.out.println(watchedEvent.getType() + "->" + watchedEvent.getState());
						try {
							// 再一次绑定事件
							zooKeeper.exists("/zk-persis-youngs", true);
						} catch (KeeperException | InterruptedException e) {
							e.printStackTrace();
						}
					}
			);

			// 通过修改事务类型触发监听事件
			Stat setData = zooKeeper.setData("/zk-persis-youngs", "1".getBytes(), stat.getVersion());

			Thread.sleep(1000);

			zooKeeper.delete("/zk-persis-youngs", setData.getVersion());

			System.in.read();
		} catch (IOException | InterruptedException | KeeperException e) {
			e.printStackTrace();
		}
	}
}
